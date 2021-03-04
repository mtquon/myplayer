package com.example.myplayer.media

import android.app.Notification
import android.app.PendingIntent
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.MediaBrowserServiceCompat.BrowserRoot.EXTRA_RECENT
import com.example.myplayer.media.extensions.*
import com.example.myplayer.media.library.*
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import kotlinx.coroutines.*


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

    private lateinit var mediaSource: MusicSource
    private lateinit var packageValidator: PackageValidator
    private lateinit var stateBuilder: PlaybackStateCompat.Builder

    /**
     * The current player will on be  ExoPlayer for localplayback or a CastPlayer for remote playback
     */
    private lateinit var currentPlayer: Player

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)


    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaSessionConnector: MediaSessionConnector
    private var currentPlayListItems: List<MediaMetadataCompat> = emptyList()

    private lateinit var storage: PersistentStorage
    private var isForegroundService=false
    private val playerListener = PlayerEventListener()

    /**
     * This must be `by lazy` because the source won't initially be ready
     * See [MusicService.onLoadChildren] to see where it's accessed (and first constructed)
     */

    private val browseTree: BrowseTree by lazy {
        BrowseTree(applicationContext, mediaSource)
    }

    private val dataSourceFactory: DefaultDataSourceFactory by lazy {
        DefaultDataSourceFactory(
                this,
                Util.getUserAgent(this, USER_AGENT),
                null
        )
    }

    private val musicPlayerAudioAttributes = AudioAttributes.Builder()
            .setContentType(C.CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()


    /**
     * Create an Exoplayer to handle audio focus
     *
     */
    private val exoPlayer: ExoPlayer by lazy {
        SimpleExoPlayer.Builder(this).build().apply {
            setAudioAttributes(musicPlayerAudioAttributes, true)
            setHandleAudioBecomingNoisy(true)
            addListener(playerListener)
        }
    }

    //TODO: implement cast player to handle communication with a cast session


    @ExperimentalCoroutinesApi
    override fun onCreate(){
        super.onCreate()
        Log.d(TAG, "onCreate")

        //Build a PendingIntent that can be used to launch the UI
        val sessionActivityPendingIntent=
            packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
                PendingIntent.getActivity(this, 0, sessionIntent, 0)
            }

        //Create a new MediaSession
        mediaSession = MediaSessionCompat(this, TAG)
            .apply {
              setSessionActivity(sessionActivityPendingIntent)
                isActive=true
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
        sessionToken = mediaSession.sessionToken

        var musicCatalog=loadAudio()

        mediaSource = LocalMusicSource(musicCatalog)
        //mediaSource = JsonSource(Uri.parse("https://storage.googleapis.com/uamp/catalog.json"))
        serviceScope.launch {
            mediaSource.load()
        }

        //ExoPlayer manages the MediaSession
        mediaSessionConnector = MediaSessionConnector(mediaSession)
        mediaSessionConnector.setPlaybackPreparer(MusicPlayerPlaybackPreparer())
        mediaSessionConnector.setQueueNavigator(MusicPlayerQueueNavigator(mediaSession))


        switchToPlayer(
                previousPlayer = null,
                        //TODO: add support for cast player
                newPlayer = exoPlayer
        )


        //TODO: add notification manager
        packageValidator= PackageValidator(this, R.xml.allowed_media_browser_callers)
        storage = PersistentStorage.getInstance(applicationContext)
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

        //val isKnownCaller = packageValidator.isKnownCaller(clientPackageName,clientUid)
        val isKnownCaller= true
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
            BrowserRoot(browserRootPath, rootExtras)
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
            BrowserRoot(EMPTY_ROOT, rootExtras)
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
                        MediaItem(item.description, item.flag)
                    }
                    result.sendResult(children)
                }else{
                    mediaSession.sendSessionEvent(NETWORK_FAILURE, null)
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


    private  fun loadAudio(): String {
        //Container for info about each audio file
        Log.d(TAG, "loadAudio started")
        var jsonMusicCatalog = "{\"music\": ["

        val collection =
                if(Build.VERSION.SDK_INT >=Build.VERSION_CODES.Q){
                    MediaStore.Audio.Media.getContentUri(
                            MediaStore.VOLUME_EXTERNAL
                    )
                }else{
                   MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }

        //show only music files
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        //Display music files in alphabetical order based on their title
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val query = contentResolver.query(
                collection,
                null,
                selection,
                null,
                sortOrder
        )

        query?.use{ cursor ->
            //Cache column indices
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationColumn= cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)


            while(cursor.moveToNext()){
                //Get values of column for music
                val displayName= cursor.getString(displayNameColumn)
                val title = cursor.getString(titleColumn)
                val id = cursor.getLong(idColumn)
                val album= cursor.getString(albumColumn)
                val artist = cursor.getString(artistColumn)
                val duration =cursor.getLong(durationColumn)

                val contentUri: Uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                )


                /**
                 * TODO: CODE FOR EMBEDDED THUMBNAILS FROM MP3 FILES
                var mmr = MediaMetadataRetriever()
                var rawArt: ByteArray?
                var art:Bitmap?
                var bfo = BitmapFactory.Options()
                mmr.setDataSource(applicationContext, contentUri)
                rawArt = mmr.embeddedPicture
                art= if(rawArt != null){
                   BitmapFactory.decodeByteArray(rawArt,0,rawArt.size,bfo)
                }else{
                    BitmapFactory.decodeResource(applicationContext.resources,R.drawable.ic_album)
                }

                **/
                /*
                Log.d("query", "DATA: ${displayName}")
                Log.d("query", "TITLE: ${title}")
                Log.d("query", "ALBUM: ${album}")
                Log.d("query", "ARTIST: ${artist}")
                Log.d("query", "ID: ${id}")
                Log.d("query", "contentURI: ${contentUri}")
                Log.d("query", "DURATION: ${duration}")
                */


                //Store column values and teh contentUri in a local object that represents the medial file
                //TODO: either change jsonsource or make json formatted data from mediastore
                jsonMusicCatalog+="""{"id": "$id","title": "$title","album": "$album",
                    |"artist": "$artist","source": "$contentUri",
                    |"data": "$displayName", "duration": "$duration"},""".trimMargin()

            }
        }

        jsonMusicCatalog=jsonMusicCatalog.dropLast(1)
        jsonMusicCatalog+= "]}"

        Log.d(TAG, "create local music source with json musiccatalog")
        Log.d("query catalog", jsonMusicCatalog)

        return jsonMusicCatalog
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveRecentSongToStorage()
        super.onTaskRemoved(rootIntent)

        /**
         * By stopping the playback, the player will transition to the [STATE_IDLE] triggering
         * [Player.EventListener.onPlayerStateChanged] to be called. This will cause the notification
         * to be hidden and trigger
         * [PlayerNotificationManager.NotificationListener.onNotificationCancelled] tp be ca;;ed
         * The service will then remove itself as a foreground service and will call [stopSelf]
         */
        currentPlayer.stop(true)
    }

    override fun onDestroy() {
        mediaSession.run {
            isActive = false
            release()
        }

        //Cancel coroutines since service is destroyed
        serviceJob.cancel()

        //Free ExoPlayer resources
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<List<MediaItem>>) {
        val resultSent = mediaSource.whenReady { successfullyInitialized ->
            if(successfullyInitialized){
                val resultList = mediaSource.search(query,extras ?: Bundle.EMPTY)
                        .map { mediaMetadata ->
                            MediaItem(mediaMetadata.description,mediaMetadata.flag)
                        }
                result.sendResult(resultList)
            }
        }
        if(!resultSent){
            result.detach()
        }
    }

    /**
     * Put list of songs and the song to play into the current player
     */

    private fun preparePlaylist(
            metadataList: List<MediaMetadataCompat>,
            itemToPlay: MediaMetadataCompat?,
            playWhenReady: Boolean,
            playbackStartPositionMs: Long
    ){
        /**
         * Since the playlist is based on some order (i.e. tracks on an album, artists), find which
         * window index to play first so that the song the user want to hear is played first
         */

        Log.d(TAG, "preparing playlist")
        val initialWindowIndex= if(itemToPlay==null) 0 else metadataList.indexOf(itemToPlay)
        currentPlayListItems = metadataList
        currentPlayer.playWhenReady = playWhenReady
        currentPlayer.stop(true)
        if(currentPlayer== exoPlayer){
            val mediaSource = metadataList.toMediaSource(dataSourceFactory)
            exoPlayer.prepare(mediaSource)
            exoPlayer.seekTo(initialWindowIndex,playbackStartPositionMs)
        }else/* currentPlayer == castPlayer*/{
            //TODO: load playlist into castPlayer
        }
    }

    private fun switchToPlayer(previousPlayer: Player?, newPlayer: Player){
        if (previousPlayer==newPlayer){
            return
        }
        currentPlayer=newPlayer
        if(previousPlayer != null){
            val playbackState = previousPlayer.playbackState
            if(currentPlayListItems.isEmpty()){
                //We are joining a playback session. Loading the session from the new player is
                //not supported, so we stop playback
                currentPlayer.stop(true)
            }else if (playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED){
                preparePlaylist(
                        metadataList = currentPlayListItems,
                        itemToPlay = currentPlayListItems[previousPlayer.currentWindowIndex],
                        playWhenReady = previousPlayer.playWhenReady,
                        playbackStartPositionMs = previousPlayer.currentPosition
                )
            }
        }
        mediaSessionConnector.setPlayer(newPlayer)
        previousPlayer?.stop(true)
    }
    /**
     *Get the current song details before saving them on a separate thread, otherwise the current
     * player may have been unloaded by the time the save routine runs.
     */
    private fun saveRecentSongToStorage(){
        val description = currentPlayListItems[currentPlayer.currentWindowIndex].description
        val position = currentPlayer.currentPosition

        serviceScope.launch {
            storage.saveRecentSong(description,position)
        }
    }

    private inner class MusicPlayerQueueNavigator(mediaSession: MediaSessionCompat) : TimelineQueueNavigator(mediaSession){

        override fun getMediaDescription(player: Player, windowIndex: Int): MediaDescriptionCompat=
            currentPlayListItems[windowIndex].description

    }

    /**
     * Support prepare/playing from search, as well as media ID.
     * Actions are declared in class below
     */
    private inner class MusicPlayerPlaybackPreparer : MediaSessionConnector.PlaybackPreparer{
        override fun onCommand(player: Player,
                               controlDispatcher: ControlDispatcher,
                               command: String,
                               extras: Bundle?,
                               cb: ResultReceiver?): Boolean = false

        override fun getSupportedPrepareActions(): Long =
                PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
                        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                        PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH or
                        PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH

        override fun onPrepare(playWhenReady: Boolean) {
            val recentSong = storage.loadRecentSong() ?: return
            onPrepareFromMediaId(recentSong.mediaId!!,playWhenReady,recentSong.description.extras)
        }

        override fun onPrepareFromMediaId(mediaId: String, playWhenReady: Boolean, extras: Bundle?) {
            mediaSource.whenReady {
                val itemToPlay: MediaMetadataCompat? = mediaSource.find { item ->
                    item.id==mediaId
                }
                if (itemToPlay == null) {
                    Log.w(TAG, "Content not found: MediaID=$mediaId")
                    //TODO: Notify caller of the error
                }else{
                    val playbackStartPositionMs =
                            extras?.getLong(MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS,
                            C.TIME_UNSET)?: C.TIME_UNSET

                    preparePlaylist(buildPlaylist(itemToPlay),itemToPlay,playWhenReady,playbackStartPositionMs)
                }
            }
        }

        /**
         * Builds a playlist from [MediaMetadataCompat]
         *
         * TODO: Support building playlist by artist, genre, etc
         *
         * @param item Item to base the playlist on
         * @return a [List] of [MediaMetadataCompat] objects representing a playlist
         */
        private fun buildPlaylist(item: MediaMetadataCompat): List<MediaMetadataCompat> =
                mediaSource.filter { it.album == item.album }.sortedBy { it.trackNumber }


        /**
         * This method is used by Google Assistant to respond to requests like:
         * -Play Look at sky from Nurture on MyPlayer
         * -Play edm on MyPlayer
         * -Play music on MyPlayer
         */
        override fun onPrepareFromSearch(query: String, playWhenReady: Boolean, extras: Bundle?) {
            mediaSource.whenReady {
                val metadataList = mediaSource.search(query,extras ?: Bundle.EMPTY)
                if(metadataList.isNotEmpty()){
                    preparePlaylist(metadataList,metadataList[0],playWhenReady,playbackStartPositionMs = C.TIME_UNSET)
                }
            }
        }

        override fun onPrepareFromUri(uri: Uri, playWhenReady: Boolean, extras: Bundle?)  = Unit

    }

    /**
     * Listener for notification events
     */
    private inner class PlayNotificationListener : PlayerNotificationManager.NotificationListener{
        override fun onNotificationPosted(notificationId: Int, notification: Notification, ongoing: Boolean) {
            if (ongoing && !isForegroundService){
                ContextCompat.startForegroundService(applicationContext,
                        Intent(applicationContext,this@MusicService.javaClass)
                )

                startForeground(notificationId, notification)
                isForegroundService= true
            }
        }

        override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
            stopForeground(true)
            isForegroundService = false
            stopSelf()
        }
    }

    /**
     * Listener for events from ExoPlayer
     */

    private inner class PlayerEventListener : Player.EventListener {
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            when(playbackState){
                Player.STATE_BUFFERING,
                    Player.STATE_READY->{
                        //TODO: add in notificaiton maanger for player
                        if(playbackState == Player.STATE_READY){
                            /**
                             * When playing or paused save the current media item in
                             * [PersistentStorage] so that playback can be resumed betweeen device
                             * reboots.
                             */
                            saveRecentSongToStorage()

                            if(!playWhenReady){
                                /**
                                 * If playback is paused we remove the foreground state which allows
                                 * the notification to be dismissed.
                                 * TODO: Maybe make a "close" button in the notification which stops
                                 * playback and clears the notification
                                 */
                                stopForeground(false)
                            }
                        }
                    }
                else -> {
                    //TODO: hide notification
                }
            }
        }


        override fun onPlayerError(error: ExoPlaybackException) {
            var message = R.string.generic_error
            when (error.type) {
                /*If the data from the MediaSource object could not be loaded the Exoplayer raises
                * a type_source error.
                * An error message is printed to the UI via a Toast
                */
                ExoPlaybackException.TYPE_SOURCE -> {
                    message= R.string.media_not_found_error
                    Log.e(TAG, "TYPE_SOURCE: " + error.sourceException.message)
                }
                //If the error occurs in a render component, Exoplayer will raise a type_remote error
                ExoPlaybackException.TYPE_RENDERER ->{
                    Log.e(TAG,"TYPE_RENDERER: " + error.rendererException.message)
                }
                //Unexpected RuntimeException ExoPlayer raises a type_unexpected error
                ExoPlaybackException.TYPE_UNEXPECTED -> {
                    Log.e(TAG, "TYPE_UNEXPECTED: "+ error.unexpectedException.message)
                }
                ExoPlaybackException.TYPE_OUT_OF_MEMORY -> {
                    Log.e(TAG, "TYPE_OUT_OF_MEMORY: " + error.outOfMemoryError.message)
                }
                ExoPlaybackException.TYPE_REMOTE -> {
                    Log.e(TAG, "TYPE_REMOTE: " + error.message)
                }
            }
            Toast.makeText(applicationContext,message,Toast.LENGTH_LONG).show()

        }
    }

    //TODO: implement cast listener

}


/**
 * Media Sesssion events
 */
const val NETWORK_FAILURE = "com.example.myplayer.media.session.NETWORK_FAILURE"

/** Content styling constants*/
private const val CONTENT_STYLE_BROWSABLE_HINT= "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
private const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT "
private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED "
private const val CONTENT_STYLE_LIST = 1
private const val CONTENT_STYLE_GRID = 2

val MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS = "playback_start_position_ms"

private const val USER_AGENT="myplayer.next"

private const val BROADCAST_PLAY_NEW_AUDIO="com.exmaple.myplayer.common.PlayNewAudio"

private const val TAG="MusicService"

