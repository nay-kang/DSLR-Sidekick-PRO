package net.codeedu.dslrsidekickpro

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale

/**
 * Isolated web server service that allows other devices to view photos from this phone.
 * Completely decoupled from CameraService and gallery logic.
 */
class PhotoWebServerService : Service() {

    private var webServer: PhotoWebServer? = null
    private val PORT = 8080
    private val CHANNEL_ID = "PhotoWebServerChannel"
    private val NOTIFICATION_ID = 2

    companion object {
        private const val TAG = "PhotoWebServerService"
        
        // Broadcast actions for external control
        const val ACTION_START_SERVER = "net.codeedu.dslrsidekickpro.START_WEB_SERVER"
        const val ACTION_STOP_SERVER = "net.codeedu.dslrsidekickpro.STOP_WEB_SERVER"
        const val ACTION_NOTIFY_NEW_PHOTO = "net.codeedu.dslrsidekickpro.NOTIFY_NEW_PHOTO"
        const val EXTRA_PORT = "port"
        const val EXTRA_PHOTO_NAME = "photo_name"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> {
                val port = intent.getIntExtra(EXTRA_PORT, PORT)
                startWebServer(port)
            }
            ACTION_STOP_SERVER -> {
                stopWebServer()
                stopSelf()
            }
            ACTION_NOTIFY_NEW_PHOTO -> {
                // Notify web clients about new photo
                val photoName = intent.getStringExtra(EXTRA_PHOTO_NAME)
                if (photoName != null) {
                    Log.i(TAG, "Received notification for new photo: $photoName")
                    onNewPhotoSaved(photoName)
                } else {
                    Log.w(TAG, "Received NOTIFY_NEW_PHOTO but no photo name provided")
                }
            }
            else -> {
                // Default behavior: start server on default port
                startWebServer(PORT)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopWebServer()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Photo Web Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Web server for photo sharing"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Photo Web Server")
            .setContentText("Serving photos on port $PORT")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start foreground service", e)
        }
    }

    private fun startWebServer(port: Int) {
        if (webServer != null && webServer!!.isAlive) {
            Log.i(TAG, "Web server already running on port ${webServer!!.listeningPort}")
            return
        }

        try {
            webServer = PhotoWebServer(this, port)
            // Start with reduced timeout and limited async threads to prevent memory issues
            webServer!!.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "Web server started on port $port (timeout: ${NanoHTTPD.SOCKET_READ_TIMEOUT}ms)")
            
            // Update notification with actual port
            updateNotification(port)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start web server", e)
        }
    }

    private fun stopWebServer() {
        try {
            webServer?.stop()
            webServer = null
            Log.i(TAG, "Web server stopped")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping web server", e)
        }
    }

    private fun updateNotification(port: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Photo Web Server")
            .setContentText("Serving photos on port $port")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Get list of photo URIs from the configured sync folder
     */
    fun getPhotoList(): List<Triple<String, String, Long>> {
        val result = mutableListOf<Triple<String, String, Long>>()
        
        val folderUriStr = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("sync_folder_uri", null) ?: return result

        try {
            val rootUri = folderUriStr.toUri()
            val treeId = DocumentsContract.getTreeDocumentId(rootUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, treeId)

            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )

            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val modifiedIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val mime = cursor.getString(mimeIdx)
                    if (mime == "image/jpeg" || mime?.startsWith("image/") == true) {
                        val docId = cursor.getString(idIdx)
                        val displayName = cursor.getString(nameIdx)
                        val lastModified = cursor.getLong(modifiedIdx)
                        val uri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)
                        
                        result.add(Triple(displayName, uri.toString(), lastModified))
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error getting photo list", e)
        }

        // Sort by last modified descending (newest first)
        return result.sortedByDescending { it.third }
    }

    /**
     * Notify web clients about a new photo (called from CameraService)
     */
    fun onNewPhotoSaved(fileName: String) {
        Log.i(TAG, "onNewPhotoSaved called for: $fileName")
        
        // Must run on background thread to avoid NetworkOnMainThreadException
        Thread {
            try {
                webServer?.notifyNewPhoto(fileName)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error notifying SSE clients", e)
            }
        }.start()
    }

    /**
     * Get photo data as byte array by URI string
     */
    fun getPhotoData(uriString: String): ByteArray? {
        return try {
            val uri = uriString.toUri()
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val byteArrayOutputStream = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    byteArrayOutputStream.write(buffer, 0, bytesRead)
                }
                byteArrayOutputStream.toByteArray()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error reading photo data", e)
            null
        }
    }

    /**
     * Inner class implementing the NanoHTTPD server
     */
    private inner class PhotoWebServer(private val context: Context, port: Int) : NanoHTTPD(port) {
        
        // Track connected SSE clients for real-time notifications
        private val sseClients = java.util.concurrent.CopyOnWriteArrayList<java.io.OutputStream>()

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method

            return try {
                when {
                    uri == "/" || uri == "/index.html" -> serveGalleryPage()
                    uri == "/api/events" && method == Method.GET -> serveEventStream()
                    uri.startsWith("/api/photos") && method == Method.GET -> servePhotoList(session)
                    uri.startsWith("/api/thumb/") && method == Method.GET -> serveThumbnail(uri)
                    uri.startsWith("/api/photo/") && method == Method.GET -> serveSinglePhoto(uri)
                    uri.startsWith("/css/") -> serveStaticAsset(uri, "text/css")
                    uri.startsWith("/js/") -> serveStaticAsset(uri, "application/javascript")
                    else -> newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "text/plain",
                        "Not Found"
                    )
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error serving request: $uri", e)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "Internal Server Error: ${e.message}"
                )
            }
        }

        /**
         * Serve Server-Sent Events stream for real-time photo updates
         */
        private fun serveEventStream(): Response {
            Log.i(TAG, "SSE client connecting...")
            
            // Create a custom response that writes directly to the output stream
            return object : Response(Status.OK, "text/event-stream", null, 0) {
                private var clientOutputStream: java.io.OutputStream? = null
                
                override fun send(outputStream: java.io.OutputStream) {
                    try {
                        // Store the output stream for later use
                        clientOutputStream = outputStream
                        sseClients.add(outputStream)
                        Log.i(TAG, "SSE client connected (direct). Total clients: ${sseClients.size}")
                        
                        // Send headers
                        val headerText = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/event-stream\r\n" +
                                "Cache-Control: no-cache\r\n" +
                                "Connection: keep-alive\r\n" +
                                "Access-Control-Allow-Origin: *\r\n" +
                                "\r\n"
                        outputStream.write(headerText.toByteArray(Charsets.UTF_8))
                        outputStream.flush()
                        
                        // Send initial connected event
                        val connectedEvent = "data: {\"type\":\"connected\",\"message\":\"SSE connection established\"}\n\n"
                        outputStream.write(connectedEvent.toByteArray(Charsets.UTF_8))
                        outputStream.flush()
                        Log.d(TAG, "Sent SSE connected event")
                        
                        // Keep connection alive with periodic keepalive
                        while (!Thread.currentThread().isInterrupted) {
                            Thread.sleep(25000) // 25 seconds
                            val keepalive = ": keepalive\n\n"
                            outputStream.write(keepalive.toByteArray(Charsets.UTF_8))
                            outputStream.flush()
                            Log.v(TAG, "Sent keepalive")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "SSE stream error: ${e.message}")
                        clientOutputStream?.let { sseClients.remove(it) }
                        Log.i(TAG, "SSE client removed. Remaining: ${sseClients.size}")
                    }
                }
            }
        }

        /**
         * Notify all SSE clients about new photos
         */
        fun notifyNewPhoto(photoName: String) {
            if (sseClients.isEmpty()) {
                Log.d(TAG, "No SSE clients to notify")
                return
            }
            
            val timestamp = System.currentTimeMillis()
            val event = "event: message\ndata: {\"type\":\"new_photo\",\"name\":\"$photoName\",\"timestamp\":$timestamp}\n\n"
            
            Log.i(TAG, "Notifying ${sseClients.size} SSE clients about: $photoName")
            
            val clientsToRemove = mutableListOf<java.io.OutputStream>()
            var successCount = 0
            
            for ((index, clientOutput) in sseClients.withIndex()) {
                try {
                    clientOutput.write(event.toByteArray(Charsets.UTF_8))
                    clientOutput.flush()
                    successCount++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to notify SSE client #$index: ${e.message}")
                    clientsToRemove.add(clientOutput)
                }
            }
            
            if (successCount > 0) {
                Log.d(TAG, "Successfully notified $successCount/${sseClients.size} clients")
            }
            
            // Remove failed clients
            sseClients.removeAll(clientsToRemove)
            if (clientsToRemove.isNotEmpty()) {
                Log.i(TAG, "Removed ${clientsToRemove.size} disconnected clients. Remaining: ${sseClients.size}")
            }
        }

        private fun serveGalleryPage(): Response {
            return try {
                // Load HTML template from assets
                val htmlContent = context.assets.open("gallery.html").bufferedReader().use { it.readText() }
                
                newFixedLengthResponse(
                    Response.Status.OK,
                    "text/html; charset=UTF-8",
                    htmlContent
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error loading gallery HTML", e)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "Error loading gallery: ${e.message}"
                )
            }
        }

        private fun servePhotoList(session: IHTTPSession): Response {
            return try {
                val photoList = getPhotoList()
                
                // Generate ETag based on photo count and last modified time
                val etagValue = if (photoList.isEmpty()) {
                    "\"empty\""
                } else {
                    val hash = "${photoList.size}_${photoList.first().third}"
                    "\"${hash}\""
                }
                
                // Check If-None-Match header for conditional request
                val ifNoneMatch = session.headers["if-none-match"]
                if (ifNoneMatch != null && ifNoneMatch == etagValue) {
                    // Content hasn't changed - return 304 Not Modified
                    return newFixedLengthResponse(
                        Response.Status.NOT_MODIFIED,
                        "application/json",
                        ""
                    )
                }
                
                val jsonArray = JSONArray()
                
                photoList.forEach { (name, uri, lastModified) ->
                    val jsonObject = JSONObject().apply {
                        put("name", name)
                        put("uri", uri)
                        put("lastModified", lastModified)
                    }
                    jsonArray.put(jsonObject)
                }
                
                val responseData = jsonArray.toString(2)
                
                // Return with ETag header
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json; charset=UTF-8",
                    responseData
                ).apply {
                    addHeader("ETag", etagValue)
                    addHeader("Cache-Control", "no-cache")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error serving photo list", e)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    JSONObject().put("error", e.message).toString()
                )
            }
        }

        private fun serveSinglePhoto(uri: String): Response {
            return try {
                // Extract photo name from URI path: /api/photo/DSC_1234.JPG
                val photoName = java.net.URLDecoder.decode(uri.substringAfterLast("/"), "UTF-8")
                
                // Find the photo in the list
                val photoList = getPhotoList()
                val photo = photoList.find { it.first == photoName }
                
                if (photo == null) {
                    return newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "text/plain",
                        "Photo not found: $photoName"
                    )
                }
                
                // Get photo data
                val photoData = getPhotoData(photo.second)
                
                if (photoData == null) {
                    return newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        "text/plain",
                        "Failed to read photo data"
                    )
                }
                
                // Use fixed length response with proper headers for better performance
                val headers = mutableMapOf<String, String>()
                headers["Cache-Control"] = "public, max-age=86400" // Cache for 1 day
                headers["Content-Length"] = photoData.size.toString()
                
                newFixedLengthResponse(
                    Response.Status.OK,
                    "image/jpeg",
                    java.io.ByteArrayInputStream(photoData),
                    photoData.size.toLong()
                ).apply {
                    headers.forEach { (key, value) -> addHeader(key, value) }
                }
            } catch (e: Exception) {
                // Ignore broken pipe errors (client disconnected)
                if (e.message?.contains("Broken pipe", ignoreCase = true) == true) {
                    Log.d(TAG, "Client disconnected during photo transfer (normal)")
                    return newFixedLengthResponse(Response.Status.OK, "image/jpeg", java.io.ByteArrayInputStream(ByteArray(0)), 0)
                }
                AppLogger.e(TAG, "Error serving photo", e)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "Error: ${e.message}"
                )
            }
        }

        private fun serveThumbnail(uri: String): Response {
            return try {
                // Extract photo name from URI path: /api/thumb/DSC_1234.JPG
                val photoName = java.net.URLDecoder.decode(uri.substringAfterLast("/"), "UTF-8")
                
                // Find the photo in the list
                val photoList = getPhotoList()
                val photo = photoList.find { it.first == photoName }
                
                if (photo == null) {
                    return newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "text/plain",
                        "Photo not found: $photoName"
                    )
                }
                
                // Get photo data
                val photoData = getPhotoData(photo.second)
                
                if (photoData == null) {
                    return newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        "text/plain",
                        "Failed to read photo data"
                    )
                }
                
                // Compress and resize to square thumbnail (200x200, crop center)
                val thumbnailData = createSquareThumbnail(photoData, 200, 75)
                
                if (thumbnailData == null) {
                    // Fallback to original if compression fails
                    Log.w(TAG, "Thumbnail creation failed, serving original")
                    return newFixedLengthResponse(
                        Response.Status.OK,
                        "image/jpeg",
                        java.io.ByteArrayInputStream(photoData),
                        photoData.size.toLong()
                    )
                }
                
                // Cache thumbnails for 1 hour
                val headers = mutableMapOf<String, String>()
                headers["Cache-Control"] = "public, max-age=3600"
                headers["Content-Length"] = thumbnailData.size.toString()
                
                newFixedLengthResponse(
                    Response.Status.OK,
                    "image/jpeg",
                    java.io.ByteArrayInputStream(thumbnailData),
                    thumbnailData.size.toLong()
                ).apply {
                    headers.forEach { (key, value) -> addHeader(key, value) }
                }
            } catch (e: Exception) {
                // Ignore broken pipe errors (client disconnected)
                if (e.message?.contains("Broken pipe", ignoreCase = true) == true) {
                    Log.d(TAG, "Client disconnected during thumbnail transfer (normal)")
                    return newFixedLengthResponse(Response.Status.OK, "image/jpeg", java.io.ByteArrayInputStream(ByteArray(0)), 0)
                }
                AppLogger.e(TAG, "Error serving thumbnail", e)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "Error: ${e.message}"
                )
            }
        }

        /**
         * Create a square thumbnail by cropping center area
         * @param imageData Original image bytes
         * @param size Square dimension (width = height)
         * @param quality JPEG quality (0-100)
         * @return Compressed square thumbnail bytes or null if failed
         */
        private fun createSquareThumbnail(
            imageData: ByteArray,
            size: Int,
            quality: Int
        ): ByteArray? {
            return try {
                // Decode with sampling to reduce memory usage
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(imageData, 0, imageData.size, options)

                // Calculate sample size for efficient decoding
                options.inSampleSize = calculateInSampleSize(options, size, size)
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.RGB_565
                
                // Decode scaled bitmap
                val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size, options)
                    ?: return null
                
                // Calculate crop coordinates to get center square
                val cropSize = minOf(bitmap.width, bitmap.height)
                val startX = (bitmap.width - cropSize) / 2
                val startY = (bitmap.height - cropSize) / 2
                
                // Crop center square
                val croppedBitmap = Bitmap.createBitmap(bitmap, startX, startY, cropSize, cropSize)
                if (croppedBitmap != bitmap) {
                    bitmap.recycle()
                }
                
                // Scale to target size
                val scaledBitmap = croppedBitmap.scale(size, size)
                if (scaledBitmap != croppedBitmap) {
                    croppedBitmap.recycle()
                }
                
                // Compress to JPEG
                val outputStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                scaledBitmap.recycle()
                
                outputStream.toByteArray()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error creating square thumbnail", e)
                null
            }
        }

        /**
         * Calculate optimal sample size for downscaling
         */
        private fun calculateInSampleSize(
            options: BitmapFactory.Options,
            reqWidth: Int,
            reqHeight: Int
        ): Int {
            val height = options.outHeight
            val width = options.outWidth
            var inSampleSize = 1
            
            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2
                
                while ((halfHeight / inSampleSize) >= reqHeight &&
                       (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            
            return inSampleSize
        }

        private fun serveStaticAsset(path: String, contentType: String): Response {
            return try {
                // Remove leading slash to get asset path
                val assetPath = path.trimStart('/')
                
                // Load asset file
                val content = context.assets.open(assetPath).bufferedReader().use { it.readText() }
                
                newFixedLengthResponse(
                    Response.Status.OK,
                    contentType,
                    content
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error serving static asset: $path", e)
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain",
                    "Asset not found: $path"
                )
            }
        }
    }
}
