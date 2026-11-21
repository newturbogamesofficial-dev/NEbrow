package com.example.nebrow

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private class Tab(val web: WebView) {
        var title: String = "Новая вкладка"
    }

    private val home = "https://duckduckgo.com"
    private val search = "https://duckduckgo.com/?q="
    private val orange = Color.parseColor("#FF7F27")
    private val ink = Color.parseColor("#202124")

    private val tabs = mutableListOf<Tab>()
    private var current = -1

    private lateinit var content: FrameLayout
    private lateinit var urlField: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tabsBtn: TextView
    private lateinit var backBtn: TextView
    private lateinit var forwardBtn: TextView

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: (() -> Unit)? = null

    private companion object {
        const val FILE_REQ = 1001
        const val PERM_REQ = 1002
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        if (Build.VERSION.SDK_INT >= 23) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        CookieManager.getInstance().setAcceptCookie(true)
        buildUi()
        addTab(intent?.dataString ?: home)
    }

    // ---------- Интерфейс ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun iconButton(text: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 22f
        tv.gravity = Gravity.CENTER
        tv.setTextColor(ink)
        tv.layoutParams = LinearLayout.LayoutParams(dp(40), dp(44))
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        tv.background = a.getDrawable(0)
        a.recycle()
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

        val toolbar = LinearLayout(this)
        toolbar.orientation = LinearLayout.HORIZONTAL
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.setPadding(dp(4), dp(2), dp(4), dp(2))

        backBtn = iconButton("‹") {
            val web = currentTab()?.web
            if (web != null && web.canGoBack()) web.goBack()
        }
        forwardBtn = iconButton("›") {
            val web = currentTab()?.web
            if (web != null && web.canGoForward()) web.goForward()
        }
        val reloadBtn = iconButton("⟳") {
            val web = currentTab()?.web
            if (web != null) {
                if (progress.visibility == View.VISIBLE) web.stopLoading() else web.reload()
            }
        }

        urlField = EditText(this)
        urlField.setSingleLine(true)
        urlField.hint = "Адрес или запрос"
        urlField.textSize = 15f
        urlField.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_URI
        urlField.imeOptions = EditorInfo.IME_ACTION_GO
        urlField.setSelectAllOnFocus(true)
        urlField.setPadding(dp(14), 0, dp(14), 0)
        val urlBg = GradientDrawable()
        urlBg.cornerRadius = dp(20).toFloat()
        urlBg.setColor(Color.parseColor("#F1F3F4"))
        urlField.background = urlBg
        urlField.layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        urlField.setOnEditorActionListener { v, actionId, event ->
            val enter = event != null &&
                event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                navigate(urlField.text.toString())
                hideKeyboard(v)
                true
            } else {
                false
            }
        }

        tabsBtn = TextView(this)
        tabsBtn.gravity = Gravity.CENTER
        tabsBtn.textSize = 13f
        tabsBtn.setTextColor(ink)
        val tabsBg = GradientDrawable()
        tabsBg.setStroke(dp(2), ink)
        tabsBg.cornerRadius = dp(6).toFloat()
        tabsBtn.background = tabsBg
        val tabsLp = LinearLayout.LayoutParams(dp(28), dp(28))
        tabsLp.setMargins(dp(8), 0, dp(4), 0)
        tabsBtn.layoutParams = tabsLp
        tabsBtn.setOnClickListener { showTabs() }

        val newTabBtn = iconButton("+") { addTab(home) }

        toolbar.addView(backBtn)
        toolbar.addView(forwardBtn)
        toolbar.addView(reloadBtn)
        toolbar.addView(urlField)
        toolbar.addView(tabsBtn)
        toolbar.addView(newTabBtn)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.progressTintList = ColorStateList.valueOf(orange)
        progress.visibility = View.GONE
        progress.layoutParams = LinearLayout.LayoutParams(match, dp(3))

        val divider = View(this)
        divider.setBackgroundColor(Color.parseColor("#E3E4E6"))
        divider.layoutParams = LinearLayout.LayoutParams(match, dp(1))

        content = FrameLayout(this)
        content.layoutParams = LinearLayout.LayoutParams(match, 0, 1f)

        root.addView(toolbar)
        root.addView(divider)
        root.addView(progress)
        root.addView(content)
        setContentView(root)
    }

    private fun hideKeyboard(v: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------- Адрес и поиск ----------

    private fun normalize(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return home
        if (Regex("""^https?://""", RegexOption.IGNORE_CASE).containsMatchIn(s)) return s
        val looksLikeHost =
            Regex("""^localhost(:\d+)?(/.*)?$""", RegexOption.IGNORE_CASE).matches(s) ||
                Regex("""^(\d{1,3}\.){3}\d{1,3}(:\d+)?(/.*)?$""").matches(s) ||
                Regex("""^[^\s/]+\.[^\s/]{2,}(:\d+)?(/.*)?$""").matches(s)
        return if (looksLikeHost) "https://$s" else search + Uri.encode(s)
    }

    private fun navigate(input: String) {
        currentTab()?.web?.loadUrl(normalize(input))
    }

    // ---------- Вкладки ----------

    private fun currentTab(): Tab? = tabs.getOrNull(current)

    private fun isCurrent(view: WebView?): Boolean =
        view != null && currentTab()?.web === view

    private fun setUrlText(text: String) {
        if (!urlField.hasFocus()) urlField.setText(text)
    }

    private fun updateNav() {
        tabsBtn.text = tabs.size.toString()
        val web = currentTab()?.web
        backBtn.alpha = if (web != null && web.canGoBack()) 1f else 0.35f
        forwardBtn.alpha = if (web != null && web.canGoForward()) 1f else 0.35f
    }

    private fun addTab(url: String?): Tab {
        val tab = Tab(createWebView())
        tabs.add(tab)
        selectTab(tabs.size - 1)
        if (url != null) tab.web.loadUrl(normalize(url))
        return tab
    }

    private fun selectTab(index: Int) {
        currentTab()?.web?.onPause()
        content.removeAllViews()
        current = index
        val tab = tabs[index]
        content.addView(tab.web)
        tab.web.onResume()
        urlField.clearFocus()
        urlField.setText(tab.web.url ?: "")
        val p = tab.web.progress
        progress.progress = p
        progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
        updateNav()
    }

    private fun closeTab(index: Int) {
        val wasCurrent = index == current
        val tab = tabs.removeAt(index)
        if (wasCurrent) content.removeAllViews() else if (index < current) current--
        tab.web.stopLoading()
        tab.web.destroy()
        if (tabs.isEmpty()) {
            current = -1
            addTab(home)
            return
        }
        if (wasCurrent) {
            current = -1
            selectTab(maxOf(0, index - 1))
        } else {
            updateNav()
        }
    }

    private fun showTabs() {
        val names = tabs.mapIndexed { i, t ->
            (if (i == current) "● " else "") + t.title
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Вкладки: ${tabs.size}")
            .setItems(names) { _, which -> selectTab(which) }
            .setPositiveButton("Новая") { _, _ -> addTab(home) }
            .setNegativeButton("Закрыть текущую") { _, _ -> if (current >= 0) closeTab(current) }
            .show()
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val web = WebView(this)
        web.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true

        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme ?: return false
                if (scheme == "http" || scheme == "https" || scheme == "about") return false
                try {
                    val intent = if (scheme == "intent") {
                        Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                            component = null
                            selector = null
                        }
                    } else {
                        Intent(Intent.ACTION_VIEW, uri)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // подходящего приложения нет, просто ничего не делаем
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (isCurrent(view)) {
                    setUrlText(url ?: "")
                    updateNav()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (isCurrent(view)) {
                    setUrlText(url ?: "")
                    updateNav()
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (!isCurrent(view)) return
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                val tab = tabs.firstOrNull { it.web === view } ?: return
                tab.title = if (title.isNullOrBlank()) "Без названия" else title
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                if (resultMsg == null) return false
                val newTab = addTab(null)
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newTab.web
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    startActivityForResult(fileChooserParams!!.createIntent(), FILE_REQ)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }

        return web
    }

    // ---------- Загрузки ----------

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            toast("Этот вид загрузки пока не поддерживается")
            return
        }
        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = { startDownload(url, userAgent, contentDisposition, mimeType) }
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), PERM_REQ)
            return
        }
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val req = DownloadManager.Request(Uri.parse(url))
            if (!mimeType.isNullOrEmpty()) req.setMimeType(mimeType)
            if (!userAgent.isNullOrEmpty()) req.addRequestHeader("User-Agent", userAgent)
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrEmpty()) req.addRequestHeader("Cookie", cookie)
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            toast("Загрузка: $name")
        } catch (e: Exception) {
            toast("Не удалось начать загрузку")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQ) {
            val job = pendingDownload
            pendingDownload = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                job?.invoke()
            } else {
                toast("Без разрешения файл сохранить нельзя")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_REQ) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // ---------- Жизненный цикл ----------

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.dataString?.let { addTab(it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val web = currentTab()?.web
        if (web != null && web.canGoBack()) {
            web.goBack()
        } else if (tabs.size > 1) {
            closeTab(current)
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        currentTab()?.web?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        currentTab()?.web?.onResume()
    }

    override fun onDestroy() {
        tabs.forEach { it.web.destroy() }
        tabs.clear()
        super.onDestroy()
    }
}
