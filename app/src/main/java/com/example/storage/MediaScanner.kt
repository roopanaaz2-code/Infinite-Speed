package com.example.storage

import android.content.ContentUris
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.example.model.FileCategory
import com.example.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MediaScanner(private val context: Context) {

    suspend fun queryFilesByCategory(category: FileCategory, query: String = ""): List<FileItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<FileItem>()
        when (category) {
            FileCategory.ALL -> {
                results.addAll(queryPhotos())
                results.addAll(queryVideos())
                results.addAll(queryAudio())
                results.addAll(queryDocuments())
                results.addAll(queryInstalledApps())
            }
            FileCategory.PHOTO -> results.addAll(queryPhotos())
            FileCategory.VIDEO -> results.addAll(queryVideos())
            FileCategory.AUDIO -> results.addAll(queryAudio())
            FileCategory.DOCUMENT -> results.addAll(queryDocuments())
            FileCategory.APP -> results.addAll(queryInstalledApps())
            FileCategory.ARCHIVE -> results.addAll(queryArchives())
            FileCategory.OTHER -> results.addAll(queryDocuments())
        }

        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            results.filter { it.name.lowercase().contains(q) }
        } else {
            results
        }
    }

    private fun queryPhotos(): List<FileItem> {
        val list = mutableListOf<FileItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_MODIFIED
        )
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC LIMIT 200"

        queryCursor(uri, projection, null, null, sortOrder) { cursor ->
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: "Photo_$id.jpg"
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))
            val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)) ?: "image/jpeg"
            val date = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)) * 1000L
            val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString()

            list.add(
                FileItem(
                    id = "photo_$id",
                    name = name,
                    path = contentUri,
                    size = size,
                    mimeType = mime,
                    category = FileCategory.PHOTO,
                    dateModified = date
                )
            )
        }
        return list
    }

    private fun queryVideos(): List<FileItem> {
        val list = mutableListOf<FileItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_MODIFIED
        )
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC LIMIT 100"

        queryCursor(uri, projection, null, null, sortOrder) { cursor ->
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)) ?: "Video_$id.mp4"
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE))
            val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)) ?: "video/mp4"
            val date = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)) * 1000L
            val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString()

            list.add(
                FileItem(
                    id = "video_$id",
                    name = name,
                    path = contentUri,
                    size = size,
                    mimeType = mime,
                    category = FileCategory.VIDEO,
                    dateModified = date
                )
            )
        }
        return list
    }

    private fun queryAudio(): List<FileItem> {
        val list = mutableListOf<FileItem>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_MODIFIED
        )
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC LIMIT 100"

        queryCursor(uri, projection, null, null, sortOrder) { cursor ->
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)) ?: "Audio_$id.mp3"
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE))
            val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)) ?: "audio/mpeg"
            val date = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)) * 1000L
            val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()

            list.add(
                FileItem(
                    id = "audio_$id",
                    name = name,
                    path = contentUri,
                    size = size,
                    mimeType = mime,
                    category = FileCategory.AUDIO,
                    dateModified = date
                )
            )
        }
        return list
    }

    private fun queryDocuments(): List<FileItem> {
        val list = mutableListOf<FileItem>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR " +
                "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR " +
                "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.pdf' OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.docx' OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.xlsx' OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.txt'"
        val selectionArgs = arrayOf(
            "application/pdf%",
            "application/msword%",
            "text/%"
        )
        val uri = MediaStore.Files.getContentUri("external")
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC LIMIT 150"

        queryCursor(uri, projection, selection, selectionArgs, sortOrder) { cursor ->
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)) ?: "Document_$id"
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE))
            val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)) ?: "application/octet-stream"
            val date = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)) * 1000L
            val contentUri = ContentUris.withAppendedId(uri, id).toString()

            list.add(
                FileItem(
                    id = "doc_$id",
                    name = name,
                    path = contentUri,
                    size = size,
                    mimeType = mime,
                    category = FileCategory.DOCUMENT,
                    dateModified = date
                )
            )
        }
        return list
    }

    private fun queryArchives(): List<FileItem> {
        val list = mutableListOf<FileItem>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.zip' OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.rar' OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.7z' OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.tar.gz'"
        val uri = MediaStore.Files.getContentUri("external")

        queryCursor(uri, projection, selection, null, null) { cursor ->
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)) ?: "Archive_$id.zip"
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE))
            val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)) ?: "application/zip"
            val date = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)) * 1000L
            val contentUri = ContentUris.withAppendedId(uri, id).toString()

            list.add(
                FileItem(
                    id = "archive_$id",
                    name = name,
                    path = contentUri,
                    size = size,
                    mimeType = mime,
                    category = FileCategory.ARCHIVE,
                    dateModified = date
                )
            )
        }
        return list
    }

    fun queryInstalledApps(): List<FileItem> {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val list = mutableListOf<FileItem>()

        for (app in apps) {
            // Include non-system or updated system apps with launchable intent
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val launchIntent = pm.getLaunchIntentForPackage(app.packageName)

            if ((!isSystem || isUpdatedSystem) && launchIntent != null) {
                val apkFile = File(app.publicSourceDir ?: app.sourceDir ?: "")
                if (apkFile.exists() && apkFile.canRead()) {
                    val label = pm.getApplicationLabel(app).toString()
                    list.add(
                        FileItem(
                            id = "app_${app.packageName}",
                            name = "$label.apk",
                            path = apkFile.absolutePath,
                            size = apkFile.length(),
                            mimeType = "application/vnd.android.package-archive",
                            category = FileCategory.APP,
                            packageName = app.packageName,
                            dateModified = apkFile.lastModified()
                        )
                    )
                }
            }
        }
        return list.sortedBy { it.name.lowercase() }
    }

    private inline fun queryCursor(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        action: (Cursor) -> Unit
    ) {
        try {
            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            cursor?.use {
                while (it.moveToNext()) {
                    action(it)
                }
            }
        } catch (_: Exception) {}
    }

    data class StorageInfo(
        val totalBytes: Long,
        val availableBytes: Long,
        val usedBytes: Long,
        val usedPercent: Float
    ) {
        val formattedUsed: String = formatBytes(usedBytes)
        val formattedTotal: String = formatBytes(totalBytes)
        val formattedAvailable: String = formatBytes(availableBytes)

        private fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 GB"
            val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            return String.format(java.util.Locale.US, "%.1f GB", gb)
        }
    }

    fun getStorageStats(): StorageInfo {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            val total = totalBlocks * blockSize
            val available = availableBlocks * blockSize
            val used = total - available
            val percent = if (total > 0) (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
            StorageInfo(total, available, used, percent)
        } catch (_: Exception) {
            StorageInfo(64L * 1024 * 1024 * 1024, 32L * 1024 * 1024 * 1024, 32L * 1024 * 1024 * 1024, 0.5f)
        }
    }
}
