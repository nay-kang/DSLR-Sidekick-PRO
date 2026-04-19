package net.codeedu.dslrsidekickpro

import android.content.*
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlinx.coroutines.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import java.security.MessageDigest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.tasks.await
import android.util.Log
import android.net.Uri

class CenterCropRegionTransformation(private val targetPoint: android.graphics.Point? = null) : BitmapTransformation() {
    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val centerX: Int
        val centerY: Int

        if (targetPoint != null) {
            centerX = targetPoint.x
            centerY = targetPoint.y
        } else {
            centerX = toTransform.width / 2
            centerY = toTransform.height / 2
        }

        val left = (centerX - outWidth / 2).coerceIn(0, (toTransform.width - outWidth).coerceAtLeast(0))
        val top = (centerY - outHeight / 2).coerceIn(0, (toTransform.height - outHeight).coerceAtLeast(0))
        val width = outWidth.coerceAtMost(toTransform.width)
        val height = outHeight.coerceAtMost(toTransform.height)
        
        return Bitmap.createBitmap(toTransform, left, top, width, height)
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("eye_crop_100_v1".toByteArray())
        targetPoint?.let { 
            messageDigest.update(it.x.toString().toByteArray())
            messageDigest.update(it.y.toString().toByteArray())
        }
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var statusBarStatus: TextView
    private lateinit var connectionIndicator: View
    private lateinit var exifInfoTextView: TextView
    private lateinit var photoViewPager: ViewPager2
    private lateinit var focusCheckImageView: android.widget.ImageView
    private lateinit var btnGallery: Button
    
    private var currentPhotoPath: String? = null
    private val allPhotos = mutableListOf<String>()
    private var currentPhotoIndex = -1
    private lateinit var pagerAdapter: PhotoPagerAdapter
    private var isCameraConnected: Boolean = false

    private var cameraService: CameraService? = null
    private var isBound = false

    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
        FaceDetection.getClient(options)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CameraService.CameraBinder
            cameraService = binder.getService()
            isBound = true
            // 添加监听器时，CameraService 会自动发送当前状态
            cameraService?.addListener(cameraListener)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService?.removeListener(cameraListener)
            cameraService = null
            isBound = false
            isCameraConnected = false
            updateStatus("相机未连接", false)
        }
    }

    private val mainScope = CoroutineScope(Dispatchers.Main + Job())

    private val cameraListener = object : CameraService.CameraEventListener {
        override fun onCameraStatusUpdate(status: CameraService.CameraStatus, extraMessage: String?) {
            val displayMessage = if (extraMessage != null) "${status.label} ($extraMessage)" else status.label
            isCameraConnected = status.isConnected
            updateStatus(displayMessage, status.isConnected)
        }

        override fun onNewPhoto(uri: Uri, realPath: String?, fromLiveEvent: Boolean) {
            runOnUiThread {
                val displayPath = realPath ?: uri.toString()
                // 检查path是否已存在，避免重复
                if (!allPhotos.contains(displayPath)) {
                    allPhotos.add(0, displayPath)
                    pagerAdapter.notifyItemInserted(0)
                    pagerAdapter.notifyDataSetChanged() // 确保 ViewPager 刷新
                }
                // 仅当是实时拍照事件时，自动切换到详情视图并更新 detail
                if (fromLiveEvent) {
                    photoViewPager.setCurrentItem(0, true)
                    updateDetailViews(displayPath)
                }
            }
        }

        override fun onSyncProgress(current: Int, total: Int) {
            runOnUiThread {
                val msg = if (total > 0) "Syncing photos: $current / $total" else "Syncing photos: $current"
                updateStatus(msg, null)
            }
        }

        override fun onSyncCompleted(total: Int) {
            runOnUiThread {
                val msg = if (total >= 0) "Sync completed: $total new photos" else "Sync completed"
                updateStatus(msg, null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        enableImmersiveMode()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val rootLayout = findViewById<View>(R.id.sidePanel).parent as View
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val displayCutout = insets.displayCutout
            
            val top = systemBars.top + (displayCutout?.safeInsetTop ?: 0)
            val bottom = systemBars.bottom + (displayCutout?.safeInsetBottom ?: 0)
            
            findViewById<View>(R.id.sidePanel).setPadding(0, top, 0, bottom)
            
            val statusBarView = findViewById<View>(R.id.statusBar)
            val params = statusBarView.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.bottomMargin = bottom + 16.dpToPx()
            statusBarView.layoutParams = params
            
            insets
        }

        statusBarStatus = findViewById(R.id.statusBarStatus)
        connectionIndicator = findViewById(R.id.connectionIndicator)
        exifInfoTextView = findViewById(R.id.exifInfoTextView)
        photoViewPager = findViewById(R.id.photoViewPager)
        focusCheckImageView = findViewById(R.id.focusCheckImageView)
        btnGallery = findViewById(R.id.btnGallery)

        btnGallery.setOnClickListener {
            finish()
        }

        setupViewPager()

        val serviceIntent = Intent(this, CameraService::class.java)
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)

        // 初始化时设置默认状态（红色表示未连接）
        updateStatus("相机未连接", false)
        
        loadAllPhotos()
        
        val photoPath = intent.getStringExtra("photo_path")
        if (photoPath != null) {
            currentPhotoPath = photoPath
            currentPhotoIndex = allPhotos.indexOf(photoPath)
            if (currentPhotoIndex != -1) {
                photoViewPager.setCurrentItem(currentPhotoIndex, false)
                updateDetailViews(photoPath)
            }
        }
    }

    private fun setupViewPager() {
        pagerAdapter = PhotoPagerAdapter(allPhotos)
        photoViewPager.adapter = pagerAdapter
        photoViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPhotoIndex = position
                val path = allPhotos[position]
                currentPhotoPath = path
                updateDetailViews(path)
            }
        })
    }

    private fun loadAllPhotos() {
        mainScope.launch {
            withContext(Dispatchers.IO) {
                val photos = mutableListOf<String>()
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
                        photos.add(cursor.getString(index))
                    }
                }
                
                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    allPhotos.clear()
                    allPhotos.addAll(photos)
                    pagerAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun updateDetailViews(path: String) {
        val isContent = path.startsWith("content://") || path.startsWith("file://")
        val file = if (!isContent) File(path) else null
        if (!isContent && (file == null || !file.exists())) return

        val fileName = if (file != null) file.name else Uri.parse(path).lastPathSegment ?: "Unknown"
        
        mainScope.launch {
            // 1. 获取 EXIF
            updateStatus("Loading: $fileName", isCameraConnected)
            val exifData = withContext(Dispatchers.IO) {
                try {
                    val exif = if (isContent) {
                        val uri = Uri.parse(path)
                        contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
                    } else {
                        ExifInterface(path)
                    }
                    val aperture = exif?.getAttribute(ExifInterface.TAG_F_NUMBER) ?: "--"
                    val shutter = exif?.getAttribute(ExifInterface.TAG_EXPOSURE_TIME) ?: "--"
                    val iso = exif?.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
                        ?: exif?.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                        ?: "--"
                    "f/$aperture   ${formatShutter(shutter)}s   ISO $iso"
                } catch (e: Exception) {
                    Log.e("MainActivity", "EXIF read error", e)
                    "--   --   --"
                }
            }
            exifInfoTextView.text = exifData
            updateStatus("Analyzing: $fileName", isCameraConnected)

            // 2. ML Kit 人眼精确检测（使用缩略图以节省内存）
            val eyePoint = withContext(Dispatchers.IO) {
                var bitmap: Bitmap? = null
                try {
                    val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                    bitmap = if (isContent) {
                        val uri = Uri.parse(path)
                        contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, options) }
                    } else {
                        BitmapFactory.decodeFile(path, options)
                    }
                    if (bitmap == null) return@withContext null

                    val image = InputImage.fromBitmap(bitmap, 0)
                    val faces = faceDetector.process(image).await()
                    val face = faces.firstOrNull() ?: return@withContext null
                    val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)
                    val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)
                    val targetLandmark = leftEye ?: rightEye
                    targetLandmark?.position?.let {
                        android.graphics.Point(it.x.toInt() * 4, it.y.toInt() * 4)
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Eye detection error", e)
                    null
                } finally {
                    bitmap?.recycle()
                }
            }

            // 3. 加载 100% 细节图（Glide 支持 content:// Uri）
            Glide.with(this@MainActivity)
                .asBitmap()
                .load(path)
                .override(com.bumptech.glide.request.target.Target.SIZE_ORIGINAL)
                .transform(CenterCropRegionTransformation(eyePoint))
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .into(focusCheckImageView)

            updateStatus("Ready: $fileName", isCameraConnected)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel all coroutines first to prevent race conditions
        mainScope.cancel()
        // Then close resources
        faceDetector.close()
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

    private fun enableImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val controller = window.insetsController
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
        }
    }

    private fun formatShutter(shutter: String): String {
        return if (shutter.contains("/")) {
            shutter
        } else {
            "1/$shutter"
        }
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}
