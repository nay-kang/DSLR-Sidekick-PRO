#include "ltdl.h"
#include <string.h>
#include <gphoto2/gphoto2-port-library.h>
#include <gphoto2/gphoto2-library.h>

// Forward declarations of static drivers
extern GPPortOperations *gp_port_library_operations(void);
extern int camera_init(Camera *camera, GPContext *context);

int lt_dlinit() { return 0; }
int lt_dlexit() { return 0; }
int lt_dlsetsearchpath(const char* path) { return 0; }
int lt_dladdsearchdir(const char* path) { return 0; }
int lt_dlforeachfile(const char* path, int (*func)(const char*, void*), void* data) { return 0; }

lt_dlhandle lt_dlopenext(const char* name) {
    if (name == NULL) return NULL;
    // libgphoto2 uses names like "libusb1" or "ptp2"
    if (strstr(name, "usb") || strstr(name, "ptp")) {
        return (lt_dlhandle)1; // Dummy handle
    }
    return NULL;
}

lt_dlhandle lt_dlopen(const char* name) {
    return lt_dlopenext(name);
}

int lt_dlclose(lt_dlhandle handle) { return 0; }
const char* lt_dlerror() { return "Static stub error"; }

void* lt_dlsym(lt_dlhandle handle, const char* symbol) {
    if (handle == (lt_dlhandle)1) {
        if (strcmp(symbol, "gp_port_library_operations") == 0) {
            return (void*)gp_port_library_operations;
        }
        if (strcmp(symbol, "camera_init") == 0) {
            return (void*)camera_init;
        }
    }
    return NULL;
}
