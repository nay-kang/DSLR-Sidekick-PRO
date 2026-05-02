package net.codeedu.dslrsidekickpro

import android.content.*
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlinx.coroutines.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import androidx.preference.PreferenceManager
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.tasks.await
import android.graphics.Point
import androidx.core.content.edit

open class ViewerActivity : AppCompatActivity() {
    private lateinit var statusBarStatus: TextView
    private lateinit var connectionIndicator: View
    private lateinit var exifInfoTextView: TextView
    private lateinit var photoViewPager: ViewPager2
    private lateinit var focusCheckImageView: android.widget.ImageView
    private lateinit var btnGallery: Button

    private var currentPhotoPath: String? = null
    private val allPhotos = mutableListOf<String>()
    private var currentPhotoIndex = 0
    private lateinit var pagerAdapter: PhotoPagerAdapter
    private var isCameraConnected = false

    private var cameraService: CameraService? = null
    private var isBound = false

    private var updateDetailJob: Job? = null
    private var lastDetailPath: String? = null

    private val requestFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Persist permission
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(it, takeFlags)

            // Save to prefs
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit {
                    putString("sync_folder_uri", it.toString())
                }

            loadAllPhotos()
        }
    }

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CameraService.CameraBinder
            cameraService = binder.getService()
            cameraService?.addListener(cameraListener)
            isBound = true
            Log.d("ViewerActivity", "Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService = null
            isBound = false
        }
    }

    private val mainScope = CoroutineScope(Dispatchers.Main + Job())

    private val cameraListener = object : CameraService.CameraEventListener {
        override fun onCameraStatusUpdate(status: CameraService.CameraStatus, extraMessage: String?) {
            val statusText = if (status == CameraService.CameraStatus.CONNECTED) "Connected: $extraMessage" else "Disconnected"
            isCameraConnected = status == CameraService.CameraStatus.CONNECTED
            updateStatus(statusText, isCameraConnected)
        }

        override fun onNewPhoto(uri: Uri, realPath: String?, fromLiveEvent: Boolean) {
            runOnUiThread {
                val path = uri.toString()
                Log.d("ViewerActivity", "New photo received: $path (live: $fromLiveEvent)")

                val isNew = !allPhotos.contains(path)
                if (isNew) {
                    allPhotos.add(0, path) // 新照片添加到开头（索引0）
                    pagerAdapter.notifyItemInserted(0)
                    // RTL布局下，notifyItemInserted(0)后需要调整位置
                    photoViewPager.post {
                        photoViewPager.setCurrentItem(0, false)
                    }
                }

                // For live events (taking a picture) or the very first photo, always jump to it
                if (fromLiveEvent || allPhotos.size == 1) {
                    val index = allPhotos.indexOf(path)
                    if (index != -1) {
                        photoViewPager.post {
                            photoViewPager.setCurrentItem(index, true)
                            updateDetailViews(path)
                        }
                    }
                } else if (photoViewPager.currentItem == 0) {
                    // If we're at the first item (newest), update to show the new photo
                    updateDetailViews(path)
                }
            }
        }

        override fun onSyncProgress(current: Int, total: Int) {
            updateStatus("Syncing: $current/$total", isCameraConnected)
        }

        override fun onSyncCompleted(total: Int) {
            runOnUiThread {
                updateStatus("Sync Complete: $total photos", isCameraConnected)
                loadAllPhotos() // Refresh list
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersiveMode()

        statusBarStatus = findViewById(R.id.statusBarStatus)
        connectionIndicator = findViewById(R.id.connectionIndicator)
        exifInfoTextView = findViewById(R.id.exifInfoTextView)
        photoViewPager = findViewById(R.id.photoViewPager)
        focusCheckImageView = findViewById(R.id.focusCheckImageView)
        btnGallery = findViewById(R.id.btnGallery)

        setupViewPager()

        btnGallery.setOnClickListener {
            val intent = Intent(this, GalleryActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
        }

        checkFolderAndLoadPhotos()

        // Bind to CameraService
        val serviceIntent = Intent(this, CameraService::class.java)
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)

        // Handle intent from Gallery
        intent.getStringExtra("photo_path")?.let { path ->
            loadAllPhotos(path)
        }
    }

    private fun checkFolderAndLoadPhotos() {
        val folderUriStr = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("sync_folder_uri", null)

        if (folderUriStr == null) {
            requestFolderLauncher.launch(null)
        } else {
            loadAllPhotos()
        }
    }

    private fun setupViewPager() {
        pagerAdapter = PhotoPagerAdapter(allPhotos)
        photoViewPager.adapter = pagerAdapter

        photoViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position < allPhotos.size) {
                    currentPhotoIndex = position
                    currentPhotoPath = allPhotos[position]
                    updateDetailViews(allPhotos[position])
                }
            }
        })
    }

    private fun loadAllPhotos(targetPhotoPath: String? = null) {
        // Immediate feedback: if we have a target, show it right away
        if (targetPhotoPath != null) {
            if (!allPhotos.contains(targetPhotoPath)) {
                allPhotos.add(0, targetPhotoPath)
                pagerAdapter.notifyDataSetChanged()
            }
            val index = allPhotos.indexOf(targetPhotoPath)
            photoViewPager.setCurrentItem(index, false)
            // Force update if it's already at the target index
            if (index == currentPhotoIndex) {
                updateDetailViews(targetPhotoPath)
            }
        }

        mainScope.launch {
            val photos = withContext(Dispatchers.IO) {
                val result = mutableListOf<Pair<String, Long>>()
                val folderUriStr = PreferenceManager.getDefaultSharedPreferences(this@ViewerActivity)
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
                    AppLogger.e("ViewerActivity", "Fast sync error", e)
                }

                result.sortByDescending { it.second } // 降序排列：新的在前（索引0），旧的在后
                result.map { it.first }
            }

            if (photos.isNotEmpty() && photos != allPhotos) {
                allPhotos.clear()
                allPhotos.addAll(photos)
                pagerAdapter.notifyDataSetChanged()

                // Restore selection
                val selection = targetPhotoPath ?: currentPhotoPath
                if (selection != null) {
                    val index = allPhotos.indexOf(selection)
                    if (index != -1) {
                        photoViewPager.setCurrentItem(index, false)
                    }
                }
            }
        }
    }

    private fun updateDetailViews(path: String) {
        if (path == lastDetailPath && updateDetailJob?.isActive == true) return
        lastDetailPath = path

        updateDetailJob?.cancel()
        updateDetailJob = mainScope.launch {
            val isContent = path.startsWith("content://")

            // 1. 获取文件名 (仅显示文件名，不显示完整路径或 Document ID)
            val fileName = withContext(Dispatchers.IO) {
                if (isContent) {
                    val uri = path.toUri()
                    var name: String? = null
                    try {
                        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                                if (nameIdx != -1) {
                                    name = cursor.getString(nameIdx)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("ViewerActivity", "Failed to query display name", e)
                    }
                    // 备选方案：从 URI 片段中提取并清理
                    name ?: uri.lastPathSegment?.substringAfterLast(':') ?: "Unknown"
                } else {
                    File(path).name
                }
            }

            // 2. 获取 EXIF
            updateStatus("Loading: $fileName", isCameraConnected)
            val exifData = withContext(Dispatchers.IO) {
                try {
                    val exif = if (isContent) {
                        val uri = path.toUri()
                        contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
                    } else {
                        ExifInterface(path)
                    }
                    val aperture = exif?.getAttribute(ExifInterface.TAG_F_NUMBER) ?: "--"
                    val shutter = exif?.getAttribute(ExifInterface.TAG_EXPOSURE_TIME) ?: "--"
                    // 使用 TAG_PHOTOGRAPHIC_SENSITIVITY 替代已弃用的 TAG_ISO_SPEED_RATINGS
                    val iso = exif?.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY) ?: "--"
                    val focalLength = formatFocalLength(exif?.getAttribute(ExifInterface.TAG_FOCAL_LENGTH))
                    "f/$aperture   ${formatShutter(shutter)}s   ISO $iso   ${focalLength}mm"
                } catch (e: Exception) {
                    AppLogger.e("ViewerActivity", "EXIF read error", e)
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
                        val uri = path.toUri()
                        contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, options) }
                    } else {
                        BitmapFactory.decodeFile(path, options)
                    }

                    if (bitmap == null) {
                        Log.w("ViewerActivity", "Failed to decode bitmap for eye detection")
                        return@withContext null
                    }

                    Log.d("ViewerActivity", "Bitmap loaded: ${bitmap.width}x${bitmap.height}, starting face detection...")

                    val image = InputImage.fromBitmap(bitmap, 0)
                    val faces = faceDetector.process(image).await()

                    Log.d("ViewerActivity", "Face detection completed, found ${faces.size} face(s)")

                    val face = faces.firstOrNull()
                    if (face == null) {
                        Log.d("ViewerActivity", "No face detected, will try EXIF or center")
                        // 继续执行后面的EXIF逻辑
                    } else {
                        Log.d("ViewerActivity", "Face bounds: ${face.boundingBox}")

                        // 获取左右眼
                        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)
                        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)

                        Log.d("ViewerActivity", "Left eye: ${leftEye?.position}, Right eye: ${rightEye?.position}")

                        // 智能选择：优先使用左眼
                        val targetLandmark = when {
                            leftEye != null && rightEye != null -> {
                                Log.d("ViewerActivity", "Both eyes detected, using left eye by default")
                                leftEye
                            }
                            leftEye != null -> {
                                Log.d("ViewerActivity", "Only left eye detected")
                                leftEye
                            }
                            rightEye != null -> {
                                Log.d("ViewerActivity", "Only right eye detected")
                                rightEye
                            }
                            else -> {
                                Log.w("ViewerActivity", "Face detected but no eyes found")
                                null
                            }
                        }

                        val result = targetLandmark?.position?.let {
                            val scaledPoint = Point(it.x.toInt() * 8, it.y.toInt() * 8)
                                 Log.i("ViewerActivity", "✅ Using eye position for crop: $scaledPoint (original: ${it.x}, ${it.y})")
                            scaledPoint
                        }

                        if (result != null) {
                            return@withContext result
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e("ViewerActivity", "Eye detection error", e)
                } finally {
                    bitmap?.recycle()
                }

                // 优先级2: 尝试从EXIF获取对焦点位置
                try {
                    val exif = if (isContent) {
                        val uri = path.toUri()
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
                                Log.d("ViewerActivity", "Using AF point from EXIF: ($x, $y)")
                                return@withContext Point(x, y)
                            }
                        }
                    }
                } catch (_: Exception) {
                    Log.d("ViewerActivity", "No AF point in EXIF, will use center")
                }

                // 优先级3: 默认使用画面中央
                Log.d("ViewerActivity", "Using center point for crop")
                return@withContext null
            }

            // 3. 加载并裁剪图片
            Log.d("ViewerActivity", "Loading detail image with crop center: $cropCenter")

            val croppedBitmap = withContext(Dispatchers.IO) {
                try {
                    val uri = if (path.startsWith("content://") || path.startsWith("file://")) {
                        path.toUri()
                    } else {
                        Uri.fromFile(File(path))
                    }

                    val decoder = contentResolver.openInputStream(uri)?.use {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            BitmapRegionDecoder.newInstance(it)
                        } else {
                            @Suppress("DEPRECATION")
                            BitmapRegionDecoder.newInstance(it, false)
                        }
                    } ?: return@withContext null

                    val fullWidth = decoder.width
                    val fullHeight = decoder.height

                    // 计算裁剪区域
                    val cropSize = 300.dpToPx()
                    val centerX = cropCenter?.x ?: (fullWidth / 2)
                    val centerY = cropCenter?.y ?: (fullHeight / 2)

                    var left = centerX - cropSize / 2
                    var top = centerY - cropSize / 2

                    // 边界检查
                    left = left.coerceIn(0, (fullWidth - cropSize).coerceAtLeast(0))
                    top = top.coerceIn(0, (fullHeight - cropSize).coerceAtLeast(0))

                    val rect = Rect(left, top, left + cropSize, top + cropSize)
                    val cropped = decoder.decodeRegion(rect, null)
                    decoder.recycle()

                    Log.d("ViewerActivity", "✅ Cropped bitmap: ${cropped?.width}x${cropped?.height}")
                    cropped
                } catch (e: Exception) {
                    AppLogger.e("ViewerActivity", "❌ Crop error", e)
                    null
                }
            }

            // 在主线程设置图片
            if (croppedBitmap != null) {
                focusCheckImageView.setImageBitmap(croppedBitmap)
                updateStatus("Ready: $fileName", isCameraConnected)
            } else {
                AppLogger.e("ViewerActivity", "Failed to create cropped bitmap")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            cameraService?.removeListener(cameraListener)
            unbindService(serviceConnection)
            isBound = false
        }
        mainScope.cancel()
    }

    private fun updateStatus(status: String, isConnected: Boolean?) {
        runOnUiThread {
            statusBarStatus.text = status
            isConnected?.let {
                connectionIndicator.setBackgroundResource(
                    if (it) R.drawable.indicator_connected else R.drawable.indicator_disconnected
                )
            }
        }
    }

    private fun enableImmersiveMode() {
        // Use WindowCompat and WindowInsetsControllerCompat for a consistent API across versions
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
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
            val s = shutter.toDouble()
            if (s >= 1) {
                s.toInt().toString()
            } else {
                "1/${(1 / s).toInt()}"
            }
        } catch (_: Exception) {
            shutter
        }
    }

    private fun formatFocalLength(focalLength: String?): String {
        if (focalLength == null) return "--"
        return try {
            if (focalLength.contains("/")) {
                val parts = focalLength.split("/")
                val num = parts[0].toDouble()
                val den = parts[1].toDouble()
                (num / den).toInt().toString()
            } else {
                focalLength.toDouble().toInt().toString()
            }
        } catch (_: Exception) {
            focalLength
        }
    }

    private fun Int.dpToPx(): Int {
        val density = resources.displayMetrics.density
        return (this * density).toInt()
    }
}



