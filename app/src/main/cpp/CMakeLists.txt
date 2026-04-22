cmake_minimum_required(VERSION 3.22.1)
project("dslrsidekickpro")

# Find Android log library
find_library(log-lib log)

# External libraries directory
set(EXTERNAL_DIR ${CMAKE_SOURCE_DIR}/../../../../external)

# ============================================================================
# libusb1 - USB communication library
# ============================================================================
add_library(usb SHARED
    ${EXTERNAL_DIR}/libusb/libusb/core.c
    ${EXTERNAL_DIR}/libusb/libusb/descriptor.c
    ${EXTERNAL_DIR}/libusb/libusb/hotplug.c
    ${EXTERNAL_DIR}/libusb/libusb/io.c
    ${EXTERNAL_DIR}/libusb/libusb/sync.c
    ${EXTERNAL_DIR}/libusb/libusb/strerror.c
    ${EXTERNAL_DIR}/libusb/libusb/os/linux_usbfs.c
    ${EXTERNAL_DIR}/libusb/libusb/os/events_posix.c
    ${EXTERNAL_DIR}/libusb/libusb/os/threads_posix.c
    ${EXTERNAL_DIR}/libusb/libusb/os/linux_netlink.c
)

target_compile_definitions(usb PRIVATE
    HAVE_CONFIG_H
    _GNU_SOURCE
)

target_compile_options(usb PRIVATE
    -fvisibility=hidden
    -pthread
)

target_include_directories(usb PUBLIC
    ${EXTERNAL_DIR}/libusb/libusb
    ${EXTERNAL_DIR}/libusb/android
)

target_link_libraries(usb ${log-lib})

# ============================================================================
# libgphoto2 - Camera control library (statically linked with PTP2 driver)
# ============================================================================
add_library(gphoto2 SHARED
    # Main JNI interface
    native-lib.cpp
    
    # ltdl stub (fake dynamic loading for static build)
    ltdl_stub.c
    
    # libgphoto2 core
    ${EXTERNAL_DIR}/libgphoto2/libgphoto2/gphoto2-camera.c
    ${EXTERNAL_DIR}/libgphoto2/libgphoto2/gphoto2-context.c
    ${EXTERNAL_DIR}/libgphoto2/libgphoto2/gphoto2-file.c
    ${EXTERNAL_DIR}/libgphoto2/libgphoto2/gphoto2-filesys.c
    ${EXTERNAL_DIR}/libgphoto2/libgphoto2/gphoto2-list.c
    ${EXTERNAL_DIR}/libgphoto2/libgphoto2/gphoto2-result.c
    ${EXTERNAL_DIR}/libgphoto2/libgphoto2/gphoto2-setting.c
    ${EXTERNAL_DIR}/libgphoto2/libgphoto2/gphoto2-abilities-list.c
    ${EXTERNAL_DIR}/libgphoto2/libgphoto2/gphoto2-widget.c
    
    # libgphoto2_port core
    ${EXTERNAL_DIR}/libgphoto2/libgphoto2_port/libgphoto2_port/gphoto2-port.c
    ${EXTERNAL_DIR}/libgphoto2/libgphoto2_port/libgphoto2_port/gphoto2-port-info-list.c
    ${EXTERNAL_DIR}/libgphoto2/libgphoto2_port/libgphoto2_port/gphoto2-port-log.c
    ${EXTERNAL_DIR}/libgphoto2/libgphoto2_port/libgphoto2_port/gphoto2-port-result.c
    ${EXTERNAL_DIR}/libgphoto2/libgphoto2_port/libgphoto2_port/gphoto2-port-locking.c
    ${EXTERNAL_DIR}/libgphoto2/libgphoto2_port/libgphoto2_port/gphoto2-port-portability.c
    
    # libusb1 port implementation
    ${EXTERNAL_DIR}/libgphoto2/libgphoto2_port/libusb1/libusb1.c
    
    # PTP2 camera driver (statically linked)
    ${EXTERNAL_DIR}/libgphoto2/camlibs/ptp2/library.c
    ${EXTERNAL_DIR}/libgphoto2/camlibs/ptp2/ptp.c
    ${EXTERNAL_DIR}/libgphoto2/camlibs/ptp2/usb.c
    ${EXTERNAL_DIR}/libgphoto2/camlibs/ptp2/config.c
    ${EXTERNAL_DIR}/libgphoto2/camlibs/ptp2/fujiptpip.c
    ${EXTERNAL_DIR}/libgphoto2/camlibs/ptp2/ptpip.c
    ${EXTERNAL_DIR}/libgphoto2/camlibs/ptp2/chdk.c
)

# Compile definitions
target_compile_definitions(gphoto2 PRIVATE
    HAVE_CONFIG_H
    _GPHOTO2_INTERNAL_CODE
    _GNU_SOURCE
    CAMLIBS_STATIC=1
    GP_MODULE_PATH="/sdcard/unused"  # Unused on Android, but required by libgphoto2
)

# Include directories
target_include_directories(gphoto2 PRIVATE
    ${CMAKE_SOURCE_DIR}                              # config.h and ltdl.h
    ${CMAKE_SOURCE_DIR}/libgphoto2                   # gphoto2-endian.h
    ${EXTERNAL_DIR}/libgphoto2                       # libgphoto2 root
    ${EXTERNAL_DIR}/libgphoto2/gphoto2               # gphoto2-camera.h
    ${EXTERNAL_DIR}/libgphoto2/libgphoto2            # libgphoto2 source
    ${EXTERNAL_DIR}/libgphoto2/libgphoto2_port       # libgphoto2_port root
    ${EXTERNAL_DIR}/libgphoto2/libgphoto2_port/gphoto2  # port headers
    ${EXTERNAL_DIR}/libgphoto2/camlibs/ptp2          # PTP2 driver
    ${EXTERNAL_DIR}/libusb/libusb                    # libusb headers
)

# Link libraries
target_link_libraries(gphoto2
    usb
    ${log-lib}
)
