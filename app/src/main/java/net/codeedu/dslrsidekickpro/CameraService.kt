package net.codeedu.dslrsidekickpro

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import android.content.ContentValues
import android.provider.MediaStore
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import android.net.Uri

class CameraService : Service() {

    private val ACTION_USB_PERMISSION = "net.codeedu.dslrsidekickpro.USB_PERMISSION"
    private var isCameraConnected = false
    private var isConnecting = false // 新增状态锁，防止并发连接
    private var usbDeviceConnection: android.hardware.usb.UsbDeviceConnection? = null
    private lateinit var usbManager: UsbManager
    private var eventPollingExecutor: java.util.concurrent.ExecutorService? = null
    
    private val binder = CameraBinder()
    // 使用 CopyOnWriteArrayList 确保线程安全，避免竞态条件
    private val listeners = CopyOnWriteArrayList<CameraEventListener>()

    enum class CameraStatus(val label: String, val isConnected: Boolean) {
        DISCONNECTED("相机未连接", false),
        CONNECTED("相机连接", true),
        SYNCING("同步中", true)
    }

    interface CameraEventListener {
        fun onCameraStatusUpdate(status: CameraStatus, extraMessage: String? = null)
        /**
         * Notify listener of a new photo saved to gallery.
         * @param uri content Uri for the saved image (always provided)
         * @param realPath real filesystem path when available (maybe null on scoped storage)
         * @param fromLiveEvent true when photo came from a live camera event (shutter press),
         *                      false when photo came from a batch sync.
         */
        fun onNewPhoto(uri: Uri, realPath: String?, fromLiveEvent: Boolean)

        /**
         * Sync progress callbacks. total may be -1 if unknown.
         */
        fun onSyncProgress(current: Int, total: Int)
        fun onSyncCompleted(total: Int)
    }

    private fun countJpgFilesRecursive(path: String, existingFiles: MutableSet<String>, countedPaths: MutableSet<String>): Int {
        if (!isCameraConnected) return 0
        var total = 0
        try {
            val folders = listFoldersInFolder(path)
            folders?.forEach { sub ->
                if (!sub.startsWith(".") && sub != "MISC") {
                    val subPath = if (path.endsWith("/")) "$path$sub" else "$path/$sub"
                    total += countJpgFilesRecursive(subPath, existingFiles, countedPaths)
                }
            }
            val files = listFilesInFolder(path)
            files?.filter { it.lowercase().endsWith(".jpg") }?.forEach { fileName ->
                val fullPathKey = if (path.endsWith('/')) "$path$fileName" else "$path/$fileName"
                if (countedPaths.contains(fullPathKey)) return@forEach
                if (existingFiles.contains(fileName)) {
                    countedPaths.add(fullPathKey)
                    return@forEach
                }
                countedPaths.add(fullPathKey)
                total++
            }
        } catch (e: Exception) {
            Log.e("CameraService", "Error counting folder: $path", e)
        }
        return total
    }

    inner class CameraBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(UsbManager::class.java) as UsbManager
        startForegroundService()

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED) // Added for dynamic USB connection handling
        @Suppress("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        findAndConnectCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        findAndConnectCamera()
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "CameraServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Camera Sync Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("DSLR Sidekick Syncing")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e("CameraService", "Failed to start foreground service", e)
        }
    }

    fun addListener(listener: CameraEventListener) {
        listeners.add(listener)
        // 添加监听器时，立即发送当前状态
        val currentStatus = if (isCameraConnected) CameraStatus.CONNECTED else CameraStatus.DISCONNECTED
        listener.onCameraStatusUpdate(currentStatus)
    }

    fun removeListener(listener: CameraEventListener) {
        listeners.remove(listener)
    }


    private fun updateStatus(status: CameraStatus, extraMessage: String? = null) {
        val logText = if (extraMessage != null) "${status.label} ($extraMessage)" else status.label
        Log.i("CameraService", logText)
        listeners.forEach { it.onCameraStatusUpdate(status, extraMessage) }
    }

    private fun findAndConnectCamera() {
        if (isCameraConnected || isConnecting) return
        val deviceList = usbManager.deviceList
        // Keep detailed USB information at DEBUG level to avoid noisy production logs
        Log.d("CameraService", "USB Device List: ${deviceList.values}")
        if (deviceList.isEmpty()) {
            updateStatus(CameraStatus.DISCONNECTED)
            return
        }
        for (device in deviceList.values) {
            Log.d("CameraService", "Checking device: ${device.deviceName} (${device.productName})")
            
            // 简单的识别：PTP Class (6) 或者常见的 VendorID
            val isPTP = (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == 6 }
            val isKnownBrand = device.vendorId == 0x04b0 || device.vendorId == 0x04a9 || device.vendorId == 0x04cb
            
            if (isPTP || isKnownBrand) {
                if (usbManager.hasPermission(device)) {
                    openAndConnect(device)
                } else {
                    requestPermission(device)
                }
                return
            }
        }
        
        // 兜底：如果没匹配到，尝试第一个
        val first = deviceList.values.first()
        if (usbManager.hasPermission(first)) openAndConnect(first) else requestPermission(first)
    }

    private fun requestPermission(device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).apply {
                setPackage(packageName) // 关键修复：指定包名，使 Intent 显式化，适配 API 34
                putExtra(UsbManager.EXTRA_DEVICE, device)
            },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun openAndConnect(device: UsbDevice) {
        if (isConnecting) return
        isConnecting = true
        try {
            usbDeviceConnection?.close()
            usbDeviceConnection = usbManager.openDevice(device)
            val connection = usbDeviceConnection
            if (connection != null) {
                val fd = connection.fileDescriptor
                Thread {
                    try {
                        var result = connectToCamera(fd)
                        var retryCount = 0

                        // 重试逻辑：最多重试2次
                        while (result != 0 && retryCount < 2) {
                            Log.w("CameraService", "Connection failed, retrying... (attempt ${retryCount + 2})")
                            Thread.sleep(1000)
                            result = connectToCamera(fd)
                            retryCount++
                        }

                        if (result == 0) {
                            isCameraConnected = true
                            updateStatus(CameraStatus.CONNECTED)
                            syncAllPhotos()
                            startEventPolling()
                        } else {
                            isCameraConnected = false
                            updateStatus(CameraStatus.DISCONNECTED, "错误: $result")
                        }
                    } finally {
                        isConnecting = false // 无论成功失败，重置状态
                    }
                }.start()
            } else {
                isConnecting = false
                updateStatus(CameraStatus.DISCONNECTED, "Failed to open USB device")
            }
        } catch (e: Exception) {
            isConnecting = false
            Log.e("CameraService", "Error in openAndConnect", e)
            updateStatus(CameraStatus.DISCONNECTED, e.message)
        }
    }

    override fun onDestroy() {
        isCameraConnected = false
        disconnectCameraNative()
        usbDeviceConnection?.close()
        // Shutdown event polling executor to prevent thread leak
        eventPollingExecutor?.shutdown()
        try {
            if (!eventPollingExecutor?.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)!!) {
                eventPollingExecutor?.shutdownNow()
            }
        } catch (_: InterruptedException) {
            eventPollingExecutor?.shutdownNow()
        }
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_USB_PERMISSION == action) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    device?.let { openAndConnect(it) } ?: findAndConnectCamera()
                } else {
                    updateStatus(CameraStatus.DISCONNECTED, "USB Permission Denied")
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                isCameraConnected = false
                disconnectCameraNative()
                updateStatus(CameraStatus.DISCONNECTED)
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == action) { // Handle USB device attachment
                // USB attach is informational but verbose; log at DEBUG level
                Log.d("CameraService", "USB Device Attached")
                findAndConnectCamera()
            }
        }
    }

    private fun syncAllPhotos() {
        if (!isCameraConnected) {
            Log.w("CameraService", "Sync aborted: Camera not connected")
            return
        }

        Log.i("CameraService", "Starting photo sync...")
        // Single thread executor for sync tasks to avoid concurrency issues with gphoto2 context
        val syncExecutor = Executors.newSingleThreadExecutor()
        val downloadedCount = AtomicInteger(0)
        syncExecutor.execute {
            try {
                updateStatus(CameraStatus.SYNCING)
                // Give camera a moment to initialize internal storage
                Thread.sleep(1500)

                val existingFiles = getExistingPublicPhotos()
                Log.d("CameraService", "Existing photos in gallery: ${existingFiles.size}")

                // Tracks full camera paths already downloaded during this sync run to prevent duplicates
                val downloadedPaths = mutableSetOf<String>()

                // Pre-count total candidate JPGs to provide progress feedback
                val countedPathsForCount = mutableSetOf<String>()
                var totalCandidates = 0
                try {
                    totalCandidates += countJpgFilesRecursive("/DCIM", existingFiles, countedPathsForCount)
                    val rootFolders = listFoldersInFolder("/") ?: emptyArray()
                    rootFolders.forEach { store ->
                        if (store.equals("DCIM", ignoreCase = true)) return@forEach
                        val path = if (store.startsWith("/")) store else "/$store"
                        totalCandidates += countJpgFilesRecursive(path, existingFiles, countedPathsForCount)
                        totalCandidates += countJpgFilesRecursive("$path/DCIM", existingFiles, countedPathsForCount)
                    }
                } catch (e: Exception) {
                    Log.e("CameraService", "Error counting files for progress", e)
                }

                // notify listeners sync started
                listeners.forEach { it.onSyncProgress(0, if (totalCandidates > 0) totalCandidates else -1) }

                // 1. Try common DCIM path
                scanFolderRecursive("/DCIM", existingFiles, downloadedPaths, downloadedCount, totalCandidates)

                // 2. Scan root for storage volumes
                val rootFolders = listFoldersInFolder("/") ?: emptyArray()
                rootFolders.forEach { store ->
                    if (store.equals("DCIM", ignoreCase = true)) return@forEach
                    val path = if (store.startsWith("/")) store else "/$store"
                    scanFolderRecursive(path, existingFiles, downloadedPaths, downloadedCount, totalCandidates)
                    scanFolderRecursive("$path/DCIM", existingFiles, downloadedPaths, downloadedCount, totalCandidates)
                }
            } catch (e: Exception) {
                    Log.e("CameraService", "Sync error", e)
            } finally {
                // 确保 executor 被关闭，防止资源泄漏
                syncExecutor.shutdown()
                Log.i("CameraService", "Photo sync completed")
                try {
                    listeners.forEach { it.onSyncCompleted(downloadedCount.get()) }
                    if (isCameraConnected) {
                        updateStatus(CameraStatus.CONNECTED)
                    }
                } catch (e: Exception) {
                    Log.e("CameraService", "Error notifying sync completion", e)
                }
            }
        }
    }

    private fun scanFolderRecursive(
        path: String,
        existingFiles: MutableSet<String>,
        downloadedPaths: MutableSet<String>,
        downloadedCount: AtomicInteger,
        totalCount: Int
    ) {
        if (!isCameraConnected) return

        try {
            val folders = listFoldersInFolder(path)
            folders?.forEach { sub ->
                if (!sub.startsWith(".") && sub != "MISC") {
                    val subPath = if (path.endsWith("/")) "$path$sub" else "$path/$sub"
                    scanFolderRecursive(subPath, existingFiles, downloadedPaths, downloadedCount, totalCount)
                }
            }

            val files = listFilesInFolder(path)
            files?.filter { it.lowercase().endsWith(".jpg") }?.reversed()?.forEach { fileName ->
                // 添加连接检查，防止断开时继续操作
                if (!isCameraConnected) return@forEach

                val fullPathKey = if (path.endsWith('/')) "$path$fileName" else "$path/$fileName"

                // Skip if this exact camera path was already downloaded in this run
                if (downloadedPaths.contains(fullPathKey)) return@forEach

                // Skip if a file with same display name already exists in gallery
                if (existingFiles.contains(fileName)) {
                    // Non-critical info — use DEBUG to avoid noisy logs in production
                    Log.d("CameraService", "Skipping (already in gallery): $fileName")
                    // still mark as downloaded path to avoid revisiting same camera file
                    downloadedPaths.add(fullPathKey)
                    return@forEach
                }

                // Per-file sync events are verbose; log at DEBUG level
                Log.d("CameraService", "Syncing new file: $fullPathKey")
                val data = downloadFileWithTimeout(path, fileName)
                if (data != null) {
                    val uri = saveToPublicGallery(fileName, data)
                    if (uri != null) {
                        // Add to existingFiles so further scans in this run won't re-download by name
                        existingFiles.add(fileName)
                        downloadedPaths.add(fullPathKey)

                        val realPath = getRealPathFromURI(uri)
                        // Notify listeners with the Uri and best-effort real path
                        listeners.forEach { listener -> listener.onNewPhoto(uri, realPath, false) }

                        // update progress
                        try {
                            val cur = downloadedCount.incrementAndGet()
                            listeners.forEach { listener -> listener.onSyncProgress(cur, if (totalCount > 0) totalCount else -1) }
                        } catch (e: Exception) {
                            Log.e("CameraService", "Progress notify error", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CameraService", "Error scanning folder: $path", e)
        }
    }

    private fun startEventPolling() {
        eventPollingExecutor = Executors.newSingleThreadExecutor()
        eventPollingExecutor?.execute {
            while (isCameraConnected) {
                try {
                    val fullPath = pollEvent(200)
                    if (fullPath == null) {
                        Thread.sleep(500)
                        continue
                    }
                    val lastSlash = fullPath.lastIndexOf('/')
                    if (lastSlash != -1) {
                        val folder = fullPath.substring(0, lastSlash)
                        val fileName = fullPath.substring(lastSlash + 1)
                        if (fileName.lowercase().endsWith(".jpg")) {
                            // 添加超时机制下载文件
                            val imageData = downloadFileWithTimeout(folder, fileName)
                            if (imageData != null) {
                                val uri = saveToPublicGallery(fileName, imageData)
                                uri?.let {
                                    val realPath = getRealPathFromURI(it)
                                    listeners.forEach { listener -> listener.onNewPhoto(it, realPath, true) }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isCameraConnected) {
                        Log.e("CameraService", "Polling error", e)
                    }
                    if (!isCameraConnected) break
                }
            }
        }
    }

    private fun saveToPublicGallery(fileName: String, data: ByteArray): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DSLR_Sidekick")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = contentResolver.insert(collection, values) ?: return null
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(data) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            Log.e("CameraService", "Error saving to gallery", e)
            contentResolver.delete(uri, null, null)
            return null
        }
    }

    private fun getExistingPublicPhotos(): MutableSet<String> {
        val names = mutableSetOf<String>()
        val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        
        // 使用更宽泛的查询方式，确保兼容性
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ?"
        }
        val selectionArgs = arrayOf("%DSLR_Sidekick%")

        try {
            contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val index = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                if (index != -1) {
                    while (cursor.moveToNext()) {
                        cursor.getString(index)?.let { names.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CameraService", "Error querying MediaStore", e)
            // Fallback: return empty set to allow sync to continue
            Log.w("CameraService", "Using fallback: treating all photos as new")
        }
        return names
    }

    private fun getRealPathFromURI(uri: Uri): String? {
        return try {
            val projection = arrayOf(MediaStore.Images.Media.DATA)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    cursor.getString(columnIndex)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("CameraService", "Error getting real path from URI", e)
            null
        }
    }

    /**
     * 带超时机制的文件下载包装方法
     * @param folderPath 文件夹路径
     * @param fileName 文件名
     * @return 文件数据，或在超时/错误时返回null
     */
    private fun downloadFileWithTimeout(folderPath: String, fileName: String): ByteArray? {
        val timeoutMs = 30000L
        return try {
            val startTime = System.currentTimeMillis()
            var result: ByteArray? = null
            val thread = Thread {
                result = downloadFile(folderPath, fileName)
            }
            thread.start()
            thread.join(timeoutMs)

            if (thread.isAlive) {
                Log.w("CameraService", "Download timeout for $folderPath/$fileName")
                thread.interrupt()
                return null
            }

            val elapsed = System.currentTimeMillis() - startTime
            // Per-file download timing is verbose; keep at DEBUG level
            Log.d("CameraService", "Downloaded $fileName in ${elapsed}ms")
            result
        } catch (e: Exception) {
            Log.e("CameraService", "Error downloading file with timeout", e)
            null
        }
    }

    external fun connectToCamera(fd: Int): Int
    external fun disconnectCameraNative()
    external fun pollEvent(timeoutMs: Int): String?
    external fun listFoldersInFolder(folderPath: String): Array<String>?
    external fun listFilesInFolder(folderPath: String): Array<String>?
    external fun downloadFile(folderPath: String, fileName: String): ByteArray?

    companion object {
        init {
            System.loadLibrary("usb")
            System.loadLibrary("gphoto2")
        }
    }
}
