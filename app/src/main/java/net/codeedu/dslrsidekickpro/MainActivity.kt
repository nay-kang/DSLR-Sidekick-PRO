package net.codeedu.dslrsidekickpro

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val ACTION_USB_PERMISSION = "net.codeedu.dslrsidekickpro.USB_PERMISSION"
    private lateinit var usbManager: UsbManager
    private lateinit var statusTextView: TextView
    private lateinit var photoImageView: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusTextView)
        photoImageView = findViewById(R.id.photoImageView)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // 验证 JNI 是否工作
        try {
            val versionInfo = stringFromJNI()
            statusTextView.text = "Native Lib Loaded: $versionInfo\nWaiting for Camera..."
        } catch (e: Exception) {
            statusTextView.text = "JNI Error: ${e.message}"
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        findAndConnectCamera()
    }

    private fun findAndConnectCamera() {
        val deviceList = usbManager.deviceList
        android.util.Log.i("DSLRSidekick", "Found ${deviceList.size} USB devices")
        if (deviceList.isEmpty()) {
            statusTextView.text = "No USB Device Found"
            return
        }

        for (device in deviceList.values) {
            android.util.Log.i("DSLRSidekick", "Device: ${device.deviceName}, VID: ${device.vendorId}, PID: ${device.productId}")
            if (usbManager.hasPermission(device)) {
                android.util.Log.i("DSLRSidekick", "Already has permission, connecting...")
                openAndConnect(device)
            } else {
                android.util.Log.i("DSLRSidekick", "Requesting permission...")
                requestPermission(device)
            }
            // 暂时只处理第一个设备
            return
        }
    }

    private fun openAndConnect(device: UsbDevice) {
        val connection = usbManager.openDevice(device)
        if (connection != null) {
            val fd = connection.fileDescriptor
            statusTextView.text = "USB Connected! FD: $fd\nInitializing libgphoto2..."
            
            // 使用线程处理耗时操作
            Thread {
                val result = connectToCamera(fd)
                runOnUiThread {
                    if (result == 0) {
                        statusTextView.text = "Connected! Ready for auto-import."
                        startEventPolling()
                    } else {
                        statusTextView.text = "Connection failed: $result"
                    }
                }
            }.start()
        } else {
            statusTextView.text = "Failed to open USB device"
        }
    }

    private fun startEventPolling() {
        android.util.Log.i("DSLRSidekick", "Starting hardware event listener...")
        Thread {
            while (true) {
                // 阻塞等待相机事件 (Timeout 1000ms)
                val fullPath = pollEvent(1000)
                
                if (fullPath != null) {
                    android.util.Log.i("DSLRSidekick", "EVENT: $fullPath")
                    
                    val lastSlash = fullPath.lastIndexOf('/')
                    if (lastSlash != -1) {
                        val folder = fullPath.substring(0, lastSlash)
                        val fileName = fullPath.substring(lastSlash + 1)
                        val ext = fileName.lowercase()
                        
                        // 过滤 JPG 文件进行实时预览
                        if (ext.endsWith(".jpg")) {
                            runOnUiThread {
                                statusTextView.text = "New Photo: $fileName. Downloading..."
                            }
                            
                            val startTime = System.currentTimeMillis()
                            // 直接在当前轮询线程执行下载，确保 libgphoto2 访问安全（非线程安全）
                            val imageData = downloadFile(folder, fileName)
                            val duration = System.currentTimeMillis() - startTime
                            
                            if (imageData != null && imageData.isNotEmpty()) {
                                val fullBitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                                if (fullBitmap != null) {
                                    runOnUiThread {
                                        photoImageView.setImageBitmap(fullBitmap)
                                        statusTextView.text = "Synced: $fileName (${imageData.size / 1024} KB) in ${duration}ms"
                                    }
                                } else {
                                    runOnUiThread { statusTextView.text = "Error: Failed to decode image." }
                                }
                            } else {
                                runOnUiThread { statusTextView.text = "Error: Download failed from camera." }
                            }
                        }
                    }
                }
            }
        }.start()
    }

    private fun requestPermission(device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION), 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { openAndConnect(it) }
                    } else {
                        statusTextView.text = "Permission Denied"
                    }
                }
            }
        }
    }

    /**
     * Native methods
     */
    external fun stringFromJNI(): String
    external fun connectToCamera(fd: Int): Int
    external fun pollEvent(timeoutMs: Int): String?
    external fun downloadThumbnail(folderPath: String, fileName: String): ByteArray?
    external fun listFoldersInFolder(folderPath: String): Array<String>?
    external fun listFilesInFolder(folderPath: String): Array<String>?
    external fun downloadFile(folderPath: String, fileName: String): ByteArray?
    external fun captureImage(): Int
    external fun getSummary(): String
    external fun getConfig(key: String): String
    external fun setConfig(key: String, value: String): Int
    external fun capturePreview(): ByteArray?

    companion object {
        init {
            System.loadLibrary("gphoto2")
            System.loadLibrary("usb")
        }
    }
}
