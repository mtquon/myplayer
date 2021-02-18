package com.example.myplayer.media.library

import android.support.v4.media.MediaMetadataCompat

interface MusicProvderSource {
    operator fun iterator(): Iterator<MediaMetadataCompat?>?
    companion object{
        const val CUSTOM_METADATA_TRACK_SOURCE = "__SOURCE__"
    }



}

