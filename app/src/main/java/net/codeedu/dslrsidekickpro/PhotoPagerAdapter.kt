package net.codeedu.dslrsidekickpro

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.github.chrisbanes.photoview.PhotoView
import java.io.File

class PhotoPagerAdapter(
    private val photos: MutableList<String>,
    private val onPhotoVisible: (String) -> Unit
) : RecyclerView.Adapter<PhotoPagerAdapter.ViewHolder>() {

    class ViewHolder(val photoView: PhotoView) : RecyclerView.ViewHolder(photoView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_viewpager_photo, parent, false) as PhotoView
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val path = photos[position]
        
        // 使用 Glide 异步加载图片，解决主线程解码导致的卡顿
        Glide.with(holder.photoView.context)
            .load(path)
            .diskCacheStrategy(DiskCacheStrategy.NONE) // 摄影工作流通常不需要磁盘缓存，直接读文件更准
            .skipMemoryCache(false)
            .into(holder.photoView)
    }

    override fun getItemCount() = photos.size
}
