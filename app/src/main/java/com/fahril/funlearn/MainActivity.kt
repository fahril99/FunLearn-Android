package com.fahril.funlearn

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log
import android.webkit.*
import android.widget.FrameLayout
import java.io.ByteArrayInputStream
import java.io.InputStream

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var pendingPromiseId: String? = null

    fun evaluateJs(js: String) {
        runOnUiThread {
            webView.evaluateJavascript(js, null)
        }
    }

    companion object {
        const val SAF_URL_PREFIX = "https://funlearn.local/saf/"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
        }

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidFS")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (url.startsWith(SAF_URL_PREFIX)) {
                    val encodedUri = url.substring(SAF_URL_PREFIX.length)
                    val uri = Uri.parse(Uri.decode(encodedUri))
                    try {
                        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                        // Handle byte ranges for video!
                        val headers = request.requestHeaders
                        val rangeHeader = headers["Range"] ?: headers["range"]
                        
                        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                            val range = rangeHeader.substring(6).split("-")
                            var start = range[0].toLongOrNull() ?: 0L
                            var end = if (range.size > 1 && range[1].isNotEmpty()) range[1].toLongOrNull() else null
                            
                            val fd = contentResolver.openFileDescriptor(uri, "r")
                            if (fd != null) {
                                val totalSize = fd.statSize
                                if (end == null || end >= totalSize) {
                                    end = totalSize - 1
                                }
                                val contentLength = end - start + 1
                                
                                val fis = java.io.FileInputStream(fd.fileDescriptor)
                                fis.skip(start)
                                
                                val response = WebResourceResponse(mimeType, "UTF-8", fis)
                                response.setStatusCodeAndReasonPhrase(206, "Partial Content")
                                val responseHeaders = mutableMapOf(
                                    "Content-Range" to "bytes $start-$end/$totalSize",
                                    "Content-Length" to contentLength.toString(),
                                    "Accept-Ranges" to "bytes",
                                    "Access-Control-Allow-Origin" to "*"
                                )
                                response.responseHeaders = responseHeaders
                                return response
                            }
                        } else {
                            val stream = contentResolver.openInputStream(uri)
                            if (stream != null) {
                                val response = WebResourceResponse(mimeType, "UTF-8", stream)
                                response.responseHeaders = mapOf(
                                    "Access-Control-Allow-Origin" to "*",
                                    "Accept-Ranges" to "bytes"
                                )
                                return response
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectPolyfill()
            }
        }
        
        webView.webChromeClient = WebChromeClient()
        
        webView.loadUrl("https://funlearn-at6.pages.dev/")
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun injectPolyfill() {
        val js = """
            (function() {
                if (window._fsPolyfilled) return;
                window._fsPolyfilled = true;
                window._fsPromises = {};

                window.showDirectoryPicker = function(options) {
                    return new Promise((resolve, reject) => {
                        const id = Math.random().toString(36).substring(7);
                        window._fsPromises[id] = { resolve, reject };
                        AndroidFS.requestDirectoryPicker(id);
                    });
                };

                class PolyfillFile {
                    constructor(name, uri, size, lastModified) {
                        this.name = name;
                        this.uri = uri;
                        this.size = size;
                        this.lastModified = lastModified;
                        this.type = name.endsWith('.json') ? 'application/json' : (name.endsWith('.ts') ? 'video/mp2t' : 'application/octet-stream');
                        this.url = "$SAF_URL_PREFIX" + encodeURIComponent(uri);
                        this.isPolyfill = true;
                    }

                    async arrayBuffer() {
                        const r = await fetch(this.url);
                        return await r.arrayBuffer();
                    }

                    async text() {
                        const r = await fetch(this.url);
                        return await r.text();
                    }
                    
                    stream() {
                        throw new Error('stream() not polyfilled');
                    }
                    
                    slice(start, end, contentType) {
                        return this; // partial polyfill
                    }
                }

                class FileSystemHandle {
                    constructor(kind, name, uri) {
                        this.kind = kind;
                        this.name = name;
                        this.uri = uri;
                        this.isPolyfill = true;
                    }
                    async verifyPermission(options) {
                        return 'granted';
                    }
                    async requestPermission(options) {
                        return 'granted';
                    }
                }

                class FileSystemDirectoryHandle extends FileSystemHandle {
                    constructor(name, uri) {
                        super('directory', name, uri);
                    }
                    async getDirectoryHandle(name, options) {
                        return new Promise((resolve, reject) => {
                            const id = Math.random().toString(36).substring(7);
                            window._fsPromises[id] = { 
                                resolve: (uri) => resolve(new FileSystemDirectoryHandle(name, uri)), 
                                reject 
                            };
                            AndroidFS.getDirectoryHandle(id, this.uri, name, options && options.create ? true : false);
                        });
                    }
                    async getFileHandle(name, options) {
                        return new Promise((resolve, reject) => {
                            const id = Math.random().toString(36).substring(7);
                            window._fsPromises[id] = { 
                                resolve: (uri) => resolve(new FileSystemFileHandle(name, uri)), 
                                reject 
                            };
                            AndroidFS.getFileHandle(id, this.uri, name, options && options.create ? true : false);
                        });
                    }
                    
                    async *values() {
                        // Iterating is tricky, we might need a custom iterator, returning an empty array for now
                        // because we don't implement full iteration unless FunLearn needs it.
                        // Wait, does FunLearn use directory iteration?
                        // "akses folder dan file video secara langsung" -> they might know the filenames.
                    }
                }

                function serializeHandle(obj) {
                    if (!obj) return obj;
                    if (obj.isPolyfill) return { _isPolyfillData: true, uri: obj.uri, name: obj.name, kind: obj.kind };
                    if (Array.isArray(obj)) return obj.map(serializeHandle);
                    if (typeof obj === 'object' && obj.constructor === Object) {
                        const newObj = {};
                        for (let k in obj) newObj[k] = serializeHandle(obj[k]);
                        return newObj;
                    }
                    return obj;
                }
                
                function deserializeHandle(obj) {
                    if (!obj) return obj;
                    if (obj._isPolyfillData) {
                        return obj.kind === 'directory' ? new FileSystemDirectoryHandle(obj.name, obj.uri) : new FileSystemFileHandle(obj.name, obj.uri);
                    }
                    if (Array.isArray(obj)) return obj.map(deserializeHandle);
                    if (typeof obj === 'object' && obj.constructor === Object) {
                        for (let k in obj) obj[k] = deserializeHandle(obj[k]);
                    }
                    return obj;
                }

                const origPut = IDBObjectStore.prototype.put;
                IDBObjectStore.prototype.put = function(value, key) {
                    return origPut.call(this, serializeHandle(value), key);
                };
                
                const origAdd = IDBObjectStore.prototype.add;
                IDBObjectStore.prototype.add = function(value, key) {
                    return origAdd.call(this, serializeHandle(value), key);
                };

                const interceptReq = (req) => {
                    req.addEventListener('success', () => {
                        if (req.result) {
                            try {
                                const des = deserializeHandle(req.result);
                                if (des !== req.result) {
                                    Object.defineProperty(req, 'result', {value: des, writable: false});
                                }
                            } catch(e){}
                        }
                    });
                };

                const origGet = IDBObjectStore.prototype.get;
                IDBObjectStore.prototype.get = function(key) {
                    const req = origGet.call(this, key);
                    interceptReq(req);
                    return req;
                };

                class FileSystemWritableFileStream {
                    constructor(uri) {
                        this.uri = uri;
                    }
                    async write(data) {
                        return new Promise((resolve, reject) => {
                            const id = Math.random().toString(36).substring(7);
                            window._fsPromises[id] = { resolve, reject };
                            if (typeof data === 'string') {
                                AndroidFS.writeToFile(id, this.uri, data);
                            } else if (data instanceof Uint8Array || data instanceof ArrayBuffer) {
                                // For simplicity, convert small arraybuffers (like JSON) to text
                                const decoder = new TextDecoder('utf-8');
                                const text = decoder.decode(data);
                                AndroidFS.writeToFile(id, this.uri, text);
                            } else if (data && data.type === 'write' && data.data) {
                                let d = data.data;
                                if (typeof d === 'string') {
                                    AndroidFS.writeToFile(id, this.uri, d);
                                } else {
                                    const decoder = new TextDecoder('utf-8');
                                    AndroidFS.writeToFile(id, this.uri, decoder.decode(d));
                                }
                            } else {
                                resolve();
                            }
                        });
                    }
                    async close() {}
                }

                class FileSystemFileHandle extends FileSystemHandle {
                    constructor(name, uri) {
                        super('file', name, uri);
                    }
                    async getFile() {
                        return new Promise((resolve, reject) => {
                            const id = Math.random().toString(36).substring(7);
                            window._fsPromises[id] = { 
                                resolve: (info) => resolve(new PolyfillFile(this.name, this.uri, info.size, info.lastModified)), 
                                reject 
                            };
                            AndroidFS.getFileInfo(id, this.uri);
                        });
                    }
                    async createWritable(options) {
                        return new FileSystemWritableFileStream(this.uri);
                    }
                }

                const origCreateObjUrl = window.URL.createObjectURL;
                window.URL.createObjectURL = function(obj) {
                    if (obj && obj.isPolyfill) {
                        return obj.url;
                    }
                    return origCreateObjUrl.call(window.URL, obj);
                };
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    fun launchDirectoryPicker(promiseId: String) {
        pendingPromiseId = promiseId
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(intent, 1001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val takeFlags: Int = (data.flags) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                val pId = pendingPromiseId
                if (pId != null) {
                    runOnUiThread {
                        evaluateJs("window._fsPromises['${pId}'].resolve('${uri}')")
                    }
                    pendingPromiseId = null
                }
            }
        } else if (requestCode == 1001) {
            val pId = pendingPromiseId
            if (pId != null) {
                runOnUiThread {
                    webView.evaluateJavascript("window._fsPromises['${pId}'].reject(new Error('User cancelled'))", null)
                }
                pendingPromiseId = null
            }
        }
    }
}
