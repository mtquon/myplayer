package com.example.myplayer.viewmodels


import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.SubscriptionCallback
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.lifecycle.*
import com.example.myplayer.MediaItemData
import com.example.myplayer.common.EMPTY_PLAYBACK_STATE
import com.example.myplayer.common.MusicServiceConnection
import com.example.myplayer.common.NOTHING_PLAYING
import com.example.myplayer.media.extensions.id
import com.example.myplayer.media.extensions.isPlaying
import com.example.myplayer.R
import com.example.myplayer.fragments.MediaItemFragment

/**
 * [ViewModel] fpr [MediaItemFragment]
 */
class MediaItemFragmentViewModel(
        private val mediaId: String,
        musicServiceConnection: MusicServiceConnection
) : ViewModel() {
    /**
     * Use a backing property so consumers of mediaItems only get a [LiveData] instance so
     * they don't modify it.
     */

    private val _mediaItems = MutableLiveData<List<MediaItemData>>()
    val mediaItems: LiveData<List<MediaItemData>> = _mediaItems

    /**
     * Pass the status of the [MusicServiceConnection.networkFailure] through.
     */
    val networkError = Transformations.map(musicServiceConnection.networkFailure){ it }

    private val subscriptionCallback = object : SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: List<MediaItem>) {
            val itemList = children.map { child ->
                val subtitle = child.description.subtitle ?: ""
                MediaItemData(
                        child.mediaId!!,
                        child.description.title.toString(),
                        subtitle.toString(),
                        child.description.iconUri!!,
                        child.isBrowsable,
                        getResourceForMediaId(child.mediaId!!)
                )
            }
            _mediaItems.postValue(itemList)
        }
    }

    /**
     * When the session's [PlaybackStateCompat] changes, the [mediaItems] need to be updated
     * so the correct [MediaItemData.playbackRes] is displayed on the active tiem.
     * i.e. play/pause button or blank
     */

    private val playbackStateObserver = Observer<PlaybackStateCompat> {
        val playbackState = it ?: EMPTY_PLAYBACK_STATE
        val metadata = musicServiceConnection.nowPlaying.value ?: NOTHING_PLAYING
        if(metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID) != null) {
            _mediaItems.postValue(updateState(playbackState,metadata))
        }
    }
    /**
     * When the session's [MediaMetadataCompat] changes, the [mediaItems] need to be updated as it
     * means the current active item has changed. As a result, the new, and potentially old item
     * (if there was one) both need to have their [MediaItemData.playbackRes] changes
     * i.e. play/pause button for blank
     */

    private val mediaMetadataObserver = Observer<MediaMetadataCompat> {
        val playbackState = musicServiceConnection.playbackState.value ?: EMPTY_PLAYBACK_STATE
        val metadata = it ?: NOTHING_PLAYING
        if (metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID) != null) {
            _mediaItems.postValue(updateState(playbackState,metadata))
        }
    }

    /**
     * Because there's a complex dance between this [ViewModel] and the [MusicServiceConnection]
     * (which is wrapping a [MediaBrowserCompat] object), the usual guidance of using
     * [Transformations] doesn't quite work
     *
     * Specifically there's three things that are watched that will cause the single piece of
     * [LiveData] exposed from this class to be updated.
     *
     * [subscriptionCallback] (defined above) is called if/when the children of this [ViewModel]'s
     * [mediaId] changes.
     *
     * [MusicServiceConnection.playbackState] changes state based on the playback state of the
     * player, which can change the [MediaItemData.playbackRes]s in the list.
     *
     * [MusicServiceConnection.nowPlaying] changes based on the item that's being played,
     * which can also change the [MediaItemData.playbackRes]s in the list.
     */

    private val musicServiceConnection = musicServiceConnection.also {
        it.subscribe(mediaId, subscriptionCallback)
        it.playbackState.observeForever(playbackStateObserver)
        it.nowPlaying.observeForever(mediaMetadataObserver)
    }

    override fun onCleared() {
        super.onCleared()

        //Remove the permanent observers from the MusicServiceConnection
        musicServiceConnection.playbackState.removeObserver(playbackStateObserver)
        musicServiceConnection.nowPlaying.removeObserver(mediaMetadataObserver)

        //And then, finally, unsubscribe the media ID that was being watched
        musicServiceConnection.unsubscribe(mediaId, subscriptionCallback)

    }

    private fun getResourceForMediaId(mediaId: String): Int {
        val isActive = mediaId == musicServiceConnection.nowPlaying.value?.id
        val isPlaying = musicServiceConnection.playbackState.value?.isPlaying ?: false
        return when {
            !isActive -> NO_RES
            isPlaying -> R.drawable.ic_pause_black_24dp
            else -> R.drawable.ic_play_arrow_black_24dp
        }
    }

    private fun updateState(
            playbackState: PlaybackStateCompat,
            mediaMetadata: MediaMetadataCompat
    ): List<MediaItemData> {
        val newResId = when (playbackState.isPlaying){
            true -> R.drawable.ic_pause_black_24dp
            else -> R.drawable.ic_play_arrow_black_24dp
        }

        return mediaItems.value?.map {
            val useResId = if(it.mediaId == mediaMetadata.id) newResId else NO_RES
            it.copy(playbackRes = useResId)
        } ?: emptyList()
    }

    class Factory(
            private val mediaId: String,
            private val musicServiceConnection: MusicServiceConnection
    ) : ViewModelProvider.NewInstanceFactory() {
        @Suppress("unchecked_cast")
        override fun <T : ViewModel?> create(modelClass: Class<T>): T{
            return MediaItemFragmentViewModel(mediaId,musicServiceConnection) as T
        }
    }
}


private const val TAG = "MediaItemFragmentVM"
private const val NO_RES=0