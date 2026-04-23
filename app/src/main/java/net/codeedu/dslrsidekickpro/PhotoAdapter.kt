package net.codeedu.dslrsidekickpro

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

class PhotoAdapter(
    private val photos: MutableList<String>,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<PhotoAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.thumbImageView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_photo, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val path = photos[position]
        val context = holder.imageView.context
        
        val thumbnailRequest = Glide.with(context)
            .load(path)
            .sizeMultiplier(0.1f)

        Glide.with(context)
            .load(path)
            .thumbnail(thumbnailRequest)
            .transition(DrawableTransitionOptions.withCrossFade())
            .centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(holder.imageView)
        
        holder.itemView.setOnClickListener { onClick(path) }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        // 清理缓存的ImageView以释放内存
        Glide.with(holder.imageView.context).clear(holder.imageView)
    }

    override fun getItemCount() = photos.size

    fun addPhoto(path: String) {
        photos.add(0, path) // 新照片放在最前面
        notifyItemInserted(0)
    }
}
