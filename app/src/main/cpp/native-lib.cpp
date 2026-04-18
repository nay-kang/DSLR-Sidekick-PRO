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
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::mutex g_camera_mutex;
#define LOCK_CAMERA std::lock_guard<std::mutex> lock(g_camera_mutex)

static Camera *g_camera = nullptr;
static GPContext *g_context = nullptr;

// External gphoto2 internal function for Android USB FD injection
extern "C" int gp_port_usb_set_sys_device(int fd);

static void log_func(GPLogLevel level, const char *domain, const char *str, void *data) {
    if (level <= GP_LOG_ERROR) {
        LOGE("[%d] %s: %s", level, domain, str);
    } else {
        LOGI("[%d] %s: %s", level, domain, str);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_net_codeedu_dslrsidekickpro_CameraService_connectToCamera(JNIEnv *env, jobject thiz, jint fd) {
    LOCK_CAMERA;
    LOGI("Initializing Nikon D610 Connection (FD: %d)", fd);

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
    gp_log_add_func(GP_LOG_VERBOSE, log_func, nullptr);

    int rc = gp_camera_new(&g_camera);
    if (rc != GP_OK) return rc;

    // Inject Nikon D610 Capabilities
    CameraAbilities ab;
    memset(&ab, 0, sizeof(ab));
    strcpy(ab.model, "Nikon DSC D610 (PTP mode)");
    strcpy(ab.library, "ptp2");
    ab.status = GP_DRIVER_STATUS_PRODUCTION;
    ab.operations = (CameraOperation)(GP_OPERATION_CAPTURE_IMAGE | GP_OPERATION_CONFIG | GP_OPERATION_CAPTURE_PREVIEW);
    ab.file_operations = (CameraFileOperation)(GP_FILE_OPERATION_DELETE | GP_FILE_OPERATION_PREVIEW | GP_FILE_OPERATION_EXIF);
    ab.folder_operations = (CameraFolderOperation)(GP_FOLDER_OPERATION_DELETE_ALL | GP_FOLDER_OPERATION_MAKE_DIR);
    ab.usb_vendor = 0x04b0;
    ab.usb_product = 0x0432;
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
        LOGI("Disconnecting camera and clearing FD");
        gp_camera_exit(g_camera, g_context);
        gp_camera_unref(g_camera);
        g_camera = nullptr;
    }
    if (g_context) {
        gp_context_unref(g_context);
        g_context = nullptr;
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
            CameraFilePath *path = (CameraFilePath *)data;
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
    if (rc != GP_OK) { gp_list_free(list); return nullptr; }
    int count = gp_list_count(list);
    jobjectArray array = env->NewObjectArray(count, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < count; i++) {
        const char *name;
        gp_list_get_name(list, i, &name);
        env->SetObjectArrayElement(array, i, env->NewStringUTF(name));
    }
    gp_list_free(list);
    return array;
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
    if (rc != GP_OK) { gp_list_free(list); return nullptr; }
    int count = gp_list_count(list);
    jobjectArray array = env->NewObjectArray(count, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < count; i++) {
        const char *name;
        gp_list_get_name(list, i, &name);
        env->SetObjectArrayElement(array, i, env->NewStringUTF(name));
    }
    gp_list_free(list);
    return array;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_net_codeedu_dslrsidekickpro_CameraService_downloadFile(JNIEnv *env, jobject thiz, jstring folder_path, jstring file_name) {
    LOCK_CAMERA;
    if (!g_camera) return nullptr;
    const char *folder = env->GetStringUTFChars(folder_path, nullptr);
    const char *name = env->GetStringUTFChars(file_name, nullptr);
    CameraFile *file;
    gp_file_new(&file);
    int rc = gp_camera_file_get(g_camera, folder, name, GP_FILE_TYPE_NORMAL, file, g_context);
    env->ReleaseStringUTFChars(folder_path, folder);
    env->ReleaseStringUTFChars(file_name, name);
    if (rc != GP_OK) { gp_file_unref(file); return nullptr; }
    const char *data;
    unsigned long size;
    gp_file_get_data_and_size(file, &data, &size);
    jbyteArray array = env->NewByteArray(size);
    env->SetByteArrayRegion(array, 0, size, (const jbyte *)data);
    gp_file_unref(file);
    return array;
}
