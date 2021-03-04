package com.example.myplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.myplayer.MediaItemData.Companion.PLAYBACK_RES_CHANGED
import com.example.myplayer.databinding.FragmentMediaitemBinding
import com.example.myplayer.media.library.ALBUMS_ROOT
import com.example.myplayer.media.library.RECENT_ROOT
import com.example.myplayer.media.library.RECOMMENDED_ROOT
import com.google.android.material.tabs.TabLayout
import kotlin.coroutines.coroutineContext

class MediaItemAdapter(private val itemClickedListener: (MediaItemData) -> Unit
) : ListAdapter<MediaItemData,MediaViewHolder>(MediaItemData.diffCallback) {
    private lateinit var context: Context
    private val roots = listOf<String>(RECOMMENDED_ROOT, ALBUMS_ROOT, RECENT_ROOT)


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = FragmentMediaitemBinding.inflate(inflater,parent,false)
        context=parent.context
        return MediaViewHolder(binding, itemClickedListener)
    }

    override fun onBindViewHolder(
            holder: MediaViewHolder,
            position: Int,
            payloads: MutableList<Any>
    ) {
        val mediaItem = getItem(position)
        var fullRefresh = payloads.isEmpty()


        if(payloads.isNotEmpty()){
            payloads.forEach{ payload ->
                when(payload) {
                    PLAYBACK_RES_CHANGED -> {
                        holder.playbackState.setImageResource(mediaItem.playbackRes)
                    }
                    //If the payload wasn't understood, refresh the full item just in case
                    else -> fullRefresh = true
                }
            }
        }

        /**
         * Normally we only fully refresh the list item if it's being initially bound, but we
         * might also do it if there was a payload that wasn't understood, just to ensure there
         * isn't a stale item.
         */
        if(fullRefresh){
            holder.item = mediaItem
            holder.titleView.text = mediaItem.title
            holder.subtitleView.text = mediaItem.subtitle
            holder.playbackState.setImageResource(mediaItem.playbackRes)

            //TODO: load mp3 art
            Log.d("full refresh","album art uri: $mediaItem")
            Log.d("full refresh","parent contex is : $context and holder context is ${holder.titleView.context}")

            //If mediaId is root node id use glid
            if(roots.contains(mediaItem.mediaId)){
                Glide.with(holder.albumArt)
                        .load(mediaItem.albumArtUri)
                        .into(holder.albumArt)

            }else{
                //If possible retrieve embedded thumbnail in mp3 file

                var mmr = MediaMetadataRetriever()
                var art: Bitmap?
                var bfo = BitmapFactory.Options()
                mmr.setDataSource(context,mediaItem.albumArtUri)
                var rawArt: ByteArray? = mmr.embeddedPicture
                art = if (rawArt != null) {
                    BitmapFactory.decodeByteArray(rawArt, 0, rawArt.size, bfo)
                } else {
                    /**
                     * TODO: fix this
                     * Currently this is bugged  https://github.com/android/uamp/issues/354
                     */
                    BitmapFactory.decodeResource(context.resources, R.drawable.ic_album)
                }

                if (art != null){
                    holder.albumArt.setImageBitmap(art)
                }else{
                    holder.albumArt.setImageDrawable(
                            ResourcesCompat.getDrawable(
                                    context.resources,R.drawable.ic_album,null
                            )
                    )
                }
            }
        }
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        onBindViewHolder(holder,position, mutableListOf())
    }


}


class MediaViewHolder(
        binding: FragmentMediaitemBinding,
        itemClickedListener: (MediaItemData) -> Unit
) : RecyclerView.ViewHolder(binding.root){
    val titleView: TextView = binding.title
    val subtitleView: TextView = binding.subtitle
    val albumArt: ImageView = binding.albumArt
    val playbackState: ImageView = binding.itemState

    var item: MediaItemData? = null
    init {
        binding.root.setOnClickListener{
            item?.let{itemClickedListener(it)}
        }
    }
}

