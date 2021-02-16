package com.example.myplayer.media.library

import android.media.browse.MediaBrowser
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.support.v4.media.MediaMetadataCompat
import android.util.Log
import androidx.annotation.IntDef
import com.example.myplayer.media.extensions.*


/**
 * Interface used by [MusicService] for looking up [MediaMetadataCompat] objects.
 *
 * Because Kotlin provides methods such as [Iterable.find] and [Iterable.filter],
 * this is a convenient interface to have on sources.
 *
 */

interface MusicSource: Iterable<MediaMetadataCompat> {


    /**
     * Begins loading the data for this music source
     */

    suspend fun load()

    /**
     * Method which will perform a given action after this [MusicSource] is ready to be used.
     *
     * @param performAction A lambda expression to be called with a boolean parameter when this
     * source is ready.
     * `true` indicates the source was successfully prepared,
     * `false` indicated an error occurred.
     */

    fun whenReady(performAction: (Boolean) -> Unit): Boolean

    fun search(query: String, extras: Bundle): List<MediaMetadataCompat>


}

@IntDef(
    STATE_CREATED,
    STATE_INITIALIZING,
    STATE_INITIALIZED,
    STATE_ERROR
)
@Retention(AnnotationRetention.SOURCE)
annotation class State

/**
 * State when the source was created, but no initialization has been done
 */
const val STATE_CREATED= 1

/**
 * State indicating initialization of the source is in progress.
 */
const val STATE_INITIALIZING= 2

/**
 * State indicating the source has been initialized and is ready to be used
 */
const val STATE_INITIALIZED= 3

/**
 * State of that an error has occurred
 */

const val STATE_ERROR = 4

/**
 * Base class for music Source
 */
abstract class AbstractMusicSource : MusicSource {

    @State
    var state: Int = STATE_CREATED
        set(value) {
            if (value== STATE_INITIALIZED || value == STATE_ERROR) {
                synchronized(onReadyListeners) {
                    field = value
                    onReadyListeners.forEach { listener ->
                        listener(state == STATE_INITIALIZED)
                    }
                }
            }else{
                    field=value
                }
            }

    private val onReadyListeners = mutableListOf<(Boolean) -> Unit>()

    /**
     * Performs an action when this MusicSource is ready.
     *
     * This method is *not* threadSafe. Ensure actions and state changes are only performed on
     * a single thread.
     */

    override fun whenReady(performAction: (Boolean) -> Unit): Boolean =
        when(state){
            STATE_CREATED, STATE_INITIALIZING -> {
                onReadyListeners +=performAction
                false
            }
            else ->{
                performAction(state != STATE_ERROR)
                true
            }
        }

    override fun search(query: String, extras: Bundle): List<MediaMetadataCompat> {
        //First attempt to search with the "focus" that's provided in the extras
        val focusedSearchResult = when(extras[MediaStore.EXTRA_MEDIA_FOCUS]) {
            MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE ->{
                //For a genre focused search, only genre is set.
                val genre = extras[EXTRA_MEDIA_GENRE]
                Log.d(TAG,"Focused genre search: '$genre'")
                filter{ song ->
                    song.genre == genre
                }
            }
            MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE ->{
                //For an Artist focused search, only the artist is set.
                val artist= extras[MediaStore.EXTRA_MEDIA_ARTIST]
                Log.d(TAG,"Focused artist search '$artist'")
                filter { song->
                    (song.artist == artist || song.albumArtist==artist)
                }
            }
            MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE ->{
                //For an album focused search, only the album and artist are set.
                val artist= extras[MediaStore.EXTRA_MEDIA_ARTIST]
                val album= extras[MediaStore.EXTRA_MEDIA_ALBUM]
                Log.d(TAG,"Focused album search: album'$album' artist='$artist'")
                filter { song->
                    (song.artist == artist || song.albumArtist == artist) && song.album == album
                }
            }
            MediaStore.Audio.Media.ENTRY_CONTENT_TYPE->{
                //For a song (aka Media) focused search, title, album, and artist are set
                val title= extras[MediaStore.EXTRA_MEDIA_TITLE]
                val artist= extras[MediaStore.EXTRA_MEDIA_ARTIST]
                val album= extras[MediaStore.EXTRA_MEDIA_ALBUM]
                Log.d(TAG,"Focused media search: title'$title' artist='$artist' album='$album'" )
                filter { song->
                    (song.artist==artist || song.albumArtist==artist) && song.album == album
                            && song.title == title
                }
            }
            else->{
                //There isn't a focus, so no results yet.
                emptyList()
            }
        }
        /**
         * If there weren't any results from the focused search (or if there wasn't a focus to begin
         * with), try to find any matches given the 'query' provided, searching against a few of the
         * fields.
         * In this sample, we're just check a few fields with the provided query, but in a more
         * complex app, more logic could be used to find fuzzy matches, etc...
         * TODO:add more logic to find fuzzy matches
         */
        if (focusedSearchResult.isEmpty()){
            return if(query.isNotBlank()){
                Log.d(TAG,"Unfocused search for '$query'")
                filter{ song->
                    song.title.containsCaseInsensitive(query)
                            || song.genre.containsCaseInsensitive(query)
                }
            }else{
                /**
                 * If the user asked to "play music" or something similar, the query will also
                 * be blank. Give the small catalog of songs in the sample, just return them
                 * all, shuffled as something to play
                 * TODO:Play all song or maybe just play most recently player?
                 */
                Log.d(TAG,"Unfocused search without keyword")
                return shuffled()
            }
        }else{
            return focusedSearchResult
        }
    }


    /**
     * [MediaStore.EXTRA_MEDIA_GENRE] is missing on API 19. Hide this fact by user our own
     * version of it
     */
    private val EXTRA_MEDIA_GENRE
        get() = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP){
            MediaStore.EXTRA_MEDIA_GENRE
        }else{
            "android.intent.extra.genre"
        }
}

private const val TAG = "MusicSource"
