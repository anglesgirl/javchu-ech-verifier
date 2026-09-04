package com.test.verifier

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private lateinit var logTv: TextView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logTv = TextView(this).apply { textSize = 12f; setPadding(24,24,24,24) }
        val btn = Button(this).apply { text = "测试 javchu ECH (GET+POST)" ; setOnClickListener { runTest() } }
        val sv = ScrollView(this).apply { addView(logTv) }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(btn)
            addView(sv, android.widget.LinearLayout.LayoutParams(-1,0,1f))
        }
        setContentView(root)
        EchHttpClient.init(this)
        append("Ech loaded=${EchHttpClient.isLoaded}\nDoH=82sew1c85i.cloudflare-gateway.com/dns-query\n")
    }
    private fun append(s: String){ logTv.append(s+"\n") }
    private fun runTest(){
        logTv.text=""
        append("=== javchu verifier BoringSSL+curl 全日志 ===")
        scope.launch(Dispatchers.IO){
            val dohUrl="https://82sew1c85i.cloudflare-gateway.com/dns-query"
            val dohResolve="82sew1c85i.cloudflare-gateway.com:443:162.159.36.20,162.159.36.5"
            suspend fun doReq(method:String, url:String, body:ByteArray?){
                withContext(Dispatchers.Main){ append("\n[$method] $url") }
                try{
                    val r = EchHttpClient.execute(method, url, mapOf("User-Agent" to "Mozilla/5.0"), body, dohUrl, dohResolve)
                    withContext(Dispatchers.Main){
                        append("-> ${r.statusCode} ech=${r.echStatus}")
                        append("headers: ${r.headers.entries.joinToString { "${it.key}=${it.value.take(1)}" }.take(300)}")
                        append("bodyLen=${r.body.size} preview=${String(r.body).take(500).replace("\n"," ")}")
                        val logs = EchHttpClient.logs().joinToString("\n")
                        append("echLogs:\n$logs")
                    }
                }catch(e:Exception){
                    withContext(Dispatchers.Main){ append("FAIL ${e.message}") }
                }
            }
            doReq("GET","https://javchu.com/login", null)
            // 尝试 POST 空表单看 419
            doReq("POST","https://javchu.com/login", "email=test@example.com&password=123456&_token=dummy".toByteArray())
            withContext(Dispatchers.Main){ append("\n=== 完成 ===")}
        }
    }
}
