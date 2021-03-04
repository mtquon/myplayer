package com.example.myplayer.viewmodels

import android.media.browse.MediaBrowser
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.example.myplayer.MediaItemData
import com.example.myplayer.common.MusicServiceConnection
import com.example.myplayer.fragments.NowPlayingFragment
import com.example.myplayer.media.extensions.id
import com.example.myplayer.media.extensions.isPlayEnabled
import com.example.myplayer.media.extensions.isPlaying
import com.example.myplayer.media.extensions.isPrepared
import com.example.myplayer.utils.Event

/**
 * A [ViewModel] that watches a [MusicServiceConnection] to become connected and provides
 * the root/initial media ID of [MediaBrowserCompat]
 */

class MainActivityViewModel(
        private val musicServiceConnection: MusicServiceConnection
        ) : ViewModel(){


    val rootMediaId: LiveData<String> =
            Transformations.map(musicServiceConnection.isConnected) {isConnected ->
                if(isConnected){
                    musicServiceConnection.rootMediaId
                }else{
                    null
                }
            }



    /**
     * [navigationToMediaItem] acts as an "event", rather than a state. [Observer]s are notified
     * of the change as usual with [LiveData], but only one [Observer] will actually read thh data.
     */

    val navigateToMediaItem: LiveData<Event<String>> get() = _navigateToMediaItem
    private val _navigateToMediaItem = MutableLiveData<Event<String>>()

    /**
     * This [LiveData] object is used to notify the [MainActivity] that the main content fragment
     * needs to be swapped.
     */
    val navigateToFragment: LiveData<Event<FragmentNavigationRequest>> get() = _navigateToFragment
    private val _navigateToFragment = MutableLiveData<Event<FragmentNavigationRequest>>()

    /**
     * This method takes a [MediaItemData] and routes it depending on whether it's browsable
     *
     * If the item is browsable, sned an event to Activity to browse it, or play it
     */

    fun mediaItemClicked(clickedItem: MediaItemData) {
        if (clickedItem.browsable){
            browseToItem(clickedItem)
        }else{
            playMedia(clickedItem, pauseAllowed = false)
            showFragment(NowPlayingFragment.newInstance())
        }
    }

    /**
     * Convenience method used to swap the fragment show in the main activity
     *
     * @param fragment the fragment to show
     * @param backStack if true, add this transaction to the back stack
     * @param tag the name to use for this fragment in the stack
     */

    fun showFragment(fragment: Fragment, backStack: Boolean = true, tag: String? = null){
        _navigateToFragment.value = Event(FragmentNavigationRequest(fragment, backStack, tag))
    }

    /**
     * This posts a browse [Event] that will be handled by the observer in [MainActivity].
     */
    private fun browseToItem(mediaItem: MediaItemData){
        _navigateToMediaItem.value = Event(mediaItem.mediaId)
    }

    /**
     * This method takes a [MediaItemData] and does one of the following:
     * - If the item is *not* the active item, check whether "pause" is a permitted command. If it
     * is, then pause playback, otherwise send "play" to resume playback.
     */
    fun playMedia(mediaItem: MediaItemData, pauseAllowed: Boolean = true){
        val nowPlaying = musicServiceConnection.nowPlaying.value
        val transportControls = musicServiceConnection.transportControls

        val isPrepared = musicServiceConnection.playbackState.value?.isPrepared ?: false

        if (isPrepared && mediaItem.mediaId == nowPlaying?.id){
            musicServiceConnection.playbackState.value?.let { playbackState ->
                when{
                    playbackState.isPlaying ->
                        if(pauseAllowed) transportControls.pause() else Unit
                    playbackState.isPlayEnabled -> transportControls.play()
                    else -> {
                        Log.w(
                                TAG, "Playable item clicked but neither play nor pause are"+
                                "enabled! (mediaId=${mediaItem.mediaId}"
                        )
                    }
                }
            }
        }else{
            transportControls.playFromMediaId(mediaItem.mediaId,null)
        }
    }

    fun playMediaId(mediaId: String){
        val nowPlaying = musicServiceConnection.nowPlaying.value
        val transportControls = musicServiceConnection.transportControls

        val isPrepared = musicServiceConnection.playbackState.value?.isPrepared ?: false
        if (isPrepared && mediaId == nowPlaying?.id) {
            musicServiceConnection.playbackState.value?.let { playbackState ->
                when{
                    playbackState.isPlaying -> transportControls.pause()
                    playbackState.isPlayEnabled -> transportControls.play()
                    else -> {
                        Log.w(
                                TAG, "Playable item clicked but neither play nor pause are"+
                                " enabled! (mediaId=$mediaId)"
                        )
                    }
                }
            }
        } else {
            transportControls.playFromMediaId(mediaId,null)
        }
    }

    class Factory(
            private val musicServiceConnection: MusicServiceConnection
    ) : ViewModelProvider.NewInstanceFactory(){

        @Suppress("unchecked_cast")
        override fun <T : ViewModel?> create(modelClass: Class<T>): T {
            return MainActivityViewModel(musicServiceConnection) as T
        }
    }
}


/**
 * Helper class used to pass fragment navigation requests between [MainActivity] and its viewModels
 */
 data class FragmentNavigationRequest(
        val fragment: Fragment,
        val backStack: Boolean = false,
        val tag: String? = null
 )

private const val TAG = "MainActivityVM"