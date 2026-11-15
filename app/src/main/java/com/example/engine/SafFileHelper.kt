package com.example.engine

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.example.model.JoinGroup
import com.example.model.JoinPartItem
import com.example.model.SplitNamingFormat
import com.example.model.SplitQueueItem
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScannedDocument(
  val uri: Uri,
  val displayName: String,
  val relativePath: String,
  val sizeBytes: Long,
)

object SafFileHelper {

  /**
   * Custom unregistered MIME type prevents AOSP FileSystemProvider from appending ".bin"
   * to PS3 split extensions such as ".66600" or ".001".
   */
  private const val SPLIT_PART_MIME_TYPE = "application/x-split666"

  fun takePersistablePermission(context: Context, uri: Uri, writeAccess: Boolean) {
    val flags =
      if (writeAccess) {
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
      } else {
        Intent.FLAG_GRANT_READ_URI_PERMISSION
      }
    runCatching {
      context.contentResolver.takePersistableUriPermission(uri, flags)
    }
  }

  fun resolveTreeDisplayName(context: Context, treeUri: Uri): String {
    val resolver = context.contentResolver
    val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
    if (treeDocId != null) {
      val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
      runCatching {
        resolver.query(
          rootDocUri,
          arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
          null,
          null,
          null,
        )?.use { cursor ->
          if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idx >= 0) {
              val name = cursor.getString(idx)
              if (!name.isNullOrBlank()) return name
            }
          }
        }
      }
      val fallback = treeDocId.substringAfterLast(':').trim('/')
      if (fallback.isNotBlank()) return fallback
    }
    return treeUri.lastPathSegment?.substringAfterLast(':') ?: "Selected Folder"
  }

  suspend fun querySingleDocument(context: Context, uri: Uri): SplitQueueItem? =
    withContext(Dispatchers.IO) {
      val resolver = context.contentResolver
      var name: String? = null
      var size: Long = -1L

      resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
      )?.use { cursor ->
        if (cursor.moveToFirst()) {
          val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
          if (nameIdx >= 0) name = cursor.getString(nameIdx)
          if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
        }
      }

      val resolvedName =
        name?.takeIf { it.isNotBlank() }
          ?: uri.lastPathSegment?.substringAfterLast('/')
          ?: "unknown_file"

      if (size < 0L) {
        size =
          runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
          }.getOrDefault(0L)
      }

      SplitQueueItem(
        id = uri.toString(),
        uri = uri,
        displayName = resolvedName,
        relativePath = resolvedName,
        sizeBytes = size.coerceAtLeast(0L),
      )
    }

  suspend fun scanTreeDocuments(
    context: Context,
    treeUri: Uri,
    recursive: Boolean = true,
  ): List<ScannedDocument> =
    withContext(Dispatchers.IO) {
      val resolver = context.contentResolver
      val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
      val results = mutableListOf<ScannedDocument>()
      scanDirectoryRecursive(
        resolver = resolver,
        treeUri = treeUri,
        parentDocId = rootDocId,
        currentPrefix = "",
        recursive = recursive,
        results = results,
      )
      results.sortedBy { it.relativePath.lowercase() }
    }

  private fun scanDirectoryRecursive(
    resolver: ContentResolver,
    treeUri: Uri,
    parentDocId: String,
    currentPrefix: String,
    recursive: Boolean,
    results: MutableList<ScannedDocument>,
  ) {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val projection =
      arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
      )

    resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
      val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
      val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
      val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
      val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

      while (cursor.moveToNext()) {
        val docId = if (idCol >= 0) cursor.getString(idCol) else continue
        val name = if (nameCol >= 0) cursor.getString(nameCol) ?: continue else continue
        val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
        val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L

        val relPath = if (currentPrefix.isEmpty()) name else "$currentPrefix/$name"
        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
          if (recursive) {
            scanDirectoryRecursive(
              resolver = resolver,
              treeUri = treeUri,
              parentDocId = docId,
              currentPrefix = relPath,
              recursive = true,
              results = results,
            )
          }
        } else {
          val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
          results.add(
            ScannedDocument(
              uri = docUri,
              displayName = name,
              relativePath = relPath,
              sizeBytes = size.coerceAtLeast(0L),
            )
          )
        }
      }
    }
  }

  fun ensureSubdirectories(
    resolver: ContentResolver,
    treeUri: Uri,
    relativeFilePath: String,
  ): Uri {
    val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
    var currentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
    val segments = relativeFilePath.split('/').dropLast(1).filter { it.isNotBlank() }

    for (segment in segments) {
      val existing = findChildByName(resolver, treeUri, currentDocUri, segment)
      if (existing != null && existing.isDirectory) {
        currentDocUri = existing.uri
      } else {
        currentDocUri =
          DocumentsContract.createDocument(
            resolver,
            currentDocUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            segment,
          ) ?: throw IOException("Could not create folder: $segment")
      }
    }
    return currentDocUri
  }

  fun createOrReplaceDocument(
    resolver: ContentResolver,
    treeUri: Uri,
    parentDocUri: Uri,
    fileName: String,
    isSplitPart: Boolean,
    overwriteExisting: Boolean,
  ): Uri {
    val existing = findChildByName(resolver, treeUri, parentDocUri, fileName)
    if (existing != null && !existing.isDirectory) {
      if (overwriteExisting) {
        val deleted = runCatching { DocumentsContract.deleteDocument(resolver, existing.uri) }.getOrDefault(false)
        if (!deleted) {
          return existing.uri
        }
      } else {
        throw IOException("File already exists: $fileName")
      }
    }

    val mimeType =
      if (isSplitPart) {
        SPLIT_PART_MIME_TYPE
      } else {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: SPLIT_PART_MIME_TYPE
      }

    return DocumentsContract.createDocument(resolver, parentDocUri, mimeType, fileName)
      ?: throw IOException("Could not create output file: $fileName")
  }

  private data class ChildInfo(val uri: Uri, val isDirectory: Boolean)

  private fun findChildByName(
    resolver: ContentResolver,
    treeUri: Uri,
    parentDocUri: Uri,
    targetName: String,
  ): ChildInfo? {
    val parentDocId = DocumentsContract.getDocumentId(parentDocUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val projection =
      arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
      )
    resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
      val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
      val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
      val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
      while (cursor.moveToNext()) {
        val name = if (nameCol >= 0) cursor.getString(nameCol) else null
        if (name == targetName) {
          val docId = cursor.getString(idCol)
          val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
          val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
          return ChildInfo(
            uri = docUri,
            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
          )
        }
      }
    }
    return null
  }

  fun buildJoinGroups(documents: List<ScannedDocument>): List<JoinGroup> {
    data class GroupKey(
      val folderPath: String,
      val baseName: String,
      val formatFamily: String,
    )

    val buckets = linkedMapOf<GroupKey, MutableList<Pair<ParsedSplitPart, ScannedDocument>>>()
    for (doc in documents) {
      val parsed = SplitNaming.parseSplitPart(doc.displayName) ?: continue
      val folder = doc.relativePath.substringBeforeLast('/', "")
      val familyKey =
        when (parsed.format) {
          SplitNamingFormat.PS3_666 -> "ps3"
          SplitNamingFormat.SEQUENTIAL_001,
          SplitNamingFormat.ZERO_BASED_000 -> "numeric"
        }
      val key = GroupKey(folder, parsed.baseFileName, familyKey)
      buckets.getOrPut(key) { mutableListOf() }.add(parsed to doc)
    }

    return buckets.entries.map { (key, entries) ->
      val sorted = entries.sortedBy { it.first.partIndex }
      val minIdx = sorted.first().first.partIndex
      val maxIdx = sorted.last().first.partIndex
      val resolvedFormat =
        when {
          sorted.first().first.format == SplitNamingFormat.PS3_666 -> SplitNamingFormat.PS3_666
          minIdx == 0 -> SplitNamingFormat.ZERO_BASED_000
          else -> SplitNamingFormat.SEQUENTIAL_001
        }
      val expectedStart = if (resolvedFormat == SplitNamingFormat.SEQUENTIAL_001) 1 else 0
      val presentSet = sorted.map { it.first.partIndex }.toSet()
      val missing = (expectedStart..maxIdx).filter { it !in presentSet }

      val parts =
        sorted.map { (parsed, doc) ->
          JoinPartItem(
            uri = doc.uri,
            displayName = doc.displayName,
            partIndex = parsed.partIndex,
            sizeBytes = doc.sizeBytes,
          )
        }

      JoinGroup(
        id = "${key.folderPath}|${key.baseName}|${resolvedFormat.id}",
        baseFileName = key.baseName,
        format = resolvedFormat,
        parts = parts,
        totalBytes = parts.sumOf { it.sizeBytes },
        missingIndices = missing,
      )
    }
  }
}
