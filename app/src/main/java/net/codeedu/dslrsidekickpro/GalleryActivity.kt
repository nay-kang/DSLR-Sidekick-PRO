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
import android.os.IBinder
import android.view.WindowManager
// ...

class GalleryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PhotoAdapter
    private val photoList = mutableListOf<String>()
    private lateinit var statusBarStatus: TextView
    private lateinit var connectionIndicator: View

    private var cameraService: CameraService? = null
    private var isBound = false

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
        override fun onStatusUpdate(text: String, isConnected: Boolean?) {
            updateStatus(text, isConnected)
        }

        override fun onNewPhoto(uri: Uri, realPath: String?, fromLiveEvent: Boolean) {
            runOnUiThread {
                val displayPath = realPath ?: uri.toString()
                adapter.addPhoto(displayPath)
                // 如果是相机拍照触发的实时事件，自动切换到详情模式；批量同步不跳转
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
        setContentView(R.layout.activity_gallery)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 不再调用 enableImmersiveMode()，让系统 UI 正常显示
        
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
        
        updateStatus("Gallery Ready. Starting Sync Service...", false)
        
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
}
