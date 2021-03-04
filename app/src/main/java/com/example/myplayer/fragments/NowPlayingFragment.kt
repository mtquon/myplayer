package com.example.myplayer.fragments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import com.bumptech.glide.Glide
import com.example.myplayer.R
import com.example.myplayer.databinding.FragmentNowPlayingBinding
import com.example.myplayer.utils.InjectorUtils
import com.example.myplayer.viewmodels.MainActivityViewModel
import com.example.myplayer.viewmodels.NowPlayingFragmentViewModel
import com.example.myplayer.viewmodels.NowPlayingFragmentViewModel.NowPlayingMetadata


/**
 *Fragment for current song being played
 */
class NowPlayingFragment : Fragment() {

    private val mainActivityViewModel by activityViewModels<MainActivityViewModel>{
        InjectorUtils.provideMainActivityViewModel(requireContext())
    }
    private val nowPlayViewModel by viewModels<NowPlayingFragmentViewModel> {
        InjectorUtils.provideNowPlayingFragmentViewModel(requireContext())
    }

    lateinit var binding: FragmentNowPlayingBinding

    companion object {
        fun newInstance() = NowPlayingFragment()
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?): View? {
        binding = FragmentNowPlayingBinding.inflate(inflater,container,false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        //Always true but lets link know that as well
        val context = activity ?: return
        Log.d(TAG, "onviewCreated")

        //Attach observers to the LiveData coming from this ViewModel
        nowPlayViewModel.mediaMetadata.observe(viewLifecycleOwner,
            Observer { mediaItem -> updateUI(view,mediaItem) })
        nowPlayViewModel.mediaButtonRes.observe(viewLifecycleOwner,
            Observer { res ->
                binding.mediaButton.setImageResource(res)
            })
        nowPlayViewModel.mediaPosition.observe(viewLifecycleOwner,
            Observer { pos ->
                binding.position.text = NowPlayingMetadata.timeStampToMSS(context, pos)
            })

        //Setup Ui Handlers for buttons
        binding.mediaButton.setOnClickListener{
            nowPlayViewModel.mediaMetadata.value?.let { mainActivityViewModel.playMediaId(it.id) }
        }

        //Initialize playback duration and position to zero
        binding.duration.text = NowPlayingMetadata.timeStampToMSS(context, 0L)
        binding.position.text = NowPlayingMetadata.timeStampToMSS(context, 0L)
    }

    /**
     * updating UI elements except for the current item playback
     */

    private fun updateUI(view: View, metadata: NowPlayingMetadata) = with(binding) {
        Log.d("NPfrag","metadata: $metadata")
        if (metadata.albumArtUri == Uri.EMPTY) {
            albumArt.setImageResource(R.drawable.ic_album_black_24dp)
        }else {
            //TODO : GET IMAGE
            var mmr = MediaMetadataRetriever()
            var art: Bitmap?
            var bfo = BitmapFactory.Options()
            mmr.setDataSource(context?.applicationContext,metadata.albumArtUri)
            var rawArt: ByteArray? = mmr.embeddedPicture

            art = if (rawArt != null) {
                BitmapFactory.decodeByteArray(rawArt, 0, rawArt.size, bfo)
            } else {
                /**
                 * TODO: fix this
                 * Currently this is bugged  https://github.com/android/uamp/issues/354
                 */
                BitmapFactory.decodeResource(resources, R.drawable.ic_album)
            }

            art.let {
                if (it != null){
                    albumArt.setImageBitmap(art)
                }else{
                    albumArt.setImageDrawable(ResourcesCompat.getDrawable(resources,R.drawable.ic_album,null))
                }
            }

            title.text = metadata.title
            subtitle.text = metadata.subtitle
            duration.text = metadata.duration
        }
    }
}
private const val TAG = "NPfrag"