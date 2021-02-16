package com.example.myplayer.media.library

import android.content.ContentUris
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.util.Log
import com.example.myplayer.media.R
import com.example.myplayer.media.extensions.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.lang.Exception
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 *
 *
 *
 */
/**
 *Source of [MediaMetaddataCompat] object created from a json string of all
 * local media files obtained from [MediaStore.Audio.Media]
 */

class LocalMusicSource(private val sourceCat: String) : AbstractMusicSource(){
    private var catalog: List<MediaMetadataCompat> = emptyList()

    init{
        state= STATE_INITIALIZING
    }

    override fun iterator(): Iterator<MediaMetadataCompat> = catalog.iterator()

    override suspend fun load() {
        Log.d(TAG,"creating local music source on load")
        updateCatalog(sourceCat)?.let{ updatedCatalog ->
            catalog = updatedCatalog
            state = STATE_INITIALIZED
        } ?: run {
            catalog= emptyList()
            state= STATE_ERROR
        }
    }

    /**
     * Function to connect to a remote URI and download/process the JSON file that
     * corresponds to [MediaMetadataCompat] objects
     *
     */


    private suspend fun updateCatalog(catalogUri: String): List<MediaMetadataCompat>?{
        Log.d(TAG,"updating catalog")
        return withContext(Dispatchers.IO){
            val musicCat=try{
                Log.d(TAG, downloadJson(catalogUri).toString())
                downloadJson(catalogUri)
            }catch (ioException: IOException){
                return@withContext null
            }
            //debug music catalog items
           /*musicCat.music.forEach{
                Log.d(TAG,"id: ${it.id }, genre: ${it.genre}, duration: ${it.duration},album: ${it.album},artist: ${it.artist},image: ${it.image},source: ${it.source},title: ${it.title},tracknumber: ${it.trackNumber},data: ${it.data}")
            }*/

            val mediaMetadataCompats = musicCat.music.map{ song->
                MediaMetadataCompat.Builder()
                        .from(song)
                        .apply{
                            displayIconUri = song.source //Used by Notification
                            albumArtUri = song.source
                        }
                        .build()
            }.toList()
            Log.d(TAG,"mediametadata $mediaMetadataCompats")
            //Add description keys to be used by the Exoplayer media session extension when
            //announcing metadat changes.

            Log.d(TAG,"MediaMetaDataCompats media Metadata: ${mediaMetadataCompats.forEach { it.mediaMetadata }}")
            Log.d(TAG,"MediaMetaDataCompats media bundle: ${mediaMetadataCompats.forEach { it.bundle }}")
            Log.d(TAG,"MediaMetaDataCompats media extras: ${mediaMetadataCompats.forEach { it.description.extras }}")
            Log.d(TAG,"MediaMetaDataCompats media description: ${mediaMetadataCompats.forEach { it.description }}")


            mediaMetadataCompats.forEach{it.description.extras?.putAll(it.bundle)}
            mediaMetadataCompats
        }
    }

    /**
     * Attempts to download a catalog from a given Uri
     *
     * @param  catalogUri URI to attempt to download the catalog form.
     * @return The catalog downloaded or an empty catalog if an error occured
     *
     */
    @Throws(IOException::class)
    private fun downloadJson(catalog: String): LocalMusicCatalog {
        val reader = catalog
        Log.d(TAG,"read in download json is $reader")
        Gson().fromJson(catalog, LocalMusicCatalog::class.java).music.forEach {   Log.d(TAG,"gson: ${it.id}") }
        return Gson().fromJson(catalog, LocalMusicCatalog::class.java)
    }
}

/**
 * Wrapper object for our JSON in order to be processed easily by GSON.
 */
class LocalMusicCatalog {

    var music: List<LocalMusic> = ArrayList()
}

/**
 * An individual piece of music included in our JSON catalog.
 * The format from the server is as specified:
 * ```
 *     { "music" : [
 *     { "title" : // Title of the piece of music
 *     "album" : // Album title of the piece of music
 *     "artist" : // Artist of the piece of music
 *     "genre" : // Primary genre of the music
 *     "source" : // Path to the music, which may be relative
 *     //TODO: refactor this
 *     "image" : // Path to the art for the music, we use the content uri which is source above
 *     "trackNumber" : // Track number
 *     "totalTrackCount" : // Track count
 *     "duration" : // Duration of the music in seconds
 *     "site" : // Source of the music, if applicable
 *     }
 *     ]}
 * ```
 * `source` and `image` can be provided in either relative or
 * absolute paths. For example:
 * ``
 *     "source" : "https://www.example.com/music/ode_to_joy.mp3",
 *     "image" : "ode_to_joy.jpg"
 * ``
 *
 * The `source` specifies the full URI to download the piece of music from, but
 * `image` will be fetched relative to the path of the JSON file itself. This means
 * that if the JSON was at "https://www.example.com/json/music.json" then the image would be found
 * at "https://www.example.com/json/ode_to_joy.jpg".
 *
 */

@Suppress("unused")
class LocalMusic{
    var id: String=""
    var title: String=""
    var album: String=""
    var artist: String=""
    var genre: String=""
    var source: String=""
    var image: String=""
    var trackNumber: Long = 0
    var totalTrackCount: Long = 0
    var duration: Long = -1
    var site: String = ""
    var data: String = ""
}

/**
 * Extension method for [MediaMetadataCompat.Builder] to set the fields from our JSON
 * constructed object (to make the code a bit easier to see)
 *
 */

fun MediaMetadataCompat.Builder.from(localMusic: LocalMusic):MediaMetadataCompat.Builder{
    //The duration from the JSON is given in seconds, but the rest of the code work in
    //milliseconds. Here's where we convert to the proper units.
    val durationMs= TimeUnit.SECONDS.toMillis(localMusic.duration)

    id= localMusic.id
    title = localMusic.title
    artist= localMusic.artist
    album= localMusic.album
    duration = localMusic.duration
    genre= localMusic.genre
    mediaUri= localMusic.source
    albumArtUri= localMusic.source
    trackNumber= localMusic.trackNumber
    trackCount =localMusic.totalTrackCount
    flag= MediaBrowserCompat.MediaItem.FLAG_PLAYABLE

    //Too make things easier for *displaying* these, set the display properties as well
    displayTitle =localMusic.title
    displaySubtitle=localMusic.artist
    displayDescription=localMusic.album
    displayIconUri=localMusic.source

    /** Add downloadStatus to force the creation of an "extras" bundle in the resulting
     * [MediaMetadataCompat] object. This is needed to send accurate metadat to the media session
     * during updates
     */
    downloadStatus= MediaDescriptionCompat.STATUS_NOT_DOWNLOADED

    //Allow it to be used in typical builder stle
    return this

}

private const val TAG= "LocalMusicSource"


