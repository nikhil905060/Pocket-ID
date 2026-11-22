package com.pocketid.vault

import android.annotation.SuppressLint
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        filePathCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
        }

        webView.addJavascriptInterface(NativeBridge(), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val accept = params?.acceptTypes?.firstOrNull() ?: "*/*"
                fileChooserLauncher.launch(if (accept.isBlank()) "*/*" else accept)
                return true
            }
        }

        webView.webViewClient = WebViewClient()
        webView.loadUrl("file:///android_asset/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("if(window.handleNativeBack){window.handleNativeBack();}", null)
            }
        })
    }

    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) {
            webView.evaluateJavascript("if(window.lockAppNow){window.lockAppNow();}", null)
        }
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun isBiometricAvailable(): Boolean {
            val bm = BiometricManager.from(this@MainActivity)
            return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
        }

        @JavascriptInterface
        fun authenticateBiometric() {
            runOnUiThread {
                val executor = ContextCompat.getMainExecutor(this@MainActivity)
                val prompt = BiometricPrompt(this@MainActivity, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            webView.evaluateJavascript("if(window.onBiometricSuccess){window.onBiometricSuccess();}", null)
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            webView.evaluateJavascript("if(window.onBiometricFail){window.onBiometricFail();}", null)
                        }
                    })
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Pocket Id Unlock")
                    .setSubtitle("Verify fingerprint to unlock vault")
                    .setNegativeButtonText("Use PIN")
                    .build()
                prompt.authenticate(info)
            }
        }

        @JavascriptInterface
        fun saveBackupToDownloads(fileName: String, jsonContent: String): String {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = contentResolver
                    val existing = resolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Downloads._ID),
                        "${MediaStore.Downloads.DISPLAY_NAME}=?",
                        arrayOf(fileName),
                        null
                    )
                    existing?.use {
                        while (it.moveToNext()) {
                            val id = it.getLong(0)
                            resolver.delete(
                                Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()),
                                null, null
                            )
                        }
                    }
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/json")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PocketIdBackups")
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    uri?.let {
                        resolver.openOutputStream(it)?.use { out ->
                            out.write(jsonContent.toByteArray(Charsets.UTF_8))
                        }
                    }
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PocketIdBackups")
                    if (!dir.exists()) dir.mkdirs()
                    FileOutputStream(File(dir, fileName)).use { out ->
                        out.write(jsonContent.toByteArray(Charsets.UTF_8))
                    }
                }
                "OK"
            } catch (e: Exception) {
                "ERR:${e.message}"
            }
        }
    }
}
