/* Fake ltdl.h for static camlibs on Android - declarations only */
#ifndef FAKE_LTDL_H
#define FAKE_LTDL_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void* lt_dlhandle;
typedef void* lt_dladvise;
typedef void* lt_ptr;

int lt_dlinit();
int lt_dlexit();
int lt_dlsetsearchpath(const char* path);
int lt_dladdsearchdir(const char* path);
int lt_dlforeachfile(const char* path, int (*func)(const char*, void*), void* data);
lt_dlhandle lt_dlopenext(const char* name);
lt_dlhandle lt_dlopen(const char* name);
int lt_dlclose(lt_dlhandle handle);
const char* lt_dlerror();
void* lt_dlsym(lt_dlhandle handle, const char* symbol);

#ifdef __cplusplus
}
#endif

#endif
