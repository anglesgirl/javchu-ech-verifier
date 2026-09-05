package com.test.verifier

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
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
import com.liar.han1meplus.EchHttpClient
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

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
    private lateinit var logView: TextView
    private var lastCookieText = ""
    private var lastHtml = ""

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        siteSpinner = Spinner(this)
        siteSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sites.map { it.name })
        urlInput = EditText(this).apply { setSingleLine(); hint = "登录页地址" }
        val btnEchLoad = Button(this).apply { text = "ECH加载登录页"; setOnClickListener { echLoad() } }
        val btnExtract = Button(this).apply { text = "提取 Cookie (ECH同步)"; setOnClickListener { extractCookie() } }
        val btnShare = Button(this).apply { text = "导出 Cookie.txt"; setOnClickListener { shareCookie() } }
        val btnCopy = Button(this).apply { text = "复制 Cookie"; setOnClickListener { copyCookie() } }
        val btnClear = Button(this).apply { text = "清除 Cookie"; setOnClickListener { clearSiteCookie() } }
        preview = TextView(this).apply { textSize = 12f; setPadding(16, 16, 16, 16); setTextIsSelectable(true) }
        logView = TextView(this).apply { textSize = 10f; setPadding(16, 8, 16, 8); setTextIsSelectable(true) }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectHijack()
                }
            }
            addJavascriptInterface(EchBridge(), "ECH")
        }
        urlInput.setText(sites.first().url)
        siteSpinner.onItemSelectedListener = SimpleItemSelected { urlInput.setText(sites[it].url) }
        root.addView(siteSpinner)
        root.addView(urlInput)
        root.addView(btnEchLoad)
        root.addView(btnExtract)
        root.addView(btnShare)
        root.addView(btnCopy)
        root.addView(btnClear)
        root.addView(preview, LinearLayout.LayoutParams(-1, 220))
        root.addView(logView, LinearLayout.LayoutParams(-1, 140))
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        EchHttpClient.init(this)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        preview.text = "选站点→ ECH加载 → 在下方页面登录 → 提取\nJNI=${EchHttpClient.isLoaded}  全程ECH fail-closed"
        log("ECH就绪 isLoaded=${EchHttpClient.isLoaded}")
    }

    private fun currentSite(): Site {
        val p = siteSpinner.selectedItemPosition.coerceIn(0, sites.size - 1)
        val s = sites[p]
        val url = urlInput.text.toString().trim()
        val u = if (url.isBlank()) s.url else url
        val host = try { Uri.parse(u).host ?: s.host } catch (_: Exception) { s.host }
        return s.copy(url = u, host = host)
    }

    private fun dohFor(host: String): String = when {
        host.contains("hanime1") -> "https://1.1.1.1/dns-query"
        host.contains("javchu") -> "https://1.1.1.1/dns-query"
        else -> "https://1.1.1.1/dns-query"
    }

    private fun echLoad() {
        val site = currentSite()
        val initialCk = try { CookieManager.getInstance().getCookie(site.url).orEmpty() } catch (_: Exception) { "" }
        preview.text = "ECH GET ${site.url} ..."
        log("ECH请求发起 ${site.url} isLoaded=${EchHttpClient.isLoaded} ckLen=${initialCk.length}")
        Thread {
            try {
                val headers = mutableMapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36",
                    "Accept" to "text/html,application/xhtml+xml",
                    "Accept-Language" to "zh-CN,zh;q=0.9",
                )
                if (initialCk.isNotBlank()) headers["Cookie"] = initialCk
                val resp = EchHttpClient.execute("GET", site.url, headers, null, dohFor(site.host), "")
                val setCookies = resp.headers.entries.filter { it.key.equals("set-cookie", true) }.flatMap { it.value }
                val html = resp.body.toString(Charsets.UTF_8)
                lastHtml = html
                val echOk = resp.echStatus.contains("accepted", true)
                runOnUiThread {
                    try {
                        syncCookiesToWebView(site, setCookies)
                    } catch (e: Throwable) { log("同步Cookie异常 ${e.message}") }
                    log("GET ${resp.statusCode} ech=${resp.echStatus} set-cookie=${setCookies.size} echOk=$echOk")
                    log("Set-Cookie: ${setCookies.take(3).joinToString(" | ") { it.take(80) }}")
                    if (!echOk) {
                        preview.text = "ECH未接受，已fail-closed，不渲染\n${resp.echStatus}"
                        return@runOnUiThread
                    }
                    // 三方同步后渲染，不走WebView直连
                    try {
                        webView.loadDataWithBaseURL(site.url, html, "text/html", "utf-8", null)
                    } catch (e: Throwable) { preview.text = "渲染失败 ${e.message}"; log("loadData异常 ${e.message}") ; return@runOnUiThread }
                    preview.text = "ECH加载完成已渲染(${html.length}字) ech=${resp.echStatus}\n请在下方完成登录/验证，提交会自动走ECH POST"
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                runOnUiThread { preview.text = "ECH GET失败: ${e::class.simpleName}:${e.message}"; log("GET异常 ${e::class.simpleName}:${e.message}") }
            }
        }.start()
    }

    private fun syncCookiesToWebView(site: Site, setCookies: List<String>) {
        val cm = CookieManager.getInstance()
        for (raw in setCookies) {
            // raw like: XSRF-TOKEN=xxx; path=/; domain=javchu.com; secure; httponly
            val cookieStr = raw.trim()
            if (cookieStr.isBlank()) continue
            // domain为当前host时直接setCookie
            cm.setCookie("https://${site.host}/", cookieStr)
            // 尝试带domain的也写入
            val domain = Regex("domain=([^;]+)", RegexOption.IGNORE_CASE).find(cookieStr)?.groupValues?.get(1)?.trim()?.trimStart('.')
            if (domain != null && domain != site.host) {
                cm.setCookie("https://$domain/", cookieStr)
            }
        }
        cm.flush()
        // 回读验证
        val merged = cm.getCookie("https://${site.host}/").orEmpty()
        lastCookieText = merged
    }

    private fun injectHijack() {
        val js = """
            (function(){
              if(window._echHooked) return; window._echHooked=true;
              function hijack(form){
                if(!form || form._ech) return;
                form._ech=true;
                form.addEventListener('submit', function(e){
                  try{
                    e.preventDefault(); e.stopPropagation();
                    const fd=new FormData(form);
                    const pairs=[];
                    for(const [k,v] of fd.entries()) pairs.push(encodeURIComponent(k)+'='+encodeURIComponent(v));
                    const body=pairs.join('&');
                    const action=form.action || location.href;
                    ECH.postLogin(JSON.stringify({url:action, body:body, html:document.documentElement.outerHTML.substring(0,2000)}));
                  }catch(err){ ECH.postLogin(JSON.stringify({url:location.href, body:'', err:String(err)})); }
                  return false;
                }, true);
              }
              document.querySelectorAll('form').forEach(hijack);
              const obs=new MutationObserver(()=>document.querySelectorAll('form').forEach(hijack));
              obs.observe(document.documentElement,{childList:true,subtree:true});
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class EchBridge {
        @JavascriptInterface
        fun postLogin(json: String) {
            // capture site on UI thread
            val siteCopy = runOnUiThreadCapture { currentSite() } ?: return
            val ckCopy = try { CookieManager.getInstance().getCookie("https://${siteCopy.host}/").orEmpty() } catch (_: Exception) { "" }
            runOnUiThread { log("拦截表单提交 $json".take(800)) }
            Thread {
                try {
                    val obj = org.json.JSONObject(json)
                    val url = obj.optString("url", siteCopy.url)
                    val body = obj.optString("body", "")
                    if (body.isBlank()) {
                        runOnUiThread { log("POST拦截body为空，忽略") }
                        return@Thread
                    }
                    val target = if (url.startsWith("http")) url else "https://${siteCopy.host}" + if (url.startsWith("/")) url else "/$url"
                    val headers = mutableMapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36",
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Accept" to "text/html,application/xhtml+xml",
                        "Origin" to "https://${siteCopy.host}",
                        "Referer" to siteCopy.url,
                    )
                    if (ckCopy.isNotBlank()) headers["Cookie"] = ckCopy
                    log("ECH POST $target bodyLen=${body.length}")
                    val resp = EchHttpClient.execute("POST", target, headers, body.toByteArray(), dohFor(siteCopy.host), "")
                    val setCookies = resp.headers.entries.filter { it.key.equals("set-cookie", true) }.flatMap { it.value }
                    val html = resp.body.toString(Charsets.UTF_8)
                    val echOk = resp.echStatus.contains("accepted", true)
                    val location = resp.headers.entries.firstOrNull { it.key.equals("location", true) }?.value?.firstOrNull().orEmpty()
                    runOnUiThread {
                        try { syncCookiesToWebView(siteCopy, setCookies) } catch (e: Throwable) { log("POST同步异常 ${e.message}") }
                        log("POST ${resp.statusCode} ech=${resp.echStatus} loc=$location set-cookie=${setCookies.size}")
                        if (!echOk) {
                            preview.text = "ECH POST未接受 fail-closed\n${resp.echStatus}"
                            return@runOnUiThread
                        }
                        try {
                            if (resp.statusCode in 300..399 && location.isNotBlank()) {
                                log("302跳转 $location")
                                preview.text = "登录POST 302 → $location 已同步Cookie"
                                webView.loadDataWithBaseURL(target, html, "text/html", "utf-8", null)
                            } else {
                                webView.loadDataWithBaseURL(target, html, "text/html", "utf-8", null)
                                preview.text = "ECH POST完成 ${resp.statusCode} ech=${resp.echStatus}\n${if (html.contains("auth.failed") || html.contains("419")) "419/失败" else "已渲染"} set-cookie=${setCookies.size}"
                            }
                        } catch (e: Throwable) { log("POST渲染异常 ${e.message}") }
                        extractCookie()
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    runOnUiThread { log("POST异常 ${e::class.simpleName}:${e.message}") }
                }
            }.start()
        }
    }

    private fun extractCookie() {
        val site = currentSite()
        val cookie = CookieManager.getInstance().getCookie("https://${site.host}/").orEmpty()
        lastCookieText = cookie
        preview.text = if (cookie.isBlank()) "未找到 Cookie，请先ECH加载并登录" else redact(cookie) + "\n\n原始长度 ${cookie.length} 已同步 JNI↔WebView"
        log("提取 Cookie ${if (cookie.isBlank()) "空" else "${cookie.length}字 ${cookie.split(';').size}条"}")
    }

    private fun redact(cookie: String): String = cookie.split(';').joinToString("; ") {
        val t = it.trim(); val i = t.indexOf('=')
        if (i > 0) t.substring(0, i).trim() + "=[REDACTED]" else t
    }

    private fun shareCookie() {
        if (lastCookieText.isBlank()) extractCookie()
        if (lastCookieText.isBlank()) { Toast.makeText(this, "无Cookie可导出", Toast.LENGTH_SHORT).show(); return }
        val file = File(cacheDir, "${currentSite().host}-cookie.txt")
        file.writeText("# Cookie ECH导出\n# 域名: ${currentSite().host}\n# 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}\nCookie: $lastCookieText\n")
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileProvider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "分享 Cookie.txt (ECH)"))
    }

    private fun copyCookie() {
        if (lastCookieText.isBlank()) extractCookie()
        if (lastCookieText.isBlank()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Cookie", lastCookieText))
        Toast.makeText(this, "Cookie已复制", Toast.LENGTH_SHORT).show()
    }

    private fun clearSiteCookie() {
        val site = currentSite()
        val cm = CookieManager.getInstance()
        // 清理：遍历当前cookie逐条过期
        val cur = cm.getCookie("https://${site.host}/").orEmpty()
        cur.split(';').forEach {
            val n = it.substringBefore('=').trim()
            if (n.isNotBlank()) cm.setCookie("https://${site.host}/", "$n=; Max-Age=0; path=/")
        }
        cm.flush(); lastCookieText = ""; preview.text = "已清除 ${site.host} Cookie"
        log("已清除Cookie")
    }

    private fun log(s: String) {
        try { runOnUiThread { logView.text = (s + "\n" + logView.text).take(4000) } } catch (_: Throwable) {}
        try { EchHttpClient.addLog(s) } catch (_: Throwable) {}
    }

    private fun <T> runOnUiThreadCapture(block: () -> T): T? {
        var res: T? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        runOnUiThread { try { res = block() } catch (_: Throwable) {} ; latch.countDown() }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return res
    }

    override fun onDestroy() { try{ webView.destroy()} catch(_:Exception){}; super.onDestroy() }

    private class SimpleItemSelected(val onSelected: (Int) -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = onSelected(position)
    }
}
