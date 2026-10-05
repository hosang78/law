import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.hosang78.suhyupcontract"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.hosang78.suhyupcontract"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"
    }

    signingConfigs {
        // Android Studio와 GitHub Actions가 같은 키로 서명해야 새 APK를 덮어 설치할 수 있다
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")   // 개인 테스트용
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.14.0")
}

/* =====================================================================
   web/ 폴더의 HTML을 앱 assets/chatbot.html 로 복사
   - GEMINI_API_KEY 가 주어지면 HTML 안의 `const API_KEY = "..."` 값을 그 키로 바꾼다
     (우선순위: 환경 변수 GEMINI_API_KEY → local.properties 의 GEMINI_API_KEY)
   - HTML이 없으면 안내 화면을 대신 넣는다 (빌드는 계속 됨)
   ===================================================================== */
abstract class PrepareWebAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val webDir: ConfigurableFileCollection

    @get:Input
    abstract val apiKey: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val target = File(out, "chatbot.html")
        val missingPage = """<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>수협 계약 규정 도우미</title>
<style>body{font:16px/1.7 system-ui,sans-serif;margin:0;padding:32px 20px;background:#EDF2F5;color:#13253A}
code{background:#fff;padding:2px 6px;border-radius:4px}</style></head><body>
<h2>챗봇 HTML이 들어 있지 않아요</h2>
<p>프로젝트의 <code>web</code> 폴더에 <code>chatbot.html</code>을 넣고 다시 빌드해 주세요.</p>
</body></html>"""
        val htmls = webDir.asFileTree.files.filter { it.isFile && it.extension.equals("html", ignoreCase = true) }
        val src = htmls.firstOrNull { it.name == "chatbot.html" } ?: htmls.minByOrNull { it.name }
        if (src == null) {
            logger.warn("w: web/chatbot.html 이 없어 안내 화면으로 빌드합니다.")
            target.writeText(missingPage)
            return
        }
        var html = src.readText(Charsets.UTF_8)
        val key = apiKey.get()
        if (key.isNotBlank()) {
            val re = Regex("""(const\s+API_KEY\s*=\s*)(["'])[^"']*\2""")
            val m = re.find(html)
            if (m != null) html = html.replaceRange(m.range, m.groupValues[1] + "\"" + key + "\"")
            else logger.warn("w: HTML에서 `const API_KEY = \"...\"` 줄을 찾지 못해 키를 넣지 않았습니다.")
        }
        if (html.contains("__GEMINI_API_KEY__")) {
            logger.warn("w: HTML의 API 키 자리(__GEMINI_API_KEY__)가 채워지지 않았습니다. GEMINI_API_KEY를 설정하세요.")
        }
        target.writeText(html, Charsets.UTF_8)

        // 미리 만든 규정 색인 (tools/build-vectors.mjs 결과, 없으면 앱이 첫 실행 때 직접 만듦)
        val vec = webDir.asFileTree.files.filter { it.parentFile?.name == "vectors" && it.name in setOf("meta.json", "vectors.bin") }
        if (vec.size == 2) {
            val dir = File(out, "vectors").apply { mkdirs() }
            vec.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
            logger.lifecycle("web/vectors → assets/vectors (미리 만든 색인 포함)")
        }
        logger.lifecycle("web/${src.name} → assets/chatbot.html" + if (key.isNotBlank()) " (API 키 주입)" else "")
    }
}

val geminiApiKey: String = System.getenv("GEMINI_API_KEY").orEmpty().trim().ifEmpty {
    val f = rootProject.file("local.properties")
    if (f.isFile) Properties().apply { f.inputStream().use { load(it) } }.getProperty("GEMINI_API_KEY", "").trim() else ""
}

val prepareWebAssets = tasks.register<PrepareWebAssets>("prepareWebAssets") {
    webDir.from(rootProject.layout.projectDirectory.dir("web"))
    apiKey.set(geminiApiKey)
    outputDir.set(layout.buildDirectory.dir("generated/webAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareWebAssets, PrepareWebAssets::outputDir)
    }
}
