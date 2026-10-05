# 수협 계약 규정 도우미 (Android WebView 앱)

`chatbot.html`(Gemini + 계약 규정 RAG)을 안드로이드 앱으로 감싼 개인 테스트용 프로젝트입니다.

- HTML은 `WebViewAssetLoader`로 `https://appassets.androidplatform.net/assets/chatbot.html` 주소에서 열리므로 **secure context**(https)로 동작합니다.
- WebView에는 Web Speech API가 없어서, 앱이 페이지보다 먼저 `speech-shim.js`를 주입해
  `window.SpeechRecognition` / `webkitSpeechRecognition` / `speechSynthesis` / `SpeechSynthesisUtterance`를
  네이티브 `SpeechRecognizer`(ko-KR, 중간 결과) · `TextToSpeech`(ko-KR, 속도 반영, 문장 큐)로 바꿉니다.
  기존 HTML 코드는 **한 줄도 고치지 않고** 그대로 씁니다.

## 폴더 구조

```
web/chatbot.html                ← 여기에 챗봇 HTML을 넣는다 (빌드 때 assets로 복사)
app/src/main/assets/speech-shim.js   ← Web Speech API 대체 shim
app/src/main/java/.../MainActivity.kt ← WebView, 권한, 뒤로가기, 회전
app/src/main/java/.../SpeechBridge.kt ← JavascriptInterface (SpeechRecognizer + TextToSpeech)
keystore/debug.keystore         ← 고정 디버그 서명 키 (Studio/Actions 빌드를 서로 덮어 설치 가능)
.github/workflows/android-debug-apk.yml
```

## API 키 넣는 방법 (둘 중 하나)

1. **HTML에 키가 그대로 있는 경우**: 아무것도 안 해도 됩니다.
2. **HTML에서 키를 빼는 경우(권장)**: HTML의 해당 줄을 `const API_KEY = "__GEMINI_API_KEY__";`로 바꾸고
   - Android Studio: 프로젝트 맨 위 `local.properties`에 `GEMINI_API_KEY=키값` 한 줄 추가
   - GitHub Actions: 저장소 Settings → Secrets and variables → Actions → `GEMINI_API_KEY` 등록

   빌드할 때 `const API_KEY = "..."` 부분이 그 키로 바뀌어 APK에 들어갑니다.
   (공개 저장소라면 APK 아티팩트에는 키가 들어 있으니, 내려받은 뒤 Actions 실행 기록을 지워도 됩니다. 아티팩트는 3일 뒤 자동 삭제됩니다.)

`web/`에 HTML이 없으면 "챗봇 HTML이 들어 있지 않아요" 안내 화면만 든 APK가 만들어집니다.

---

## (A) Android Studio로 빌드하기

1. **Android Studio 설치**: https://developer.android.com/studio 에서 내려받아 설치하고, 첫 실행 마법사에서 `Standard`를 골라 SDK까지 설치합니다.
2. **프로젝트 받기**: 이 저장소를 내려받습니다 (GitHub에서 `Code → Download ZIP` 후 압축 풀기, 또는 `git clone`).
3. **HTML 넣기**: 압축을 푼 폴더 안 `web` 폴더에 챗봇 파일을 `chatbot.html` 이름으로 복사합니다.
4. **열기**: Android Studio 시작 화면 → `Open` → 압축 푼 **최상위 폴더**(`settings.gradle.kts`가 보이는 폴더)를 선택 → `Trust Project`.
5. **Gradle 동기화 기다리기**: 오른쪽 아래 진행 막대가 끝날 때까지 기다립니다(처음엔 몇 분 걸림).
   - "Android SDK Platform 36 이 없다"는 안내가 뜨면 파란 링크(`Install ...`)를 눌러 설치합니다.
   - 업그레이드 도우미(AGP Upgrade Assistant)가 뜨면 일단 닫아도 됩니다.
6. (키를 HTML에서 뺐다면) 왼쪽 프로젝트 창 위쪽 보기를 `Project`로 바꾸고 `local.properties`를 열어 맨 아래에 `GEMINI_API_KEY=키값`을 추가 → 저장.
7. **빌드**: 메뉴 `Build → Build App Bundle(s) / APK(s) → Build APK(s)`
   (메뉴 이름이 다르면 `Build → Generate App Bundles or APKs → Generate APKs`).
8. 끝나면 오른쪽 아래 알림의 **`locate`** 를 누르면 APK 폴더가 열립니다.
   위치: `app/build/outputs/apk/debug/app-debug.apk`
9. (선택) 휴대폰을 USB로 연결해 바로 설치하려면: 휴대폰 `개발자 옵션 → USB 디버깅` 켜기 → 위쪽 기기 목록에서 휴대폰 선택 → ▶(Run) 버튼.

## (B) GitHub Actions로 빌드하기

워크플로 파일: `.github/workflows/android-debug-apk.yml` (push할 때마다, 또는 Actions 탭에서 수동 실행)

1. GitHub에서 **비공개(Private)** 저장소를 만듭니다. (이미 공개 저장소라면 Settings → General → 맨 아래 Danger Zone → `Change visibility` → Private)
2. 이 프로젝트 파일 전체를 올립니다.
   - 웹으로: 저장소 화면 `Add file → Upload files`에 프로젝트 폴더 안의 내용물을 끌어다 놓고 `Commit changes`.
     (`.github` 폴더처럼 점으로 시작하는 폴더가 안 보이면 탐색기에서 '숨긴 항목 표시'를 켜세요)
   - git으로:
     ```bash
     git init && git add . && git commit -m "수협 계약 규정 도우미 앱"
     git branch -M main
     git remote add origin https://github.com/<아이디>/<저장소>.git
     git push -u origin main
     ```
3. `web/chatbot.html`도 같이 올립니다(웹 업로드라면 `web` 폴더 안으로 들어가서 Upload files).
4. 키를 HTML에서 뺐다면 Settings → Secrets and variables → Actions → `New repository secret` → 이름 `GEMINI_API_KEY`, 값에 키.
5. 저장소의 **Actions** 탭 → `Android debug APK` 실행이 초록 체크가 될 때까지 기다립니다(약 3~6분).
   수동 실행: Actions → `Android debug APK` → `Run workflow`.
6. 실행 결과 화면 아래 **Artifacts** 의 `suhyup-contract-helper-debug-apk`를 내려받아 압축을 풀면 `app-debug.apk`가 있습니다.

---

## 휴대폰에 설치하기

1. APK를 휴대폰으로 옮깁니다: 카카오톡 '나와의 채팅', 구글 드라이브, USB 복사, 또는 휴대폰 브라우저로 GitHub에 로그인해 Artifacts를 직접 내려받기(zip이면 '내 파일'에서 압축 해제).
2. 휴대폰 **내 파일(파일 관리자)** 에서 `app-debug.apk`를 누릅니다.
3. "보안상 이 출처의 알 수 없는 앱은 설치할 수 없습니다"가 뜨면 **설정** → 지금 APK를 연 앱(내 파일 / 크롬 / 카카오톡 등)의 **"이 출처 허용"** 을 켜고 뒤로 돌아옵니다.
   - 삼성: 설정 → 애플리케이션 → 메뉴(⋮) → 특별한 접근 → 출처를 알 수 없는 앱 설치 → 해당 앱 허용
   - 픽셀: 설정 → 앱 → 특별한 앱 액세스 → 알 수 없는 앱 설치
4. **설치** 를 누릅니다. "Play 프로텍트" 경고가 나오면 `세부정보 더보기 → 무시하고 설치`(또는 `그래도 설치`).
5. 설치가 끝나면 앞에서 켠 "이 출처 허용"을 다시 꺼 두는 것이 안전합니다.
6. 새 버전으로 바꿀 때는 그냥 새 APK를 다시 설치하면 덮어써집니다(같은 디버그 키로 서명되므로 대화 설정·색인이 유지됨).
   "앱이 설치되지 않았습니다 / 패키지가 충돌" 이 나오면 기존 앱을 지우고 설치하세요.

## 동작 확인 체크리스트

**준비**
- [ ] 와이파이/데이터 연결됨
- [ ] 휴대폰에 **Google 앱**(음성 인식 엔진)과 **Google 음성 인식 및 합성**(TTS 엔진)이 설치·업데이트되어 있음
- [ ] 설정 → 일반 → 텍스트 음성 변환(TTS)에서 기본 엔진이 정해져 있고 **한국어 음성 데이터**가 설치됨
- [ ] 미디어 볼륨이 켜져 있음 (읽어주기는 미디어 볼륨을 씀)

**화면·기본**
- [ ] 앱 아이콘이 파란 바탕에 흰 물결, 이름이 "수협 계약 규정 도우미"
- [ ] 첫 화면 안내 문구에 "https가 아니라서…"나 "앱 안의 브라우저…" 같은 차단 메시지가 **없음**
- [ ] 예시 질문을 누르면 답이 오고, 조문 번호를 누르면 원문 패널이 열림
- [ ] 화면 상단·하단이 상태바/내비게이션바에 가리지 않고, 키보드가 올라와도 입력창이 보임

**음성 인식 (입력창 마이크 버튼)**
- [ ] 처음 누르면 **마이크 권한 요청** 창이 뜸 → 허용
- [ ] 말하는 동안 입력창에 글자가 **실시간으로(중간 결과)** 바뀜
- [ ] 말이 끝나면 문장이 확정되고, 설정의 '말이 끝나면 바로 보내기'가 켜져 있으면 바로 질문이 보내짐
- [ ] 말을 안 하고 기다리면 조용히 멈춤 (오류 창 없음)
- [ ] 권한을 '거부'하면 권한 안내 메시지가 나오고, 휴대폰 설정에서 허용 후 다시 되면 정상

**읽어주기 (TTS)**
- [ ] 설정 → '읽어주는 목소리' 목록에 한국어 목소리가 보임
- [ ] 설정 → '들어보기'를 누르면 한국어로 읽음
- [ ] 말하는 속도를 0.7 / 1.6 으로 바꿔 '들어보기' → 실제 속도가 달라짐
- [ ] 목소리를 바꾸면 소리도 바뀜
- [ ] 조문 보기의 읽어주기 버튼: 누르면 읽고, 다시 누르면 즉시 멈춤
- [ ] '답변 자동으로 읽어주기'를 켜면 답이 나오는 동안 **문장 단위로 차례로** 읽음

**음성 대화 (물결 버튼)**
- [ ] 열면 "듣고 있어요" → 말하면 글자가 실시간 표시
- [ ] 답을 찾은 뒤 읽어 주고, 다 읽으면 **자동으로 다시 듣기** 상태가 됨
- [ ] 읽는 도중 원을 누르면 읽기를 끊고 바로 듣기로 바뀜
- [ ] '읽기 멈추기' 버튼을 누르면 읽기가 멈춤

**뒤로가기·회전·전환**
- [ ] 음성 대화 / 설정 / 조문 보기 / 목차가 열려 있을 때 뒤로가기 → **패널만 닫힘**
- [ ] 받아쓰기 중 뒤로가기 → 받아쓰기만 멈춤
- [ ] 아무것도 안 열린 상태에서 뒤로가기 → 앱이 닫힘(백그라운드로)
- [ ] 대화 중 화면을 가로/세로로 돌려도 **대화 내용이 그대로**
- [ ] 홈 버튼으로 나갔다 돌아와도 대화 유지 (나갈 때 마이크는 자동으로 꺼짐)

## 문제 해결

| 증상 | 확인할 것 |
| --- | --- |
| "이 환경에서는 브라우저 음성 인식을 쓸 수 없어요" | Google 앱이 없거나 꺼져 있음 → Play 스토어에서 Google 앱 설치/사용 설정 |
| "음성 인식 서버에 연결하지 못했어요" | 인터넷 연결, 또는 Google 앱 업데이트 |
| 목소리 목록이 비어 있음 / 소리 안 남 | TTS 엔진·한국어 음성 데이터 설치, 미디어 볼륨 |
| "마이크를 쓸 수 없어요" | 통화·녹음·빅스비 등 다른 앱이 마이크를 쓰는 중 |
| 답변이 안 옴 (요청 실패 4xx) | API 키가 들어갔는지, 키에 웹사이트(리퍼러) 제한이 걸려 있지 않은지 |

PC 크롬에서 `chrome://inspect`를 열면 USB로 연결된 휴대폰의 앱 WebView를 개발자 도구로 볼 수 있습니다(디버그 빌드만).

## 참고

- 시스템 다크 모드를 앱이 켜진 상태에서 바꾸면(대화 유지를 위해 화면을 다시 만들지 않으므로) 앱을 다시 열거나 앱 설정 → 화면 → '테마'를 쓰세요.
- `keystore/debug.keystore`는 개인 테스트용 디버그 키입니다(비밀번호 `android`). 배포용 서명에는 쓰지 마세요.
- `continuous` 연속 인식은 지원하지 않습니다(한 번 말하면 끝남). 이 HTML은 `continuous=false`만 씁니다.
