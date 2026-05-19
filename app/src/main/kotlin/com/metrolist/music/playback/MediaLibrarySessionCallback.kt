/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.offline.Download
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import coil3.imageLoader
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.utils.completed
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.R
import com.metrolist.music.automotive.AutomotiveLoginActivity
import com.metrolist.music.constants.AndroidAutoSectionsOrderKey
import com.metrolist.music.constants.AndroidAutoYouTubePlaylistsKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.MediaSessionConstants
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.ui.screens.settings.AndroidAutoSection
import com.metrolist.music.ui.screens.settings.deserializeSections
import com.metrolist.music.ui.screens.settings.serializeSections
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.plus
import javax.inject.Inject
import com.metrolist.music.automotive.AlbumArtContentProvider

class MediaLibrarySessionCallback
@Inject
constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    val downloadUtil: DownloadUtil,
) : MediaLibrarySession.Callback {
    private val scope = CoroutineScope(Dispatchers.Main) + Job()
    lateinit var service: MusicService
    var toggleLike: () -> Unit = {}
    var toggleStartRadio: () -> Unit = {}
    var toggleLibrary: () -> Unit = {}
    var addToTargetPlaylist: () -> Unit = {}

    @Volatile
    private var lastSearchSongs: List<SongItem> = emptyList()
    @Volatile
    private var lastRecommendedSongs: List<SongItem> = emptyList()

    fun release() {
        scope.cancel()
    }

    /**
     * SongItem 을 MediaItem 으로 변환.
     * 썸네일은 ArtworkProvider 의 content:// URI 를 사용한다.
     */
    private fun SongItem.toCarMediaItem(parentId: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId("$parentId/$id")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(artists.joinToString(", ") { it.name })
                    .setArtist(artists.joinToString(", ") { it.name })
                    .setArtworkUri(thumbnail?.toUri()?.let { AlbumArtContentProvider.mapUri(it) })
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            ).build()
    }

    /**
     * 브라우저블 MediaItem (Artists/Albums 폴더용) 을 만든다.
     * 썸네일은 ArtworkProvider 의 content:// URI 를 사용한다.
     */
    private fun browsableMediaItemWithArtwork(
        id: String,
        title: String,
        subtitle: String?,
        artworkUrl: String?,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
    ): MediaItem {
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtist(subtitle)
                    .setArtworkUri(artworkUrl?.toUri()?.let { AlbumArtContentProvider.mapUri(it) })
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(mediaType)
                    .build()
            ).build()
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val connectionResult = super.onConnect(session, controller)
        return MediaSession.ConnectionResult.accept(
            connectionResult.availableSessionCommands
                .buildUpon()
                .add(MediaSessionConstants.CommandToggleLike)
                .add(MediaSessionConstants.CommandToggleStartRadio)
                .add(MediaSessionConstants.CommandToggleLibrary)
                .add(MediaSessionConstants.CommandToggleShuffle)
                .add(MediaSessionConstants.CommandToggleRepeatMode)
                .add(MediaSessionConstants.CommandAddToTargetPlaylist)
                .build(),
            connectionResult.availablePlayerCommands,
        )
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            MediaSessionConstants.ACTION_TOGGLE_LIKE -> toggleLike()
            MediaSessionConstants.ACTION_TOGGLE_START_RADIO -> toggleStartRadio()
            MediaSessionConstants.ACTION_TOGGLE_LIBRARY -> toggleLibrary()
            MediaSessionConstants.ACTION_TOGGLE_SHUFFLE -> session.player.shuffleModeEnabled =
                !session.player.shuffleModeEnabled

            MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE -> session.player.toggleRepeatMode()
            MediaSessionConstants.ACTION_ADD_TO_TARGET_PLAYLIST -> addToTargetPlaylist()
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    @Deprecated("Deprecated in MediaLibrarySession.Callback")
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaItemsWithStartPosition> {
        return SettableFuture.create<MediaItemsWithStartPosition>()
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(
            LibraryResult.ofItem(
                MediaItem
                    .Builder()
                    .setMediaId(MusicService.ROOT)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setIsPlayable(false)
                            .setIsBrowsable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build(),
                    ).build(),
                params,
            ),
        )

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future(Dispatchers.IO) {
            android.util.Log.d("PTUBE", "onGetChildren: parentId=$parentId")
            val items: List<MediaItem> = when (parentId) {
                MusicService.ROOT -> {
                    listOf(
                        browsableMediaItem(
                            MusicService.RECOMMENDED,
                            context.getString(R.string.android_auto_recommended),
                            null,
                            drawableUri(R.drawable.explore_outlined),
                            MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                        ),
                        browsableMediaItem(
                            MusicService.LIBRARY,
                            "Library",
                            null,
                            drawableUri(R.drawable.queue_music),
                            MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                        ),
                    )
                }

                MusicService.SONG -> {
                    val songs: List<SongItem> = try {
                        YouTube.library("FEmusic_liked_videos").completed().getOrNull()
                            ?.items?.filterIsInstance<SongItem>()
                            ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                            ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                            ?: emptyList()
                    } catch (e: Exception) {
                        reportException(e)
                        emptyList()
                    }

                    val shuffleItem: MediaItem = MediaItem.Builder()
                        .setMediaId("${MusicService.SONG}/${MusicService.SHUFFLE_ACTION}")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(context.getString(R.string.shuffle))
                                .setArtworkUri(drawableUri(R.drawable.shuffle))
                                .setIsPlayable(true)
                                .setIsBrowsable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .build()
                        ).build()

                    val songItems: List<MediaItem> = songs.map { it.toCarMediaItem(MusicService.SONG) }

                    listOf(shuffleItem) + songItems
                }

                MusicService.ARTIST -> {
                    val artistList: List<ArtistItem> = try {
                        YouTube.library("FEmusic_library_corpus_artists").completed().getOrNull()
                            ?.items?.filterIsInstance<ArtistItem>()
                            ?: emptyList()
                    } catch (e: Exception) {
                        reportException(e)
                        emptyList()
                    }

                    android.util.Log.d("PTUBE", "ARTIST count=${artistList.size}, first=${artistList.firstOrNull()?.title}")

                    artistList.map { artist ->
                        browsableMediaItemWithArtwork(
                            "${MusicService.ARTIST}/${artist.id}",
                            artist.title,
                            null,
                            artist.thumbnail,
                            MediaMetadata.MEDIA_TYPE_ARTIST,
                        )
                    }
                }

                MusicService.ALBUM -> {
                    val albumList: List<AlbumItem> = try {
                        YouTube.library("FEmusic_liked_albums").completed().getOrNull()
                            ?.items?.filterIsInstance<AlbumItem>()
                            ?: emptyList()
                    } catch (e: Exception) {
                        reportException(e)
                        emptyList()
                    }

                    albumList.map { album ->
                        browsableMediaItemWithArtwork(
                            "${MusicService.ALBUM}/${album.id}",
                            album.title,
                            album.artists?.joinToString(", ") { it.name },
                            album.thumbnail,
                            MediaMetadata.MEDIA_TYPE_ALBUM,
                        )
                    }
                }

                MusicService.PLAYLIST -> {
                    val likedSongCount = database.likedSongsCount().first()
                    val downloadedSongCount = downloadUtil.downloads.value.size

                    listOf(
                        browsableMediaItem(
                            "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}",
                            context.getString(R.string.liked_songs),
                            context.resources.getQuantityString(R.plurals.n_song, likedSongCount, likedSongCount),
                            drawableUri(R.drawable.favorite),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST,
                        ),
                        browsableMediaItem(
                            "${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}",
                            context.getString(R.string.downloaded_songs),
                            context.resources.getQuantityString(R.plurals.n_song, downloadedSongCount, downloadedSongCount),
                            drawableUri(R.drawable.download),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST,
                        ),
                    ) + database.playlistsByCreateDateAsc().first().map { playlist ->
                        browsableMediaItem(
                            "${MusicService.PLAYLIST}/${playlist.id}",
                            playlist.playlist.name,
                            context.resources.getQuantityString(R.plurals.n_song, playlist.songCount, playlist.songCount),
                            playlist.thumbnails.firstOrNull()?.toUri(),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST,
                        )
                    }
                }

                MusicService.YOUTUBE_PLAYLIST -> {
                    if (!context.dataStore.get(AndroidAutoYouTubePlaylistsKey, false)) {
                        emptyList()
                    } else {
                        try {
                            val allSections = mutableListOf<com.metrolist.innertube.pages.HomePage.Section>()
                            var continuation: String? = null
                            val maxPages = 4

                            for (page in 0 until maxPages) {
                                val result = YouTube.home(continuation)
                                    .onFailure { reportException(it) }
                                    .getOrNull() ?: break
                                allSections.addAll(result.sections)
                                continuation = result.continuation
                                if (continuation == null) break
                            }

                            val savedBrowseIds = database.playlistsByCreateDateAsc()
                                .first()
                                .mapNotNullTo(mutableSetOf()) { it.playlist.browseId }

                            val playlists = allSections
                                .flatMap { it.items }
                                .filterIsInstance<PlaylistItem>()
                                .filterNot { it.id in savedBrowseIds }
                                .distinctBy { it.id }

                            playlists.map { playlist ->
                                browsableMediaItemWithArtwork(
                                    "${MusicService.YOUTUBE_PLAYLIST}/${playlist.id}",
                                    playlist.title,
                                    playlist.author?.name,
                                    playlist.thumbnail,
                                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                )
                            }

                        } catch (e: Exception) {
                            reportException(e)
                            emptyList()
                        }
                    }
                }

                MusicService.LIBRARY -> {
                    listOf(
                        browsableMediaItem(
                            MusicService.PLAYLIST,
                            context.getString(R.string.playlists),
                            null,
                            drawableUri(R.drawable.queue_music),
                            MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                        ),
                        browsableMediaItem(
                            MusicService.ARTIST,
                            context.getString(R.string.artists),
                            null,
                            drawableUri(R.drawable.artist),
                            MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                        ),
                        browsableMediaItem(
                            MusicService.SUBSCRIPTION,
                            "Subscriptions",
                            null,
                            drawableUri(R.drawable.explore_outlined),
                            MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                        ),
                    )
                }

                MusicService.SUBSCRIPTION -> {
                    listOf<MediaItem>()
                }
                MusicService.RECOMMENDED -> {
                    try {
                        val allSections = mutableListOf<com.metrolist.innertube.pages.HomePage.Section>()
                        var continuation: String? = null
                        val maxPages = 4

                        for (page in 0 until maxPages) {
                            val result = YouTube.home(continuation)
                                .onFailure { reportException(it) }
                                .getOrNull() ?: break
                            allSections.addAll(result.sections)
                            continuation = result.continuation
                            if (continuation == null) break
                        }

                        val songs = allSections
                            .flatMap { it.items }
                            .filterIsInstance<SongItem>()
                            .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                            .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                            .distinctBy { it.id }

                        lastRecommendedSongs = songs

                        android.util.Log.d("PTUBE", "RECOMMENDED songs count=${songs.size}")

                        val shuffleItem: MediaItem = MediaItem.Builder()
                            .setMediaId("${MusicService.RECOMMENDED}/${MusicService.SHUFFLE_ACTION}")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(context.getString(R.string.shuffle))
                                    .setArtworkUri(drawableUri(R.drawable.shuffle))
                                    .setIsPlayable(true)
                                    .setIsBrowsable(false)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                    .build()
                            ).build()

                        listOf(shuffleItem) + songs.map { it.toCarMediaItem(MusicService.RECOMMENDED) }
                    } catch (e: Exception) {
                        reportException(e)
                        emptyList()
                    }
                }

                else -> {
                    when {
                        parentId.startsWith("${MusicService.ARTIST}/") -> {
                            val artistId = parentId.removePrefix("${MusicService.ARTIST}/")
                            val songs: List<SongItem> = try {
                                YouTube.artist(artistId).getOrNull()?.sections
                                    ?.flatMap { it.items }
                                    ?.filterIsInstance<SongItem>()
                                    ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                    ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                    ?: emptyList()
                            } catch (e: Exception) {
                                reportException(e)
                                emptyList()
                            }

                            val shuffleItem: MediaItem = MediaItem.Builder()
                                .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(context.getString(R.string.shuffle))
                                        .setArtworkUri(drawableUri(R.drawable.shuffle))
                                        .setIsPlayable(true)
                                        .setIsBrowsable(false)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                        .build()
                                ).build()

                            val songItems: List<MediaItem> = songs.map { it.toCarMediaItem(parentId) }

                            listOf(shuffleItem) + songItems
                        }

                        parentId.startsWith("${MusicService.ALBUM}/") -> {
                            val albumId = parentId.removePrefix("${MusicService.ALBUM}/")
                            val songs: List<SongItem> = try {
                                YouTube.album(albumId).getOrNull()?.songs
                                    ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                    ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                    ?: emptyList()
                            } catch (e: Exception) {
                                reportException(e)
                                emptyList()
                            }

                            val shuffleItem: MediaItem = MediaItem.Builder()
                                .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(context.getString(R.string.shuffle))
                                        .setArtworkUri(drawableUri(R.drawable.shuffle))
                                        .setIsPlayable(true)
                                        .setIsBrowsable(false)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                        .build()
                                ).build()

                            val songItems: List<MediaItem> = songs.map { it.toCarMediaItem(parentId) }

                            listOf(shuffleItem) + songItems
                        }

                        parentId.startsWith("${MusicService.PLAYLIST}/") -> {
                            val playlistId = parentId.removePrefix("${MusicService.PLAYLIST}/")

                            if (playlistId == PlaylistEntity.LIKED_PLAYLIST_ID) {
                                val songs: List<SongItem> = try {
                                    YouTube.library("FEmusic_liked_videos").completed().getOrNull()
                                        ?.items?.filterIsInstance<SongItem>()
                                        ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                        ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                        ?: emptyList()
                                } catch (e: Exception) {
                                    reportException(e)
                                    emptyList()
                                }

                                val shuffleItem: MediaItem = MediaItem.Builder()
                                    .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(context.getString(R.string.shuffle))
                                            .setArtworkUri(drawableUri(R.drawable.shuffle))
                                            .setIsPlayable(true)
                                            .setIsBrowsable(false)
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                            .build()
                                    ).build()

                                val songItems: List<MediaItem> = songs.map { it.toCarMediaItem(parentId) }

                                listOf(shuffleItem) + songItems
                            } else {
                                val songs: List<Song> = when (playlistId) {
                                    PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> {
                                        val downloads = downloadUtil.downloads.value
                                        database
                                            .allSongs()
                                            .flowOn(Dispatchers.IO)
                                            .map { allSongs ->
                                                allSongs.filter {
                                                    downloads[it.id]?.state == Download.STATE_COMPLETED
                                                }
                                            }.map { filteredSongs ->
                                                filteredSongs
                                                    .map { it to downloads[it.id] }
                                                    .sortedBy { it.second?.updateTimeMs ?: 0L }
                                                    .map { it.first }
                                            }.first()
                                    }
                                    else -> database.playlistSongs(playlistId).map { list ->
                                        list.map { it.song }
                                    }.first()
                                }

                                val shuffleItem: MediaItem = MediaItem.Builder()
                                    .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(context.getString(R.string.shuffle))
                                            .setArtworkUri(drawableUri(R.drawable.shuffle))
                                            .setIsPlayable(true)
                                            .setIsBrowsable(false)
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                            .build()
                                    ).build()

                                listOf(shuffleItem) + songs.map { it.toMediaItem(parentId) }
                            }
                        }

                        parentId.startsWith("${MusicService.YOUTUBE_PLAYLIST}/") -> {
                            val playlistId = parentId.removePrefix("${MusicService.YOUTUBE_PLAYLIST}/")
                            val songs: List<SongItem> = try {
                                YouTube.playlist(playlistId).getOrNull()?.songs
                                    ?.take(100)
                                    ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                    ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                    ?: emptyList()
                            } catch (e: Exception) {
                                reportException(e)
                                emptyList()
                            }

                            val shuffleItem: MediaItem = MediaItem.Builder()
                                .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(context.getString(R.string.shuffle))
                                        .setArtworkUri(drawableUri(R.drawable.shuffle))
                                        .setIsPlayable(true)
                                        .setIsBrowsable(false)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                        .build()
                                ).build()

                            val songItems: List<MediaItem> = songs.map { it.toCarMediaItem(parentId) }

                            listOf(shuffleItem) + songItems
                        }

                        parentId.startsWith("${MusicService.RECOMMENDED}/") -> {
                            try {
                                val allSections = mutableListOf<com.metrolist.innertube.pages.HomePage.Section>()
                                var continuation: String? = null
                                val maxPages = 4

                                for (page in 0 until maxPages) {
                                    val result = YouTube.home(continuation)
                                        .onFailure { reportException(it) }
                                        .getOrNull() ?: break
                                    allSections.addAll(result.sections)
                                    continuation = result.continuation
                                    if (continuation == null) break
                                }

                                val songs = allSections
                                    .flatMap { it.items }
                                    .filterIsInstance<SongItem>()
                                    .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                    .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                    .distinctBy { it.id }

                                val shuffleItem: MediaItem = MediaItem.Builder()
                                    .setMediaId("${MusicService.RECOMMENDED}/${MusicService.SHUFFLE_ACTION}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(context.getString(R.string.shuffle))
                                            .setArtworkUri(drawableUri(R.drawable.shuffle))
                                            .setIsPlayable(true)
                                            .setIsBrowsable(false)
                                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                            .build()
                                    ).build()

                                listOf(shuffleItem) + songs.map { it.toCarMediaItem(MusicService.RECOMMENDED) }
                            } catch (e: Exception) {
                                reportException(e)
                                emptyList()
                            }
                        }

                        else -> emptyList()
                    }
                }
            }

            LibraryResult.ofItemList(items, params)
        }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future(Dispatchers.IO) {
            database.song(mediaId).first()?.toMediaItem()?.let {
                LibraryResult.ofItem(it, null)
            } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
        }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        session.notifySearchResultChanged(browser, query, 1, params)
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return scope.future(Dispatchers.IO) {
            if (query.isEmpty()) {
                return@future LibraryResult.ofItemList(emptyList<MediaItem>(), params)
            }

            try {
                val searchResults = mutableListOf<MediaItem>()

                val localSongs = database.allSongs().first().filter { song ->
                    song.song.title.contains(query, ignoreCase = true) ||
                            song.artists.any { it.name.contains(query, ignoreCase = true) } ||
                            song.album?.title?.contains(query, ignoreCase = true) == true
                }

                val artistSongs = database.searchArtists(query).first().flatMap { artist ->
                    database.artistSongsByCreateDateAsc(artist.id).first()
                }

                val albumSongs = database.searchAlbums(query).first().flatMap { album ->
                    database.albumSongs(album.id).first()
                }

                val playlistSongs = database.searchPlaylists(query).first().flatMap { playlist ->
                    database.playlistSongs(playlist.id).first().map { it.song }
                }

                val allLocalSongs = (localSongs + artistSongs + albumSongs + playlistSongs)
                    .distinctBy { it.id }

                allLocalSongs.forEach { song ->
                    searchResults.add(song.toMediaItem(
                        path = "${MusicService.SEARCH}/$query",
                        isPlayable = true,
                        isBrowsable = true
                    ))
                }

                try {
                    val onlineResults = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                        .getOrNull()
                        ?.items
                        ?.filterIsInstance<SongItem>()
                        ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                        ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                        ?.filter { onlineSong ->
                            !allLocalSongs.any { localSong ->
                                localSong.id == onlineSong.id ||
                                        (localSong.song.title.equals(onlineSong.title, ignoreCase = true) &&
                                                localSong.artists.any { artist ->
                                                    onlineSong.artists.any {
                                                        it.name.equals(artist.name, ignoreCase = true)
                                                    }
                                                })
                            }
                        } ?: emptyList()

                    lastSearchSongs = onlineResults

                    onlineResults.forEach { songItem ->
                        try {
                            database.query { insert(songItem.toMediaMetadata()) }
                        } catch (e: Exception) {
                        }

                        searchResults.add(
                            MediaItem.Builder()
                                .setMediaId("${MusicService.SEARCH}/$query/${songItem.id}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(songItem.title)
                                        .setSubtitle(songItem.artists.joinToString(", ") { it.name })
                                        .setArtist(songItem.artists.joinToString(", ") { it.name })
                                        .setArtworkUri(songItem.thumbnail.toUri().let { AlbumArtContentProvider.mapUri(it) })
                                        .setIsPlayable(true)
                                        .setIsBrowsable(true)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                        .build()
                                )
                                .build()
                        )
                    }
                } catch (e: Exception) {
                    reportException(e)
                }

                LibraryResult.ofItemList(searchResults, params)

            } catch (e: Exception) {
                reportException(e)
                LibraryResult.ofItemList(emptyList<MediaItem>(), params)
            }
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> =
        scope.future {
            val defaultResult = MediaItemsWithStartPosition(emptyList<MediaItem>(), startIndex, startPositionMs)
            android.util.Log.d("PTUBE", "onSetMediaItems called: mediaId=${mediaItems.firstOrNull()?.mediaId}, count=${mediaItems.size}")
            val voiceQuery = mediaItems.firstOrNull()?.requestMetadata?.searchQuery

            val path = if (!voiceQuery.isNullOrBlank()) {
                listOf(MusicService.SEARCH, voiceQuery, "")
            } else {
                mediaItems.firstOrNull()?.mediaId?.split("/")
            } ?: return@future defaultResult

            when (path.firstOrNull()) {
                MusicService.SONG -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    try {
                        val ytSongs: List<SongItem> = YouTube.library("FEmusic_liked_videos").completed().getOrNull()
                            ?.items?.filterIsInstance<SongItem>()
                            ?: emptyList()

                        if (songId == MusicService.SHUFFLE_ACTION) {
                            MediaItemsWithStartPosition(
                                ytSongs.shuffled().map { it.toMediaItem() },
                                0,
                                C.TIME_UNSET
                            )
                        } else {
                            MediaItemsWithStartPosition(
                                ytSongs.map { it.toMediaItem() },
                                ytSongs.indexOfFirst { it.id == songId }.takeIf { idx -> idx != -1 } ?: 0,
                                startPositionMs
                            )
                        }
                    } catch (e: Exception) {
                        reportException(e)
                        defaultResult
                    }
                }

                MusicService.ARTIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val artistId = path.getOrNull(1) ?: return@future defaultResult
                    try {
                        val ytSongs: List<SongItem> = YouTube.artist(artistId).getOrNull()?.sections
                            ?.flatMap { it.items }
                            ?.filterIsInstance<SongItem>()
                            ?: emptyList()

                        if (songId == MusicService.SHUFFLE_ACTION) {
                            MediaItemsWithStartPosition(
                                ytSongs.shuffled().map { it.toMediaItem() },
                                0,
                                C.TIME_UNSET
                            )
                        } else {
                            MediaItemsWithStartPosition(
                                ytSongs.map { it.toMediaItem() },
                                ytSongs.indexOfFirst { it.id == songId }.takeIf { idx -> idx != -1 } ?: 0,
                                startPositionMs
                            )
                        }
                    } catch (e: Exception) {
                        reportException(e)
                        defaultResult
                    }
                }

                MusicService.ALBUM -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val albumId = path.getOrNull(1) ?: return@future defaultResult
                    try {
                        val ytSongs: List<SongItem> = YouTube.album(albumId).getOrNull()?.songs
                            ?: emptyList()

                        if (songId == MusicService.SHUFFLE_ACTION) {
                            MediaItemsWithStartPosition(
                                ytSongs.shuffled().map { it.toMediaItem() },
                                0,
                                C.TIME_UNSET
                            )
                        } else {
                            MediaItemsWithStartPosition(
                                ytSongs.map { it.toMediaItem() },
                                ytSongs.indexOfFirst { it.id == songId }.takeIf { idx -> idx != -1 } ?: 0,
                                startPositionMs
                            )
                        }
                    } catch (e: Exception) {
                        reportException(e)
                        defaultResult
                    }
                }

                MusicService.PLAYLIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val playlistId = path.getOrNull(1) ?: return@future defaultResult

                    if (playlistId == PlaylistEntity.LIKED_PLAYLIST_ID) {
                        return@future try {
                            val ytSongs: List<SongItem> = YouTube.library("FEmusic_liked_videos").completed().getOrNull()
                                ?.items?.filterIsInstance<SongItem>()
                                ?: emptyList()

                            android.util.Log.d("PTUBE", "LIKED playback: ytSongs.size=${ytSongs.size}, songId=$songId, firstTitle=${ytSongs.firstOrNull()?.title}")

                            if (songId == MusicService.SHUFFLE_ACTION) {
                                MediaItemsWithStartPosition(
                                    ytSongs.shuffled().map { it.toMediaItem() },
                                    0,
                                    C.TIME_UNSET
                                )
                            } else {
                                MediaItemsWithStartPosition(
                                    ytSongs.map { it.toMediaItem() },
                                    ytSongs.indexOfFirst { it.id == songId }.takeIf { idx -> idx != -1 } ?: 0,
                                    startPositionMs
                                )
                            }
                        } catch (e: Exception) {
                            reportException(e)
                            defaultResult
                        }
                    }

                    val songs: List<Song> = when (playlistId) {
                        PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> {
                            val downloads = downloadUtil.downloads.value
                            database
                                .allSongs()
                                .flowOn(Dispatchers.IO)
                                .map { allSongs ->
                                    allSongs.filter {
                                        downloads[it.id]?.state == Download.STATE_COMPLETED
                                    }
                                }.map { filteredSongs ->
                                    filteredSongs
                                        .map { it to downloads[it.id] }
                                        .sortedBy { it.second?.updateTimeMs ?: 0L }
                                        .map { it.first }
                                }.first()
                        }
                        else -> database.playlistSongs(playlistId).map { list ->
                            list.map { it.song }
                        }.first()
                    }

                    if (songId == MusicService.SHUFFLE_ACTION) {
                        MediaItemsWithStartPosition(
                            songs.shuffled().map { it.toMediaItem() },
                            0,
                            C.TIME_UNSET
                        )
                    } else {
                        MediaItemsWithStartPosition(
                            songs.map { it.toMediaItem() },
                            songs.indexOfFirst { it.id == songId }.takeIf { idx -> idx != -1 } ?: 0,
                            startPositionMs
                        )
                    }
                }

                MusicService.YOUTUBE_PLAYLIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val playlistId = path.getOrNull(1) ?: return@future defaultResult

                    val songs: List<MediaItem> = try {
                        YouTube.playlist(playlistId).getOrNull()?.songs?.map {
                            it.toMediaItem()
                        } ?: emptyList()
                    } catch (e: Exception) {
                        reportException(e)
                        return@future defaultResult
                    }

                    if (songId == MusicService.SHUFFLE_ACTION) {
                        MediaItemsWithStartPosition(
                            songs.shuffled(),
                            0,
                            C.TIME_UNSET
                        )
                    } else {
                        MediaItemsWithStartPosition(
                            songs,
                            songs.indexOfFirst { it.mediaId.endsWith(songId) }.takeIf { idx -> idx != -1 } ?: 0,
                            C.TIME_UNSET
                        )
                    }
                }

                MusicService.RECOMMENDED -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    val ytSongs = lastRecommendedSongs
                    if (ytSongs.isEmpty()) return@future defaultResult

                    if (songId == MusicService.SHUFFLE_ACTION) {
                        MediaItemsWithStartPosition(
                            ytSongs.shuffled().map { it.toMediaItem() },
                            0,
                            C.TIME_UNSET
                        )
                    } else {
                        MediaItemsWithStartPosition(
                            ytSongs.map { it.toMediaItem() },
                            ytSongs.indexOfFirst { it.id == songId }.takeIf { idx -> idx != -1 } ?: 0,
                            C.TIME_UNSET
                        )
                    }
                }

                MusicService.SEARCH -> {
                    val rawId = path.lastOrNull()
                    val songId = rawId ?: ""

                    fun debugItem(msg: String): MediaItemsWithStartPosition {
                        val di = MediaItem.Builder()
                            .setMediaId("debug/${System.currentTimeMillis()}")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(msg)
                                    .setArtist("DEBUG")
                                    .setIsPlayable(true)
                                    .setIsBrowsable(false)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                    .build()
                            ).build()
                        return MediaItemsWithStartPosition(listOf(di), 0, C.TIME_UNSET)
                    }

                    if (rawId == null) {
                        return@future debugItem("DEBUG-1: path 비었음 path=${path.joinToString("|")}")
                    }
                    if (songId.isBlank() || songId == MusicService.SEARCH) {
                        return@future debugItem("DEBUG-2: songId 비정상 path=${path.joinToString("|")}")
                    }

                    val song = try {
                        database.song(songId).first()
                    } catch (e: Exception) {
                        return@future debugItem("DEBUG-3: DB조회 예외 ${e.message}")
                    }

                    if (song != null) {
                        return@future MediaItemsWithStartPosition(
                            listOf(song.toMediaItem()),
                            0,
                            C.TIME_UNSET
                        )
                    }

                    val ytSong = try {
                        YouTube.search(songId, YouTube.SearchFilter.FILTER_SONG)
                            .getOrNull()
                            ?.items
                            ?.filterIsInstance<SongItem>()
                            ?.firstOrNull { it.id == songId }
                    } catch (e: Exception) {
                        return@future debugItem("DEBUG-4: 네트워크 예외 ${e.message}")
                    }

                    if (ytSong != null) {
                        return@future MediaItemsWithStartPosition(
                            listOf(ytSong.toMediaItem()),
                            0,
                            C.TIME_UNSET
                        )
                    }

                    return@future debugItem("DEBUG-5: DB없음+네트워크없음 songId=$songId")
                }

                else -> defaultResult
            }
        }

    private fun drawableUri(
        @DrawableRes id: Int,
    ) = Uri
        .Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.resources.getResourcePackageName(id))
        .appendPath(context.resources.getResourceTypeName(id))
        .appendPath(context.resources.getResourceEntryName(id))
        .build()

    private fun browsableMediaItem(
        id: String,
        title: String,
        subtitle: String?,
        iconUri: Uri?,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
    ) = MediaItem
        .Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(iconUri)
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setMediaType(mediaType)
                .build(),
        ).build()

    private fun Song.toMediaItem(path: String, isPlayable: Boolean = true, isBrowsable: Boolean = false): MediaItem {
        return MediaItem
            .Builder()
            .setMediaId("$path/$id")
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(song.title)
                    .setSubtitle(artists.joinToString { it.name })
                    .setArtist(artists.joinToString { it.name })
                    .setArtworkUri(song.thumbnailUrl?.toUri()?.let { AlbumArtContentProvider.mapUri(it) })
                    .setIsPlayable(isPlayable)
                    .setIsBrowsable(isBrowsable)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            ).build()
    }
}