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
import android.provider.MediaStore
import android.provider.DocumentsContract
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import androidx.core.net.toUri

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
            AppLogger.e("CameraService", "Failed to start foreground service", e)
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
                val vendorId = device.vendorId
                val productId = device.productId
                Thread {
                    try {
                        var result = connectToCamera(fd, vendorId, productId)
                        var retryCount = 0

                        // 重试逻辑：最多重试2次
                        while (result != 0 && retryCount < 2) {
                            Log.w("CameraService", "Connection failed, retrying... (attempt ${retryCount + 2})")
                            Thread.sleep(1000)
                            result = connectToCamera(fd, vendorId, productId)
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
            AppLogger.e("CameraService", "Error in openAndConnect", e)
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
        syncExecutor.execute {
            val downloadedCountRef = intArrayOf(0)
            val syncStartTime = System.currentTimeMillis()
            try {
                updateStatus(CameraStatus.SYNCING)
                // 优化: 缩短相机初始化等待时间到500ms
                Thread.sleep(500)

                val (existingFiles, maxLocalSequence) = getExistingPublicPhotos()
                Log.i("CameraService", "getExistingPublicPhotos took ${System.currentTimeMillis() - syncStartTime}ms")

                // Tracks full camera paths already downloaded during this sync run to prevent duplicates
                val downloadedPaths = mutableSetOf<String>()

                // Notify listeners sync started with indeterminate total to skip slow pre-counting
                val totalCandidates = -1
                listeners.forEach { it.onSyncProgress(0, totalCandidates) }

                // 1. Try common DCIM path (some virtual filesystems expose this at root)
                scanFolderRecursive("/DCIM", existingFiles, downloadedPaths, downloadedCountRef, totalCandidates, maxLocalSequence)

                // 2. Scan root for storage volumes
                val rootFolders = listFoldersInFolder("/")?.sortedDescending() ?: emptyList<String>()
                rootFolders.forEach { store ->
                    if (store.equals("DCIM", ignoreCase = true)) return@forEach
                    val path = if (store.startsWith("/")) store else "/$store"
                    // Recursive scan starting from store root will naturally find DCIM and other folders
                    scanFolderRecursive(path, existingFiles, downloadedPaths, downloadedCountRef, totalCandidates, maxLocalSequence)
                }
            } catch (e: Exception) {
                AppLogger.e("CameraService", "Sync error", e)
            } finally {
                // Ensure we report the final count and shutdown executor even if an exception occurred
                Log.i("CameraService", "Photo sync completed in ${System.currentTimeMillis() - syncStartTime}ms")
                try {
                    listeners.forEach { it.onSyncCompleted(downloadedCountRef[0]) }
                    if (isCameraConnected) {
                        updateStatus(CameraStatus.CONNECTED)
                    }
                } catch (e: Exception) {
                    AppLogger.e("CameraService", "Error notifying sync completion", e)
                }
                // 确保 executor 被关闭，防止资源泄漏
                syncExecutor.shutdown()
            }
        }
    }

    private fun scanFolderRecursive(
        path: String,
        existingFiles: MutableSet<String>,
        downloadedPaths: MutableSet<String>,
        downloadedCountRef: IntArray, // Use IntArray as mutable reference in single-thread context
        totalCount: Int,
        maxLocalSequence: Int = 0 // Optimization: skip files with sequence <= this
    ): Boolean {
        if (!isCameraConnected) return false
        var reachedThreshold = false

        try {
            // Optimization: Skip listing subfolders in known leaf-only directories (DCIM/100CANON etc)
            // This avoids slow 10s+ folder listing on some Nikon/PTP cameras.
            val isLikelyLeaf = path.contains("/DCIM/", ignoreCase = true) && 
                              path.split('/').lastOrNull()?.matches(Regex("\\d{3}.*")) == true
            
            val folders = if (!isLikelyLeaf) {
                val listFolderStart = System.currentTimeMillis()
                val result = listFoldersInFolder(path)?.sortedDescending()
                if (result != null) {
                    Log.v("CameraService", "listFoldersInFolder($path) took ${System.currentTimeMillis() - listFolderStart}ms")
                    for (sub in result) {
                        if (sub.startsWith(".") || sub == "MISC") continue
                        val subPath = if (path.endsWith("/")) "$path$sub" else "$path/$sub"
                        if (scanFolderRecursive(subPath, existingFiles, downloadedPaths, downloadedCountRef, totalCount, maxLocalSequence)) {
                            reachedThreshold = true
                            break // Found old files in the newest subfolder, stop scanning older ones
                        }
                    }
                }
                result
            } else {
                Log.d("CameraService", "Skipping subfolder check for likely leaf folder: $path")
                null
            }

            if (reachedThreshold) return true

            // Optimization: Skip listing files in known container-only directories to save PTP overhead
            val isContainer = path == "/" || path.endsWith("/DCIM", ignoreCase = true) || 
                             path.contains(Regex("/store_[0-9a-fA-F]+$"))
            
            if (isContainer && folders != null && folders.isNotEmpty()) {
                Log.v("CameraService", "Skipping file list for container: $path")
                return false
            }

            val listFilesStart = System.currentTimeMillis()
            val files = listFilesInFolder(path)
            if (files != null) {
                Log.v("CameraService", "listFilesInFolder($path) found ${files.size} entries in ${System.currentTimeMillis() - listFilesStart}ms")
            }
            // Filter and sort descending to process newest files first
            val jpgFiles = files?.filter {
                it.lowercase().endsWith(".jpg") || it.lowercase().endsWith(".jpeg")
            }?.sortedDescending()

            if (jpgFiles != null) {
                for (fileName in jpgFiles) {
                    // 添加连接检查，防止断开时继续操作
                    if (!isCameraConnected) break

                    val fullPathKey = if (path.endsWith('/')) "$path$fileName" else "$path/$fileName"

                    // Skip if this exact camera path was already downloaded in this run
                    if (downloadedPaths.contains(fullPathKey)) continue

                    // Skip if a file with same display name already exists in gallery
                    if (existingFiles.contains(fileName)) {
                        downloadedPaths.add(fullPathKey)
                        continue
                    }

                    // OPTIMIZATION: Check sequence number to skip old files
                    if (maxLocalSequence > 0) {
                        val fileSequence = extractSequenceNumber(fileName)
                        if (fileSequence > 0 && fileSequence <= maxLocalSequence) {
                            // Since we are sorted descending, everything else in this folder is also old
                            Log.d("CameraService", "Reached files <= maxLocalSequence ($maxLocalSequence), stopping folder scan: $fileName")
                            reachedThreshold = true
                            break
                        }
                    }

                    // Per-file sync events are verbose; log at DEBUG level
                    Log.d("CameraService", "Syncing new file: $fileName")
                    val downloadStart = System.currentTimeMillis()
                    val data = downloadFileWithTimeout(path, fileName)
                    if (data != null) {
                        Log.v("CameraService", "downloadFileWithTimeout($fileName) took ${System.currentTimeMillis() - downloadStart}ms")
                        val saveStart = System.currentTimeMillis()
                        val uri = saveToSelectedFolder(fileName, data)
                        if (uri != null) {
                            Log.v("CameraService", "saveToSelectedFolder($fileName) took ${System.currentTimeMillis() - saveStart}ms")
                            // Add to existingFiles so further scans in this run won't re-download by name
                            existingFiles.add(fileName)
                            downloadedPaths.add(fullPathKey)

                            val realPath = getRealPathFromURI(uri)
                            // Notify listeners with the Uri and best-effort real path
                            listeners.forEach { listener -> listener.onNewPhoto(uri, realPath, false) }
                            
                            // Notify web server about new photo for SSE real-time updates
                            try {
                                val webServerIntent = Intent(this, PhotoWebServerService::class.java)
                                webServerIntent.action = PhotoWebServerService.ACTION_NOTIFY_NEW_PHOTO
                                webServerIntent.putExtra(PhotoWebServerService.EXTRA_PHOTO_NAME, fileName)
                                startService(webServerIntent)
                            } catch (e: Exception) {
                                AppLogger.e("CameraService", "Failed to send SSE notification", e)
                            }

                            // update progress
                            try {
                                downloadedCountRef[0]++
                                listeners.forEach { listener -> listener.onSyncProgress(downloadedCountRef[0], if (totalCount > 0) totalCount else -1) }
                            } catch (e: Exception) {
                                AppLogger.e("CameraService", "Progress notify error", e)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("CameraService", "Error scanning folder: $path", e)
        }
        return reachedThreshold
    }

    /**
     * Extract sequence number from filename (e.g., "DSC_1234.JPG" -> 1234)
     * Returns 0 if no sequence number found
     */
    private fun extractSequenceNumber(fileName: String): Int {
        return try {
            // Common patterns: DSC_0001.JPG, IMG_0001.JPG, etc.
            val regex = Regex("(\\d+)")
            val match = regex.find(fileName)
            match?.value?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun startEventPolling() {
        eventPollingExecutor = Executors.newSingleThreadExecutor()
        
        eventPollingExecutor?.execute {
            while (isCameraConnected) {
                try {
                    // 优化: 缩短poll超时到100ms,提高响应速度
                    val fullPath = pollEvent(100)
                    if (fullPath == null) {
                        Thread.sleep(200) // 优化: 缩短无事件时的等待时间
                        continue
                    }
                    val lastSlash = fullPath.lastIndexOf('/')
                    if (lastSlash != -1) {
                        val folder = fullPath.substring(0, lastSlash)
                        val fileName = fullPath.substring(lastSlash + 1)
                        if (fileName.lowercase().endsWith(".jpg") || fileName.lowercase().endsWith(".jpeg")) {
                            Log.i("CameraService", "New photo detected via event: $fileName")
                            
                            // USB传输单线程最快,直接在当前线程下载
                            try {
                                val imageData = downloadFileWithTimeout(folder, fileName)
                                if (imageData != null) {
                                    val uri = saveToSelectedFolder(fileName, imageData)
                                    if (uri != null) {
                                        val realPath = getRealPathFromURI(uri)
                                        listeners.forEach { listener -> listener.onNewPhoto(uri, realPath, true) }
                                        
                                        // Notify web server about new photo for SSE
                                        try {
                                            val webServerIntent = Intent(this@CameraService, PhotoWebServerService::class.java)
                                            webServerIntent.action = PhotoWebServerService.ACTION_NOTIFY_NEW_PHOTO
                                            webServerIntent.putExtra(PhotoWebServerService.EXTRA_PHOTO_NAME, fileName)
                                            startService(webServerIntent)
                                        } catch (e: Exception) {
                                            AppLogger.e("CameraService", "Failed to send SSE notification", e)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                AppLogger.e("CameraService", "Download error", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isCameraConnected) {
                        AppLogger.e("CameraService", "Polling error", e)
                    }
                    if (!isCameraConnected) break
                }
            }
        }
    }

    private fun saveToSelectedFolder(fileName: String, data: ByteArray): Uri? {
        val folderUriStr = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("sync_folder_uri", null) ?: return null
            
        val folderUri = folderUriStr.toUri()
        val rootTree = DocumentFile.fromTreeUri(this, folderUri) ?: return null
        
        // Ensure folder exists and we have access
        if (!rootTree.exists() || !rootTree.canWrite()) {
             return null
        }

        val file = rootTree.createFile("image/jpeg", fileName) ?: return null
        
        return try {
            contentResolver.openOutputStream(file.uri)?.use { it.write(data) }
            file.uri
        } catch (e: Exception) {
            AppLogger.e("CameraService", "Error saving to SAF folder", e)
            null
        }
    }

    private fun getExistingPublicPhotos(): Pair<MutableSet<String>, Int> {
        val names = mutableSetOf<String>()
        var maxSequenceNumber = 0
        
        val folderUriStr = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("sync_folder_uri", null)
        
        if (folderUriStr != null) {
            try {
                val treeUri = folderUriStr.toUri()
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

                contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIdx)
                        val mime = cursor.getString(mimeIdx)
                        if (name != null && (mime?.startsWith("image/") == true || name.lowercase().endsWith(".jpg") || name.lowercase().endsWith(".jpeg"))) {
                            names.add(name)
                            val seqNum = extractSequenceNumber(name)
                            if (seqNum > maxSequenceNumber) {
                                maxSequenceNumber = seqNum
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("CameraService", "Error listing SAF folder via DocumentsContract", e)
            }
        }

        Log.i("CameraService", "Found ${names.size} existing photos, max sequence: $maxSequenceNumber")
        return Pair(names, maxSequenceNumber)
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
            AppLogger.e("CameraService", "Error getting real path from URI", e)
            null
        }
    }

    /**
     * 带超时机制的文件下载包装方法
     * @param folderPath 文件夹路径
     * @param fileName 文件名
     * @return 文件数据,或在超时/错误时返回null
     */
    private fun downloadFileWithTimeout(folderPath: String, fileName: String): ByteArray? {
        // 优化: 缩短超时时间到10秒,快速失败重试
        val timeoutMs = 10000L
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
            AppLogger.e("CameraService", "Error downloading file with timeout", e)
            null
        }
    }

    external fun connectToCamera(fd: Int, vendorId: Int, productId: Int): Int
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
