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
            Log.d("CenterCrop", "Target point: ($centerX, $centerY), Output size: ${outWidth}x${outHeight}, Source size: ${toTransform.width}x${toTransform.height}")
        } else {
            centerX = toTransform.width / 2
            centerY = toTransform.height / 2
            Log.d("CenterCrop", "Using center point: ($centerX, $centerY)")
        }

        // 计算裁剪区域，确保不超出图片边界
        var left = centerX - outWidth / 2
        var top = centerY - outHeight / 2
        
        // 边界检查
        left = left.coerceIn(0, (toTransform.width - outWidth).coerceAtLeast(0))
        top = top.coerceIn(0, (toTransform.height - outHeight).coerceAtLeast(0))
        
        val width = outWidth.coerceAtMost(toTransform.width - left)
        val height = outHeight.coerceAtMost(toTransform.height - top)
        
        Log.d("CenterCrop", "Cropping from ($left, $top) with size ${width}x${height}")
        
        return Bitmap.createBitmap(toTransform, left, top, width, height)
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("eye_crop_100_v2".toByteArray())
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
                    
                    // 优化: 立即显示加载状态,提升用户感知速度
                    if (fromLiveEvent) {
                        updateStatus("📸 New photo received! Loading...", isCameraConnected)
                    }
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
                    val focalLength = formatFocalLength(exif?.getAttribute(ExifInterface.TAG_FOCAL_LENGTH))
                    "f/$aperture   ${formatShutter(shutter)}s   ISO $iso   ${focalLength}mm"
                } catch (e: Exception) {
                    Log.e("MainActivity", "EXIF read error", e)
                    "--   --   --   --"
                }
            }
            exifInfoTextView.text = exifData
            updateStatus("Analyzing: $fileName", isCameraConnected)

            // 2. 确定放大中心点（优先级：人眼 > EXIF对焦点 > 中央）
            val cropCenter = withContext(Dispatchers.IO) {
                // 优先级1: 尝试人眼检测
                var bitmap: Bitmap? = null
                try {
                    val options = BitmapFactory.Options().apply { inSampleSize = 8 }
                    bitmap = if (isContent) {
                        val uri = Uri.parse(path)
                        contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, options) }
                    } else {
                        BitmapFactory.decodeFile(path, options)
                    }
                    
                    if (bitmap == null) {
                        Log.w("MainActivity", "Failed to decode bitmap for eye detection")
                        return@withContext null
                    }
                    
                    Log.d("MainActivity", "Bitmap loaded: ${bitmap.width}x${bitmap.height}, starting face detection...")
                    
                    val image = InputImage.fromBitmap(bitmap, 0)
                    val faces = faceDetector.process(image).await()
                    
                    Log.d("MainActivity", "Face detection completed, found ${faces.size} face(s)")
                    
                    val face = faces.firstOrNull()
                    if (face == null) {
                        Log.d("MainActivity", "No face detected, will try EXIF or center")
                        // 继续执行后面的EXIF逻辑
                    } else {
                        Log.d("MainActivity", "Face bounds: ${face.boundingBox}")
                        
                        // 获取左右眼
                        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)
                        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)
                        
                        Log.d("MainActivity", "Left eye: ${leftEye?.position}, Right eye: ${rightEye?.position}")
                        
                        // 智能选择：优先使用左眼
                        val targetLandmark = when {
                            leftEye != null && rightEye != null -> {
                                Log.d("MainActivity", "Both eyes detected, using left eye by default")
                                leftEye
                            }
                            leftEye != null -> {
                                Log.d("MainActivity", "Only left eye detected")
                                leftEye
                            }
                            rightEye != null -> {
                                Log.d("MainActivity", "Only right eye detected")
                                rightEye
                            }
                            else -> {
                                Log.w("MainActivity", "Face detected but no eyes found")
                                null
                            }
                        }
                        
                        val result = targetLandmark?.position?.let {
                            val scaledPoint = android.graphics.Point(it.x.toInt() * 8, it.y.toInt() * 8)
                            Log.i("MainActivity", "✅ Using eye position for crop: $scaledPoint (original: ${it.x}, ${it.y})")
                            scaledPoint
                        }
                        
                        if (result != null) {
                            return@withContext result
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Eye detection error", e)
                } finally {
                    bitmap?.recycle()
                }
                
                // 优先级2: 尝试从EXIF获取对焦点位置
                try {
                    val exif = if (isContent) {
                        val uri = Uri.parse(path)
                        contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
                    } else {
                        ExifInterface(path)
                    }
                    
                    // 获取对焦点坐标（不同相机厂商可能使用不同标签）
                    val afPointX = exif?.getAttribute("InteroperabilityIndex") 
                        ?: exif?.getAttribute("SubjectLocation")
                        ?: exif?.getAttribute("SubjectArea")
                    
                    if (!afPointX.isNullOrEmpty()) {
                        // 解析对焦点坐标，格式可能是 "x,y" 或 "x,y,width,height"
                        val parts = afPointX.split(",", " ").filter { it.isNotEmpty() }
                        if (parts.size >= 2) {
                            val x = parts[0].toIntOrNull()
                            val y = parts[1].toIntOrNull()
                            if (x != null && y != null) {
                                Log.d("MainActivity", "Using AF point from EXIF: ($x, $y)")
                                return@withContext android.graphics.Point(x, y)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d("MainActivity", "No AF point in EXIF, will use center")
                }
                
                // 优先级3: 默认使用画面中央
                Log.d("MainActivity", "Using center point for crop")
                return@withContext null
            }
            
            // 3. 加载并裁剪图片
            Log.d("MainActivity", "Loading detail image with crop center: $cropCenter")
            
            mainScope.launch {
                val croppedBitmap = withContext(Dispatchers.IO) {
                    try {
                        // 加载原图
                        val originalBitmap = if (isContent) {
                            val uri = Uri.parse(path)
                            contentResolver.openInputStream(uri)?.use { stream ->
                                BitmapFactory.decodeStream(stream)
                            }
                        } else {
                            BitmapFactory.decodeFile(path)
                        }
                        
                        if (originalBitmap == null) {
                            Log.e("MainActivity", "Failed to load original bitmap")
                            return@withContext null
                        }
                        
                        Log.d("MainActivity", "Original bitmap loaded: ${originalBitmap.width}x${originalBitmap.height}")
                        
                        // 计算裁剪区域
                        val cropSize = 300.dpToPx() // 使用focusCheckCard的高度
                        val centerX = cropCenter?.x ?: (originalBitmap.width / 2)
                        val centerY = cropCenter?.y ?: (originalBitmap.height / 2)
                        
                        var left = centerX - cropSize / 2
                        var top = centerY - cropSize / 2
                        
                        // 边界检查
                        left = left.coerceIn(0, (originalBitmap.width - cropSize).coerceAtLeast(0))
                        top = top.coerceIn(0, (originalBitmap.height - cropSize).coerceAtLeast(0))
                        
                        val width = cropSize.coerceAtMost(originalBitmap.width - left)
                        val height = cropSize.coerceAtMost(originalBitmap.height - top)
                        
                        Log.d("CenterCrop", "Cropping from ($left, $top) size ${width}x${height}, center: ($centerX, $centerY)")
                        
                        // 执行裁剪
                        val cropped = Bitmap.createBitmap(originalBitmap, left, top, width, height)
                        originalBitmap.recycle() // 释放原图内存
                        
                        Log.d("MainActivity", "✅ Cropped bitmap: ${cropped.width}x${cropped.height}")
                        cropped
                    } catch (e: Exception) {
                        Log.e("MainActivity", "❌ Crop error", e)
                        null
                    }
                }
                
                // 在主线程设置图片
                if (croppedBitmap != null) {
                    focusCheckImageView.setImageBitmap(croppedBitmap)
                    updateStatus("Ready: $fileName", isCameraConnected)
                } else {
                    Log.e("MainActivity", "Failed to create cropped bitmap")
                }
            }
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
        return try {
            if (shutter.contains("/")) {
                // 已经是分数格式，如 "1/250"
                shutter
            } else {
                // 小数格式，如 "0.004"，转换为分数 "1/250"
                val decimalValue = shutter.toDouble()
                if (decimalValue >= 1.0) {
                    // 大于等于1秒，直接显示
                    "${decimalValue.toInt()}"
                } else {
                    // 小于1秒，转换为分数
                    val denominator = (1.0 / decimalValue).toInt()
                    "1/$denominator"
                }
            }
        } catch (e: Exception) {
            shutter
        }
    }

    private fun formatFocalLength(focalLength: String?): String {
        return try {
            if (focalLength.isNullOrEmpty() || focalLength == "0") {
                "--"
            } else if (focalLength.contains("/")) {
                // 分数格式，如 "550/10"，计算为 "55"
                val parts = focalLength.split("/")
                if (parts.size == 2) {
                    val numerator = parts[0].toDouble()
                    val denominator = parts[1].toDouble()
                    if (denominator != 0.0) {
                        val result = numerator / denominator
                        // 如果是整数，不显示小数点
                        if (result == result.toInt().toDouble()) {
                            "${result.toInt()}"
                        } else {
                            String.format("%.1f", result)
                        }
                    } else {
                        "--"
                    }
                } else {
                    focalLength
                }
            } else {
                // 已经是数字格式
                val value = focalLength.toDouble()
                if (value == value.toInt().toDouble()) {
                    "${value.toInt()}"
                } else {
                    String.format("%.1f", value)
                }
            }
        } catch (e: Exception) {
            "--"
        }
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}
