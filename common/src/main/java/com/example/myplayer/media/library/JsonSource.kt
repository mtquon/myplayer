package com.example.myplayer.media.library

import android.net.Uri
import android.provider.MediaStore
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat.STATUS_NOT_DOWNLOADED
import com.example.myplayer.media.extensions.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 *Source of [MediaMetaddataCompat] object created from a basic JSON stream.
 *
 * The definition of the JSON is specified in the docs of [JsonMusic] in this file,
 * which is the object representation of it.
 * TODO: Grab sources from andorid device locally
 */

class JsonSource(private val source: Uri) : AbstractMusicSource(){
    private var catalog: List<MediaMetadataCompat> = emptyList()

    init{
        state= STATE_INITIALIZING
    }

    override fun iterator(): Iterator<MediaMetadataCompat> = catalog.iterator()

    override suspend fun load() {
        updateCatalog(source)?.let{ updatedCatalog ->
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


    private suspend fun updateCatalog(catalogUri: Uri): List<MediaMetadataCompat>?{
        return withContext(Dispatchers.IO){
            val musicCat=try{
                downloadJson(catalogUri)
            }catch (ioException: IOException){
                return@withContext null
            }

            //Get the base URI to fix up relative reference later
            val baseUri= catalogUri.toString().removeSuffix(catalogUri.lastPathSegment ?:"")

            val mediaMetadataCompats = musicCat.music.map{ song->
                //The JSON may have paths that are relative to the soruce of the JSON itslef.
                //We need to fix them up to turn them into absolute paths.
                catalogUri.scheme?.let { scheme->
                    if(!song.source.startsWith(scheme)){
                        song.source = baseUri + song.source
                    }
                    if(!song.image.startsWith(scheme)) {
                        song.image= baseUri + song.image
                    }
                }

                MediaMetadataCompat.Builder()
                    .from(song)
                    .apply{
                        displayIconUri = song.image //Used by Notification
                        albumArtUri = song.image
                    }
                    .build()
            }.toList()
            //Add description keys to be used by the Exoplayer media session extension when
            //announcing metadat changes.
            //TODO: Remove?
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
    private fun downloadJson(catalogUri: Uri): JsonCatalog {
        val catalogConn= URL(catalogUri.toString())
        val reader = BufferedReader(InputStreamReader(catalogConn.openStream()))
        return Gson().fromJson(reader, JsonCatalog::class.java)
    }

}

/**
 * Wrapper object for our JSON in order to be processed easily by GSON.
 */
class JsonCatalog {
    var music: List<JsonMusic> = ArrayList()
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
 *     "image" : // Path to the art for the music, which may be relative
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
class JsonMusic{
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
}

/**
 * Extension method for [MediaMetadataCompat.Builder] to set the fields from our JSON
 * constructed object (to make the code a bit easier to see)
 * TODO: Might have to remove (or change) since we use local source
 */

fun MediaMetadataCompat.Builder.from(jsonMusic: JsonMusic):MediaMetadataCompat.Builder{
    //The duration from the JSON is given in seconds, but the rest of the code work in
    //milliseconds. Here's where we convert to the proper units.
    val durationMs= TimeUnit.SECONDS.toMillis(jsonMusic.duration)

    id= jsonMusic.id
    title = jsonMusic.title
    artist= jsonMusic.artist
    album= jsonMusic.album
    duration = jsonMusic.duration
    genre= jsonMusic.genre
    mediaUri= jsonMusic.source
    albumArtUri= jsonMusic.image
    trackNumber= jsonMusic.trackNumber
    trackCount =jsonMusic.totalTrackCount
    flag= MediaItem.FLAG_PLAYABLE

    //Too make things easier for *displaying* these, set the display properties as well
    displayTitle =jsonMusic.title
    displaySubtitle=jsonMusic.artist
    displayDescription=jsonMusic.album
    displayIconUri=jsonMusic.image

    /** Add downloadStatus to force the creation of an "extras" bundle in the resulting
     * [MediaMetadataCompat] object. This is needed to send accurate metadat to the media session
     * during updates
     */
    downloadStatus= STATUS_NOT_DOWNLOADED

    //Allow it to be used in typical builder stle
    return this

}

