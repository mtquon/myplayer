package com.example.myplayer.common

import android.content.Context
import android.content.ComponentName
import android.drm.DrmStore
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.IMediaControllerCallback
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.lifecycle.MutableLiveData
import com.example.myplayer.media.MusicService
import com.example.myplayer.media.NETWORK_FAILURE
import com.example.myplayer.media.extensions.id
import kotlin.coroutines.coroutineContext


/**
 *class that manages a connection to a [MediaBrowserServiceCompat] instance, typically a
 * [MusicSerivce] or one of its subclasses.
 *
 * Tpically it's best to construct/inject dependencies either using DI or, using [Injectorutils]
 * in the app module. There are a few difficulties for that here:
 * - [MediaBrowsercompat] is a final class, so mocking it directly is difficult.
 * - A [MediaBrowserConnectionCallback] is a parameter into the construction of a
 *   a [MediaControllerCompat] that will be used to control the [MediaSessionCompat].
 *
 *   Because of these reasons, rather than constructing additional classes, this is treated as a
 *   black box(which is why there's very little logic here).
 *
 *   This is also why the parameters to construct a [MusicServiceConnection] are simple
 *   parameters, rather than private properties. They're only requied to build the
 *   [MediaBrowserConnectionCallback] and [MediaBrowserCompat]
 */

class MusicServiceConnection(context: Context, serviceComponent: ComponentName){
    val isConnected= MutableLiveData<Boolean>().apply { postValue(false) }
    val networkFailure=MutableLiveData<Boolean>().apply { postValue(false) }

    val rootMediaId: String get()= mediaBrowser.root

    val playbackState = MutableLiveData<PlaybackStateCompat>().apply { postValue(EMPTY_PLAYBACK_STATE) }

    val nowPlaying = MutableLiveData<MediaMetadataCompat>().apply { postValue(NOTHING_PLAYING) }

    val transportControls: MediaControllerCompat.TransportControls
        get() = mediaController.transportControls

    private val mediaBrowserConnectionCallback = MediaBrowserConnectionCallback(context)
    private val mediaBrowser = MediaBrowserCompat(
        context,serviceComponent,
        mediaBrowserConnectionCallback,null)
        .apply { connect() }
    private lateinit var mediaController: MediaControllerCompat

    fun subscribe(parentId: String, callback: MediaBrowserCompat.SubscriptionCallback){
        mediaBrowser.subscribe(parentId,callback)
    }

    fun unsubscribe(parentId: String,callback: MediaBrowserCompat.SubscriptionCallback){
        mediaBrowser.unsubscribe(parentId,callback)
    }

    fun sendCommand(command: String, parameters: Bundle?) =
        sendCommand(command,parameters) {_, _ -> }
    fun sendCommand(
        command: String,
        parameters: Bundle?,
        resultCallback: ((Int, Bundle?) -> Unit)
    ) = if (mediaBrowser.isConnected){
        mediaController.sendCommand(command,parameters,object : ResultReceiver(Handler()) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                resultCallback(resultCode, resultData)
            }
        })
        true
    }else{
        false
    }


    private inner class MediaBrowserConnectionCallback(private val context: Context) :
        MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            //get a MediaController for the MediaSession.
            mediaController= MediaControllerCompat(context,mediaBrowser.sessionToken).apply {
                registerCallback(MediaControllerCallback())
            }

            isConnected.postValue(true)
        }

        override fun onConnectionSuspended() {
            isConnected.postValue(false)
        }

        override fun onConnectionFailed() {
            isConnected.postValue(false )
        }
    }

    private inner class MediaControllerCallback: MediaControllerCompat.Callback(){

        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            playbackState.postValue(state ?: EMPTY_PLAYBACK_STATE)
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            /**
             * When ExoPlayer stops we will receive a callback with "empty" metadata.
             * This is a metadata object which has been instantiated with default values.
             * The default value for media ID is null so we assume that if this value is null
             * we are not playing anything
             *
             * TODO: see if this is needed for MediaPlayer
             */

            nowPlaying.postValue(
                if (metadata?.id ==null){
                    NOTHING_PLAYING
                }else{
                    metadata
                }
            )
        }

        override fun onQueueChanged(queue: MutableList<MediaSessionCompat.QueueItem>?) {
        }

        override fun onSessionEvent(event: String?, extras: Bundle?) {
            super.onSessionEvent(event, extras)
            when(event){
                NETWORK_FAILURE -> networkFailure.postValue(true )
            }
        }

        /**
         * Normally if a [MediaBrowserServiceCompat] drops its connection the callback comes via
         * [MediaControllerCompat.Callback] (here). But since other connection status events are
         * sent to [MediaBrowserCompat.ConnectionCallback], we catch the disconnect here and
         * send it on to the other callback.
         */

        override fun onSessionDestroyed() {
            mediaBrowserConnectionCallback.onConnectionSuspended()
        }
    }


    companion object{
        //For singleton instantiation.
        @Volatile
        private var instance: MusicServiceConnection ?= null

        fun getInstance(context: Context, serviceComponent: ComponentName)=
            instance ?: synchronized(this){
                instance ?: MusicServiceConnection(context,serviceComponent)
                    .also { instance = it }
            }
    }
}


@Suppress("PropertyName")
val EMPTY_PLAYBACK_STATE: PlaybackStateCompat = PlaybackStateCompat.Builder()
    .setState(PlaybackStateCompat.STATE_NONE,0,0f)
    .build()

@Suppress("PropertyName")
val NOTHING_PLAYING: MediaMetadataCompat = MediaMetadataCompat.Builder()
    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID,"")
    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION,0)
    .build()