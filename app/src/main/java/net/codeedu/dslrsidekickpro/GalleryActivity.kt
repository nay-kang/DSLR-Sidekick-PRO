package net.codeedu.dslrsidekickpro

import android.content.Intent
import android.net.Uri
import com.google.android.material.snackbar.Snackbar
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.os.Build
import android.content.ComponentName
import android.content.ServiceConnection
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.Parcelable
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.DocumentsContract
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.toColorInt

class GalleryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PhotoAdapter
    private val photoList = mutableListOf<String>()
    private lateinit var statusBarStatus: TextView
    private lateinit var connectionIndicator: View
    private lateinit var clearPhotosButton: Button
    private lateinit var webServerToggleButton: Button

    private var cameraService: CameraService? = null
    private var isBound = false
    private var isWebServerRunning = false

    private var pendingScrollState: Parcelable? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())

    private val requestFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit {
                    putString("sync_folder_uri", it.toString())
                }
            
            loadPhotos()
        } ?: run {
            updateStatus("未选择文件夹，无法显示照片", false)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CameraService.CameraBinder
            cameraService = binder.getService()
            isBound = true
            cameraService?.addListener(cameraListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService?.removeListener(cameraListener)
            cameraService = null
            isBound = false
        }
    }

    private var syncSnackbar: Snackbar? = null

    private val cameraListener = object : CameraService.CameraEventListener {
        override fun onCameraStatusUpdate(status: CameraService.CameraStatus, extraMessage: String?) {
            val displayMessage = if (extraMessage != null) "${status.label} ($extraMessage)" else status.label
            updateStatus(displayMessage, status.isConnected)
        }

        override fun onNewPhoto(uri: Uri, realPath: String?, fromLiveEvent: Boolean) {
            runOnUiThread {
                val displayPath = realPath ?: uri.toString()
                adapter.addPhoto(displayPath)
                
                // Scroll to top to show the newest photo
                recyclerView.smoothScrollToPosition(0)
                
                if (fromLiveEvent) {
                    val intent = Intent(this@GalleryActivity, MainActivity::class.java)
                    intent.putExtra("photo_path", displayPath)
                    startActivity(intent)
                }
            }
        }

        override fun onSyncProgress(current: Int, total: Int) {
            runOnUiThread {
                val msg = if (total > 0) "Syncing photos: $current / $total" else "Syncing photos: $current"
                val root = findViewById<View>(android.R.id.content)
                if (syncSnackbar == null) {
                    syncSnackbar = Snackbar.make(root, msg, Snackbar.LENGTH_INDEFINITE)
                    syncSnackbar?.view?.translationZ = -1f
                    syncSnackbar?.show()
                } else {
                    syncSnackbar?.setText(msg)
                }
            }
        }

        override fun onSyncCompleted(total: Int) {
            runOnUiThread {
                val msg = if (total >= 0) "Sync completed: $total new photos" else "Sync completed"
                syncSnackbar?.setText(msg)
                syncSnackbar?.duration = 3000
                syncSnackbar?.show()
                syncSnackbar = null
                
                // 同步完成后重新加载照片列表以确保正确排序
                loadPhotos()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent?.action) {
            finish()
            return
        }

        setContentView(R.layout.activity_gallery)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusBarStatus = findViewById(R.id.statusBarStatus)
        connectionIndicator = findViewById(R.id.connectionIndicator)
        clearPhotosButton = findViewById(R.id.clearPhotosButton)
        webServerToggleButton = findViewById(R.id.webServerToggleButton)
        recyclerView = findViewById(R.id.galleryRecyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, 4)

        adapter = PhotoAdapter(photoList) { path ->
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("photo_path", path)
            startActivity(intent)
        }
        recyclerView.adapter = adapter

        // 设置清空按钮点击事件
        clearPhotosButton.setOnClickListener {
            showClearPhotosDialog()
        }
        
        // 设置 Web 服务器开关按钮点击事件
        webServerToggleButton.setOnClickListener {
            toggleWebServer()
        }
        
        // 设置按钮颜色以适配主题 - 使用白色文字确保在深色背景上可见
        clearPhotosButton.setTextColor(android.graphics.Color.WHITE)

        checkFolderAndLoadPhotos()

        // 在布局完成后恢复滚动状态（处理 Activity 重建）
        if (pendingScrollState != null) {
            recyclerView.post {
                recyclerView.layoutManager?.onRestoreInstanceState(pendingScrollState)
                pendingScrollState = null
            }
        }

        updateStatus("相机未连接", false)

        // 启动并绑定服务
        val intent = Intent(this, CameraService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            cameraService?.removeListener(cameraListener)
            unbindService(serviceConnection)
        }
    }

    override fun onPause() {
        super.onPause()
        // 保存当前滚动状态，以备恢复
        pendingScrollState = recyclerView.layoutManager?.onSaveInstanceState()
    }

    override fun onResume() {
        super.onResume()
        // 从暂停中恢复时，尝试恢复滚动位置（如果焦点尚未恢复，将在 onWindowFocusChanged 中兜底）
        pendingScrollState?.let {
            recyclerView.post {
                recyclerView.layoutManager?.onRestoreInstanceState(it)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 窗口重获焦点时，强制恢复滚动位置，解决 USB 连接导致的短暂失焦问题
        if (hasFocus && pendingScrollState != null) {
            recyclerView.post {
                recyclerView.layoutManager?.onRestoreInstanceState(pendingScrollState)
                pendingScrollState = null // 恢复后清除，避免用户正常滚动后被覆盖
            }
        }
    }

    private fun updateStatus(text: String, isConnected: Boolean? = null) {
        runOnUiThread {
            statusBarStatus.text = text
            isConnected?.let {
                @Suppress("DEPRECATION")
                connectionIndicator.setBackgroundColor(
                    if (it) android.graphics.Color.GREEN else android.graphics.Color.RED
                )
            }
        }
    }

    private fun checkFolderAndLoadPhotos() {
        val folderUriStr = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("sync_folder_uri", null)
        
        if (folderUriStr != null) {
            val folderUri = folderUriStr.toUri()
            val isPermissionGranted = contentResolver.persistedUriPermissions.any {
                it.uri == folderUri && it.isReadPermission
            }
            if (isPermissionGranted) {
                loadPhotos()
            } else {
                requestFolderLauncher.launch(null)
            }
        } else {
            requestFolderLauncher.launch(null)
        }
    }

    private fun loadPhotos() {
        mainScope.launch {
            // 第一步：快速加载照片列表（使用文件名排序）
            val photos = withContext(Dispatchers.IO) {
                val result = mutableListOf<Triple<String, Int, String>>() // uri, sequenceNumber, displayName
                val folderUriStr = PreferenceManager.getDefaultSharedPreferences(this@GalleryActivity)
                    .getString("sync_folder_uri", null) ?: return@withContext emptyList<String>()
                
                try {
                    val rootUri = folderUriStr.toUri()
                    val treeId = DocumentsContract.getTreeDocumentId(rootUri)
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, treeId)
                    
                    val projection = arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    )
                    
                    contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                        val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        
                        while (cursor.moveToNext()) {
                            val mime = cursor.getString(mimeIdx)
                            if (mime == "image/jpeg") {
                                val docId = cursor.getString(idIdx)
                                val displayName = cursor.getString(nameIdx)
                                val uri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)
                                val seqNum = extractSequenceNumber(displayName)
                                result.add(Triple(uri.toString(), seqNum, displayName))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GalleryActivity", "Fast sync error", e)
                }
                
                // 先按序列号降序排列（快速）
                result.sortByDescending { it.second }
                result.map { it.first }
            }

            if (photos.isNotEmpty()) {
                photoList.clear()
                photoList.addAll(photos)
                adapter.notifyDataSetChanged()
                
                // 第二步：异步后台优化排序（使用EXIF日期）
                launch {
                    optimizeSortWithExifDates(photos)
                }
            }
        }
    }
    
    private suspend fun optimizeSortWithExifDates(initialPhotos: List<String>) {
        withContext(Dispatchers.IO) {
            try {
                // 批量读取EXIF日期（并行处理）
                val dateMap = mutableMapOf<String, Long>()
                initialPhotos.chunked(10).forEach { batch ->
                    batch.forEach { uriStr ->
                        val uri = uriStr.toUri()
                        val captureDate = getCaptureDateFromExif(uri)
                        dateMap[uriStr] = captureDate
                    }
                }
                
                // 按EXIF日期重新排序
                val sortedPhotos = initialPhotos.sortedByDescending { dateMap[it] ?: 0L }
                
                // 如果排序有变化，更新UI
                if (sortedPhotos != initialPhotos) {
                    withContext(Dispatchers.Main) {
                        photoList.clear()
                        photoList.addAll(sortedPhotos)
                        adapter.notifyDataSetChanged()
                        Log.d("GalleryActivity", "Optimized sort with EXIF dates")
                    }
                }
            } catch (e: Exception) {
                Log.e("GalleryActivity", "Failed to optimize sort", e)
            }
        }
    }
    
    private fun extractSequenceNumber(fileName: String): Int {
        return try {
            val regex = Regex("""(?:_DSC|DSC_|IMG_|P_|DSC)(\d+)""")
            val matchResult = regex.find(fileName)
            matchResult?.groupValues?.get(1)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        }
    }
    
    private fun getCaptureDateFromExif(uri: Uri): Long {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = androidx.exifinterface.media.ExifInterface(inputStream)
                val dateStr = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME)
                
                dateStr?.let {
                    java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(it)?.time
                } ?: System.currentTimeMillis()
            } ?: System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e("GalleryActivity", "Failed to read EXIF date for $uri", e)
            System.currentTimeMillis()
        }
    }
    
    private fun showClearPhotosDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_photos)
            .setMessage(R.string.confirm_clear_photos)
            .setPositiveButton(R.string.confirm) { _, _ ->
                clearAllPhotos()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearAllPhotos() {
        mainScope.launch {
            val deletedCount = withContext(Dispatchers.IO) {
                var count = 0
                val folderUriStr = PreferenceManager.getDefaultSharedPreferences(this@GalleryActivity)
                    .getString("sync_folder_uri", null) ?: return@withContext 0
                
                try {
                    val rootUri = folderUriStr.toUri()
                    val treeId = DocumentsContract.getTreeDocumentId(rootUri)
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, treeId)
                    
                    val projection = arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    )
                    
                    contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                        val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        
                        val photosToDelete = mutableListOf<String>()
                        while (cursor.moveToNext()) {
                            val mime = cursor.getString(mimeIdx)
                            if (mime == "image/jpeg") {
                                val docId = cursor.getString(idIdx)
                                photosToDelete.add(docId)
                            }
                        }
                        
                        // 删除所有照片文件
                        for (docId in photosToDelete) {
                            try {
                                val documentUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)
                                if (DocumentsContract.deleteDocument(contentResolver, documentUri)) {
                                    count++
                                }
                            } catch (e: Exception) {
                                Log.e("GalleryActivity", "Failed to delete photo: $docId", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GalleryActivity", "Clear photos error", e)
                }
                
                count
            }
            
            // 更新UI
            runOnUiThread {
                photoList.clear()
                adapter.notifyDataSetChanged()
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.photos_cleared) + " ($deletedCount)",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Toggle web server on/off
     */
    private fun toggleWebServer() {
        if (isWebServerRunning) {
            stopWebServer()
        } else {
            startWebServer()
        }
    }

    /**
     * Start the photo web server service
     */
    private fun startWebServer() {
        try {
            val intent = Intent(this, PhotoWebServerService::class.java).apply {
                action = PhotoWebServerService.ACTION_START_SERVER
                putExtra(PhotoWebServerService.EXTRA_PORT, 8080)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            isWebServerRunning = true
            webServerToggleButton.text = "⏹ Stop Web"
            webServerToggleButton.setBackgroundColor("#FF5252".toColorInt())
            
            // Show info snackbar with access instructions
            val deviceIP = getDeviceIPAddress()
            Snackbar.make(
                findViewById(android.R.id.content),
                "Web server started! Access from other devices:\nhttp://$deviceIP:8080",
                Snackbar.LENGTH_LONG
            ).show()
            
            Log.i("GalleryActivity", "Web server started on port 8080")
        } catch (e: Exception) {
            Log.e("GalleryActivity", "Failed to start web server", e)
            Snackbar.make(
                findViewById(android.R.id.content),
                "Failed to start web server: ${e.message}",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Stop the photo web server service
     */
    private fun stopWebServer() {
        try {
            val intent = Intent(this, PhotoWebServerService::class.java).apply {
                action = PhotoWebServerService.ACTION_STOP_SERVER
            }
            startService(intent)
            
            isWebServerRunning = false
            webServerToggleButton.text = "🌐 Start Web"
            webServerToggleButton.setBackgroundColor("#4CAF50".toColorInt())
            
            Snackbar.make(
                findViewById(android.R.id.content),
                "Web server stopped",
                Snackbar.LENGTH_SHORT
            ).show()
            
            Log.i("GalleryActivity", "Web server stopped")
        } catch (e: Exception) {
            Log.e("GalleryActivity", "Failed to stop web server", e)
        }
    }

    /**
     * Get device IP address for display
     */
    private fun getDeviceIPAddress(): String {
        return try {
            // Method 1: Try to enumerate network interfaces (works without permissions)
            java.net.NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.firstOrNull { address ->
                    // Prefer WiFi or Ethernet addresses
                    val interfaceName = java.net.NetworkInterface.getByInetAddress(address)?.name ?: ""
                    interfaceName.contains("wlan", ignoreCase = true) ||
                    interfaceName.contains("eth", ignoreCase = true) ||
                    interfaceName.contains("wifi", ignoreCase = true)
                }?.hostAddress
                ?: java.net.NetworkInterface.getNetworkInterfaces()?.asSequence()
                    ?.flatMap { it.inetAddresses.asSequence() }
                    ?.filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                    ?.firstOrNull()?.hostAddress
                ?: "your-device-ip"
        } catch (e: Exception) {
            Log.e("GalleryActivity", "Error getting IP address via NetworkInterface", e)
            
            // Fallback: Use ConnectivityManager for modern Android
            try {
                val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                val activeNetwork = connectivityManager?.activeNetwork
                val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)
                
                if (capabilities != null) {
                    // Check if connected via WiFi or Ethernet
                    if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        
                        // Get link properties to find IP address
                        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
                        val ipAddresses = linkProperties?.linkAddresses
                        
                        ipAddresses?.firstOrNull { 
                            it.address is java.net.Inet4Address && !it.address.isLoopbackAddress 
                        }?.address?.hostAddress
                            ?: "your-device-ip"
                    } else {
                        "your-device-ip"
                    }
                } else {
                    "your-device-ip"
                }
            } catch (e2: Exception) {
                Log.e("GalleryActivity", "Fallback IP detection also failed", e2)
                "your-device-ip"
            }
        }
    }
}
