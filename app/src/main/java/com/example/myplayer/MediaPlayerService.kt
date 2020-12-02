package com.example.myplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.IOException


class MediaPlayerService: Service(), MediaPlayer.OnCompletionListener, MediaPlayer.OnPreparedListener,
        MediaPlayer.OnErrorListener, MediaPlayer.OnSeekCompleteListener, MediaPlayer.OnInfoListener,
        MediaPlayer.OnBufferingUpdateListener, AudioManager.OnAudioFocusChangeListener {

    private val iBinder: IBinder = LocalBinder()
    private var mediaPlayer: MediaPlayer ?= null
    private var mediaFile: String?= null
    private var resumePostion: Int?=null
    private var audioManager: AudioManager?=null
    private var focusRequest: AudioFocusRequest?=null

    //Handle incoming phone calls
    private var ongoingCall: Boolean= false
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyManager: TelephonyManager? = null

    //List of available Audio files
    private lateinit var audioList: ArrayList<Audio>
    private var audioIndex = -1
    private var activeAudio: Audio? = null //an object of the currently playing audio

    companion object{
        public val ACTION_PLAY = "com.example.myplayer.ACTION_PLAY"
        public val ACTION_PAUSE = "com.example.myplayer.ACTION_PAUSE"
        public val ACTION_PREVIOUS = "com.example.myplayer.ACTION_PREVIOUS"
        public val ACTION_NEXT ="com.example.myplayer.ACTION_NEXT"
        public val ACTION_STOP = "com.example.myplayer.ACTION_STOP"



        //AudioPlayer notification ID
        private const val NOTIFICATION_ID = 101



    }

    //MediaSession
    private var mediaSessionManager: MediaSessionManager?= null
    private var mediaSession: MediaSessionCompat?=null
    private var transportControls: MediaControllerCompat.TransportControls?= null


    override fun onCreate() {
        super.onCreate()
        //Perform one-time setup procedures

        //Manage incoming phone calls during playback
        //Pause MediaPLayer on incoming call
        //Resume on hangup
        callStateListener()
        //ACTION_AUDIO_BECOMING NOISY -- chang in audio outputs -- BroadcastReceiver
        registerBecomingNoisyReceiver()
        //List for new Audio to play -- BroadcastReceiver
        registerPlayNewAudio()
    }

    override  fun onBind(intent: Intent): IBinder? {
        return iBinder
    }

    override fun onBufferingUpdate(mp: MediaPlayer?, percent: Int) {
        //Invoked indicating buffering status of a com.example.myplayer.media resource being streamed over a network
    }

    override fun onCompletion(mp: MediaPlayer?) {
        //Invoke when playback of a media source has completed
        stopMedia()
        //stop the service
        stopSelf()
    }

    //Handle Errors
    override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        //Invoked when there has bee nan error during an asynchronous operation
        when(what){
            MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK -> Log.d(
                    "MediaPlayer Error",
                    "MEDIA ERROR NOT VALID FOR PROGRESSIVE PLAYBACK $extra"
            )
            MediaPlayer.MEDIA_ERROR_SERVER_DIED -> Log.d(
                    "MediaPlayer Error",
                    "MEDIA ERROR SERVER DIED $extra"
            )
            MediaPlayer.MEDIA_ERROR_UNKNOWN -> Log.d(
                    "MediaPlayer Error",
                    "MEDIA ERROR UNKOWN $extra"
            )
        }
        return false
    }

    override fun onPrepared(mp: MediaPlayer?){
        //Invoked when the com.example.myplayer.media source is ready for playback
        playMedia()
    }

    override fun onSeekComplete(mp: MediaPlayer?) {
        //Invoked indicating the completion of a seek operation
    }


    override fun onAudioFocusChange(focusChange: Int) {
        //Invoked when the audio focus of the system is updated.
        when(focusChange){
            AudioManager.AUDIOFOCUS_GAIN -> {
                //resume playback
                if (mediaPlayer == null) initMediaPlayer()
                else if (!mediaPlayer!!.isPlaying) mediaPlayer!!.start()
                mediaPlayer!!.setVolume(1.0f, 1.0f)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                //Lost focus for an unbounded amount of time: stop playback and release player
                if (mediaPlayer!!.isPlaying) mediaPlayer!!.stop()
                mediaPlayer?.release()
                mediaPlayer = null
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                //Lost focus for a short time, but we have to stop playback. Don't release player because playback might resume
                if (mediaPlayer!!.isPlaying) mediaPlayer!!.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                //Lost focus for a short time, but it's ok to keep playing at an attenuated level
                if (mediaPlayer!!.isPlaying()) mediaPlayer!!.setVolume(0.1f, 0.1f)
            }
        }
    }

    override fun onInfo(mp: MediaPlayer, what: Int, extra: Int): Boolean{
        //Invoked to communicate some info.
        return false
    }


    inner class LocalBinder : Binder() {
        fun getService() : MediaPlayerService?{
            return this@MediaPlayerService
        }

        //fun getService() = this@MediaPlayerService
    }

    private fun initMediaPlayer(){
        if(mediaPlayer ==null) mediaPlayer = MediaPlayer()


        //Set up MediaPlayer event listeners
        mediaPlayer?.setOnCompletionListener(this)
        mediaPlayer?.setOnErrorListener(this)
        mediaPlayer?.setOnPreparedListener(this)
        mediaPlayer?.setOnBufferingUpdateListener(this)
        mediaPlayer?.setOnSeekCompleteListener(this)
        mediaPlayer?.setOnInfoListener(this)
        //Reset so that the MediaPlayer is not pointing to another data source
        mediaPlayer?.reset()

        //create uri
        //val id: Long = activeAudio!!.uri
        //val contentUri: Uri = ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        val contentUri: Uri = Uri.parse(activeAudio!!.uri)

        mediaPlayer?.setAudioAttributes(
                AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
        )

        Log.d("mp initialized", "$mediaPlayer")
        Log.d("mp init", "$activeAudio")
        Log.d("mp init", "url for song is $contentUri")

        try {
            //Set the data source to the mediaFile location

            mediaPlayer?.setDataSource(applicationContext, contentUri)

        } catch (e: IOException){
            e.printStackTrace()
            stopSelf()

        }
        mediaPlayer!!.prepareAsync()

    }

    private fun playMedia(){
        if (!mediaPlayer!!.isPlaying){
            mediaPlayer?.start()
        }
    }

    private fun stopMedia(){
        if(mediaPlayer == null) return
        if(mediaPlayer!!.isPlaying){
            mediaPlayer!!.stop()
        }
    }

    private fun pauseMedia(){
        if(mediaPlayer == null) return
        if(mediaPlayer!!.isPlaying){
            mediaPlayer?.stop()
        }
    }

    private fun resumeMedia(){
        if(!mediaPlayer!!.isPlaying){
            mediaPlayer!!.seekTo(resumePostion!!)
            mediaPlayer!!.start()
        }
    }

    private fun requestAudioFocus(): Boolean{
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        focusRequest= AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).build()
        var result: Int= audioManager!!.requestAudioFocus(focusRequest!!)
        if(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED){
            //Focus gained
            return true
        }
        //could not gain focus
        return false
    }

    private fun removeAudioFocus(): Boolean{

        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED == audioManager!!.abandonAudioFocusRequest(
                focusRequest!!
        )
    }

    //The system calls this method when an activity, requests the service to be started
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int{

        try{
            //Load data from SharedPreferences
            var storage = StorageUtil(applicationContext)
            audioList = storage.loadAudio()
            audioIndex = storage.loadAudioIndex()

            Log.d("onstart", mediaSessionManager.toString())
            Log.d("onstart", "audio index is $audioIndex")

            Log.d("onstart", "audio size is ${audioList!!.size}")

            Log.d("audio list","audio list is $audioList")

            if(audioIndex != -1 && audioIndex < audioList!!.size){
                //index is in valid range
                activeAudio = audioList!![audioIndex]
                Log.d("index is in valid range ", "${activeAudio?.data}")
            }else{
                Log.d("index not in valid range", "active audio is $activeAudio")
                stopSelf()
            }
        }catch (e: NullPointerException){
            stopSelf()
        }

        //Request audio focus
        if(!requestAudioFocus()){
            //Could not gain focus
            stopSelf()
        }

        if(mediaSessionManager == null){
            try{
                initMediaSession()
                Log.d("md init ", "$mediaSession")
                initMediaPlayer()
            }catch (e: RemoteException){
                e.printStackTrace()
                stopSelf()
            }
            buildNotification(PlaybackStatus.PLAYING)
        }

        //Handle Intent action from MediaSession.TransportControls
        handleIncomingAction(intent)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy(){
        super.onDestroy()
        if(mediaPlayer != null){
            stopMedia()
            mediaPlayer?.release()
        }
        removeAudioFocus()
        //Disable the PhoneStateListener
        if(phoneStateListener!=null){
            telephonyManager!!.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        }
        removeNotification()

        //unregister BroadcastReceivers
        unregisterReceiver(becomingNoisyReceiver)
        unregisterReceiver(playNewAudio)

        //clear cached playlist
        StorageUtil(applicationContext).clearCachedAudioPlaylist()

    }


    //Becoming noisy
    private val becomingNoisyReceiver: BroadcastReceiver = object : BroadcastReceiver(){
        override fun onReceive(context: Context?, intent: Intent?) {
            //pause audio on ACTION_AUDIO_BECOMING_NOISY
            pauseMedia()
            buildNotification(PlaybackStatus.PAUSED)
        }

    }

    private fun registerBecomingNoisyReceiver(){
        //register after getting audio focus
        var intentFilter: IntentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(becomingNoisyReceiver, intentFilter)
    }

    //Handle Incoming phone calls
    private fun callStateListener(){
        //Get the telephone manager
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        //Start listening for PhoneState chagnes
        phoneStateListener = object : PhoneStateListener(){
            override fun onCallStateChanged(state: Int, incomingNumber: String) {
                when(state){
                    //if at least one call exists or the phone is ringing pause the MediaPlayer
                    TelephonyManager.CALL_STATE_OFFHOOK, TelephonyManager.CALL_STATE_RINGING -> if (mediaPlayer != null) {
                        pauseMedia()
                        ongoingCall = true
                    }
                    TelephonyManager.CALL_STATE_IDLE ->         //Phone idle. Start Playing.
                        if (mediaPlayer != null) {
                            if (ongoingCall) {
                                ongoingCall = false
                                resumeMedia()
                            }
                        }
                }
            }
        }
        //Register the listener with the telephony manager
        //Listen for changes to the device call state.
        telephonyManager!!.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)


    }

    private  val playNewAudio= object : BroadcastReceiver(){
        override fun onReceive(context: Context?, intent: Intent?) {

            //Get the new media index from SharedPreferences
            audioIndex = StorageUtil(applicationContext).loadAudioIndex()
            if(audioIndex != -1 && audioIndex < audioList!!.size ){
                //index is in a valid range
                activeAudio = audioList!![audioIndex]
            }else{
                stopSelf()
            }
            //A PLAY_NEW_AUDIO action received
            //reset mediaPlayer to play the new Audio
            stopMedia()
            mediaPlayer?.reset()
            initMediaPlayer()
            updateMetaData()
            Log.d("md", "$mediaSession")
            buildNotification(PlaybackStatus.PLAYING)
        }
    }

    private fun registerPlayNewAudio() {
        //Register playNewMedia receiver
        var filter = IntentFilter(MainActivity.Broadcast_PLAY_NEW_AUDIO)
        registerReceiver(playNewAudio, filter)


    }



    @Throws(RemoteException::class)
    private fun initMediaSession() {
        if(mediaSessionManager != null) return //mediaSessionManager exists

        mediaSessionManager =  getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        //Create a new MediaSession
        mediaSession = MediaSessionCompat(applicationContext, "AudioPlayer")
        //Get MediaSessions transport controls
        transportControls = mediaSession!!.controller.transportControls
        //set MediaSession -> read to receive media commands
        mediaSession!!.isActive = true
        //indicate that the MediaSession handles transport control commands
        //through its MediaSessionCompact.Callback

        //Set mediaSessions's MetaData
        updateMetaData()

        //Attach Callback to receive MediaSession updates
        mediaSession?.setCallback(object : MediaSessionCompat.Callback() {
            //Implement callbacks
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
                //stop the service
                stopSelf()
            }

            override fun onSeekTo(pos: Long) {
                super.onSeekTo(pos)
            }

        })

    }

    private fun updateMetaData(){
        var albumArt = BitmapFactory.decodeResource(resources, R.drawable.image)//replace with medias album art
        //update the current metadata
        Log.d("artist", activeAudio.toString())
        mediaSession!!.setMetadata(
                MediaMetadataCompat.Builder()
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, activeAudio?.artist)
                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, activeAudio!!.album)
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, activeAudio!!.title)
                        .build()
        )

    }

    private fun skipToNext(){
        if(audioIndex == audioList!!.size - 1 ){
            //if last in playlist
            audioIndex =0
            activeAudio = audioList!![audioIndex]

        }else{
            //get next in playlist
            activeAudio= audioList!![++audioIndex]

        }

        //Update stored index
        StorageUtil(applicationContext).storeAudioIndex(audioIndex)

        stopMedia()
        //reset mediaPlayer
        mediaPlayer!!.reset()
        initMediaPlayer()
    }

    private fun skipToPrevious(){
        if(audioIndex ==0){
            //if first in playlist
            //set index to the last of audioList
            audioIndex = audioList!!.size -1
            activeAudio = audioList!![audioIndex]
        }else{
            //get previous in playlist
            activeAudio = audioList!!.get(--audioIndex)
        }

        //Update stored index
        StorageUtil(applicationContext).storeAudioIndex(audioIndex)

        stopMedia()
        //reset mediaPlayer
        mediaPlayer!!.reset()
        initMediaPlayer()

    }


    private fun buildNotification(playbackStatus: PlaybackStatus) {
        /**
         * Notification actions -> playbackAction
         * 0 -> Play
         * 1 -> Pause
         * 2 -> Next track
         * 3-> Prev track
         */

        var notificationAction = android.R.drawable.ic_media_pause //needs to be initialized
        var play_pauseAction: PendingIntent? = null

        //Build a new notification according to the current state of the MediaPlayer
        if(playbackStatus == PlaybackStatus.PLAYING){
            notificationAction = android.R.drawable.ic_media_pause
            //create the pause action
            play_pauseAction = playbackAction(1)
        }else if(playbackStatus == PlaybackStatus.PAUSED){
            notificationAction = android.R.drawable.ic_media_play
            //create the play action
            play_pauseAction = playbackAction(0)
        }

        var largeIcon= BitmapFactory.decodeResource(resources, R.drawable.image)//replace with own image

        //create a new notification
        var notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_ID.toString())
                .setShowWhen(false)
                //Set the Notification style
                .setStyle(
                        androidx.media.app.NotificationCompat.MediaStyle()
                                //Attach our MediaSession token
                                .setMediaSession(mediaSession!!.sessionToken)
                                //Show our playback controls in the compact notification view
                                .setShowActionsInCompactView(0, 1, 2)
                )
                //Set teh notification color
                .setColor(ContextCompat.getColor(this, R.color.design_default_color_primary))
                //Set the large and small icon
                .setLargeIcon(largeIcon)
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                //Set Notification content information
                .setContentText(activeAudio!!.artist)
                .setContentTitle(activeAudio!!.album)
                .setContentInfo(activeAudio!!.title)
                //Add playback action
                .addAction(android.R.drawable.ic_media_previous, "previous", playbackAction(3))
                .addAction(notificationAction, "pause", play_pauseAction)
                .addAction(android.R.drawable.ic_media_next, "next", playbackAction(2))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

               // var notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                //notificationManager.notify(NOTIFICATION_ID,notificationBuilder.build())
                createNotificationChannel()

    }

    private fun createNotificationChannel(){
        //Create the NotificationChannel, but only on API 26+1 because the NotificationChannel class is new and not in the support library
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            val name = getString(R.string.channel_name)
            val descriptionText = getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIFICATION_ID.toString(),name,importance).apply {
                description= descriptionText
            }
            //Register the channel with the system
            val notificationManager: NotificationManager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun removeNotification(){
        var notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun playbackAction(actionNumber: Int): PendingIntent? {
        var playbackAction = Intent(this, MediaPlayerService::class.java)
        when(actionNumber){
            0 -> {//Play
                playbackAction.action = ACTION_PLAY
                return PendingIntent.getService(this, actionNumber, playbackAction, 0)
            }
            1 -> {
                //Pause
                playbackAction.action = ACTION_PAUSE
                return PendingIntent.getService(this, actionNumber, playbackAction, 0)
            }
            2 -> {
                //Next track
                playbackAction.action = ACTION_NEXT
                return PendingIntent.getService(this, actionNumber, playbackAction, 0)
            }
            3 -> {
                //Previous track
                playbackAction.action = ACTION_PREVIOUS
                return PendingIntent.getService(this, actionNumber, playbackAction, 0)
            }
            else->{}

        }
        return null
    }

    private fun handleIncomingAction(playbackAction: Intent){
        if(playbackAction.action == null) return

        var actionString = playbackAction.action
        when {
            actionString.equals(ACTION_PLAY, ignoreCase = true) -> {
                transportControls!!.play()
            }
            actionString.equals(ACTION_PAUSE, ignoreCase = true) -> {
                transportControls!!.pause()
            }
            actionString.equals(ACTION_NEXT, ignoreCase = true) -> {
                transportControls!!.skipToNext()
            }
            actionString.equals(ACTION_PREVIOUS, ignoreCase = true) -> {
                transportControls!!.skipToPrevious()
            }
            actionString.equals(ACTION_STOP, ignoreCase = true) -> {
                transportControls!!.stop()
            }
        }
    }


}