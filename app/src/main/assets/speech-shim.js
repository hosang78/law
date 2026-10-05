/*
 * 안드로이드 WebView용 음성 shim
 * - window.SpeechRecognition / webkitSpeechRecognition → android.speech.SpeechRecognizer
 * - window.speechSynthesis / SpeechSynthesisUtterance   → android.speech.tts.TextToSpeech
 * 네이티브 쪽은 window.AndroidSpeech(JavascriptInterface)이고,
 * 네이티브에서 오는 이벤트는 window.__androidSpeech._emit(msg)로 들어온다.
 * 페이지 스크립트보다 먼저 실행되어야 하므로 앱이 문서 시작 시점에 주입한다.
 */
(function () {
  "use strict";
  if (window.__androidSpeech) return;
  var N = window.AndroidSpeech;
  if (!N) return;

  /* 앱 WebView 표시("; wv)")가 남아 있으면 페이지가 '카카오톡 같은 앱 안 브라우저'로 판단해 마이크를 막는다.
     앱이 User-Agent에서 이미 지우지만, 혹시 남아 있어도 페이지에는 지운 값이 보이게 한다. */
  try {
    var ua = navigator.userAgent;
    if (/wv\)/i.test(ua)) {
      var clean = ua.replace(/;\s*wv\)/i, ")").replace(/wv\)/i, ")");
      Object.defineProperty(navigator, "userAgent", { get: function () { return clean; }, configurable: true });
    }
  } catch (e) {}

  /* ---------- 이벤트 도우미 (on<type> 속성 + addEventListener) ---------- */
  function Emitter() {}
  Emitter.prototype.addEventListener = function (type, fn) {
    if (!fn) return;
    var m = this.__l || (this.__l = {});
    (m[type] || (m[type] = [])).push(fn);
  };
  Emitter.prototype.removeEventListener = function (type, fn) {
    var a = this.__l && this.__l[type];
    if (!a) return;
    var i = a.indexOf(fn);
    if (i >= 0) a.splice(i, 1);
  };
  Emitter.prototype.dispatchEvent = function (ev) {
    var h = this["on" + ev.type];
    try { if (typeof h === "function") h.call(this, ev); } catch (e) { setTimeout(function () { throw e; }); }
    var a = this.__l && this.__l[ev.type];
    if (a) a.slice().forEach(function (fn) {
      try { typeof fn === "function" ? fn.call(this, ev) : fn.handleEvent(ev); } catch (e) { setTimeout(function () { throw e; }); }
    }, this);
    return true;
  };
  function fire(target, type, props) {
    var ev = { type: type, target: target, currentTarget: target, timeStamp: Date.now(), preventDefault: function () {}, stopPropagation: function () {} };
    if (props) for (var k in props) ev[k] = props[k];
    target.dispatchEvent(ev);
  }
  function inherit(C) { C.prototype = Object.create(Emitter.prototype); C.prototype.constructor = C; }
  function listOf(arr) { arr.item = function (i) { return this[i] || null; }; return arr; }

  /* =====================================================================
     음성 인식
     ===================================================================== */
  var recs = {}, recSeq = 0;

  function SpeechRecognition() {
    this.lang = "";
    this.continuous = false;
    this.interimResults = false;
    this.maxAlternatives = 1;
    this.grammars = null;
    this._id = null;
    this._results = [];
  }
  inherit(SpeechRecognition);
  SpeechRecognition.prototype.start = function () {
    if (this._id) {
      var err = new Error("recognition has already started");
      err.name = "InvalidStateError";
      throw err;
    }
    var id = "r" + (++recSeq);
    this._id = id;
    this._results = [];
    recs[id] = this;
    N.startRecognition(id, String(this.lang || document.documentElement.lang || "ko-KR"),
      !!this.interimResults, Math.max(1, this.maxAlternatives | 0));
  };
  SpeechRecognition.prototype.stop = function () {
    if (this._id) N.stopRecognition(this._id);
  };
  SpeechRecognition.prototype.abort = function () {
    var id = this._id;
    if (!id) return;
    N.abortRecognition(id);
    this._end("aborted");
  };
  SpeechRecognition.prototype._end = function (error) {
    if (!this._id) return;
    delete recs[this._id];
    this._id = null;
    if (error) fire(this, "error", { error: error, message: "" });
    fire(this, "end");
  };
  SpeechRecognition.prototype._result = function (m) {
    var alts = listOf((m.transcripts || []).map(function (t, i) {
      return { transcript: t, confidence: m.confidences && m.confidences[i] != null ? m.confidences[i] : 0 };
    }));
    alts.isFinal = !!m.final;
    var rs = this._results;
    // 아직 확정되지 않은 마지막 결과는 새 결과로 바꾸고, 확정되면 그대로 둔다
    var idx = rs.length && !rs[rs.length - 1].isFinal ? rs.length - 1 : rs.length;
    rs[idx] = alts;
    fire(this, "result", { resultIndex: idx, results: listOf(rs.slice()) });
  };
  function onRecognition(m) {
    var r = recs[m.id];
    if (!r) return;               // 이미 abort된 세션 등
    switch (m.type) {
      case "result": r._result(m); break;
      case "error": r._end(m.error || "unknown"); break;
      case "end": r._end(null); break;
      default: fire(r, m.type);   // start, audiostart, soundstart, speechstart, speechend, soundend, audioend
    }
  }

  /* =====================================================================
     음성 출력
     ===================================================================== */
  function SpeechSynthesisUtterance(text) {
    this.text = text == null ? "" : String(text);
    this.lang = "";
    this.voice = null;
    this.rate = 1;
    this.pitch = 1;
    this.volume = 1;
  }
  inherit(SpeechSynthesisUtterance);

  var queue = [], byId = {}, uttSeq = 0, voicesCache = null;

  function SpeechSynthesis() { this.onvoiceschanged = null; this.paused = false; }
  inherit(SpeechSynthesis);
  Object.defineProperty(SpeechSynthesis.prototype, "speaking", { get: function () { return queue.length > 0; } });
  Object.defineProperty(SpeechSynthesis.prototype, "pending", { get: function () { return queue.length > 1; } });
  SpeechSynthesis.prototype.getVoices = function () {
    if (!voicesCache) {
      try { voicesCache = JSON.parse(N.getVoices() || "[]"); } catch (e) { voicesCache = []; }
    }
    return voicesCache.slice();
  };
  SpeechSynthesis.prototype.speak = function (u) {
    if (!(u instanceof SpeechSynthesisUtterance)) throw new TypeError("SpeechSynthesisUtterance가 필요합니다");
    var id = "u" + (++uttSeq);
    queue.push(id);
    byId[id] = u;
    var v = u.voice, num = function (x, d) { x = +x; return isFinite(x) ? x : d; };
    N.ttsSpeak(id, String(u.text == null ? "" : u.text), String((v && v.lang) || u.lang || "ko-KR"),
      String((v && v.voiceURI) || ""), num(u.rate, 1), num(u.pitch, 1), num(u.volume, 1));
  };
  SpeechSynthesis.prototype.cancel = function () {
    var ids = queue;
    queue = [];
    var olds = ids.map(function (id) { var u = byId[id]; delete byId[id]; return u; });
    N.ttsStop();
    this.paused = false;
    // 브라우저처럼 취소된 문장마다 error 이벤트를 보낸다 (앞쪽 = 읽던 문장은 interrupted)
    olds.forEach(function (u, i) { if (u) fire(u, "error", { error: i === 0 ? "interrupted" : "canceled", utterance: u, charIndex: 0, elapsedTime: 0 }); });
  };
  SpeechSynthesis.prototype.pause = function () { this.paused = true; };   // TextToSpeech에는 일시정지가 없음
  SpeechSynthesis.prototype.resume = function () { this.paused = false; };

  var synth = new SpeechSynthesis();

  function finishUtterance(id) {
    var i = queue.indexOf(id);
    if (i >= 0) queue.splice(i, 1);
    var u = byId[id];
    delete byId[id];
    return u;
  }
  function onSynthesis(m) {
    if (m.type === "voiceschanged") { voicesCache = null; fire(synth, "voiceschanged"); return; }
    var u = byId[m.id];
    if (!u) return;               // cancel()로 이미 정리된 문장
    if (m.type === "start") fire(u, "start", { utterance: u, charIndex: 0, elapsedTime: 0 });
    else if (m.type === "end") { finishUtterance(m.id); fire(u, "end", { utterance: u, charIndex: 0, elapsedTime: 0 }); }
    else if (m.type === "error") { finishUtterance(m.id); fire(u, "error", { error: m.error || "synthesis-failed", utterance: u, charIndex: 0, elapsedTime: 0 }); }
  }

  /* ---------- 전역 교체 ---------- */
  function define(name, value) {
    try { Object.defineProperty(window, name, { value: value, configurable: true, writable: true }); }
    catch (e) { window[name] = value; }
  }
  define("SpeechRecognition", SpeechRecognition);
  define("webkitSpeechRecognition", SpeechRecognition);
  define("speechSynthesis", synth);
  define("SpeechSynthesisUtterance", SpeechSynthesisUtterance);

  /* ---------- 네이티브 → JS 입구, 뒤로가기 처리 ---------- */
  window.__androidSpeech = {
    _emit: function (m) {
      if (typeof m === "string") m = JSON.parse(m);
      if (m.kind === "sr") onRecognition(m);
      else if (m.kind === "tts") onSynthesis(m);
    },
    /** 열린 패널이 있으면 닫고 true, 없으면 false (앱이 종료 처리) */
    back: function () {
      var $ = function (id) { return document.getElementById(id); };
      var has = function (id, cls) { var el = $(id); return !!(el && el.classList.contains(cls)); };
      if (has("micBtn", "live")) { $("micBtn").click(); return true; }   // 받아쓰기 중이면 멈춤
      var open = has("voice", "show") || has("settings", "show") || has("shell", "viewing") || has("shell", "toc-open");
      if (!open) return false;
      document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
      return true;
    },
  };
})();
