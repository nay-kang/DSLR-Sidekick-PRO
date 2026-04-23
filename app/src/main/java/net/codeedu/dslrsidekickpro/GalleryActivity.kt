package net.codeedu.dslrsidekickpro

import android.content.Intent
import android.net.Uri
import com.google.android.material.snackbar.Snackbar
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
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

class GalleryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PhotoAdapter
    private val photoList = mutableListOf<String>()
    private lateinit var statusBarStatus: TextView
    private lateinit var connectionIndicator: View

    private var cameraService: CameraService? = null
    private var isBound = false

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
        recyclerView = findViewById(R.id.galleryRecyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, 4)

        adapter = PhotoAdapter(photoList) { path ->
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("photo_path", path)
            startActivity(intent)
        }
        recyclerView.adapter = adapter

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
            val photos = withContext(Dispatchers.IO) {
                val result = mutableListOf<Pair<String, Long>>()
                val folderUriStr = PreferenceManager.getDefaultSharedPreferences(this@GalleryActivity)
                    .getString("sync_folder_uri", null) ?: return@withContext emptyList<String>()
                
                try {
                    val rootUri = folderUriStr.toUri()
                    val treeId = DocumentsContract.getTreeDocumentId(rootUri)
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, treeId)
                    
                    val projection = arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    )
                    
                    contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                        val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val modIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        
                        while (cursor.moveToNext()) {
                            val mime = cursor.getString(mimeIdx)
                            if (mime == "image/jpeg") {
                                val docId = cursor.getString(idIdx)
                                val lastMod = cursor.getLong(modIdx)
                                val uri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)
                                result.add(uri.toString() to lastMod)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GalleryActivity", "Fast sync error", e)
                }
                
                result.sortByDescending { it.second }
                result.map { it.first }
            }

            if (photos.isNotEmpty()) {
                photoList.clear()
                photoList.addAll(photos)
                adapter.notifyDataSetChanged()
                // Only scroll to top if we weren't already somewhere else
                if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                    // Consider if you really want to scroll to top every time
                }
            }
        }
    }
}
