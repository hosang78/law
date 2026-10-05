/*
 * 미리 만든 규정 색인(벡터) 넣기
 * - 빌드 때 만든 assets/vectors/meta.json + vectors.bin 을 페이지가 쓰는 IndexedDB(rg_rag/vec)에 그대로 넣는다.
 * - 페이지는 색인을 만들기 전에 Gemini 모델 목록부터 받아 오므로, Gemini로 가는 fetch를 이 작업이 끝날 때까지
 *   잠깐 기다리게 한다. 그러면 페이지는 넣어 둔 벡터를 찾아 "규정 색인 준비 완료"로 바로 시작한다.
 * - 벡터 파일이 없거나 실패하면 아무것도 하지 않는다 (페이지가 예전처럼 직접 만든다).
 */
(function () {
  "use strict";
  if (window.__vectorPreload) return;
  var origFetch = window.fetch.bind(window);
  var BASE = "/assets/vectors/";

  function openDb() {
    return new Promise(function (res, rej) {
      var r = indexedDB.open("rg_rag", 1);
      r.onupgradeneeded = function () { r.result.createObjectStore("vec", { keyPath: "k" }); };
      r.onsuccess = function () { res(r.result); };
      r.onerror = function () { rej(r.error); };
    });
  }
  function has(db, key) {
    return new Promise(function (res) {
      var rq = db.transaction("vec", "readonly").objectStore("vec").get(key);
      rq.onsuccess = function () { res(!!rq.result); };
      rq.onerror = function () { res(false); };
    });
  }
  function setLS(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} }

  async function preload() {
    var res = await origFetch(BASE + "meta.json");
    if (!res.ok) return "none";
    var meta = await res.json();
    if (!meta.keys || !meta.keys.length) return "none";
    var key = function (k) { return meta.model + "|" + k; };

    // 페이지가 색인 버전이 다르면 저장된 벡터를 지우므로 맞춰 두고, 이 모델을 먼저 쓰게 한다
    setLS("rg_idx_v", meta.idxVersion);
    setLS("rg_embed_model", meta.model);

    var db = await openDb();
    if (await has(db, key(meta.keys[0])) && await has(db, key(meta.keys[meta.keys.length - 1]))) { db.close(); return "cached"; }

    var buf = await (await origFetch(BASE + "vectors.bin")).arrayBuffer();
    var size = meta.dims * 4;
    if (buf.byteLength < meta.keys.length * size) { db.close(); return "bad"; }
    await new Promise(function (res, rej) {
      var tx = db.transaction("vec", "readwrite"), os = tx.objectStore("vec");
      meta.keys.forEach(function (k, i) {
        // slice로 복사해야 조각마다 전체 버퍼가 저장되지 않는다
        os.put({ k: key(k), v: new Float32Array(buf.slice(i * size, (i + 1) * size)) });
      });
      tx.oncomplete = res;
      tx.onerror = function () { rej(tx.error); };
    });
    db.close();
    return "loaded " + meta.keys.length;
  }

  var done = preload().then(
    function (r) { console.log("[vector-preload]", r); },
    function (e) { console.warn("[vector-preload] 실패:", e && e.message); }
  );
  // 최대 10초만 기다린다
  var gate = Promise.race([done, new Promise(function (r) { setTimeout(r, 10000); })]);
  window.__vectorPreload = gate;

  window.fetch = function (input, init) {
    var url = typeof input === "string" ? input : (input && input.url) || String(input);
    if (/generativelanguage\.googleapis\.com/.test(url)) return gate.then(function () { return origFetch(input, init); });
    return origFetch(input, init);
  };
})();
