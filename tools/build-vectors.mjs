// 규정 조문 벡터(의미 검색 색인)를 미리 만들어 web/vectors/ 에 저장한다.
// GitHub Actions에서 APK를 만들 때 실행되며, 앱은 첫 실행 때 이 벡터를 그대로 IndexedDB에 넣는다.
//   node tools/build-vectors.mjs [출력 폴더=web/vectors]
// - API 키: 환경 변수 GEMINI_API_KEY
// - 이전 결과(같은 폴더)가 있으면 조각 해시가 같은 것은 재사용하고 빠진 것만 만든다
// - 호출 한도 등으로 중간에 멈춰도 만든 만큼 저장하고 정상 종료한다 (나머지는 앱이 채움)
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";

const API_ROOT = process.env.API_ROOT || "https://generativelanguage.googleapis.com/v1beta/";
const KEY = (process.env.GEMINI_API_KEY || "").trim();
const OUT = process.argv[2] || "web/vectors";
const BUDGET_MS = Number(process.env.VECTOR_BUDGET_MIN || 15) * 60_000;
const BATCH = 20;
const t0 = Date.now();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.log("[vectors]", ...a);

/* ---------- HTML에서 조문 데이터와 해시 함수 가져오기 (페이지와 똑같은 키를 쓰기 위해 페이지 코드를 그대로 사용) ---------- */
const webDir = path.dirname(OUT);
const htmls = fs.readdirSync(webDir).filter((f) => /\.html$/i.test(f)).sort();
const htmlFile = htmls.includes("chatbot.html") ? "chatbot.html" : htmls[0];
if (!htmlFile) { log("HTML이 없어 건너뜁니다."); process.exit(0); }
const html = fs.readFileSync(path.join(webDir, htmlFile), "utf8");
const kb = JSON.parse(html.match(/<script type="application\/json" id="kb">([\s\S]*?)<\/script>/)[1]);
const fnText = html.match(/const chunkEmbedText = [^\n]+/)?.[0];
const hashText = html.match(/function strHash\([^\n]+/)?.[0];
const hashLine = html.match(/const CH_HASH = CH\.map\(([^\n]+)\);/)?.[1];
if (!fnText || !hashText || !hashLine) { log("HTML의 색인 코드 모양이 달라 건너뜁니다."); process.exit(0); }
const lib = new Function(`${fnText}\n${hashText}\nreturn { chunkEmbedText, strHash, hashOf: ${hashLine} };`)();
const CH = kb.chunks;
const keys = CH.map(lib.hashOf);
const texts = CH.map(lib.chunkEmbedText);
const idxVersion = Number(html.match(/store\.get\("rg_idx_v",\s*0\)\s*!==\s*(\d+)/)?.[1] || 2);
log(`${htmlFile}: 조각 ${CH.length}개, 색인 버전 ${idxVersion}`);

/* ---------- 이전 결과 불러오기 ---------- */
fs.mkdirSync(OUT, { recursive: true });
let prev = { model: null, map: new Map() };
try {
  const meta = JSON.parse(fs.readFileSync(path.join(OUT, "meta.json"), "utf8"));
  const buf = fs.readFileSync(path.join(OUT, "vectors.bin"));
  const map = new Map();
  meta.keys.forEach((k, i) => map.set(k, new Float32Array(buf.buffer.slice(buf.byteOffset + i * meta.dims * 4, buf.byteOffset + (i + 1) * meta.dims * 4))));
  prev = { model: meta.model, map };
  log(`이전 결과 ${map.size}개 (${meta.model})`);
} catch { /* 처음 */ }

if (!KEY) { log("GEMINI_API_KEY가 없어 새로 만들지 않습니다."); process.exit(0); }

/* ---------- 모델 고르기 (페이지의 embedCandidates와 같은 우선순위) ---------- */
async function api(url, body) {
  for (let i = 0; ; i++) {
    try {
      return await fetch(API_ROOT + url, {
        method: body ? "POST" : "GET",
        headers: { "Content-Type": "application/json", "x-goog-api-key": KEY },
        body: body ? JSON.stringify(body) : undefined,
      });
    } catch (e) {
      if (i >= 3) throw e;
      await sleep(3000 * (i + 1));
    }
  }
}
let candidates = [];
try {
  const r = await api("models?pageSize=200");
  if (r.ok) {
    const models = (await r.json()).models || [];
    candidates = models.filter((m) => /embed/i.test(m.name) && (m.supportedGenerationMethods || []).some((x) => /embed/i.test(x)))
      .map((m) => m.name.replace("models/", ""))
      .sort((a, b) => (/gemini-embedding/.test(b) - /gemini-embedding/.test(a)) || b.localeCompare(a, "en", { numeric: true }));
  } else log(`모델 목록 실패 (${r.status})`);
} catch (e) { log("모델 목록 실패:", e.message); }
candidates = [...new Set([prev.model, ...candidates, "gemini-embedding-001", "text-embedding-004"].filter(Boolean))];

function normalize(v) { let n = 0; for (const x of v) n += x * x; n = Math.sqrt(n) || 1; return Float32Array.from(v, (x) => x / n); }

/* ---------- 벡터 만들기 ---------- */
let useTaskType = true, result = null;
for (const model of candidates) {
  const vecs = new Map();
  if (model === prev.model) keys.forEach((k) => prev.map.has(k) && vecs.set(k, prev.map.get(k)));
  const missing = keys.map((k, i) => i).filter((i) => !vecs.has(keys[i]));
  log(`${model}: 재사용 ${vecs.size}개, 새로 만들 것 ${missing.length}개`);
  let stop = null, unusable = false;
  for (let s = 0; s < missing.length && !stop; ) {
    if (Date.now() - t0 > BUDGET_MS) { stop = "시간 제한"; break; }
    const batch = missing.slice(s, s + BATCH);
    const body = { requests: batch.map((i) => ({ model: `models/${model}`, content: { parts: [{ text: texts[i] }] }, ...(useTaskType ? { taskType: "RETRIEVAL_DOCUMENT" } : {}) })) };
    let r;
    try { r = await api(`models/${model}:batchEmbedContents`, body); } catch (e) { stop = "네트워크 오류: " + e.message; break; }
    if (r.ok) {
      const out = (await r.json()).embeddings || [];
      if (out.length !== batch.length) { stop = "임베딩 개수가 맞지 않음"; break; }
      batch.forEach((i, j) => vecs.set(keys[i], normalize(out[j].values)));
      s += BATCH;
      log(`  ${vecs.size}/${keys.length}`);
      await sleep(300);
      continue;
    }
    let msg = ""; try { msg = (await r.json()).error?.message || ""; } catch {}
    if (r.status === 400 && useTaskType && /task/i.test(msg)) { useTaskType = false; continue; }
    if (r.status === 429 && !/limit:\s*0/.test(msg)) {
      const sec = parseFloat(msg.match(/retry in ([\d.]+)s/i)?.[1] || "30");
      if (sec <= 120) { log(`  호출 한도: ${Math.ceil(sec + 1)}초 대기`); await sleep((sec + 1) * 1000); continue; }
      stop = "하루 호출 한도 초과"; break;
    }
    if (r.status >= 500) { await sleep(5000); continue; }
    if ([400, 404].includes(r.status) || (r.status === 429 && vecs.size === 0)) { unusable = true; log(`  이 모델은 쓸 수 없음 (${r.status}) ${msg.split("\n")[0]}`); break; }
    stop = `요청 실패 (${r.status}) ${msg.split("\n")[0]}`;
  }
  if (unusable) continue;
  result = { model, vecs, stop };
  break;
}
if (!result || !result.vecs.size) { log("만든 벡터가 없습니다. 앱이 처음 실행될 때 직접 만듭니다."); process.exit(0); }

/* ---------- 저장 ---------- */
const order = keys.filter((k, i, a) => result.vecs.has(k) && a.indexOf(k) === i);
const dims = result.vecs.get(order[0]).length;
const bin = Buffer.alloc(order.length * dims * 4);
order.forEach((k, i) => Buffer.from(result.vecs.get(k).buffer).copy(bin, i * dims * 4));
const version = crypto.createHash("sha1").update(result.model).update(bin).digest("hex").slice(0, 12);
fs.writeFileSync(path.join(OUT, "vectors.bin"), bin);
fs.writeFileSync(path.join(OUT, "meta.json"), JSON.stringify({ version, model: result.model, dims, idxVersion, total: keys.length, keys: order }));
log(`저장: ${order.length}/${new Set(keys).size}개, ${dims}차원, ${(bin.length / 1048576).toFixed(1)}MB, 모델 ${result.model}` + (result.stop ? ` (중단: ${result.stop})` : ""));
if (process.env.GITHUB_STEP_SUMMARY) {
  fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `### 미리 만든 규정 색인\n- ${order.length}/${new Set(keys).size}개 조각, 모델 \`${result.model}\`${result.stop ? `\n- 중단: ${result.stop} (나머지는 앱이 첫 실행 때 만듦)` : ""}\n`);
}
