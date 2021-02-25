package com.example.myplayer.media

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.content.ContextCompat.getSystemService
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions

const val MUSIC_CHANNEL_ID= "com.example.myplayer.media.MUSIC_CHANNEL_ID"
const val NOW_PLAYING_NOTIFICATION_ID= 412 //Arbitrary number used to identify our notification
const val REQUEST_CODE = 100

/**
 *TODO: Keeps track of a notification and updates it automatically for a given MediaSession.
 *
 */

class NotificationManager(private val musicService: MusicService) : BroadcastReceiver() {
    private lateinit var  mSessionToken: MediaSessionCompat.Token
    private lateinit var mController: MediaControllerCompat
    private lateinit var mTransportControls: MediaController.TransportControls

    private lateinit var mPlaybackState: PlaybackStateCompat
    private lateinit var mMetadata: MediaMetadataCompat


    /*private val mNotificationManager: NotificationManager = musicService

    private val mPlayIntent: PendingIntent
    private val mPauseIntent: PendingIntent
    private val mPreviousIntent: PendingIntent
    private val mNextIntent: PendingIntent
    private val mStopIntent: PendingIntent
    private val mStopCastIntent: PendingIntent*/

    private val mNotificationColor: Int = 0 //TODO: implement resource helper

    private var mStarted = false




    init{
        updateSessionToken()


    }

    private fun updateSessionToken() {
        TODO("Not yet implemented")
    }


    override fun onReceive(p0: Context?, p1: Intent?) {
        TODO("Not yet implemented")
    }



}



private const val TAG="NotificationManager"
const val ACTION_PAUSE = "com.example.myplayer.pause"
const val ACTION_PLAY = "com.example.myplayer.play"
const val ACTION_NEXT = "com.example.myplayer.next"
const val ACTION_PREV = "com.example.myplayer.prev"
const val ACTION_STOP = "com.example.myplayer.stop"
const val ACTION_STOP_CASTING ="com.example.myplayer.stop_cast"

const val NOTIFICATION_LARGE_ICON_SIZE = 144 // px
