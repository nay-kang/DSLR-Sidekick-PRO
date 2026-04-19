package net.codeedu.dslrsidekickpro

import android.content.Intent
import android.net.Uri
import com.google.android.material.snackbar.Snackbar
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.provider.MediaStore
import android.os.Build
import android.content.ComponentName
import android.content.ServiceConnection
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.Parcelable
import android.view.WindowManager
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class GalleryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PhotoAdapter
    private val photoList = mutableListOf<String>()
    private lateinit var statusBarStatus: TextView
    private lateinit var connectionIndicator: View

    private var cameraService: CameraService? = null
    private var isBound = false

    private var pendingScrollState: Parcelable? = null

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
                syncSnackbar?.setDuration(3000)
                syncSnackbar?.show()
                syncSnackbar = null
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

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }

        statusBarStatus = findViewById(R.id.statusBarStatus)
        connectionIndicator = findViewById(R.id.connectionIndicator)
        recyclerView = findViewById(R.id.galleryRecyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, 4)

        loadPhotos()
        adapter = PhotoAdapter(photoList) { path ->
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("photo_path", path)
            startActivity(intent)
        }
        recyclerView.adapter = adapter

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

    private fun loadPhotos() {
        // Example implementation: Load photos from a predefined directory
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%Pictures/DSLR_Sidekick%")
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            while (cursor.moveToNext()) {
                photoList.add(cursor.getString(index))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 保存状态用于 Activity 重建
        recyclerView.layoutManager?.onSaveInstanceState()?.let {
            outState.putParcelable("recycler_state", it)
        }
    }

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
    }
}
