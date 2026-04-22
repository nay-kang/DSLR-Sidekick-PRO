/*
 * Optimized libgphoto2 config.h for Android NDK
 * 
 * This file provides compile-time configuration for libgphoto2
 * when building statically on Android.
 */
#ifndef GPHOTO2_CONFIG_H
#define GPHOTO2_CONFIG_H

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <stdarg.h>
#include <time.h>
#include <sys/time.h>
#include <endian.h>

/* USB support via libusb1 */
#define HAVE_LIBUSB 1
#define HAVE_LIBUSB1 1
#define HAVE_LIBUSB_WRAP_SYS_DEVICE 1
#define HAVE_LIBUSB_OPTION_NO_DEVICE_DISCOVERY 1

/* Standard library features */
#define HAVE_REGEX 1
#define HAVE_STRDLEN 1
#define HAVE_GETTIMEOFDAY 1

/* Available headers */
#define HAVE_SYSLOG_H 1
#define HAVE_UNISTD_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_LIMITS_H 1
#define HAVE_STDARG_H 1
#define HAVE_ENDIAN_H 1
#define HAVE_TIME_H 1
#define HAVE_SYS_TIME_H 1
#define HAVE_MALLOC_H 1

/* Available functions */
#define HAVE_MALLOC 1
#define HAVE_CALLOC 1
#define HAVE_REALLOC 1
#define HAVE_FREE 1
#define HAVE_STRTOUL 1
#define HAVE_SPRINTF 1
#define HAVE_SNPRINTF 1
#define HAVE_QSORT 1
#define HAVE_ABS 1
#define HAVE_STRCASECMP 1
#define HAVE_STRNCASECMP 1

/* Package information */
#define VERSION "2.5.31"
#define PACKAGE "libgphoto2"
#define GETTEXT_PACKAGE "libgphoto2"
#define GETTEXT_PACKAGE_LIBGPHOTO2 "libgphoto2"
#define GETTEXT_PACKAGE_LIBGPHOTO2_PORT "libgphoto2_port"
#define URL_USB_MASSSTORAGE "https://github.com/libgphoto2"

/* Static linking configuration */
#define HAVE_LTDL 1        /* Fake ltdl stub implementation */
#define CAMLIBS_STATIC 1   /* All camera drivers linked statically */

/* Directory paths (unused on Android, but required by libgphoto2) */
#ifndef LOCALEDIR
# define LOCALEDIR "/sdcard/unused/locale"
#endif
#ifndef CAMLIBS
# define CAMLIBS "/sdcard/unused/camlibs"
#endif
#ifndef IOLIBS
# define IOLIBS "/sdcard/unused/iolibs"
#endif

/* Disable unnecessary features to reduce binary size */
#undef HAVE_LIBEXIF    /* Using Android's ExifInterface instead */
#undef HAVE_LIBJPEG    /* Not needed for core functionality */
#undef HAVE_AA         /* Anti-aliasing not needed */
#undef HAVE_GD         /* Graphics library not needed */

#endif /* GPHOTO2_CONFIG_H */
