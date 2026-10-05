package io.github.hosang78.suhyupcontract

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var speech: SpeechBridge

    private val micCallbacks = ArrayList<(Boolean) -> Unit>()
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "마이크 권한이 없어 음성 인식을 쓸 수 없어요.\n휴대폰 설정 > 애플리케이션 > ${getString(R.string.app_name)} > 권한에서 마이크를 허용해 주세요.",
                Toast.LENGTH_LONG
            ).show()
        }
        val cbs = micCallbacks.toList()
        micCallbacks.clear()
        cbs.forEach { it(granted) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)   // PC 크롬 chrome://inspect 로 디버깅 가능
        }

        val root = FrameLayout(this)
        webView = WebView(this)
        root.addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // 상태바·내비게이션바·노치·키보드 영역만큼 안쪽으로 밀어 웹 화면이 가려지지 않게
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        speech = SpeechBridge(this, ::emitToPage, ::ensureMicPermission)
        setupWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(function(){try{return !!(window.__androidSpeech&&window.__androidSpeech.back())}catch(e){return false}})()"
                ) { closed ->
                    if (closed != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(START_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        // 페이지보다 먼저 실행할 스크립트들 (음성 shim, 미리 만든 색인 넣기)
        val shim = INJECT_FILES.joinToString("\n;\n") { f -> assets.open(f).bufferedReader().use { it.readText() } }
        val startScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true                 // localStorage (설정 저장)
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            // 기본 UA의 "; wv" 표시가 있으면 페이지가 '앱 안 브라우저'로 판단해 마이크를 막으므로 제거
            userAgentString = userAgentString.replace("; wv)", ")") + " SuhyupContractHelper/" + appVersion()
        }
        webView.addJavascriptInterface(speech, "AndroidSpeech")

        if (startScript) {
            // 문서가 만들어지는 즉시(페이지 스크립트보다 먼저) shim 실행
            WebViewCompat.addDocumentStartJavaScript(webView, shim, setOf(ORIGIN))
        }

        val assetsHandler = WebViewAssetLoader.AssetsPathHandler(this)
        val loader = WebViewAssetLoader.Builder()
            .setDomain(DOMAIN)
            .addPathHandler("/assets/") { path ->
                if (!startScript && path.endsWith(".html")) injectShimTag(assetsHandler.handle(path))
                else assetsHandler.handle(path)
            }
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == DOMAIN) return false
                // 외부 링크는 휴대폰 기본 브라우저로
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "링크를 열 앱이 없어요", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // 페이지가 getUserMedia로 마이크를 직접 쓰는 경우(녹음 후 받아쓰기 등)도 허용
            override fun onPermissionRequest(request: PermissionRequest) {
                if (request.origin.host != DOMAIN ||
                    request.resources.any { it != PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                ) {
                    request.deny()
                    return
                }
                ensureMicPermission { granted ->
                    if (granted) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else request.deny()
                }
            }

            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d("WebConsole", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
    }

    private fun appVersion(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

    /** DOCUMENT_START_SCRIPT를 못 쓰는 오래된 WebView용: HTML <head> 맨 앞에 주입 스크립트 <script>를 끼워 넣는다 */
    private fun injectShimTag(res: WebResourceResponse?): WebResourceResponse? {
        val data = res?.data ?: return res
        val html = data.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val tag = INJECT_FILES.joinToString("") { "<script src=\"/assets/$it\"></script>" }
        val m = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        val out = if (m != null) html.replaceRange(m.range.last + 1, m.range.last + 1, tag) else tag + html
        return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(out.toByteArray(Charsets.UTF_8)))
    }

    /** 네이티브 → 페이지 이벤트 전달 (어느 스레드에서 불러도 됨) */
    private fun emitToPage(msg: JSONObject) {
        val js = "window.__androidSpeech&&window.__androidSpeech._emit($msg)"
        runOnUiThread { if (!isDestroyed) webView.evaluateJavascript(js, null) }
    }

    private fun ensureMicPermission(onResult: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
            return
        }
        micCallbacks.add(onResult)
        if (micCallbacks.size == 1) micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onPause() {
        speech.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        speech.destroy()
        webView.removeJavascriptInterface("AndroidSpeech")
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val DOMAIN = "appassets.androidplatform.net"   // WebViewAssetLoader.DEFAULT_DOMAIN
        private const val ORIGIN = "https://$DOMAIN"
        private const val START_URL = "$ORIGIN/assets/chatbot.html"
        private val INJECT_FILES = listOf("speech-shim.js", "vector-preload.js")
    }
}
