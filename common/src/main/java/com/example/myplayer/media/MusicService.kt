package com.example.myplayer.media

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.drm.DrmStore
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media.MediaBrowserServiceCompat
import androidx.media.MediaBrowserServiceCompat.BrowserRoot.EXTRA_RECENT
import com.example.myplayer.common.PlaybackStatus
import com.example.myplayer.media.extensions.album
import com.example.myplayer.media.extensions.artist
import com.example.myplayer.media.extensions.flag
import com.example.myplayer.media.extensions.title
import com.example.myplayer.media.library.*
import kotlinx.coroutines.*
import java.lang.Exception


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


    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener?=null
    private var ongoingCall: Boolean= false

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var mediaControllerCompat: MediaControllerCompat

    private var currentPlayListItems: List<MediaMetadataCompat> = emptyList()

    private lateinit var storage: PersistentStorage

    private var resumePosition: Int?=null


    /**
     * This must be `by lazy` because the source won't initially be ready
     * See [MusicService.onLoadChildren] to see where it's accessed (and first constructed)
     */

    private val browseTree: BrowseTree by lazy {
        BrowseTree(applicationContext, mediaSource)
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


    @RequiresApi(Build.VERSION_CODES.M)
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
                //Enable callbacks from MediaButtons and TransportControls
                setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                        or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
                //Set an initial PlaybackState with ACTION_PLAY, so media buttons can start the player
                stateBuilder = PlaybackStateCompat.Builder()
                        .setActions(PlaybackStateCompat.ACTION_PLAY
                                or PlaybackStateCompat.ACTION_PLAY_PAUSE)
                setPlaybackState(stateBuilder.build())

                //MySessionCallback() has methods that handle callbacks from a media controller
                setCallback(object : MediaSessionCompat.Callback() {
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

        /**
         * In order for [MediaBrowserCompat.ConnectionCallback.onConnected] to be called,
         * a [MediaSessionCompat.Token] needs to be set on the [MediaBrowserServiceCompat].
         *
         * It is possible to wait to set the session token, if required for a specific use-case.
         * However, the token *must* be set by the time [MediaBrowserServiceCompat.onGetRoot]
         * returns, or the connection will fail silently. (The system will not even call
         * [MediaBrowserCompat.ConnectionCallback.onConnectionFailed].)
         */

        packageValidator= PackageValidator(this, R.xml.allowed_media_browser_callers)
        storage = PersistentStorage.getInstance(applicationContext)

        Log.d(TAG, "goign to load audio")
        var musicCatalog=loadAudio()

        mediaSource = LocalMusicSource(musicCatalog)
        //mediaSource = JsonSource(Uri.parse("https://storage.googleapis.com/uamp/catalog.json"))
        serviceScope.launch {
            mediaSource.load()
        }


        //Manage incoming phone calls during playback
        callStateListener()
        //change in audio outputs
        //registerBecomingNoisyReceiver()
        //play new audio
        //registerPlayNewAudio()




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




    //Handle incoming calls
    private fun callStateListener(){
        //Get telephony manager
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        //Start listening for phonestate changes
        phoneStateListener = object : PhoneStateListener(){
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when(state){
                    //if at least one call exists or the phone is ringing pause the MediaPlayer
                    TelephonyManager.CALL_STATE_OFFHOOK, TelephonyManager.CALL_STATE_RINGING-> if(currentPlayer != null){
                        pauseMedia()
                        ongoingCall=true
                    }
                    //Phone idle. start playing
                    TelephonyManager.CALL_STATE_IDLE ->
                        if(currentPlayer!=null){
                            if(ongoingCall){
                                ongoingCall=false
                                resumeMedia()
                            }
                        }
                }
            }
        }
        //Register the listener with the telephony manager
        //Listen for changes to the device call state
        telephonyManager!!.listen(phoneStateListener,PhoneStateListener.LISTEN_CALL_STATE)
    }

    //Becoming noisy
   private val becomingNoiseReceiver: BroadcastReceiver = object: BroadcastReceiver(){
        override fun onReceive(context: Context, intent: Intent) {
            TODO("Not yet implemented")
        }
   }
   private fun registerBecomingNoisyReceiver(){
        //register after getting audio focus
        var intentFilter: IntentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(becomingNoiseReceiver,intentFilter)
   }

   /* private val playNewAudio= object : BroadcastReceiver(){
        //TODO: make this compatable with persistent storage and musicsource
        override fun onReceive(context: Context?, intent: Intent?) {
            //get the new media index from from SharedPReferences
            audioIndex = StorageUtil(applicationContext).loadAudioIndex()
            if(audioIndex != -1 && audioIndex < audioList!!.size){
                //index is in valid range
                activeAudio= audioList!![audioIndex]
            }else{
                stopSelf()
            }
            //A PLAY_NEW_AUDIO action received
            //reset mediaPlayer to play the new audio
            stopMedia()
            currentPlayer?.reset()
            initMediaPlayer()
            updateMetaData()
            buildNotification(PlaybackStatus.PLAYING)
        }
    }

    private fun registerPlayNewAudio(){
        //register playNewMedia receiver
        var filter = IntentFilter(BROADCAST_PLAY_NEW_AUDIO)
        registerReceiver(playNewAudio,filter)
    }*/

    @SuppressLint("NewApi")
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

    private fun removeNotification(){
        var notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun updateMetaData() {
        var albumArt = BitmapFactory.decodeResource(resources, R.drawable.default_art) //TODO: replace with medias album art
        //update current metadata
        mediaSession.setMetadata(
                MediaMetadataCompat.Builder()
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentPlayListItems[currentPlayer.currentPosition].artist)
                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentPlayListItems[currentPlayer.currentPosition].album)
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentPlayListItems[currentPlayer.currentPosition].title)
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

private const val BROADCAST_PLAY_NEW_AUDIO="com.exmaple.myplayer.common.PlayNewAudio"


