package com.example.myplayer.media.library

import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import android.os.AsyncTask
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.util.Log
import com.example.myplayer.media.R
import com.example.myplayer.media.library.MediaIDHelper.createMediaID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.lang.IllegalArgumentException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlin.collections.ArrayList

/**
 * data provider for music tracks. The actual metadata source is delegated to a MusicProviderSource
 * defined by a constructor argument of this class
 */
class MusicProvider(source: MusicProvderSource){
    private val mSource: MusicProvderSource = source
    private val mediaIdToChildren = mutableMapOf<String, MutableList<MediaMetadataCompat>>()

    //Catagorized chaches for music track data:

    private lateinit var mMusicListByGenre : ConcurrentMap<String, List<MediaMetadataCompat>>
    private val mMusicListById =  emptyMap<String, MutableMediaMetadata>() as ConcurrentMap<String, MutableMediaMetadata>
    private val mFavouriteTracks: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    internal enum class State {
        NON_INITIALIZED, INITIALIZING, INITIALIZED
    }


    @Volatile private var mCurrentState = State.NON_INITIALIZED

    interface Callback {
        fun onMusicCatalogReady(success: Boolean)
    }

    /**
     * Get an iteravtor over the list of genres
     *
     *@return genres
     */
    open fun getGenres():Iterable<String>{
        if(mCurrentState != State.INITIALIZED){
            return emptyList()
        }
        return mMusicListByGenre.keys
    }

    /**
     * Get an iterator over a shuffled collection of all songs
     */

    fun getShuffledMusic(): Iterable<MediaMetadataCompat>{
        if(mCurrentState != State.INITIALIZED){
            return emptyList()
        }
        var shuffled : MutableList<MediaMetadataCompat> = ArrayList(mMusicListById.size)
        for (mutableMetadata in mMusicListById.values){
            shuffled.add(mutableMetadata.metadata)
        }
        shuffled.shuffle()
        return shuffled
    }

    /**
     * Get music tracks of the given genre
     */

    fun getMusicByGenre(genre: String): List<MediaMetadataCompat> {
        if (mCurrentState != State.INITIALIZED || !mMusicListByGenre.containsKey(genre)){
            return emptyList()
        }
        return mMusicListByGenre[genre]?: throw IllegalArgumentException("cannot find genre in mMusicListByGenre: $mMusicListByGenre")
    }



    /**
     * Very basic implementation of a search that filters out music tracks with titles containing
     * the given query
     */

    fun searchMusicBySongTitle(query: String) : List<MediaMetadataCompat>{
        return searchMusic(MediaMetadataCompat.METADATA_KEY_TITLE, query)
    }

    /**
     * search music by album
     */
    fun searchMusicByAlbum(query: String): List<MediaMetadataCompat>{
        return searchMusic(MediaMetadataCompat.METADATA_KEY_ALBUM, query)
    }

    /**
     * search music by artist
     */
    fun searchMusicByArtist(query: String): List<MediaMetadataCompat>{
        return searchMusic(MediaMetadataCompat.METADATA_KEY_ARTIST, query)
    }

    /**
     * search by genre
     */

    fun searchMusicByGenre(query: String): List<MediaMetadataCompat>{
        return searchMusic(MediaMetadataCompat.METADATA_KEY_GENRE, query)
    }


    private fun searchMusic(metadataField: String, search: String): List<MediaMetadataCompat>{
        if(mCurrentState != State.INITIALIZED){
            return emptyList()
        }
        var result = ArrayList<MediaMetadataCompat>()
        var query = search.toLowerCase(Locale.US)
        for(track in mMusicListById!!.values){
            if(track.metadata.getString(metadataField).toLowerCase(Locale.US)
                            .contains(query)){
                result.add(track.metadata)
            }
        }
        return result
    }

    /**
     * Return the [MediaMetadataCompat] for the given musicID
     *
     * @param musicID is the unique, non-heirarchical music ID
     */

    fun getMusic(musicId: String): MediaMetadataCompat? {
        return if (mMusicListById!!.contains(musicId)){
            mMusicListById[musicId]!!.metadata
        }else{
            null
        }
    }
    @Synchronized
    //TODO: use embedded thumbnail
    fun updateMusicArt(musicId: String, albumArt: Bitmap?, icon: Bitmap?) {
        var metadata= MediaMetadataCompat.Builder(getMusic(musicId))
                //Set high resolution bitmpa in METADATA_KEY_ALBYM_ART. This is used, for
                //example on the lockscreen background when the media session is active
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
                //Set small version of the album art in the DISPLAY_ICON. This is used on the
                //MediaDescription and thus it should be small
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, icon)
                .build()
        var mutableMetadata: MutableMediaMetadata = checkNotNull(mMusicListById[musicId]){""+
                "Inconsistent data structures in Music Provider"}

        mutableMetadata.metadata=metadata
    }

    fun setFavourite(musicId: String, favourite: Boolean){
        if(favourite){
            mFavouriteTracks.add(musicId)
        }else{
            mFavouriteTracks.remove(musicId)
        }
    }

    fun isInitialized():Boolean{
        return mCurrentState==State.INITIALIZED
    }

    fun isFavourite(musicId: String): Boolean{
        return mFavouriteTracks.contains(musicId)
    }

    /**
     * Get the list of music tracks from phone and caches teh track information for future reference,
     * keying tracks by musicId adn grouping by genre
     */

    suspend fun retrieveMediaSync(callback: Callback){
        Log.d(TAG, "retrieveMediaAsync called")
        if(mCurrentState==State.INITIALIZED){
            if(callback != null){
                //do nothing, execute call back
                callback.onMusicCatalogReady(true)
            }
            return
        }

        //Asynchronously load teh music catalog in a sepearte thread
        object: AsyncTask<Void, Void, State>(){
            override fun doInBackground(vararg p0: Void?): State {
                retrieveMedia()
                return mCurrentState
            }

            override fun onPostExecute(result: State?) {
                if(callback!=null){
                    callback.onMusicCatalogReady(result == State.INITIALIZED)
                }
            }
        }.execute()
    }

    @Synchronized
    private fun buildListsByGenre(){
        var newMusicListByGenre: ConcurrentMap<String, List<MediaMetadataCompat>> = ConcurrentHashMap()

        for(m in mMusicListById.values){
            var genre = m.metadata.getString(MediaMetadataCompat.METADATA_KEY_GENRE)
            var list = newMusicListByGenre[genre] as MutableList<MediaMetadataCompat>
            if (list == null){
                list= ArrayList()
                newMusicListByGenre.put(genre, list)
            }
            list.add(m.metadata)
        }
        mMusicListByGenre=newMusicListByGenre
    }

    @Synchronized
    private fun retrieveMedia(){
        try{
            if(mCurrentState==State.NON_INITIALIZED){
                mCurrentState=State.INITIALIZING
                var tracks = mSource.iterator()!!
                while(tracks.hasNext()){
                    var item= tracks.next()!!
                    var musicId= item.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
                    mMusicListById[musicId] = MutableMediaMetadata(musicId, item)
                }
            }

        }finally {
            if(mCurrentState != State.INITIALIZED){
                //Something bad ahppened, so we reset state to NON_INITIALIZED to allow
                //retries
                mCurrentState= State.NON_INITIALIZED
            }
        }
    }

    fun getChildren(mediaId: String, resources: Resources): List<MediaBrowserCompat.MediaItem>{
        var mediaItems: MutableList<MediaBrowserCompat.MediaItem> = ArrayList()

        if(MediaIDHelper.isBrowseable(mediaId)){
            return mediaItems
        }

        if(MEDIA_ID_ROOT == mediaId){
            mediaItems.add(createBrowsableMediaItemForRoot(resources))
        } else if (MEDIA_ID_BY_GENRE == mediaId){
            for (genre in getGenres()){
                mediaItems.add(createBrowsableMediaItemForGenre(genre, resources))
            }
        }else if (mediaId.startsWith(MEDIA_ID_BY_GENRE)){
            var genre= MediaIDHelper.getHierarchy(mediaId)[1]
            //TODO: refactor null
            for(metadata in getMusicByGenre(genre)!!){
                mediaItems.add(createMediaItem(metadata))
            }
        }else{
            Log.d(TAG,"Skipping unmatched mediaId: $mediaId")
        }
        return mediaItems
    }

    private fun createBrowsableMediaItemForGenre(genre: String, resources: Resources): MediaBrowserCompat.MediaItem {
        var description = MediaDescriptionCompat.Builder()
                .setMediaId(createMediaID(null, MEDIA_ID_BY_GENRE,genre))
                .setTitle(genre)
                .setSubtitle(resources.getString(R.string.browse_musics_by_genre_subtitle,genre))
                .build()
        return MediaBrowserCompat.MediaItem(description,MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    private fun createMediaItem(metadata: MediaMetadataCompat): MediaBrowserCompat.MediaItem {
        /**
         * Since mediaMetadata fields are immutable, we need to create a copy, so we
         * can set a hierarchy=aware mediaID. We will need to know the media hierarchy when we
         * get a onPlayFromMusicID call, so we can create the proper queue based on where
         * the music was selected from (by artist, genre, album, etc.)
         *
         */

        var genre= metadata.getString(MediaMetadataCompat.METADATA_KEY_GENRE)
        var hierarchyAwareMediaID = MediaIDHelper.createMediaID(
                metadata.description.mediaId, MEDIA_ID_BY_GENRE,genre)
        var copy = MediaMetadataCompat.Builder(metadata)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, hierarchyAwareMediaID)
                .build()
        return MediaBrowserCompat.MediaItem(copy.description,MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    private fun createBrowsableMediaItemForRoot(resources: Resources): MediaBrowserCompat.MediaItem {
        var description= MediaDescriptionCompat.Builder()
                .setMediaId(MEDIA_ID_BY_GENRE)
                .setTitle(resources.getString(R.string.browse_genres))
                .setSubtitle(resources.getString(R.string.browse_genre_subtitle))
                .setIconUri(Uri.parse("android.resource://"+
                    "com.example.myplayer/drawable/ic_recommended"))
                .build()
        return MediaBrowserCompat.MediaItem(description,MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

}
private const val TAG = "MusicProvider"