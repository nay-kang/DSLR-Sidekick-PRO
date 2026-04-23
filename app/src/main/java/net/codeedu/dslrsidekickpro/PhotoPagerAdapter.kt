package net.codeedu.dslrsidekickpro

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.github.chrisbanes.photoview.PhotoView

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

        // 使用 Glide 异步加载图片，解决主线程解码导致的卡顿
        Glide.with(context)
            .load(path)
            .thumbnail(thumbnailRequest)
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
