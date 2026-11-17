package ua.nichnyk.listen.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaMetadata

/**
 * Метадані глави в **черзі відтворення** (шторка, екран блокування, Bluetooth):
 * зверху книга, під нею глава.
 *
 * `displayTitle` обов'язковий. Без нього платформа складає опис сесії сама з
 * трійки «назва, виконавець, альбом», і MIUI на екрані блокування склеює два
 * останні без роздільника (на Redmi Note 11 Pro 13 листопада 2026:
 * `description=BookVoices. Вступ, BookVoices, BookVoices. Вступ`). З SoundFlow,
 * `playback/PlayerManager.kt` → `mediaItemOf()`, коміт `4c6db05`.
 *
 * `artist` і `albumTitle` лишаються: їх читають Bluetooth-магнітоли (AVRCP).
 * [PlayerManager.refreshChapterTitles] міняє лише `subtitle` — `displayTitle`
 * (книга) від перейменування глави не залежить.
 */
internal fun queueChapterMetadata(
    bookTitle: String,
    author: String,
    chapterTitle: String,
    artworkUri: Uri?,
    extras: Bundle? = null,
): MediaMetadata = MediaMetadata.Builder()
    .setTitle(bookTitle)
    .setDisplayTitle(bookTitle)
    .setSubtitle(chapterTitle)
    .setArtist(author)
    .setAlbumTitle(bookTitle)
    .setArtworkUri(artworkUri)
    .setIsPlayable(true)
    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
    .apply { if (extras != null) setExtras(extras) }
    .build()

/**
 * Метадані глави для **перегляду** (список глав в Auto): зверху глава, під
 * нею книга. Див. [queueChapterMetadata] про `displayTitle`.
 */
internal fun browseChapterMetadata(
    bookTitle: String,
    author: String,
    chapterTitle: String,
    artworkUri: Uri?,
    extras: Bundle?,
): MediaMetadata = MediaMetadata.Builder()
    .setTitle(chapterTitle)
    .setDisplayTitle(chapterTitle)
    .setSubtitle(bookTitle)
    .setArtist(author)
    .setAlbumTitle(bookTitle)
    .setArtworkUri(artworkUri)
    .setIsPlayable(true)
    .setIsBrowsable(false)
    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
    .apply { if (extras != null) setExtras(extras) }
    .build()
