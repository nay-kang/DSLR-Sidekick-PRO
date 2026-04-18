package net.codeedu.dslrsidekickpro

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

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
        Glide.with(holder.imageView.context)
            .load(path)
            .centerCrop()
            .into(holder.imageView)
        
        holder.itemView.setOnClickListener { onClick(path) }
    }

    override fun getItemCount() = photos.size

    fun addPhoto(path: String) {
        photos.add(0, path) // 新照片放在最前面
        notifyItemInserted(0)
    }
}
