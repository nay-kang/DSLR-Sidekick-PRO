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

class CameraService : Service() {

    private val ACTION_USB_PERMISSION = "net.codeedu.dslrsidekickpro.USB_PERMISSION"
    private var isCameraConnected = false
    private var usbDeviceConnection: android.hardware.usb.UsbDeviceConnection? = null
    private lateinit var usbManager: UsbManager
    
    private val binder = CameraBinder()
    private val listeners = mutableListOf<CameraEventListener>()

    interface CameraEventListener {
        fun onStatusUpdate(text: String, isConnected: Boolean? = null)
        fun onNewPhoto(path: String)
    }

    inner class CameraBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        startForegroundService()
        
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
        
        findAndConnectCamera()
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
    }

    fun removeListener(listener: CameraEventListener) {
        listeners.remove(listener)
    }

    private fun updateStatus(text: String, isConnected: Boolean? = null) {
        Log.i("CameraService", text)
        listeners.forEach { it.onStatusUpdate(text, isConnected) }
    }

    private fun findAndConnectCamera() {
        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            updateStatus("No USB Device Found", false)
            return
        }
        for (device in deviceList.values) {
            if (usbManager.hasPermission(device)) {
                openAndConnect(device)
            } else {
                requestPermission(device)
            }
            return
        }
    }

    private fun requestPermission(device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION), 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun openAndConnect(device: UsbDevice) {
        usbDeviceConnection?.close()
        usbDeviceConnection = usbManager.openDevice(device)
        val connection = usbDeviceConnection
        if (connection != null) {
            val fd = connection.fileDescriptor
            Thread {
                val result = connectToCamera(fd)
                if (result == 0) {
                    isCameraConnected = true
                    updateStatus("Connected! Syncing...", true)
                    syncAllPhotos()
                    startEventPolling()
                } else {
                    isCameraConnected = false
                    updateStatus("Connection failed: $result", false)
                }
            }.start()
        }
    }

    override fun onDestroy() {
        isCameraConnected = false
        disconnectCameraNative()
        usbDeviceConnection?.close()
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
                    device?.let { openAndConnect(it) }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                isCameraConnected = false
                disconnectCameraNative()
                updateStatus("Camera Disconnected", false)
            }
        }
    }

    private fun syncAllPhotos() {
        if (!isCameraConnected) return
        
        // Single thread executor for sync tasks to avoid concurrency issues with gphoto2 context
        val syncExecutor = Executors.newSingleThreadExecutor()
        syncExecutor.execute {
            try {
                // Give camera a moment to initialize internal storage
                Thread.sleep(1500)
                
                val existingFiles = getExistingPublicPhotos()
                Log.i("CameraService", "Existing photos in gallery: ${existingFiles.size}")

                // 1. Try common DCIM path
                scanFolderRecursive("/DCIM", existingFiles)

                // 2. Scan root for storage volumes
                val rootFolders = listFoldersInFolder("/") ?: emptyArray()
                rootFolders.forEach { store ->
                    if (store.equals("DCIM", ignoreCase = true)) return@forEach
                    val path = if (store.startsWith("/")) store else "/$store"
                    scanFolderRecursive(path, existingFiles)
                    scanFolderRecursive("$path/DCIM", existingFiles)
                }
            } catch (e: Exception) {
                Log.e("CameraService", "Sync error", e)
            }
        }
    }

    private fun scanFolderRecursive(path: String, existingFiles: Set<String>) {
        if (!isCameraConnected) return
        
        val folders = listFoldersInFolder(path)
        folders?.forEach { sub ->
            if (!sub.startsWith(".") && sub != "MISC") {
                val subPath = if (path.endsWith("/")) "$path$sub" else "$path/$sub"
                scanFolderRecursive(subPath, existingFiles)
            }
        }

        val files = listFilesInFolder(path)
        files?.filter { it.lowercase().endsWith(".jpg") }?.reversed()?.forEach { fileName ->
            if (!existingFiles.contains(fileName)) {
                Log.i("CameraService", "Syncing new file: $path/$fileName")
                val data = downloadFile(path, fileName)
                if (data != null) {
                    saveToPublicGallery(fileName, data)
                }
            }
        }
    }

    private fun startEventPolling() {
        Executors.newSingleThreadExecutor().execute {
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
                            val imageData = downloadFile(folder, fileName)
                            if (imageData != null) {
                                val uri = saveToPublicGallery(fileName, imageData)
                                uri?.let { 
                                    val realPath = getRealPathFromURI(it)
                                    realPath?.let { p -> 
                                        listeners.forEach { it.onNewPhoto(p) } 
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CameraService", "Polling error", e)
                }
            }
        }
    }

    private fun saveToPublicGallery(fileName: String, data: ByteArray): android.net.Uri? {
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
            contentResolver.delete(uri, null, null)
            return null
        }
    }

    private fun getExistingPublicPhotos(): Set<String> {
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
        }
        return names
    }

    private fun getRealPathFromURI(uri: android.net.Uri): String? {
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            cursor.moveToFirst()
            return cursor.getString(columnIndex)
        }
        return null
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
