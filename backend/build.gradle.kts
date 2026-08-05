import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.hibernate.orm)
    alias(libs.plugins.ben.manes.versions)
    jacoco
}

group = "org.booklore"

// The repo-root VERSION file is the single source of truth for the app version.
// APP_VERSION (set by release automation and by the production Dockerfile) overrides
// it for tagged builds; the literal fallback only applies to a checkout without either.
val releaseVersion = System.getenv("APP_VERSION")?.takeIf { it.isNotBlank() }
val baseVersion = (releaseVersion
    ?: file("$rootDir/../VERSION").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    ?: "0.0.1-SNAPSHOT").removePrefix("v")

// Local builds append the build counter that `./booklib.sh rebuild` bumps, so two dev builds
// of the same VERSION are distinguishable. Nothing in Gradle writes the counter — a build that
// only runs tests leaves it alone, which is what keeps `test` up-to-date between runs.
// Release builds (APP_VERSION set) stay a clean semantic version so the GitHub release
// comparison in VersionService keeps working.
val buildNumber = if (releaseVersion != null) null else {
    file("$rootDir/../.build-number").takeIf { it.isFile }
        ?.readText()?.trim()?.takeIf { it.matches(Regex("\\d+")) }
}

version = if (buildNumber != null) "$baseVersion+$buildNumber" else baseVersion

providers.gradleProperty("externalBuildDir")
    .map { file(it) }
    .orNull
    ?.let { layout.buildDirectory.set(it) }

val defaultFrontendDistDir = file("${rootDir}/../frontend/dist/booklib/browser")
val configuredFrontendDistDir = providers.gradleProperty("frontendDistDir")
    .map { file(it) }

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("--enable-preview")
}

val useLocalLibs = providers.gradleProperty("useLocalLibs").isPresent
val mainSourceSet = the<SourceSetContainer>()["main"]
the<SourceSetContainer>().configureEach {
    java.exclude("**/._*")
    resources.exclude("**/._*")
}
val openApiOutputDir = layout.buildDirectory.dir("openapi")
val openApiOutputFile = openApiOutputDir.map { it.file("booklib-openapi.json") }
val openApiLogFile = openApiOutputDir.map { it.file("export-openapi.log") }
val openApiExportScript = layout.projectDirectory.file("scripts/export-openapi.sh")

repositories {
    if (useLocalLibs) mavenLocal()
    mavenCentral()
    maven(url = "https://jitpack.io")
    // Static, credential-free GitHub Pages Maven repo publishing the symbol-hidden epub4j build
    // (see the epub4j comment block below for why that build exists). Declared for ALL builds, not
    // just useLocalLibs ones, so CI and a fresh checkout resolve the same artifact.
    //
    // content { includeGroup(...) } is a guard, not a convenience: this is a third-party host
    // serving unsigned artifacts, so it must never be consulted for anything but the group it
    // exists to serve. It does NOT make org.grimmory exclusive to this repo -- mavenCentral is
    // still allowed to serve that group, which is exactly why the declaration order below matters:
    // org.grimmory:pdfium4j is found in an earlier repository, so this one is never contacted for
    // it, and a prakunin.github.io outage cannot take pdfium4j resolution down with it.
    maven(url = "https://prakunin.github.io/epub4j/maven/") {
        content { includeGroup("org.grimmory") }
    }
}

fun pdfiumNativesClassifier(): String {
    // Support cross-compilation: check for explicit target overrides first
    val targetPlatform = System.getenv("TARGETPLATFORM")
        ?: project.findProperty("targetPlatform")?.toString()
    val targetArch = System.getenv("TARGETARCH")
        ?: project.findProperty("targetArch")?.toString()

    val osName: String
    val arch: String

    if (targetPlatform != null) {
        // Docker TARGETPLATFORM format: linux/amd64, linux/arm64
        val parts = targetPlatform.split("/")
        osName = parts.getOrElse(0) { "linux" }
        arch = parts.getOrElse(1) { "amd64" }
    } else {
        osName = System.getProperty("os.name").lowercase()
        arch = targetArch ?: System.getProperty("os.arch").lowercase()
    }

    val osKey = when {
        "win" in osName -> "windows"
        "mac" in osName || "darwin" in osName -> "darwin"
        "nux" in osName || "linux" in osName -> {
            val libcOverride = (System.getenv("TARGETLIBC")
                ?: project.findProperty("targetLibc")?.toString())?.lowercase()
            val isMusl = when (libcOverride) {
                "musl" -> true
                "gnu", "glibc" -> false
                else -> if (targetPlatform != null) false else runCatching {
                    val libDir = File("/lib")
                    libDir.exists() && (libDir.listFiles()?.any { f -> f.name.startsWith("ld-musl-") } == true)
                }.getOrElse {
                    runCatching { File("/proc/self/maps").readText().contains("musl") }.getOrDefault(false)
                }
            }
            if (isMusl) "linux-musl" else "linux"
        }
        else -> error("Unsupported OS: $osName")
    }

    val archKey = when (arch) {
        "x86_64", "amd64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> error("Unsupported architecture: $arch")
    }

    return "natives-$osKey-$archKey"
}

fun epub4jNativesClassifier(): String {
    // Support cross-compilation: check for explicit target overrides first
    val targetPlatform = System.getenv("TARGETPLATFORM")
        ?: project.findProperty("targetPlatform")?.toString()
    val targetArch = System.getenv("TARGETARCH")
        ?: project.findProperty("targetArch")?.toString()

    val osName: String
    val arch: String

    if (targetPlatform != null) {
        // Docker TARGETPLATFORM format: linux/amd64, linux/arm64
        val parts = targetPlatform.split("/")
        osName = parts.getOrElse(0) { "linux" }
        arch = parts.getOrElse(1) { "amd64" }
    } else {
        osName = System.getProperty("os.name").lowercase()
        arch = targetArch ?: System.getProperty("os.arch").lowercase()
    }

    val osKey = when {
        "win" in osName -> "windows"
        "mac" in osName || "darwin" in osName -> "macos"
        "nux" in osName || "linux" in osName -> {
            val libcOverride = (System.getenv("TARGETLIBC")
                ?: project.findProperty("targetLibc")?.toString())?.lowercase()
            val isMusl = when (libcOverride) {
                "musl" -> true
                "gnu", "glibc" -> false
                else -> if (targetPlatform != null) false else runCatching {
                    val libDir = File("/lib")
                    libDir.exists() && (libDir.listFiles()?.any { f -> f.name.startsWith("ld-musl-") } == true)
                }.getOrElse {
                    runCatching { File("/proc/self/maps").readText().contains("musl") }.getOrDefault(false)
                }
            }
            if (isMusl) "linux-musl" else "linux"
        }
        else -> error("Unsupported OS: $osName")
    }

    val archKey = when (arch) {
        "x86_64", "amd64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> error("Unsupported architecture: $arch")
    }

    return "$osKey-$archKey"
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

val openApiExportRuntimeOnly by configurations.creating

// --- Book & Image Processing ---
// No one keeps a local pdfium4j build in mavenLocal today, so "+" bought nothing but an
// unpinned reach into mavenCentral's latest release -- it silently drifted useLocalLibs builds
// from the locked 1.2.0 to a newer remote version with different behavior. Pin it exactly like
// epub4j below; a future local pdfium4j build should get its own exact-version pin here (or the
// pin moved to that version), not a bare "+".
val pdfium4jVersion = libs.versions.pdfium4j.get()

// epub4j-grimmory fork publishes as org.grimmory:epub4j-core / :epub4j-native (native archive
// parsing). The pinned version is NOT a plain upstream release: `1.4.0-symbols-hidden.1` is a
// rebuild of the upstream 1.4.0 sources with hidden symbol visibility.
//
// WHY: upstream's libepub4j_native.so statically links its own libarchive, built without
// lzma/bz2/zstd, and exports all 270 archive_* symbols globally. nightcompress can then bind to
// that stripped copy instead of the system libarchive. It is a per-JVM coin flip, and in an
// affected process every lzma-compressed catalog archive is unreadable. The rebuild exports 0
// archive_* symbols, so no collision is possible. Diagnosis and the rebuild steps are in the
// workspace's .superpowers/sdd/2026-08-03-local-catalog-backfill/task-11a-fix-report.md (this repo
// checkout's parent directory, not tracked in this repo).
//
// WHERE: published to https://prakunin.github.io/epub4j/maven/ (declared in `repositories` above)
// from the fork github.com/prakunin/epub4j, branch fix/hide-vendored-symbols, tag
// v1.4.0-symbols-hidden.1. The artifacts are unsigned and the host is a personal GitHub Pages site.
//
// RETIRES WHEN: upstream PR grimmory-tools/epub4j#39 is merged AND released. At that point set this
// version to that upstream release and delete the prakunin.github.io repository declaration above;
// nothing else here depends on the fork.
val epub4jVersion = libs.versions.epub4j.get()
val epub4jCoords = "org.grimmory:epub4j-core:$epub4jVersion"
val epub4jNativeCoords = "org.grimmory:epub4j-native:$epub4jVersion"

dependencies {
    // --- Spring Boot ---
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation(libs.nimbus.jose.jwt)

    // --- Reactive Streams ---
    implementation("io.projectreactor:reactor-core")

    // --- Database & Migration ---
    implementation(libs.mariadb.java.client)
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation(libs.flyway.mysql)

    // --- Book & Image Processing ---
    implementation("org.grimmory:pdfium4j:$pdfium4jVersion")
    // Writes the DjVu PDF rendition. pdfium4j above only reads.
    implementation(libs.pdfbox)

    // --- TwelveMonkeys ImageIO ---
    implementation(libs.twelvemonkeys.imageio.jpeg)
    implementation(libs.twelvemonkeys.imageio.tiff)
    implementation(libs.twelvemonkeys.imageio.webp)
    implementation(libs.twelvemonkeys.imageio.bmp)

    implementation(epub4jCoords)
    implementation(epub4jNativeCoords)

    // --- Audio Metadata (Audiobook Support) ---
    implementation(libs.jaudiotagger)

    // --- Archive Support ---
    implementation(libs.nightcompress)

    // --- JSON & Web Scraping ---
    implementation(libs.jsoup)

    // --- i18n / Language Normalization ---
    implementation(libs.nv.i18n)

    // --- Mapping (DTOs & Entities) ---
    implementation(libs.mapstruct)

    // --- API Documentation ---
    implementation(libs.springdoc.openapi.starter.webmvc.api)
    implementation(libs.commons.compress)
    implementation(libs.xz) // Required by commons-compress for 7z support
    implementation(libs.commons.text)

    // --- MIME Detection ---
    implementation(libs.tika.core)

    // Apache POI: metadata (title/author) from Word documents — .docx via XWPF core properties,
    // .doc (and other OLE2) via HPSF SummaryInformation.
    implementation(libs.poi.ooxml)
    // Body text of legacy .doc lives in HWPF, which ships separately from poi/poi-ooxml. Pure Java
    // on the same POI version, and the only way to read a Word 97-2003 document without an office
    // suite in the image.
    implementation(libs.poi.scratchpad)

    // --- XML Support (JAXB) ---
    implementation(libs.jakarta.xml.bind.api)

    // --- Template Engine ---
    implementation(libs.freemarker)

    // --- Jackson 3 ---
    // Version is pinned via the dependencyManagement BOM import below. A plain
    // `platform(...)` import here is silently overridden by Spring Boot's managed
    // Jackson version, so it would leave jackson-core/databind on the older Boot
    // pin instead of the version we intend.
    implementation("tools.jackson.core:jackson-core")
    implementation("tools.jackson.core:jackson-databind")

    // --- Jackson 2 (Compatibility) ---
    // jackson-annotations version is managed by Jackson 3 BOM (requires 2.20+)
    implementation("com.fasterxml.jackson.core:jackson-annotations")

    // --- Caching ---
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation(libs.caffeine)

    // --- Lombok (For Clean Code) ---
    compileOnly(libs.lombok)

    // --- Annotation Processors ---
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.mapstruct.processor)

    // --- Native libraries (resolved at runtime only, keyed by platform classifier) ---
    runtimeOnly("org.grimmory:pdfium4j:$pdfium4jVersion:${pdfiumNativesClassifier()}")
    runtimeOnly("$epub4jNativeCoords:${epub4jNativesClassifier()}")
    runtimeOnly(libs.jaxb.runtime)

    // --- Test Dependencies ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-test-autoconfigure")
    testImplementation(libs.assertj.core)
    testRuntimeOnly("com.h2database:h2")

    // --- OpenAPI export tooling classpath ---
    add(openApiExportRuntimeOnly.name, "com.h2database:h2")
}

dependencyManagement {
    imports {
        // Import the Jackson 3 BOM here (not via a `platform(...)` dependency) so it
        // overrides the Jackson version managed by the Spring Boot BOM. Imports declared
        // here take precedence over Boot's managed versions, keeping jackson-core,
        // jackson-databind and jackson-annotations aligned on this BOM.
        mavenBom("tools.jackson:jackson-bom:${libs.versions.jackson.bom.get()}")
    }
    dependencies {
        // Keep flyway-core aligned with the explicitly pinned flyway-mysql module. Boot
        // manages flyway-core (to an older release), so a plain `implementation` version
        // would be overridden; declaring it here keeps core and the mysql module on the
        // same Flyway release train instead of leaving core several minors behind.
        dependency("org.flywaydb:flyway-core:${libs.versions.flyway.get()}")
    }
}

// Dependency locking pins every configuration to backend/gradle.lockfile. This used to be scoped to
// !useLocalLibs builds, because useLocalLibs deliberately resolved a different epub4j version from
// mavenLocal and so could not satisfy the lock through no fault of its own. That is no longer true:
// epub4j and pdfium4j are both pinned to exact versions that resolve identically with and without
// useLocalLibs, and no dependency in this build uses a dynamic version ("+", a range, or
// latest.release). useLocalLibs now only changes WHICH repository serves a module, never which
// version -- and lock verification checks versions -- so locking applies unconditionally and every
// build, local or CI, gets lock protection.
// If a dynamic or local-override version is ever reintroduced, do NOT re-add a blanket
// `if (!useLocalLibs)` guard: it disables lock verification for the WHOLE classpath, silently and
// without warning, not just for the dependency that needed the exception.
dependencyLocking {
    lockAllConfigurations()
}

hibernate {
    enhancement {
        enableAssociationManagement = false
        enableLazyInitialization = true
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    maxHeapSize = "2560m"
    jvmArgs("-XX:+EnableDynamicAgentLoading", "--enable-native-access=ALL-UNNAMED", "--enable-preview")
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.named<Copy>("processResources") {
    val frontendResourcesDir = configuredFrontendDistDir
        .orElse(providers.provider { defaultFrontendDistDir })
        .get()

    inputs.property("frontendDistDir", frontendResourcesDir.absolutePath)
    inputs.property("hasFrontendResources", frontendResourcesDir.exists())

    if (frontendResourcesDir.exists()) {
        from(frontendResourcesDir) {
            into("static")
        }
    }
}

// Generates META-INF/build-info.properties, which backs the BuildProperties bean that
// VersionService reads. Without it the version is only visible through the jar manifest,
// so `bootRun` (which runs from build/classes, not a jar) would report no version at all.
springBoot {
    buildInfo {
        // Drop build.time. It changes on every run, so leaving it in makes the generated
        // resource differ each build and knocks `classes` (and everything downstream,
        // including `test`) out of up-to-date. Setting properties.time to null does not
        // work — the task falls back to the build instant; excluding the key does.
        excludes.add("time")
    }
}

tasks.named<BootRun>("bootRun") {
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--enable-preview")
    if (System.getenv("REMOTE_DEBUG_ENABLED") == "true") {
        jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005")
    }
}

tasks.named<BootJar>("bootJar") {
    mainClass.set("org.booklore.BookloreApplication")
}

tasks.register("exportOpenApi") {
    group = "documentation"
    description = "Boot the backend with the openapi-export profile and write build/openapi/booklib-openapi.json."
    dependsOn(tasks.named("classes"))
    inputs.files(mainSourceSet.runtimeClasspath, openApiExportRuntimeOnly, openApiExportScript)
    outputs.file(openApiOutputFile)

    doLast {
        val outputFile = openApiOutputFile.get().asFile
        val logFile = openApiLogFile.get().asFile
        val classpath = files(mainSourceSet.runtimeClasspath, openApiExportRuntimeOnly).asPath
        val javaExecutable = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }.get().executablePath.asFile.absolutePath

        val result = ProcessBuilder(
            "bash",
            openApiExportScript.asFile.absolutePath,
            javaExecutable,
            classpath,
            outputFile.absolutePath
        )
            .directory(project.projectDir)
            .inheritIO()
            .apply {
                environment()["OPENAPI_EXPORT_LOG_FILE"] = logFile.absolutePath
            }
            .start()

        val exitCode = result.waitFor()
        check(exitCode == 0) { "OpenAPI export script failed with exit code $exitCode. See ${logFile.absolutePath}." }
    }
}

tasks.register("buildOpenApiArtifacts") {
    group = "build"
    description = "Build the backend jar and export build/openapi/booklib-openapi.json from the openapi-export profile."
    dependsOn(tasks.named("bootJar"), tasks.named("exportOpenApi"))
}
