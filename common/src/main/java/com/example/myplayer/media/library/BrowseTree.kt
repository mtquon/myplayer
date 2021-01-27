package com.example.myplayer.media.library

import android.content.Context
import android.media.browse.MediaBrowser
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaMetadataCompat
import com.example.myplayer.media.R
import com.example.myplayer.media.extensions.*

/**
 * Represent a tree of media that's used by [MusicService.onLoadChildren].
 *
 * [BrowseTree] maps a media id (see: [MediaMetadataCompat.METADATA_KEY_MEDIA_ID]) to one (or
 * more) [MediaMetadataCompat] objects, which are children of that media id.
 *
 * For example, given the following tree:
 * root
 * +--Albums
 * |    +-- Album_A
 * |    |   +-- Song_1
 * |    |   +-- Song_2
 * ...
 * +--Artists
 * ...
 *
 * Request `browseTree["root"]` would return a list that included "Albums, "Artists, and any other
 * direct children. Taking the media ID of "Albums" ("Albums" in this example),
 * `browsetree["Albums"]` would return a single item list "Album_A", and finally,
 * `browsetree["Album_A]` would return "Song_1" and "Song_2". Since those are leaf nodes,
 * requesting `browsetree["Song_1"] would return null (there aren't any children of it)
 *
 *
 */

class BrowseTree(
    val context: Context,
    musicSource: MusicSource,
    val recentMediaId: String ? = null
) {
    private val mediaIdToChildren= mutableMapOf<String,MutableList<MediaMetadataCompat>>()
    /**
     * Whether to allow clients which are unknown to use search on this [BrowseTree]
     */

    val searchableByUnkownCaller= true

    /**
     * In this example we have a single root node (identified by the constant [BROWSABLE_ROOT]).
     * The root's children are each album included in the [MusicSource], and the children of each
     * album are the songs on the album (See [BrowseTree.buildAlbumRoot] for more details.)
     *
     * TODO:Expand to allow more browsing types.
     */

    init{
        val rootList = mediaIdToChildren[BROWSABLE_ROOT] ?: mutableListOf()

        val recommendedMetadata = MediaMetadataCompat.Builder().apply {
            id = RECOMMENDED_ROOT
            title = context.getString(R.string.recommended_title)
            albumArtUri = RESOURCE_ROOT_URI +
                    context.resources.getResourceEntryName(R.drawable.ic_recommended)
            flag = MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        }.build()

        val albumsMetadata = MediaMetadataCompat.Builder().apply {
            id= ALBUMS_ROOT
            title= "Albums"
            albumArtUri= RESOURCE_ROOT_URI +
                    context.resources.getResourceEntryName(R.drawable.ic_album)
            flag= MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        }.build()

        rootList += recommendedMetadata
        rootList+= albumsMetadata
        mediaIdToChildren[BROWSABLE_ROOT] = rootList

        musicSource.forEach{ mediaItem->
            val albumMediaId = mediaItem.album.urlEncoded
            val albumChildren = mediaIdToChildren[albumMediaId] ?: buildAlbumRoot(mediaItem)
            albumChildren += mediaItem

            //Add the first tack of each album to the Recommended catagory
            //TODO: change to to songs
            if(mediaItem.trackNumber ==1L){
                val recommendedChildren = mediaIdToChildren[RECOMMENDED_ROOT] ?: mutableListOf()
                recommendedChildren+= mediaItem
                mediaIdToChildren[RECOMMENDED_ROOT] = recommendedChildren
            }

            //If this was recently played, add it to the recent root.
            if (mediaItem.id == recentMediaId){
                mediaIdToChildren[RECENT_ROOT] = mutableListOf(mediaItem)
            }
        }
    }

    /**
     * Provide access to the list of children with the `get` operator.
     * i.e. : `browsTree\[BROWSABLE_ROOT\]`
     */

    operator fun get(mediaId: String) = mediaIdToChildren[mediaId]

    /**
     * Builds a node, under the root, that represents an album, give a [MediaMetadataCompat] object
     * that's one of the songs on teh album, marking the items as [MediaItem.FLAG_BROWSABLE],
     * since it will have child node(s). AKA at least 1 song
     */
    private fun buildAlbumRoot(mediaItem: MediaMetadataCompat): MutableList<MediaMetadataCompat> {
        val albumMetadata = MediaMetadataCompat.Builder().apply {
            id= mediaItem.album.urlEncoded
            title= mediaItem.album
            artist=mediaItem.artist
            albumArt=mediaItem.albumArt
            albumArtUri=mediaItem.albumArtUri.toString()
            flag= MediaItem.FLAG_BROWSABLE
        }.build()

        // Adds this album to the 'Albums' category.
        val rootList= mediaIdToChildren[ALBUMS_ROOT] ?: mutableListOf()
        rootList+=albumMetadata
        mediaIdToChildren[ALBUMS_ROOT] = rootList

        //Insert the album's root with an empty list for its children, and return the list.
        return mutableListOf<MediaMetadataCompat>().also{
            mediaIdToChildren[albumMetadata.id!!]=it
        }

    }
}

const val BROWSABLE_ROOT="/"
const val RECOMMENDED_ROOT="__RECOMMENDED__"
const val ALBUMS_ROOT ="__ALBUMS__"
const val RECENT_ROOT = "__RECENT__"
const val EMPTY_ROOT ="@empoty@"

const val MEDIA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"

const val RESOURCE_ROOT_URI="android.resource://com.exmaple.myplayer/drawable/"