#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <android/log.h>
#include <gphoto2/gphoto2-camera.h>
#include <gphoto2/gphoto2-port-log.h>
#include <libusb.h>
#include <cstring>
#include <unistd.h>

#define LOG_TAG "DSLR-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::mutex g_camera_mutex;
#define LOCK_CAMERA std::lock_guard<std::mutex> lock(g_camera_mutex)

static Camera *g_camera = nullptr;
static GPContext *g_context = nullptr;

// Helper function to convert CameraList to Java StringArray
static jobjectArray cameraListToJavaArray(JNIEnv *env, CameraList *list) {
    if (!list) return nullptr;
    int count = gp_list_count(list);
    jobjectArray array = env->NewObjectArray(count, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < count; i++) {
        const char *name;
        if (gp_list_get_name(list, i, &name) == GP_OK) {
            env->SetObjectArrayElement(array, i, env->NewStringUTF(name));
        }
    }
    gp_list_free(list);
    return array;
}

// Dynamic camera name lookup using libgphoto2 abilities list
static std::string getCameraModelName(uint16_t vendor_id, uint16_t product_id) {
    CameraAbilitiesList *abilities_list = nullptr;
    gp_abilities_list_new(&abilities_list);
    
    // Create temporary context for loading abilities
    GPContext *temp_context = gp_context_new();
    
    // Load all camera abilities from gphoto2 database
    int rc = gp_abilities_list_load(abilities_list, temp_context);
    if (rc != GP_OK) {
        LOGW("Failed to load gphoto2 abilities list: %d", rc);
        gp_abilities_list_free(abilities_list);
        gp_context_unref(temp_context);
        
        // Fallback: use generic name
        char buffer[64];
        snprintf(buffer, sizeof(buffer), "PTP Camera (0x%04x:0x%04x)", vendor_id, product_id);
        return {buffer};
    }
    
    // Search for matching camera by USB IDs
    int count = gp_abilities_list_count(abilities_list);
    std::string model_name;
    bool found = false;
    
    for (int i = 0; i < count; i++) {
        CameraAbilities ab;
        rc = gp_abilities_list_get_abilities(abilities_list, i, &ab);
        if (rc == GP_OK && ab.usb_vendor == vendor_id && ab.usb_product == product_id) {
            model_name = std::string(ab.model) + " (PTP mode)";
            LOGI("Found camera in gphoto2 database: %s (index %d/%d)", ab.model, i, count);
            found = true;
            break;
        }
    }
    
    if (!found) {
        LOGI("Camera not found in gphoto2 database (%d entries checked), using generic name", count);
        char buffer[64];
        snprintf(buffer, sizeof(buffer), "PTP Camera (0x%04x:0x%04x)", vendor_id, product_id);
        model_name = std::string(buffer);
    }
    
    gp_abilities_list_free(abilities_list);
    gp_context_unref(temp_context);
    return model_name;
}

extern "C" JNIEXPORT jint JNICALL
Java_net_codeedu_dslrsidekickpro_CameraService_connectToCamera(JNIEnv *env, jobject thiz, jint fd, jint vendor_id, jint product_id) {
    LOCK_CAMERA;
    
    // Get camera model name dynamically
    std::string model_name = getCameraModelName((uint16_t)vendor_id, (uint16_t)product_id);
    LOGI("Initializing %s (FD: %d, USB: 0x%04x:0x%04x)", 
         model_name.c_str(), fd, vendor_id, product_id);

    // Disable libusb device discovery to bypass Android SELinux restrictions
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);

    // Global cleanup before new connection
    if (g_camera) {
        gp_camera_exit(g_camera, g_context);
        gp_camera_unref(g_camera);
        g_camera = nullptr;
        usleep(200000); // Wait for hardware PTP stack reset
    }
    if (g_context) {
        gp_context_unref(g_context);
        g_context = nullptr;
    }

    gp_port_usb_set_sys_device(fd);
    g_context = gp_context_new();
    // 注释掉日志函数注册，减少性能开销
    // gp_log_add_func(GP_LOG_VERBOSE, log_func, nullptr);

    int rc = gp_camera_new(&g_camera);
    if (rc != GP_OK) return rc;

    // Set camera abilities dynamically based on USB IDs
    CameraAbilities ab;
    memset(&ab, 0, sizeof(ab));
    
    // Set model name
    strncpy(ab.model, model_name.c_str(), sizeof(ab.model) - 1);
    ab.model[sizeof(ab.model) - 1] = '\0';
    
    // Use PTP2 driver for all modern DSLRs
    strncpy(ab.library, "ptp2", sizeof(ab.library) - 1);
    ab.library[sizeof(ab.library) - 1] = '\0';
    
    ab.status = GP_DRIVER_STATUS_PRODUCTION;
    ab.operations = (CameraOperation)(GP_OPERATION_CAPTURE_IMAGE | GP_OPERATION_CONFIG | GP_OPERATION_CAPTURE_PREVIEW);
    ab.file_operations = (CameraFileOperation)(GP_FILE_OPERATION_DELETE | GP_FILE_OPERATION_PREVIEW | GP_FILE_OPERATION_EXIF);
    ab.folder_operations = (CameraFolderOperation)(GP_FOLDER_OPERATION_DELETE_ALL | GP_FOLDER_OPERATION_MAKE_DIR);
    
    // Set USB vendor and product IDs from parameters
    ab.usb_vendor = (uint16_t)vendor_id;
    ab.usb_product = (uint16_t)product_id;
    ab.device_type = GP_DEVICE_STILL_CAMERA;
    
    gp_camera_set_abilities(g_camera, ab);

    // Port Configuration
    GPPortInfoList *pil = nullptr;
    gp_port_info_list_new(&pil);
    GPPortInfo pi;
    gp_port_info_new(&pi);
    gp_port_info_set_name(pi, "Android USB Port");
    gp_port_info_set_path(pi, "usb:");
    gp_port_info_set_type(pi, GP_PORT_USB);
    gp_port_info_set_library_filename(pi, (char*)"libusb1");
    gp_port_info_list_append(pil, pi);

    GPPortInfo confirmed;
    gp_port_info_list_get_info(pil, 0, &confirmed);
    gp_camera_set_port_info(g_camera, confirmed);
    gp_port_info_list_free(pil);

    // Increase timeout for stable Android USB forwarding
    gp_port_set_timeout(g_camera->port, 5000);

    // Initial attempt
    rc = gp_camera_init(g_camera, g_context);

    // Retry logic for PTP handshake stability
    if (rc != GP_OK) {
        LOGI("Initial PTP handshake failed (%d), retrying after reset...", rc);
        usleep(300000);
        rc = gp_camera_init(g_camera, g_context);
    }

    if (rc == GP_OK) {
        LOGI("Camera initialized successfully!");
    } else {
        LOGE("Failed to initialize camera: %d", rc);
        gp_camera_unref(g_camera);
        g_camera = nullptr;
    }

    return rc;
}

extern "C" JNIEXPORT void JNICALL
Java_net_codeedu_dslrsidekickpro_CameraService_disconnectCameraNative(JNIEnv *env, jobject thiz) {
    LOCK_CAMERA;
    if (g_camera) {
        LOGI("Disconnecting camera (Safe Mode)");
        Camera *cam = g_camera;
        g_camera = nullptr;

        // 针对 Android 平台的特殊处理：
        // 当 USB 设备已经物理断开时，libgphoto2 的 libusb1 后端在释放端口时（gp_port_free）
        // 极易因尝试访问已失效的 libusb 设备列表而触发引用计数断言失败并崩溃。
        // 通过将 cam->port 置为 NULL，我们强制 gp_camera_free 跳过端口清理逻辑。
        // 这虽然会导致极小的内存泄漏，但保证了在相机拔出时应用不会闪退。
        if (cam && cam->port) {
            LOGI("Bypassing port destruction to prevent libusb crash");
            cam->port = nullptr;
        }

        // 不调用 gp_camera_exit，直接 unref。因为设备已消失，exit 尝试通信会导致更多错误。
        gp_camera_unref(cam);
    }
    if (g_context) {
        GPContext *ctx = g_context;
        g_context = nullptr;
        gp_context_unref(ctx);
    }
    gp_port_usb_set_sys_device(-1);
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_codeedu_dslrsidekickpro_CameraService_pollEvent(JNIEnv *env, jobject thiz, jint timeout_ms) {
    LOCK_CAMERA;
    if (!g_camera || !g_context) return nullptr;

    CameraEventType type;
    void *data = nullptr;
    int rc = gp_camera_wait_for_event(g_camera, timeout_ms, &type, &data, g_context);

    if (rc == GP_OK && data != nullptr) {
        if (type == GP_EVENT_FILE_ADDED || type == GP_EVENT_FILE_CHANGED) {
            auto *path = (CameraFilePath *)data;
            std::string folderStr = (path->folder[0] == '/') ? path->folder : ("/" + std::string(path->folder));
            std::string full_path = folderStr + (folderStr.back() == '/' ? "" : "/") + path->name;
            free(data);
            return env->NewStringUTF(full_path.c_str());
        }
        free(data);
    }
    return nullptr;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_net_codeedu_dslrsidekickpro_CameraService_listFoldersInFolder(JNIEnv *env, jobject thiz, jstring folder_path) {
    LOCK_CAMERA;
    if (!g_camera) return nullptr;
    const char *path = env->GetStringUTFChars(folder_path, nullptr);
    CameraList *list;
    gp_list_new(&list);
    int rc = gp_camera_folder_list_folders(g_camera, path, list, g_context);
    env->ReleaseStringUTFChars(folder_path, path);
    if (rc != GP_OK) { 
        gp_list_free(list); 
        return nullptr; 
    }
    return cameraListToJavaArray(env, list);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_net_codeedu_dslrsidekickpro_CameraService_listFilesInFolder(JNIEnv *env, jobject thiz, jstring folder_path) {
    LOCK_CAMERA;
    if (!g_camera) return nullptr;
    const char *path = env->GetStringUTFChars(folder_path, nullptr);
    CameraList *list;
    gp_list_new(&list);
    int rc = gp_camera_folder_list_files(g_camera, path, list, g_context);
    env->ReleaseStringUTFChars(folder_path, path);
    if (rc != GP_OK) { 
        gp_list_free(list); 
        return nullptr; 
    }
    return cameraListToJavaArray(env, list);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_net_codeedu_dslrsidekickpro_CameraService_downloadFile(JNIEnv *env, jobject thiz, jstring folder_path, jstring file_name) {
    LOCK_CAMERA;
    if (!g_camera) return nullptr;
    const char *folder = env->GetStringUTFChars(folder_path, nullptr);
    const char *name = env->GetStringUTFChars(file_name, nullptr);
    CameraFile *file;
    gp_file_new(&file);
    
    // 摄影师需要原图才能判断拍摄效果,直接下载完整文件
    int rc = gp_camera_file_get(g_camera, folder, name, GP_FILE_TYPE_NORMAL, file, g_context);
    
    env->ReleaseStringUTFChars(folder_path, folder);
    env->ReleaseStringUTFChars(file_name, name);
    if (rc != GP_OK) { 
        LOGE("Failed to download file: %s/%s, error: %d", folder, name, rc);
        gp_file_unref(file); 
        return nullptr; 
    }
    const char *data;
    unsigned long size;
    gp_file_get_data_and_size(file, &data, &size);
    LOGI("Downloaded %lu bytes for %s", size, name);
    jbyteArray array = env->NewByteArray(size);
    env->SetByteArrayRegion(array, 0, size, (const jbyte *)data);
    gp_file_unref(file);
    return array;
}
