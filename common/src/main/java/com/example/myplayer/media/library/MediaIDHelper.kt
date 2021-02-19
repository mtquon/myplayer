package com.example.myplayer.media.library

import android.app.Activity
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.text.TextUtils
import androidx.annotation.NonNull
import org.w3c.dom.Text
import java.util.*

/**
 * Utility class to help queue related tasks
 */
object MediaIDHelper {

    /**
     * Create a String value that represents a playable or a browseable media.
     *
     * Encode the media browseable categories, if any, and the unique music ID, if any,
     * into a single string mediaID
     *
     * MediaIDs are of the form <categoryType>/<categoryValue>|<musicUniqueId>, to make it easy
     * to find the category(like genre) that a msuic was selected from, so we can correctly build
     * the playing queue. This is useful when one music can appear in more than one list,
     * like "by genre -> genre_1" and "by_artist->artist_1"
     *
     * @param musicID unqiue music ID for playable items, or null for browsable items.
     * @param categories heirarchy of categories representign this item's browsing parents
     * @return a heirarchy-aware media ID
     */

    fun createMediaID(musicID: String?, vararg categories: String):String{
        var sb= StringBuilder()
        if(categories != null){
            for (i in 0..categories.size){
                if(!isValidCategory(categories[i])){
                    throw IllegalArgumentException("Invalid category: " + categories[i])
                }
                sb.append(categories[i])
                if(i<categories.size-1){
                    sb.append(CATEGORY_SEPARATOR)
                }
            }
        }
        if(musicID!=null){
            sb.append(LEAF_SEPARATOR).append(musicID)
        }
        return sb.toString()
    }

    private fun isValidCategory(category: String): Boolean {
        return category == null ||
                (
                        category.indexOf(CATEGORY_SEPARATOR)<0 &&
                        category.indexOf(LEAF_SEPARATOR)<0
                        )
    }

    /**
     * Extracts unique musicID from the mediaID. mediaID is, by this sample's convention a
     * concatenation of category (eg "by_benre"), categoryValue (eg Classical) and unique musicID.
     * This is necessary so we know where the user selected the music from, when the music exists in
     * more than one music list, and thus we are able to correctly build the playing queue.
     *
     * @param mediaID that contains the musicID
     * @return musicID
     */

    fun extractMusicIDFromMediaId(@NonNull mediaID: String): String? {
        var pos=mediaID.indexOf(LEAF_SEPARATOR)
        if(pos>=0){
            return mediaID.substring(pos + 1)
        }
        return null
    }

    /**
     * Extracts category and categoryValue from the mediaID.
     *
     * @param mediaID that contains a category and categoryValue
     */

    @NonNull
    fun getHierarchy(@NonNull mediaID: String): Array<String> {
        var mediaID = mediaID
        var pos = mediaID.indexOf(LEAF_SEPARATOR)
        if (pos>=0){

            mediaID = mediaID.substring(0, pos)
        }
        return mediaID.split(CATEGORY_SEPARATOR.toString()).toTypedArray()
    }

    fun extractBrowseCategoryValueFromMediaId(@NonNull mediaID: String): String?{
        var hierarchy = getHierarchy(mediaID)
        if(hierarchy.size ==2){
            return hierarchy[1]
        }
        return null
    }

    fun isBrowseable(@NonNull mediaID: String) : Boolean{
        return mediaID.indexOf(LEAF_SEPARATOR)<0
    }


    fun getParentMediaID(@NonNull mediaID: String): String {
        val hierarchy: Array<String> = getHierarchy(mediaID)
        if (!isBrowseable(mediaID)) {
            return createMediaID(null, *hierarchy)
        }
        if (hierarchy.size <= 1) {
            return MEDIA_ID_ROOT
        }

        //TODO: change below to kotlin copyof
        val parentHierarchy = Arrays.copyOf(hierarchy, hierarchy.size - 1)
        return createMediaID(null, *parentHierarchy)

    }
    /**
     * Determine if media item is playing (matches the currently playing item).
     *
     * @param context for retrieving the [MediaContollerCompat]
     * @param mediaItem to compare to currently playing [MediaBrowseCompat.MediaItem]
     * @return boolean indicating whether media item matches currently playing media item
     */

    fun isMediaItemPlaying(context: Activity, mediaItem: MediaBrowserCompat.MediaItem): Boolean{
        //Media item is considered to be playing or paused based on the controller's current
        // media id

        var controller = MediaControllerCompat.getMediaController(context)
        if(controller != null && controller.metadata != null){
            //todo:refactor this
            var currentPlayingMediaId = controller.metadata.description.mediaId
            val itemMusicId: String? = MediaIDHelper.extractMusicIDFromMediaId(
                    mediaItem.description.mediaId!!)
            if(currentPlayingMediaId != null && TextUtils.equals(currentPlayingMediaId,itemMusicId)){
                return true
            }
        }
        return false
    }
}
//Media IDs used on browseable items of MediaBrowser
const val MEDIA_ID_EMPTY_ROOT = "__EMPTY_ROOT__"
const val  MEDIA_ID_ROOT="__ROOT__"
const val MEDIA_ID_BY_GENRE="__BY_GENRE__"
const val MEDIA_ID_BY_SEARCH="__BY_SEARCH__"

private const val  CATEGORY_SEPARATOR = '/'
private const val LEAF_SEPARATOR ='|'