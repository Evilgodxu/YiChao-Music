package com.yichao.evilgodxu.screens.home.home_assembly.playlist_area

import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.screens.home.data.PlaylistGroup

// 按 id 集合从全量曲目中解析曲目，保持集合顺序
internal fun resolveTracks(all: List<MusicTrack>, ids: Collection<Long>): List<MusicTrack> =
    ids.mapNotNull { id -> all.find { it.id == id } }

// 常听：按最近播放顺序解析
internal fun recentTracks(all: List<MusicTrack>, recentIds: List<Long>): List<MusicTrack> =
    recentIds.mapNotNull { id -> all.find { it.id == id } }

internal fun smartTrackCount(all: List<MusicTrack>, ids: Collection<Long>): Int =
    ids.count { id -> all.any { it.id == id } }

internal fun distinctAlbumCount(all: List<MusicTrack>): Int = all.map { it.albumId }.distinct().size

internal fun distinctArtistCount(all: List<MusicTrack>): Int = all.map { it.artist }.distinct().size

// 按专辑分组，组名回退为未知专辑文案
internal fun albumGroups(all: List<MusicTrack>, unknownAlbum: String): List<PlaylistGroup> =
    all.groupBy { it.albumId }
        .map { (albumId, list) ->
            PlaylistGroup(
                key = "album:$albumId",
                name = list.firstOrNull()?.albumName?.takeIf { it.isNotBlank() } ?: unknownAlbum,
                trackIds = list.map { it.id },
            )
        }
        .sortedBy { it.name }

// 按艺术家分组，组名回退为未知艺术家文案
internal fun artistGroups(all: List<MusicTrack>, unknownArtist: String): List<PlaylistGroup> =
    all.groupBy { it.artist }
        .map { (artist, list) ->
            PlaylistGroup(
                key = "artist:$artist",
                name = artist.ifBlank { unknownArtist },
                trackIds = list.map { it.id },
            )
        }
        .sortedBy { it.name }
