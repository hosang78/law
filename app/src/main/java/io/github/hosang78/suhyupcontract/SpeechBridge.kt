package io.github.hosang78.suhyupcontract

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * speech-shim.js 와 짝을 이루는 JavascriptInterface (JS 이름: AndroidSpeech).
 *
 * JS 호출은 JavaBridge 스레드에서 들어오므로 실제 작업은 모두 메인 스레드로 넘긴다.
 * 결과는 [emit]으로 window.__androidSpeech._emit(...)에 전달한다.
 */
class SpeechBridge(
    private val context: Context,
    private val emit: (JSONObject) -> Unit,
    /** 마이크 권한이 없으면 요청하고 결과를 콜백으로 알려 준다 (메인 스레드에서 호출됨) */
    private val ensureMicPermission: (onResult: (Boolean) -> Unit) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    /* =====================================================================
       음성 인식 (SpeechRecognizer)
       ===================================================================== */
    private var recognizer: SpeechRecognizer? = null
    private var recId: String? = null          // 지금 듣고 있는 세션
    private var waitingId: String? = null      // 권한 응답을 기다리는 세션

    @JavascriptInterface
    fun startRecognition(id: String, lang: String, interim: Boolean, maxAlternatives: Int) {
        main.post {
            recId?.let { old ->                // 이전 세션이 남아 있으면 정리
                releaseRecognizer(cancel = true)
                srEvent(old, "error") { put("error", "aborted") }
                srEvent(old, "end")
            }
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                srEvent(id, "error") { put("error", "service-not-allowed") }
                srEvent(id, "end")
                return@post
            }
            waitingId = id
            ensureMicPermission { granted ->
                if (waitingId != id) return@ensureMicPermission   // 그사이 abort됨
                waitingId = null
                if (granted) beginRecognition(id, lang, interim, maxAlternatives)
                else {
                    srEvent(id, "error") { put("error", "not-allowed") }
                    srEvent(id, "end")
                }
            }
        }
    }

    @JavascriptInterface
    fun stopRecognition(id: String) {
        main.post {
            if (recId == id) recognizer?.stopListening()
            else {
                // 아직 시작 전(권한 대기 등)이거나 이미 끝난 세션: JS가 멈춰 있지 않도록 end를 보낸다
                if (waitingId == id) waitingId = null
                srEvent(id, "end")
            }
        }
    }

    /** JS 쪽에서 이미 error(aborted)/end를 보냈으므로 여기서는 조용히 끊기만 한다. */
    @JavascriptInterface
    fun abortRecognition(id: String) {
        main.post {
            if (waitingId == id) waitingId = null
            if (recId == id) {
                recId = null
                releaseRecognizer(cancel = true)
            }
        }
    }

    private fun beginRecognition(id: String, lang: String, interim: Boolean, maxAlternatives: Int) {
        releaseRecognizer(cancel = true)
        val r = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            Log.w(TAG, "SpeechRecognizer 생성 실패", e)
            srEvent(id, "error") { put("error", "service-not-allowed") }
            srEvent(id, "end")
            return
        }
        recognizer = r
        recId = id
        r.setRecognitionListener(object : RecognitionListener {
            private fun live() = recId == id
            private var started = false

            override fun onReadyForSpeech(params: Bundle?) {
                if (!live() || started) return
                started = true
                srEvent(id, "start")
                srEvent(id, "audiostart")
            }

            override fun onBeginningOfSpeech() {
                if (!live()) return
                srEvent(id, "soundstart")
                srEvent(id, "speechstart")
            }

            override fun onEndOfSpeech() {
                if (!live()) return
                srEvent(id, "speechend")
                srEvent(id, "soundend")
                srEvent(id, "audioend")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!live() || !interim) return
                val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (texts.isNullOrEmpty() || texts[0].isNullOrBlank()) return
                srEvent(id, "result") {
                    put("final", false)
                    put("transcripts", JSONArray(texts.take(maxAlternatives)))
                }
            }

            override fun onResults(results: Bundle?) {
                if (!live()) return
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                finish()
                if (texts.isNullOrEmpty() || texts[0].isNullOrBlank()) {
                    srEvent(id, "error") { put("error", "no-speech") }
                } else {
                    val n = minOf(texts.size, maxAlternatives)
                    srEvent(id, "result") {
                        put("final", true)
                        put("transcripts", JSONArray(texts.take(n)))
                        if (scores != null) put("confidences", JSONArray().apply {
                            for (i in 0 until minOf(n, scores.size)) put(scores[i].toDouble())
                        })
                    }
                }
                srEvent(id, "end")
            }

            override fun onError(error: Int) {
                if (!live()) return
                finish()
                srEvent(id, "error") { put("error", errorName(error)) }
                srEvent(id, "end")
            }

            private fun finish() {
                recId = null
                // 콜백 안에서 바로 destroy하지 않고 다음 차례에 정리
                main.post { if (recognizer === r && recId == null) releaseRecognizer(cancel = false) }
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            val tag = lang.ifBlank { "ko-KR" }
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxOf(1, maxAlternatives))
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening 실패", e)
            recId = null
            releaseRecognizer(cancel = true)
            srEvent(id, "error") { put("error", "audio-capture") }
            srEvent(id, "end")
        }
    }

    private fun releaseRecognizer(cancel: Boolean) {
        val r = recognizer ?: return
        recognizer = null
        try {
            if (cancel) r.cancel()
            r.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "SpeechRecognizer 정리 실패", e)
        }
    }

    /** SpeechRecognizer 오류 코드 → Web Speech API 오류 이름 */
    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER -> "network"
        SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "audio-capture"
        SpeechRecognizer.ERROR_CLIENT -> "aborted"
        10 /* ERROR_TOO_MANY_REQUESTS */ -> "network"
        11 /* ERROR_SERVER_DISCONNECTED */ -> "network"
        12 /* ERROR_LANGUAGE_NOT_SUPPORTED */, 13 /* ERROR_LANGUAGE_UNAVAILABLE */ -> "language-not-supported"
        else -> "error-$code"
    }

    private fun srEvent(id: String, type: String, fill: JSONObject.() -> Unit = {}) {
        emit(JSONObject().put("kind", "sr").put("id", id).put("type", type).apply(fill))
    }

    /* =====================================================================
       음성 출력 (TextToSpeech)
       ===================================================================== */
    private class Pending(
        val id: String, val text: String, val lang: String, val voice: String,
        val rate: Float, val pitch: Float, val volume: Float,
    )

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private val pending = ArrayList<Pending>()
    private var appliedVoiceKey: String? = null
    private var installedVoices: List<Voice> = emptyList()

    @Volatile
    private var voicesJson = "[]"

    init {
        main.post { initTts() }
    }

    private fun initTts() {
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post { onTtsInit(status) }
        }
    }

    private fun onTtsInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech 초기화 실패: $status")
            ttsFailed = true
            pending.forEach { ttsEvent(it.id, "error") { put("error", "synthesis-unavailable") } }
            pending.clear()
            return
        }
        t.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        t.setLanguage(Locale.KOREA)
        appliedVoiceKey = "lang:ko-KR"
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = part(utteranceId) { id, i, _ ->
                if (i == 0) ttsEvent(id, "start")
            }

            override fun onDone(utteranceId: String?) = part(utteranceId) { id, i, n ->
                if (i == n - 1) ttsEvent(id, "end")
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)

            override fun onError(utteranceId: String?, errorCode: Int) = part(utteranceId) { id, _, _ ->
                ttsEvent(id, "error") { put("error", "synthesis-failed") }
            }

            // stop()으로 끊긴 문장은 JS 쪽 cancel()이 이미 처리했으므로 무시
            override fun onStop(utteranceId: String?, interrupted: Boolean) {}
        })
        loadVoices(t)
        ttsReady = true
        pending.forEach { speakNow(it) }
        pending.clear()
        emit(JSONObject().put("kind", "tts").put("type", "voiceschanged"))
    }

    /** "u12#0/3" 처럼 나눠 읽은 조각 ID를 풀어 메인 스레드에서 처리 */
    private fun part(utteranceId: String?, block: (String, Int, Int) -> Unit) {
        val m = PART_ID.matchEntire(utteranceId ?: return) ?: return
        val id = m.groupValues[1]
        val i = m.groupValues[2].toInt()
        val n = m.groupValues[3].toInt()
        main.post { block(id, i, n) }
    }

    private fun loadVoices(t: TextToSpeech) {
        val all = try {
            t.voices.orEmpty().filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        } catch (e: Exception) {
            emptyList()
        }
        val korean = all.filter { it.locale.language == "ko" }
        val default = try { t.defaultVoice } catch (e: Exception) { null }
        installedVoices = (korean.ifEmpty { all }).sortedWith(
            compareBy<Voice>({ it != default }, { it.isNetworkConnectionRequired }, { it.name })
        )
        voicesJson = JSONArray().apply {
            installedVoices.forEachIndexed { i, v ->
                put(JSONObject()
                    .put("voiceURI", v.name)
                    .put("name", voiceLabel(v, i))
                    .put("lang", v.locale.toLanguageTag())
                    .put("localService", !v.isNetworkConnectionRequired)
                    .put("default", v == default))
            }
        }.toString()
    }

    private fun voiceLabel(v: Voice, index: Int): String {
        val lang = if (v.locale.language == "ko") "한국어" else v.locale.getDisplayName(Locale.KOREAN)
        val where = if (v.isNetworkConnectionRequired) "온라인 음성" else "기기 음성"
        return "$lang ${index + 1} · $where (${v.name})"
    }

    @JavascriptInterface
    fun getVoices(): String = voicesJson

    @JavascriptInterface
    fun ttsSpeak(id: String, text: String, lang: String, voice: String, rate: Float, pitch: Float, volume: Float) {
        val p = Pending(id, text, lang, voice, rate, pitch, volume)
        main.post {
            when {
                ttsFailed -> ttsEvent(id, "error") { put("error", "synthesis-unavailable") }
                !ttsReady -> pending.add(p)
                else -> speakNow(p)
            }
        }
    }

    @JavascriptInterface
    fun ttsStop() {
        main.post {
            pending.clear()
            tts?.stop()
        }
    }

    private fun speakNow(p: Pending) {
        val t = tts ?: return
        // 목소리 / 언어 (바뀐 경우에만 다시 설정)
        val chosen = if (p.voice.isNotBlank()) installedVoices.firstOrNull { it.name == p.voice } else null
        val key = if (chosen != null) "voice:${chosen.name}" else "lang:${p.lang.ifBlank { "ko-KR" }}"
        if (key != appliedVoiceKey) {
            if (chosen != null) t.setVoice(chosen)
            else t.setLanguage(Locale.forLanguageTag(p.lang.ifBlank { "ko-KR" }))
            appliedVoiceKey = key
        }
        t.setSpeechRate(p.rate.coerceIn(0.1f, 4f))
        t.setPitch(p.pitch.coerceIn(0.1f, 4f))
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, p.volume.coerceIn(0f, 1f))
        }

        val text = p.text.trim()
        if (text.isEmpty()) {             // 빈 문장(오디오 잠금 해제용)은 아주 짧은 무음으로 처리
            if (t.playSilentUtterance(1, TextToSpeech.QUEUE_ADD, "${p.id}#0/1") != TextToSpeech.SUCCESS) {
                ttsEvent(p.id, "error") { put("error", "synthesis-failed") }
            }
            return
        }
        val chunks = splitForTts(text, TextToSpeech.getMaxSpeechInputLength() - 100)
        chunks.forEachIndexed { i, chunk ->
            val r = t.speak(chunk, TextToSpeech.QUEUE_ADD, params, "${p.id}#$i/${chunks.size}")
            if (r != TextToSpeech.SUCCESS) {
                ttsEvent(p.id, "error") { put("error", "synthesis-failed") }
                return
            }
        }
    }

    private fun splitForTts(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > max) {
            val at = rest.lastIndexOfAny(charArrayOf('.', '?', '!', '\n', ' '), max - 1)
            val cut = if (at < max / 2) max else at + 1
            out.add(rest.substring(0, cut))
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotBlank()) out.add(rest)
        return out
    }

    private fun ttsEvent(id: String, type: String, fill: JSONObject.() -> Unit = {}) {
        emit(JSONObject().put("kind", "tts").put("id", id).put("type", type).apply(fill))
    }

    /* =====================================================================
       수명 주기
       ===================================================================== */

    /** 앱이 화면에서 사라지면 마이크를 놓는다 (읽어주기는 계속). */
    fun onPause() {
        // waitingId는 건드리지 않는다: 마이크 권한 창이 뜰 때도 onPause가 불리기 때문
        recId?.let { id ->
            recId = null
            releaseRecognizer(cancel = true)
            srEvent(id, "error") { put("error", "aborted") }
            srEvent(id, "end")
        }
    }

    fun destroy() {
        recId = null
        waitingId = null
        releaseRecognizer(cancel = true)
        tts?.let {
            it.stop()
            it.shutdown()
        }
        tts = null
    }

    companion object {
        private const val TAG = "SpeechBridge"
        private val PART_ID = Regex("""(.+)#(\d+)/(\d+)""")
    }
}
