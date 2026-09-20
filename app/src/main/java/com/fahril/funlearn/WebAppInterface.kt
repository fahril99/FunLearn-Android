package com.fahril.funlearn

import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.JavascriptInterface
import java.io.OutputStream

class WebAppInterface(private val context: MainActivity) {

    @JavascriptInterface
    fun requestDirectoryPicker(promiseId: String) {
        context.launchDirectoryPicker(promiseId)
    }

    @JavascriptInterface
    fun getDirectoryHandle(promiseId: String, parentUriString: String, name: String, create: Boolean) {
        val parentUri = Uri.parse(parentUriString)
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, DocumentsContract.getDocumentId(parentUri))
            var foundUri: Uri? = null
            
            context.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIdx) == name) {
                        foundUri = DocumentsContract.buildDocumentUriUsingTree(parentUri, cursor.getString(idIdx))
                        break
                    }
                }
            }

            if (foundUri != null) {
                context.evaluateJs("window._fsPromises['${promiseId}'].resolve('${foundUri}')")
            } else if (create) {
                val newUri = DocumentsContract.createDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(parentUri, DocumentsContract.getDocumentId(parentUri)), DocumentsContract.Document.MIME_TYPE_DIR, name)
                if (newUri != null) {
                    context.evaluateJs("window._fsPromises['${promiseId}'].resolve('${newUri}')")
                } else {
                    rejectPromise(promiseId, "Could not create directory")
                }
            } else {
                rejectPromise(promiseId, "Directory not found")
            }
        } catch (e: Exception) {
            rejectPromise(promiseId, e.message ?: "Error getting directory")
        }
    }

    @JavascriptInterface
    fun getFileHandle(promiseId: String, parentUriString: String, name: String, create: Boolean) {
        val parentUri = Uri.parse(parentUriString)
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, DocumentsContract.getDocumentId(parentUri))
            var foundUri: Uri? = null
            
            context.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIdx) == name) {
                        foundUri = DocumentsContract.buildDocumentUriUsingTree(parentUri, cursor.getString(idIdx))
                        break
                    }
                }
            }

            if (foundUri != null) {
                context.evaluateJs("window._fsPromises['${promiseId}'].resolve('${foundUri}')")
            } else if (create) {
                val mimeType = if (name.endsWith(".json")) "application/json" else "application/octet-stream"
                val newUri = DocumentsContract.createDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(parentUri, DocumentsContract.getDocumentId(parentUri)), mimeType, name)
                if (newUri != null) {
                    context.evaluateJs("window._fsPromises['${promiseId}'].resolve('${newUri}')")
                } else {
                    rejectPromise(promiseId, "Could not create file")
                }
            } else {
                rejectPromise(promiseId, "File not found")
            }
        } catch (e: Exception) {
            rejectPromise(promiseId, e.message ?: "Error getting file")
        }
    }

    @JavascriptInterface
    fun getFileInfo(promiseId: String, uriString: String) {
        val uri = Uri.parse(uriString)
        try {
            var size = 0L
            var lastModified = 0L
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    size = cursor.getLong(0)
                    lastModified = cursor.getLong(1)
                }
            }
            context.evaluateJs("window._fsPromises['${promiseId}'].resolve({size: $size, lastModified: $lastModified})")
        } catch (e: Exception) {
            rejectPromise(promiseId, e.message ?: "Error getting file info")
        }
    }

    @JavascriptInterface
    fun writeToFile(promiseId: String, uriString: String, content: String) {
        val uri = Uri.parse(uriString)
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
            }
            context.evaluateJs("window._fsPromises['${promiseId}'].resolve()")
        } catch (e: Exception) {
            rejectPromise(promiseId, e.message ?: "Error writing to file")
        }
    }
    
    private fun rejectPromise(promiseId: String, error: String) {
        context.evaluateJs("window._fsPromises['${promiseId}'].reject(new Error('${error.replace("'", "\\'")}'))")
    }
}
