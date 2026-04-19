cmake_minimum_required(VERSION 3.22.1)

project("dslrsidekickpro")

find_library(log-lib log)

set(EXTERNAL_DIR ${CMAKE_SOURCE_DIR}/../../../../external)

# --- libusb 配置 ---
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

target_compile_definitions(usb PRIVATE HAVE_CONFIG_H _GNU_SOURCE)
target_compile_options(usb PRIVATE -fvisibility=hidden -pthread)
target_include_directories(usb PUBLIC
    ${EXTERNAL_DIR}/libusb/libusb
    ${EXTERNAL_DIR}/libusb/android
)
target_link_libraries(usb log)

# --- libexif 配置 ---
# 暂时禁用 libexif，Android 使用 ExifInterface 代替
# add_library(exif SHARED ... )
# target_compile_definitions(exif PRIVATE HAVE_CONFIG_H)
# target_include_directories(exif PUBLIC ... )
# target_link_libraries(exif log)

# --- libgphoto2 核心与 PTP2 驱动静态链接 ---
# 我们把核心库和 ptp2 驱动编译到一起，避免 Android 上的动态加载问题
add_library(gphoto2 SHARED
        native-lib.cpp

        # libgphoto2 核心
        ${EXTERNAL_DIR}/libgphoto2/libgphoto2/gphoto2-camera.c
        ${EXTERNAL_DIR}/libgphoto2/libgphoto2/gphoto2-context.c
        ${EXTERNAL_DIR}/libgphoto2/libgphoto2/gphoto2-file.c
        ${EXTERNAL_DIR}/libgphoto2/libgphoto2/gphoto2-filesys.c
        ${EXTERNAL_DIR}/libgphoto2/libgphoto2/gphoto2-list.c
        ${EXTERNAL_DIR}/libgphoto2/libgphoto2/gphoto2-result.c
        ${EXTERNAL_DIR}/libgphoto2/libgphoto2/gphoto2-setting.c
        ${EXTERNAL_DIR}/libgphoto2/libgphoto2/gphoto2-abilities-list.c
        ${EXTERNAL_DIR}/libgphoto2/libgphoto2/gphoto2-widget.c

        # libgphoto2_port 核心
        ${EXTERNAL_DIR}/libgphoto2/libgphoto2_port/libgphoto2_port/gphoto2-port.c
        ${EXTERNAL_DIR}/libgphoto2/libgphoto2_port/libgphoto2_port/gphoto2-port-info-list.c
        ${EXTERNAL_DIR}/libgphoto2/libgphoto2_port/libgphoto2_port/gphoto2-port-log.c
        ${EXTERNAL_DIR}/libgphoto2/libgphoto2_port/libgphoto2_port/gphoto2-port-result.c
        ${EXTERNAL_DIR}/libgphoto2/libgphoto2_port/libgphoto2_port/gphoto2-port-locking.c
        ${EXTERNAL_DIR}/libgphoto2/libgphoto2_port/libgphoto2_port/gphoto2-port-portability.c

        # libusb1 端口实现
        ${EXTERNAL_DIR}/libgphoto2/libgphoto2_port/libusb1/libusb1.c

        # PTP2 驱动核心 (静态编译进入)
        ${EXTERNAL_DIR}/libgphoto2/camlibs/ptp2/library.c
        ${EXTERNAL_DIR}/libgphoto2/camlibs/ptp2/ptp.c
        ${EXTERNAL_DIR}/libgphoto2/camlibs/ptp2/usb.c
        ${EXTERNAL_DIR}/libgphoto2/camlibs/ptp2/config.c
        ${EXTERNAL_DIR}/libgphoto2/camlibs/ptp2/fujiptpip.c
        ${EXTERNAL_DIR}/libgphoto2/camlibs/ptp2/ptpip.c
        ${EXTERNAL_DIR}/libgphoto2/camlibs/ptp2/chdk.c

        # ltdl stub
        ltdl_stub.c
)

# 宏定义
target_compile_definitions(gphoto2 PRIVATE
    HAVE_CONFIG_H
    _GPHOTO2_INTERNAL_CODE
    _GNU_SOURCE
    CAMLIBS_STATIC=1
    GP_MODULE_PATH="/sdcard/unused"
)

target_include_directories(gphoto2 PRIVATE
        ${CMAKE_SOURCE_DIR}                     # 包含 config.h 和 ltdl.h
        ${CMAKE_SOURCE_DIR}/libgphoto2          # 包含 gphoto2-endian.h
        ${EXTERNAL_DIR}/libgphoto2
        ${EXTERNAL_DIR}/libgphoto2/gphoto2      # 包含 gphoto2-camera.h
        ${EXTERNAL_DIR}/libgphoto2/libgphoto2
        ${EXTERNAL_DIR}/libgphoto2/libgphoto2_port
        ${EXTERNAL_DIR}/libgphoto2/libgphoto2_port/gphoto2
        ${EXTERNAL_DIR}/libgphoto2/camlibs/ptp2
        ${EXTERNAL_DIR}/libusb/libusb
)

target_link_libraries(gphoto2 usb log)
