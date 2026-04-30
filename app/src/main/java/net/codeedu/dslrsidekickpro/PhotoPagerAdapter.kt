package net.codeedu.dslrsidekickpro

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import android.graphics.Bitmap
import android.graphics.Matrix
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.github.chrisbanes.photoview.PhotoView
import java.security.MessageDigest

class PhotoPagerAdapter(
    private val photos: MutableList<String>
) : RecyclerView.Adapter<PhotoPagerAdapter.ViewHolder>() {

    class ViewHolder(val photoView: PhotoView) : RecyclerView.ViewHolder(photoView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_viewpager_photo, parent, false) as PhotoView
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val path = photos[position]
        val context = holder.photoView.context
        
        val thumbnailRequest = Glide.with(context)
            .load(path)
            .sizeMultiplier(0.1f)
            .transform(ForceLandscapeTransformation())

        // 使用 Glide 异步加载图片，解决主线程解码导致的卡顿
        Glide.with(context)
            .load(path)
            .thumbnail(thumbnailRequest)
            .transform(ForceLandscapeTransformation())
            .transition(DrawableTransitionOptions.withCrossFade())
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(holder.photoView)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        // 清理缓存以释放内存
        Glide.with(holder.photoView.context).clear(holder.photoView)
    }

    override fun getItemCount() = photos.size
}

// Transformation that forces portrait images into landscape by rotating 90 degrees.
// This is intentionally simple: if width >= height the bitmap is returned unchanged.
// If the bitmap is portrait (width < height) we rotate it clockwise 90deg so it
// displays in landscape in the full-screen viewer.
private class ForceLandscapeTransformation : BitmapTransformation() {
    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("ForceLandscapeTransformation-v1".toByteArray())
    }

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        if (toTransform.width >= toTransform.height) return toTransform

        val matrix = Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(toTransform, 0, 0, toTransform.width, toTransform.height, matrix, true)
        return rotated
    }
}

