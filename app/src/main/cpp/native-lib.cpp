#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include "config.h"
#include "libusb.h"
#include "ltdl.h"
#include "gphoto2/gphoto2-camera.h"
#include "gphoto2/gphoto2-port-log.h"
#include "gphoto2/gphoto2-setting.h"
#include "gphoto2/gphoto2-port.h"
#include "gphoto2/gphoto2-library.h"
#include "gphoto2/gphoto2-port-library.h"

#define LOG_TAG "GPhoto2-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 强制导出静态链接的符号，供 ltdl.h 桩使用
extern "C" {
    int camera_abilities(CameraAbilitiesList *list);
    int gp_port_library_list(GPPortInfoList *list);
    GPPortType gp_port_library_type(void);
    GPPortOperations *gp_port_library_operations(void);

    // PTP2 驱动中的入口
    int camera_id(CameraText *id);
    int camera_init(Camera *camera, GPContext *context);

    // 实现 ltdl 桩中的符号查找
    void* lt_dlsym(lt_dlhandle handle, const char* symbol) {
        if (std::strcmp(symbol, "gp_port_library_list") == 0) return (void*)gp_port_library_list;
        if (std::strcmp(symbol, "gp_port_library_type") == 0) return (void*)gp_port_library_type;
        if (std::strcmp(symbol, "gp_port_library_operations") == 0) return (void*)gp_port_library_operations;
        if (std::strcmp(symbol, "camera_id") == 0) return (void*)camera_id;
        if (std::strcmp(symbol, "camera_init") == 0) return (void*)camera_init;
        if (std::strcmp(symbol, "camera_abilities") == 0) return (void*)camera_abilities;
        return nullptr;
    }

    int lt_dlinit() { return 0; }
    int lt_dlexit() { return 0; }
    int lt_dlsetsearchpath(const char* path) { return 0; }
    int lt_dladdsearchdir(const char* path) { return 0; }
    int lt_dlforeachfile(const char* path, int (*func)(const char*, void*), void* data) { return 0; }
    lt_dlhandle lt_dlopenext(const char* name) { return (lt_dlhandle)1; }
    lt_dlhandle lt_dlopen(const char* name) { return (lt_dlhandle)1; }
    int lt_dlclose(lt_dlhandle handle) { return 0; }
    const char* lt_dlerror() { return "ltdl stub error"; }
}

static void log_func(GPLogLevel level, const char *domain, const char *str, void *data) {
    if (level <= GP_LOG_ERROR) LOGE("gPhoto2 [%s] %s", domain, str);
    else if (level <= GP_LOG_DEBUG) LOGI("gPhoto2 [%s] %s", domain, str);
}

static Camera *g_camera = nullptr;
static GPContext *g_context = nullptr;

extern "C" JNIEXPORT jint JNICALL
Java_net_codeedu_dslrsidekickpro_MainActivity_connectToCamera(
        JNIEnv* env, jobject /* this */, jint fileDescriptor) {

    LOGI("--- gPhoto2 Android Bridge (FD: %d) ---", fileDescriptor);

    // 设置 HOME 环境变量以避免配置目录报错
    setenv("HOME", "/data/data/net.codeedu.dslrsidekickpro/cache", 1);

    if (g_camera) {
        gp_camera_unref(g_camera);
        g_camera = nullptr;
    }
    if (g_context) {
        gp_context_unref(g_context);
        g_context = nullptr;
    }

    GPPortInfoList *portInfoList;
    GPPortInfo portInfo;
    CameraAbilitiesList *abilitiesList;
    int rc;

    gp_log_add_func(GP_LOG_VERBOSE, log_func, nullptr);
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);

    g_context = gp_context_new();
    gp_port_usb_set_sys_device(fileDescriptor);

    // 1. 配置虚拟端口
    gp_port_info_list_new(&portInfoList);
    GPPortInfo pi;
    gp_port_info_new(&pi);
    gp_port_info_set_path(pi, "usb:0,0");
    gp_port_info_set_name(pi, "USB-Android-Bridge");
    gp_port_info_set_type(pi, GP_PORT_USB);
    gp_port_info_set_library_filename(pi, (char*)"libusb1");
    gp_port_info_list_append(portInfoList, pi);
    gp_port_info_list_get_info(portInfoList, 0, &portInfo);

    // 2. 加载型号列表并选择最匹配的驱动
    gp_abilities_list_new(&abilitiesList);
    camera_abilities(abilitiesList);

    gp_camera_new(&g_camera);
    gp_camera_set_port_info(g_camera, portInfo);

    int aidx = gp_abilities_list_lookup_model(abilitiesList, "Nikon DSC D610 (PTP mode)");
    if (aidx < 0) aidx = gp_abilities_list_lookup_model(abilitiesList, "USB PTP Class Camera");

    if (aidx >= 0) {
        CameraAbilities ab;
        gp_abilities_list_get_abilities(abilitiesList, aidx, &ab);
        // 回退到 Class 匹配模式，这是 Android 上最稳健的方式
        ab.usb_vendor  = 0;
        ab.usb_product = 0;
        ab.usb_class   = 6; // PTP Class
        std::strcpy(ab.library, "libptp2");
        gp_camera_set_abilities(g_camera, ab);
        LOGI("Driver selected: %s (Class 6 PTP)", ab.model);
    }
    else {
        LOGE("No compatible driver found in abilities list!");
    }

    // 3. 执行初始化
    LOGI("Calling gp_camera_init...");
    rc = gp_camera_init(g_camera, g_context);

    gp_abilities_list_free(abilitiesList);
    gp_port_info_list_free(portInfoList);

    if (rc == GP_OK) {
        LOGI("Initialization SUCCESS!");
        return 0;
    } else {
        LOGE("Initialization FAILED: %d", rc);
        gp_camera_unref(g_camera);
        gp_context_unref(g_context);
        g_camera = nullptr;
        g_context = nullptr;
        return rc;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_net_codeedu_dslrsidekickpro_MainActivity_captureImage(JNIEnv* env, jobject) {
    if (!g_camera || !g_context) return -1;

    CameraFilePath path;
    int rc = gp_camera_capture(g_camera, GP_CAPTURE_IMAGE, &path, g_context);
    if (rc == GP_OK) {
        LOGI("Capture successful: %s/%s", path.folder, path.name);
    } else {
        LOGE("Capture failed: %d", rc);
    }
    return rc;
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_codeedu_dslrsidekickpro_MainActivity_getConfig(JNIEnv* env, jobject, jstring key) {
    if (!g_camera || !g_context) return env->NewStringUTF("Not connected");

    const char *native_key = env->GetStringUTFChars(key, 0);
    CameraWidget *root_widget, *child_widget;
    int rc = gp_camera_get_config(g_camera, &root_widget, g_context);
    if (rc != GP_OK) {
        env->ReleaseStringUTFChars(key, native_key);
        return env->NewStringUTF("Error getting config");
    }

    rc = gp_widget_get_child_by_name(root_widget, native_key, &child_widget);
    if (rc != GP_OK) {
        gp_widget_free(root_widget);
        env->ReleaseStringUTFChars(key, native_key);
        return env->NewStringUTF("Key not found");
    }

    char *value;
    rc = gp_widget_get_value(child_widget, &value);
    jstring result;
    if (rc == GP_OK) {
        result = env->NewStringUTF(value);
    } else {
        result = env->NewStringUTF("Error getting value");
    }

    gp_widget_free(root_widget);
    env->ReleaseStringUTFChars(key, native_key);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_codeedu_dslrsidekickpro_MainActivity_getSummary(JNIEnv* env, jobject) {
    if (!g_camera || !g_context) return env->NewStringUTF("Not connected");

    CameraText summary;
    int rc = gp_camera_get_summary(g_camera, &summary, g_context);
    if (rc == GP_OK) {
        return env->NewStringUTF(summary.text);
    } else {
        return env->NewStringUTF("Failed to get summary");
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_net_codeedu_dslrsidekickpro_MainActivity_capturePreview(JNIEnv* env, jobject) {
    if (!g_camera || !g_context) return nullptr;

    CameraFile *file;
    gp_file_new(&file);

    int rc = gp_camera_capture_preview(g_camera, file, g_context);
    if (rc != GP_OK) {
        gp_file_unref(file);
        return nullptr;
    }

    const char *data;
    unsigned long size;
    gp_file_get_data_and_size(file, &data, &size);

    jbyteArray result = env->NewByteArray((jsize)size);
    env->SetByteArrayRegion(result, 0, (jsize)size, (const jbyte*)data);

    gp_file_unref(file);
    return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_net_codeedu_dslrsidekickpro_MainActivity_listFoldersInFolder(JNIEnv* env, jobject, jstring folderPath) {
    if (!g_camera || !g_context) return nullptr;
    const char *path = env->GetStringUTFChars(folderPath, nullptr);
    CameraList *list;
    gp_list_new(&list);
    LOGI("Listing folders in: %s", path);
    int rc = gp_camera_folder_list_folders(g_camera, path, list, g_context);
    env->ReleaseStringUTFChars(folderPath, path);
    if (rc != GP_OK) {
        gp_list_free(list);
        return nullptr;
    }
    int count = gp_list_count(list);
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(count, stringClass, nullptr);
    for (int i = 0; i < count; i++) {
        const char *name;
        gp_list_get_name(list, i, &name);
        env->SetObjectArrayElement(result, i, env->NewStringUTF(name));
    }
    gp_list_free(list);
    return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_net_codeedu_dslrsidekickpro_MainActivity_listFilesInFolder(JNIEnv* env, jobject, jstring folderPath) {
    if (!g_camera || !g_context) return nullptr;
    const char *path = env->GetStringUTFChars(folderPath, nullptr);
    CameraList *list;
    gp_list_new(&list);
    LOGI("Listing files in: %s", path);
    int rc = gp_camera_folder_list_files(g_camera, path, list, g_context);
    env->ReleaseStringUTFChars(folderPath, path);

    if (rc != GP_OK) {
        gp_list_free(list);
        return nullptr;
    }

    int count = gp_list_count(list);
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(count, stringClass, nullptr);

    for (int i = 0; i < count; i++) {
        const char *name;
        gp_list_get_name(list, i, &name);
        env->SetObjectArrayElement(result, i, env->NewStringUTF(name));
    }

    gp_list_free(list);
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_net_codeedu_dslrsidekickpro_MainActivity_downloadFile(JNIEnv* env, jobject, jstring folderPath, jstring fileName) {
    if (!g_camera || !g_context) return nullptr;

    const char *fpath = env->GetStringUTFChars(folderPath, nullptr);
    const char *fname = env->GetStringUTFChars(fileName, nullptr);

    LOGI("Downloading file: %s/%s", fpath, fname);

    CameraFile *file;
    gp_file_new(&file);

    int rc = gp_camera_file_get(g_camera, fpath, fname, GP_FILE_TYPE_NORMAL, file, g_context);
    env->ReleaseStringUTFChars(folderPath, fpath);
    env->ReleaseStringUTFChars(fileName, fname);

    if (rc != GP_OK) {
        LOGE("Failed to download file, error code: %d", rc);
        gp_file_unref(file);
        return nullptr;
    }

    const char *data;
    unsigned long size;
    gp_file_get_data_and_size(file, &data, &size);
    LOGI("Download successful, size: %lu bytes", size);

    jbyteArray result = env->NewByteArray((jsize)size);
    env->SetByteArrayRegion(result, 0, (jsize)size, (const jbyte*)data);

    gp_file_unref(file);
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_net_codeedu_dslrsidekickpro_MainActivity_downloadThumbnail(JNIEnv* env, jobject, jstring folderPath, jstring fileName) {
    if (!g_camera || !g_context) return nullptr;
    const char *fpath = env->GetStringUTFChars(folderPath, nullptr);
    const char *fname = env->GetStringUTFChars(fileName, nullptr);

    CameraFile *file;
    gp_file_new(&file);
    // 使用 GP_FILE_TYPE_PREVIEW 获取内置缩略图
    int rc = gp_camera_file_get(g_camera, fpath, fname, GP_FILE_TYPE_PREVIEW, file, g_context);
    env->ReleaseStringUTFChars(folderPath, fpath);
    env->ReleaseStringUTFChars(fileName, fname);

    if (rc != GP_OK) {
        gp_file_unref(file);
        return nullptr;
    }

    const char *data;
    unsigned long size;
    gp_file_get_data_and_size(file, &data, &size);
    jbyteArray result = env->NewByteArray((jsize)size);
    env->SetByteArrayRegion(result, 0, (jsize)size, (const jbyte*)data);
    gp_file_unref(file);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_codeedu_dslrsidekickpro_MainActivity_pollEvent(JNIEnv* env, jobject, jint timeoutMs) {
    if (!g_camera || !g_context) return nullptr;

    CameraEventType type;
    void *data = nullptr;

    // 阻塞式等待。硬件中断一到，此函数会立即返回，不再受 10ms 轮询限制。
    int rc = gp_camera_wait_for_event(g_camera, timeoutMs, &type, &data, g_context);

    if (rc != GP_OK || type == GP_EVENT_UNKNOWN || type == GP_EVENT_TIMEOUT) {
        if (data) free(data);
        return nullptr;
    }

    if (type == GP_EVENT_FILE_ADDED) {
        CameraFilePath *path = (CameraFilePath *)data;
        char fullPath[512];
        snprintf(fullPath, sizeof(fullPath), "%s/%s", path->folder, path->name);
        free(data);
        return env->NewStringUTF(fullPath);
    }

    if (data) free(data);
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_codeedu_dslrsidekickpro_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "libgphoto2 " + std::string(VERSION);
    return env->NewStringUTF(hello.c_str());
}
