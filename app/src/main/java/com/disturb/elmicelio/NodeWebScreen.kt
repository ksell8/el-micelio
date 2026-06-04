package com.disturb.elmicelio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val NOTIF_CHANNEL_ID = "downloads"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeWebScreen(navController: NavController) {
    val context = LocalContext.current
    var pageTitle by remember { mutableStateOf("") }
    var webView: WebView? by remember { mutableStateOf(null) }

    ensureNotificationChannel(context)

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { wv ->
                wv.clearHistory()
                wv.clearCache(true)
                wv.clearFormData()
            }
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pageTitle.ifEmpty { WiFiConnector.SERVER_URL }, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (webView?.canGoBack() == true) webView?.goBack()
                        else navController.popBackStack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        cacheMode = WebSettings.LOAD_NO_CACHE
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean = false
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView, title: String) {
                            pageTitle = title
                        }
                    }
                    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                        Toast.makeText(ctx, "Downloading $fileName…", Toast.LENGTH_SHORT).show()
                        val cookie = CookieManager.getInstance().getCookie(url)
                        CoroutineScope(Dispatchers.IO).launch {
                            downloadInProcess(ctx, url, userAgent, cookie, fileName, mimeType)
                        }
                    }
                    loadUrl(WiFiConnector.SERVER_URL)
                    webView = this
                }
            },
            update = { webView = it },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        )
    }
}

private suspend fun downloadInProcess(
    context: Context,
    url: String,
    userAgent: String,
    cookie: String?,
    fileName: String,
    mimeType: String
) {
    val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notifId = url.hashCode()

    val progressNotif = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(fileName)
        .setContentText("Downloading…")
        .setOngoing(true)
        .setProgress(0, 0, true)
        .build()
    notifManager.notify(notifId, progressNotif)

    runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", userAgent)
            if (!cookie.isNullOrEmpty()) setRequestProperty("Cookie", cookie)
            connect()
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!

        resolver.openOutputStream(uri)!!.use { output ->
            connection.inputStream.use { it.copyTo(output) }
        }

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        withContext(Dispatchers.Main) {
            val doneNotif = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(fileName)
                .setContentText("Download complete")
                .setAutoCancel(true)
                .build()
            notifManager.notify(notifId, doneNotif)
        }
    }.onFailure {
        withContext(Dispatchers.Main) {
            notifManager.cancel(notifId)
            Toast.makeText(context, "Download failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }
}

private fun ensureNotificationChannel(context: Context) {
    val channel = NotificationChannel(
        NOTIF_CHANNEL_ID,
        "Downloads",
        NotificationManager.IMPORTANCE_DEFAULT
    )
    (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .createNotificationChannel(channel)
}
