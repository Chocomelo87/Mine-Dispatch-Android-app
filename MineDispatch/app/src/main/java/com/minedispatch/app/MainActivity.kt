package com.minedispatch.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null

    private val chooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cb = fileCallback ?: return@registerForActivityResult
            var uris: Array<Uri>? = null
            if (res.resultCode == RESULT_OK) {
                uris = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
                if (uris == null && cameraUri != null) uris = arrayOf(cameraUri!!)
            }
            cb.onReceiveValue(uris)
            fileCallback = null
            cameraUri = null
        }

    // Replaces the page's blob-URL download(), which WebView cannot handle.
    private val downloadOverride = """
        (function(){
          window.download = async function(content, name, type){
            const blob = content instanceof Blob ? content : new Blob([content], {type: type});
            const dataUrl = await new Promise((res, rej) => {
              const r = new FileReader();
              r.onload = () => res(r.result);
              r.onerror = () => rej(r.error);
              r.readAsDataURL(blob);
            });
            AndroidBridge.saveFile(name, blob.type || type || "application/octet-stream", dataUrl.split(",")[1]);
          };
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true      // enables IndexedDB
            settings.allowFileAccess = false
            settings.allowContentAccess = true

            addJavascriptInterface(Bridge(), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.host != "appassets.androidplatform.net"

                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(downloadOverride, null)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(v: WebView, url: String, message: String, result: JsResult): Boolean {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                        .setOnCancelListener { result.cancel() }
                        .show()
                    return true
                }

                override fun onJsConfirm(v: WebView, url: String, message: String, result: JsResult): Boolean {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                        .setOnCancelListener { result.cancel() }
                        .show()
                    return true
                }

                override fun onShowFileChooser(
                    wv: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback

                    val picker = params.createIntent()
                    val extras = mutableListOf<Intent>()
                    if (params.acceptTypes.any { it.contains("image") }) {
                        try {
                            val dir = File(cacheDir, "captures").apply { mkdirs() }
                            val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
                            cameraUri = FileProvider.getUriForFile(
                                this@MainActivity, "$packageName.fileprovider", file
                            )
                            extras += Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                                putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
                                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            }
                        } catch (_: Exception) { cameraUri = null }
                    }
                    val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                        putExtra(Intent.EXTRA_INTENT, picker)
                        putExtra(Intent.EXTRA_TITLE, "Select file or take photo")
                        if (extras.isNotEmpty()) putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
                    }
                    return try {
                        chooserLauncher.launch(chooser)
                        true
                    } catch (e: Exception) {
                        fileCallback?.onReceiveValue(null)
                        fileCallback = null
                        false
                    }
                }
            }
        }
        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(function(){var m=document.getElementById('modalBackdrop');" +
                    "if(m&&m.classList.contains('open')){closeModal();return 'closed';}return 'exit';})()"
                ) { r ->
                    if (r?.contains("exit") == true) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface
        fun saveFile(name: String, mime: String, base64: String) {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val where: String
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("Could not create file")
                    contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                    where = "Downloads"
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
                    File(dir, name).writeBytes(bytes)
                    where = dir.absolutePath
                }
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Saved $name to $where", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
