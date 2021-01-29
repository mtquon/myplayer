package com.example.myplayer.media

import android.app.PendingIntent
import android.content.ContentUris
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import com.example.myplayer.media.library.BrowseTree
import com.example.myplayer.media.library.MusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch



/**
 * This class is the entry point for browsing and playback commands from the APP's UI and
 * other apps that wish to play music via MyPlayer (i.e. Android Auto or Google Assistant)
 *
 * Browsing begins with [MusicService.onGetRoot] and continues in the callback
 * [MusicService.onLoadChildren].
 *
 * TODO: add support for cast sessions
 */

open class MusicService : MediaBrowserServiceCompat() {

    private lateinit var notificaitonManager: NotificaitonManager
    private lateinit var mediaSource: MusicSource
    private lateinit var packageValidator: PackageValidator

    /**
     * The current player will on be a MediaPlayer for local playback
     */

    private lateinit var currentPlayer: MediaPlayer
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    protected  lateinit var mediaSession: MediaSessionCompat
    private var currentPlayListItems: List<MediaMetadataCompat> = emptyList()

    private lateinit var storage: PersistentStorage

    /**
     * This must be `by lazy` because the source won't initially be ready
     * See [MusicService.onLoadChildren] to see where it's accessed (and first constructed)
     */

    private val browseTree: BrowseTree by lazy {
        BrowseTree(applicationContext,mediaSource)
    }

    private val isForegroundService= false

    /**
     * TODO: create a CastPlayer to handle ocmmunication with a cast session
     */


    @ExperimentalCoroutinesApi
    override fun onCreate(){
        super.onCreate()

        //Build a PendingIntent that can be used to launch the UI
        val sessionActivityPendingIntent=
            packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
                PendingIntent.getActivity(this,0,sessionIntent,0)
            }

        //Create a new MediaSession
        mediaSession = MediaSessionCompat(this,"MusicService")
            .apply {
                setSessionActivity(sessionActivityPendingIntent)
                isActive = true
            }

        /**
         * In order for [MediaBrowserCompat.ConnectionCallback.onConnected] to be called,
         * a [MediaSessionCompat.Token] needs to be set on the [MediaBrowserServiceCompat].
         *
         * It is possible to wait to set the session token, if required for a specific use-case.
         * However, the token *must* be set by the time [MediaBrowserServiceCompat.onGetRoot]
         * returns, or the connection will fail silently. (The system will not even call
         * [MediaBrowserCompat.ConnectionCallback.onConnectionFailed].)
         */
        sessionToken=mediaSession.sessionToken

        /**
         * The notification manager will use our player and media session to decide when to post
         * notifications. When notifications are posted or removed our listener will be called,
         * this allows us to promote the service to foreground (required so that we're not killed if
         * the main UI is not visible).
         */


        //Perform one-time setup procedures

        //Manage incoming phone calls during playback
        //Pause MediaPLayer on incoming call
        //Resume on hangup
       // callStateListener()
        //ACTION_AUDIO_BECOMING NOISY -- chang in audio outputs -- BroadcastReceiver
      //  registerBecomingNoisyReceiver()
        //List for new Audio to play -- BroadcastReceiver
       // registerPlayNewAudio()

    }






    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        TODO("Not yet implemented")
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        TODO("Not yet implemented")
    }
}


val MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS = "playback_start_position_ms"
const val NETWORK_FAILURE = "com.example.myplayer.media.session.NETWORK_FAILURE"
