/**
 * Fake ltdl implementation for Android static build
 * 
 * libgphoto2 normally uses ltdl for dynamic loading of camera drivers.
 * On Android, we statically link all drivers, so these functions are stubs.
 */

#include "ltdl.h"
#include <string.h>
#include <gphoto2/gphoto2-port-library.h>
#include <gphoto2/gphoto2-library.h>

// Forward declarations of statically linked drivers
extern GPPortOperations *gp_port_library_operations(void);
extern int camera_init(Camera *camera, GPContext *context);

/* Initialization and cleanup - no-op for static build */
__attribute__((unused)) int lt_dlinit(void) { return 0; }
__attribute__((unused)) int lt_dlexit(void) { return 0; }

/* Search path functions - not needed for static linking */
__attribute__((unused)) int lt_dlsetsearchpath(const char *path) { (void)path; return 0; }
__attribute__((unused)) int lt_dladdsearchdir(const char *path) { (void)path; return 0; }
__attribute__((unused)) int lt_dlforeachfile(const char *path, int (*func)(const char *, void *), void *data) {
    (void)path;
    (void)func;
    (void)data;
    return 0;
}

/**
 * Open a library by name
 * Returns dummy handle for known static libraries (usb, ptp)
 */
lt_dlhandle lt_dlopenext(const char *name) {
    if (name == NULL) {
        return NULL;
    }
    
    // Check if this is a known static library
    // libgphoto2 uses names like "libusb1" or "ptp2"
    if (strstr(name, "usb") != NULL || strstr(name, "ptp") != NULL) {
        return (lt_dlhandle)1;  // Dummy non-NULL handle
    }
    
    return NULL;  // Unknown library
}

lt_dlhandle lt_dlopen(const char *name) {
    return lt_dlopenext(name);
}

int lt_dlclose(lt_dlhandle handle) {
    (void)handle;
    return 0;
}

const char *lt_dlerror(void) {
    return "Static stub error";
}

/**
 * Get symbol address from loaded library
 * Maps static function pointers for known symbols
 */
void *lt_dlsym(lt_dlhandle handle, const char *symbol) {
    if (handle != (lt_dlhandle)1 || symbol == NULL) {
        return NULL;
    }
    
    // Map symbol names to static function pointers
    if (strcmp(symbol, "gp_port_library_operations") == 0) {
        return (void *)gp_port_library_operations;
    }
    
    if (strcmp(symbol, "camera_init") == 0) {
        return (void *)camera_init;
    }
    
    return NULL;  // Symbol not found
}
