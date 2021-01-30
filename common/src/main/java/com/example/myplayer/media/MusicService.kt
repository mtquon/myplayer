package com.example.myplayer.media

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ContentUris
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.session.MediaSessionManager
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.service.media.MediaBrowserService
import androidx.media.MediaBrowserServiceCompat.BrowserRoot.EXTRA_RECENT
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import com.example.myplayer.common.PlaybackStatus
import com.example.myplayer.media.extensions.album
import com.example.myplayer.media.extensions.artist
import com.example.myplayer.media.extensions.flag
import com.example.myplayer.media.extensions.title
import com.example.myplayer.media.library.*
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
private const val MY_MEDIA_ROOT_ID ="media_root_id"


open class MusicService : MediaBrowserServiceCompat() {

    private lateinit var mediaSource: MusicSource
    private lateinit var packageValidator: PackageValidator
    private lateinit var stateBuilder: PlaybackStateCompat.Builder



    /**
     * The current player will on be a MediaPlayer for local playback
     */

    private lateinit var currentPlayer: MediaPlayer
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaSessionManager: MediaSessionManager
    private var currentPlayListItems: List<MediaMetadataCompat> = emptyList()

    private lateinit var storage: PersistentStorage
    private var resumePosition: Int?=null

    /**
     * This must be `by lazy` because the source won't initially be ready
     * See [MusicService.onLoadChildren] to see where it's accessed (and first constructed)
     */

    private val browseTree: BrowseTree by lazy {
        BrowseTree(applicationContext,mediaSource)
    }

    companion object{
        public val ACTION_PLAY="com.example.myplayer.common.ACTION_PLAY"
        public val ACTION_PAUSE= "com.example.myplayer.common.ACTION_PAUSE"
        public val ACTION_PREVIOUS= "com.example.myplayer.common.ACTION_PREVIOUS"
        public val ACTION_NEXT= "com.example.myplayer.ACTION_NEXT"
        public val ACTION_STOP = "com.exmaple.myplayer.ACTION_STOP"

        //AudioPlayer notification ID
        private const val NOTIFICATION_ID = 101
    }


    /**
     * TODO: create a CastPlayer to handle ocmmunication with a cast session
     *
     *
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
        mediaSession = MediaSessionCompat(this,TAG)
            .apply {
                //Enable callbacks from MediaButtons and TransportControls
                setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                        or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
                //Set an initial PlaybackState with ACTION_PLAY, so media buttons can start the player
                stateBuilder = PlaybackStateCompat.Builder()
                        .setActions(PlaybackStateCompat.ACTION_PLAY
                                or PlaybackStateCompat.ACTION_PLAY_PAUSE)
                setPlaybackState(stateBuilder.build())

                //MySessionCallback() has methods that handle callbacks from a media controller
                setCallback(object : MediaSessionCompat.Callback(){
                    override fun onPlay() {
                        super.onPlay()
                        resumeMedia()
                        buildNotification(PlaybackStatus.PLAYING)
                    }

                    override fun onPause() {
                        super.onPause()
                        pauseMedia()
                        buildNotification(PlaybackStatus.PAUSED)
                    }

                    override fun onSkipToNext() {
                        super.onSkipToNext()
                        onSkipToNext()
                        updateMetaData()
                        buildNotification(PlaybackStatus.PLAYING)
                    }

                    override fun onSkipToPrevious() {
                        super.onSkipToPrevious()
                        onSkipToPrevious()
                        updateMetaData()
                        buildNotification(PlaybackStatus.PLAYING)
                    }

                    override fun onStop() {
                        super.onStop()
                        removeNotification()
                        stopSelf()
                    }

                    override fun onSeekTo(pos: Long) {
                        super.onSeekTo(pos)
                    }

                })
                setSessionToken(sessionToken)
            }

        //TODO:add mediastore methods, store local songs, make tree of media here.
        //TODO: make datasource the local files from mediastore
    }


    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        /**
         * Control leveling of access for the specified package name.
         *
         * By default, all known clients are permitted to search, but only tell unkown callers
         * about search if permitted by the [BrowseTree]
         */

        //TODO: decide if needed
        val isKnownCaller = packageValidator.isKnownCaller(clientPackageName,clientUid)
        val rootExtras = Bundle().apply{
            putBoolean(
                    MEDIA_SEARCH_SUPPORTED,
                    isKnownCaller || browseTree.searchableByUnkownCaller
            )
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
        }

        return if(isKnownCaller){
            /**
             * By default return browsable root. Treat the EXTRA_RECENT flag as a special case and
             * return the recent root instead
             */
            val isRecentRequest = rootHints?.getBoolean(EXTRA_RECENT) ?: false
            val browserRootPath= if(isRecentRequest) RECENT_ROOT else BROWSABLE_ROOT
            BrowserRoot(browserRootPath,rootExtras)
        }else{
            /**
             * Caller is unknown. There are two ways to handle this:
             * 1) Return a root without any content, which still allows the connecting client to
             * issue commands.
             * 2) Return `null`, which will cause the system to disconnect the app.
             *
             *
             * We go with 1)
             */
            BrowserRoot(EMPTY_ROOT,rootExtras)
        }
    }

    /**
     * Returns (via the [result] parameter) a list of [MediaItem]s that are child items of
     * the provided [parentMediaID]. See [BrowseTree] for more details on how this is build/more
     * details about the relationships
     */
    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaItem>>
    ) {
        //If the caller requests the recent root, return the most recently played song.
        if(parentId == RECENT_ROOT){
            result.sendResult(storage.loadRecentSong()?.let { song -> listOf(song) })
        }else{
            //If the media source is ready, the results will be set synchronously here.
            val resultSent=mediaSource.whenReady { successfullyInitialized ->
                if(successfullyInitialized){
                    val children= browseTree[parentId]?.map { item ->
                        MediaItem(item.description,item.flag)
                    }
                    result.sendResult(children)
                }else{
                    mediaSession.sendSessionEvent(NETWORK_FAILURE,null)
                    result.sendResult(null)
                }
            }

            /**
             * If the results are not ready, the service must "detach" the results before the
             * method returns. After the source is ready, the lambda above will run, and the
             * caller will be notified that the results are ready.
             *
             * See [MediaItemFragmentViewModel.subscriptionCallback] for how this is passed to the
             * UI/displayed in the [RecyclerView]
             */

            if(!resultSent){
                result.detach()
            }
        }
    }



    private fun removeNotification(){
        var notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun updateMetaData() {
        var albumArt = BitmapFactory.decodeResource(resources,R.drawable.default_art) //TODO: replace with medias album art
        //update current metadata
        mediaSession.setMetadata(
                MediaMetadataCompat.Builder()
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART,albumArt)
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,currentPlayListItems[currentPlayer.currentPosition].artist)
                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentPlayListItems[currentPlayer.currentPosition].album)
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE,currentPlayListItems[currentPlayer.currentPosition].title)
                        .build()
        //FIXME: make current audio object or fix above to ensure we get the current audio that is playing
        )
    }

    private fun pauseMedia(){
        if(currentPlayer == null) return
        if(currentPlayer.isPlaying){
            currentPlayer.stop()
        }
    }

    private fun resumeMedia(){
        if(!currentPlayer.isPlaying){
            currentPlayer.seekTo(resumePosition!!)
            currentPlayer.start()
        }
    }
    fun buildNotification(any: Any) {
        //TODO
    }
}


val MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS = "playback_start_position_ms"
const val NETWORK_FAILURE = "com.example.myplayer.media.session.NETWORK_FAILURE"
private const val TAG="MusicService"

/** Content styling constants*/
private const val CONTENT_STYLE_BROWSABLE_HINT= "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
private const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT "
private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED "
private const val CONTENT_STYLE_LIST = 1
private const val CONTENT_STYLE_GRID = 2
