package com.example.myplayer.utils

import android.app.Application
import android.content.ComponentName
import android.content.Context
import com.example.myplayer.MainActivity
import com.example.myplayer.common.MusicServiceConnection
import com.example.myplayer.media.MusicService
import com.example.myplayer.viewmodels.MainActivityViewModel
import com.example.myplayer.viewmodels.MediaItemFragmentViewModel
import com.example.myplayer.viewmodels.NowPlayingFragmentViewModel

/**
 * Static methods used to inject classes needed for Activities and Fragments
 */
object InjectorUtils {
    private fun provideMusicServiceConnection(context: Context): MusicServiceConnection{
        return MusicServiceConnection.getInstance(context,
                ComponentName(context, MusicService::class.java))
    }

    fun provideMainActivityViewModel(content: Context): MainActivityViewModel.Factory {
        val applicationContext = content.applicationContext
        val musicServiceConnection = provideMusicServiceConnection(applicationContext)
        return MainActivityViewModel.Factory(musicServiceConnection)
    }

    fun provideMediaItemFragmentViewModel(context: Context, mediaId: String)
        : MediaItemFragmentViewModel.Factory{
        val applicationContext = context.applicationContext
        val musicServiceConnection = provideMusicServiceConnection(applicationContext)
        return MediaItemFragmentViewModel.Factory(mediaId,musicServiceConnection)
    }

    fun provideNowPlayingFragmentViewModel(context: Context)
    : NowPlayingFragmentViewModel.Factory{
        val applicationContext = context.applicationContext
        val musicServiceConnection = provideMusicServiceConnection(applicationContext)
        return NowPlayingFragmentViewModel.Factory(
                applicationContext as Application, musicServiceConnection
        )
    }
}