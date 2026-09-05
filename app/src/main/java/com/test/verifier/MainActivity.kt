package com.test.verifier

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

class MainActivity : AppCompatActivity() {
    private data class Site(val name: String, val url: String, val host: String)

    private val sites = listOf(
        Site("Hanime1", "https://hanime1.me/login", "hanime1.me"),
        Site("Javchu", "https://javchu.com/login", "javchu.com"),
    )
    private lateinit var siteSpinner: Spinner
    private lateinit var urlInput: EditText
    private lateinit var webView: WebView
    private lateinit var preview: TextView
    private var lastCookieText = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        siteSpinner = Spinner(this)
        siteSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sites.map { it.name })
        urlInput = EditText(this).apply { setSingleLine(); hint = "登录页地址" }
        val btnOpen = Button(this).apply { text = "打开登录页"; setOnClickListener { openSite() } }
        val btnExtract = Button(this).apply { text = "提取 Cookie"; setOnClickListener { extractCookie() } }
        val btnShare = Button(this).apply { text = "导出 Cookie.txt"; setOnClickListener { shareCookie() } }
        val btnCopy = Button(this).apply { text = "复制 Cookie"; setOnClickListener { copyCookie() } }
        val btnClear = Button(this).apply { text = "清除当前站点 Cookie"; setOnClickListener { clearSiteCookie() } }
        preview = TextView(this).apply { textSize = 12f; setPadding(16, 16, 16, 16); setTextIsSelectable(true) }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
        }
        urlInput.setText(sites.first().url)
        siteSpinner.setOnItemSelectedListener(SimpleItemSelected { urlInput.setText(sites[it].url) })
        root.addView(siteSpinner)
        root.addView(urlInput)
        root.addView(btnOpen)
        root.addView(btnExtract)
        root.addView(btnShare)
        root.addView(btnCopy)
        root.addView(btnClear)
        root.addView(preview, LinearLayout.LayoutParams(-1, 130))
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        EchHttpClient.init(this)
        preview.text = "选择站点后打开登录页，完成登录/人机验证，再点提取 Cookie。\nJNI 已加载=${EchHttpClient.isLoaded}"
    }

    private fun currentSite(): Site {
        val selected = sites[siteSpinner.selectedItemPosition]
        val url = urlInput.text.toString().trim()
        return selected.copy(url = url.ifBlank { selected.url }, host = Uri.parse(url.ifBlank { selected.url }).host ?: selected.host)
    }

    private fun openSite() {
        val site = currentSite()
        webView.loadUrl(site.url)
        preview.text = "已打开 ${site.host}，请在下方完成登录。"
    }

    private fun extractCookie() {
        val site = currentSite()
        val cookie = CookieManager.getInstance().getCookie("https://${site.host}/").orEmpty()
        lastCookieText = cookie
        preview.text = if (cookie.isBlank()) "未找到 Cookie，请先完成登录/验证。" else redact(cookie)
    }

    private fun redact(cookie: String): String = cookie.split(';').joinToString("; ") {
        val name = it.substringBefore('=').trim()
        if (it.contains('=')) "$name=[REDACTED]" else it.trim()
    }

    private fun shareCookie() {
        if (lastCookieText.isBlank()) extractCookie()
        if (lastCookieText.isBlank()) return
        val file = File(cacheDir, "${currentSite().host}-cookie.txt")
        file.writeText("# Cookie 导出\n# 域名: ${currentSite().host}\nCookie: $lastCookieText\n")
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileProvider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "分享 Cookie.txt"))
    }

    private fun copyCookie() {
        if (lastCookieText.isBlank()) extractCookie()
        if (lastCookieText.isBlank()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Cookie", lastCookieText))
        Toast.makeText(this, "Cookie 已复制", Toast.LENGTH_SHORT).show()
    }

    private fun clearSiteCookie() {
        CookieManager.getInstance().setCookie("https://${currentSite().host}/", "clear=1; Max-Age=0; path=/")
        CookieManager.getInstance().flush()
        lastCookieText = ""
        preview.text = "已清除当前域名 Cookie。"
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private class SimpleItemSelected(val onSelected: (Int) -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = onSelected(position)
    }
}
