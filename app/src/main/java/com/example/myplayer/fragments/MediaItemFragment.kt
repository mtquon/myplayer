package com.example.myplayer.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import com.example.myplayer.MediaItemAdapter
import com.example.myplayer.databinding.FragmentMediaitemListBinding
import com.example.myplayer.utils.InjectorUtils
import com.example.myplayer.viewmodels.MainActivityViewModel
import com.example.myplayer.viewmodels.MediaItemFragmentViewModel


/**
 * A fragment representing a list of MediaItems
 */
class MediaItemFragment : Fragment() {
    private val mainActivityViewModel by activityViewModels<MainActivityViewModel>{
        InjectorUtils.provideMainActivityViewModel(requireContext())
    }

    private val mediaItemFragmentViewModel by viewModels<MediaItemFragmentViewModel>{
        InjectorUtils.provideMediaItemFragmentViewModel(requireContext(),mediaId)
    }

    private lateinit var mediaId: String
    private lateinit var binding: FragmentMediaitemListBinding

    private val listAdapter = MediaItemAdapter{clickedItem ->
        mainActivityViewModel.mediaItemClicked(clickedItem)
    }

    companion object{
        fun newInstance(mediaId: String): MediaItemFragment {
            return MediaItemFragment().apply {
                arguments = Bundle().apply {
                    putString(MEDIA_ID_ARGS,mediaId)
                }
            }
        }
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        binding = FragmentMediaitemListBinding.inflate(inflater,container,false)
        return binding.root
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        //Always true but lets lint know
        mediaId = arguments?.getString(MEDIA_ID_ARGS) ?: return

        mediaItemFragmentViewModel.mediaItems.observe(viewLifecycleOwner,
        Observer { list->
            binding.loadingSpinner.visibility =
                    if(list?.isNotEmpty() == true) View.GONE else View.VISIBLE
            listAdapter.submitList(list)
        })
        mediaItemFragmentViewModel.networkError.observe(viewLifecycleOwner,
        Observer { error ->
            if (error){
                binding.loadingSpinner.visibility = View.GONE
                binding.networkError.visibility = View.VISIBLE
            }else{
                binding.networkError.visibility = View.GONE
            }
        })

        //set the adapter
        binding.list.adapter = listAdapter
    }
}

private const val  MEDIA_ID_ARGS = "com.exmaple.myplayer.fragments.MediaItemFragment.MEDIA_ID"