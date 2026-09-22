import com.android.build.api.artifact.SingleArtifact
import com.android.build.gradle.internal.tasks.AndroidTestTask
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Element
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
}

/** Rejects missing device results instead of accepting a successful runner process. */
object DeviceTestResults {
    private fun reports(directory: File): List<File> =
        directory.walkTopDown().filter {
            it.isFile && it.name.startsWith("TEST-") && it.extension == "xml"
        }.toList()

    fun clear(directory: File) {
        reports(directory).forEach { report ->
            check(report.delete()) { "Cannot remove stale device-test report: $report" }
        }
    }

    fun verify(directory: File): Int {
        val reports = reports(directory)
        check(reports.isNotEmpty()) {
            "No device-test reports were produced in $directory. " +
                "The instrumentation runner may not have started; see CONTRIBUTING.md."
        }
        return reports.sumOf { report ->
            try {
                verifyReport(report)
            } catch (exception: Exception) {
                throw GradleException(
                    "Device-test verification failed for $report: ${exception.message}. " +
                        "A successful runner process is not a test pass; see CONTRIBUTING.md.",
                    exception
                )
            }
        }
    }

    private fun verifyReport(report: File): Int {
        val factory = DocumentBuilderFactory.newDefaultInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder().apply {
            setErrorHandler(object : DefaultHandler() {
                override fun error(exception: SAXParseException) = throw exception
                override fun fatalError(exception: SAXParseException) = throw exception
            })
        }
        val root = builder.parse(report).documentElement
        check(root.tagName == "testsuite" || root.tagName == "testsuites") {
            "Not a JUnit test report"
        }
        val declaredTests = root.getAttribute("tests").toIntOrNull()
        check(declaredTests != null && declaredTests > 0) { "No tests completed" }
        listOf("failures", "errors").forEach { attribute ->
            check(root.getAttribute(attribute).toIntOrNull() == 0) {
                "Report has $attribute or lacks a valid $attribute count"
            }
        }
        check(root.getElementsByTagName("failure").length == 0 &&
            root.getElementsByTagName("error").length == 0) {
            "Report contains a failed test"
        }
        val cases = root.getElementsByTagName("testcase")
        check(cases.length == declaredTests) { "Test count does not match reported test cases" }
        val completed = (0 until cases.length).count { index ->
            val case = cases.item(index) as Element
            case.getElementsByTagName("skipped").length == 0 &&
                case.getAttribute("status") != "notrun"
        }
        check(completed > 0) { "No tests completed (all were skipped)" }
        return completed
    }
}

// AGP 9.4.1 can report success after `am instrument` rejects userId -2, leaving
// only a zero-test XML report. Use the runner's actual resultsDir, not a guessed
// build path. This AGP-specific hook must be revisited when its task API changes.
tasks.configureEach {
    if (this !is AndroidTestTask) return@configureEach
    val resultDirectory = resultsDir
    doFirst {
        // AGP normally clears these too; doing so before it runs also covers an
        // early return and prevents a previous successful run from masking it.
        DeviceTestResults.clear(resultDirectory.get().asFile)
    }
    doLast {
        val count = DeviceTestResults.verify(resultDirectory.get().asFile)
        logger.lifecycle("Verified $count completed device test(s) from fresh reports.")
    }
}

val verifyDeviceTestResultsGuard = tasks.register("verifyDeviceTestResultsGuard") {
    group = "verification"
    description = "Checks that missing, empty, stale, or failed device results cannot pass"
    doLast {
        val directory = temporaryDir.resolve("reports")
        check(directory.mkdirs() || directory.isDirectory)
        val report = directory.resolve("TEST-fixture.xml")
        fun expectFailure(label: String, action: () -> Unit) {
            check(runCatching(action).isFailure) { "Guard incorrectly accepted $label" }
        }
        fun suite(body: String, tests: Int = 1, failures: Int = 0, errors: Int = 0) =
            "<testsuite tests=\"$tests\" failures=\"$failures\" errors=\"$errors\">$body</testsuite>"
        val passed = "<testcase classname=\"Fixture\" name=\"passed\" time=\"0.1\"/>"
        val skipped = "<testcase classname=\"Fixture\" name=\"skipped\"><skipped/></testcase>"

        DeviceTestResults.clear(directory)
        expectFailure("missing reports") { DeviceTestResults.verify(directory) }
        val rejected = listOf(
            "zero tests" to "<testsuites tests=\"0\" failures=\"0\" errors=\"0\"/>",
            "missing test cases" to suite(""),
            "mismatched test count" to suite(passed, tests = 2),
            "failed counter" to suite(passed, failures = 1),
            "error counter" to suite(passed, errors = 1),
            "failed case" to suite("<testcase name=\"failed\"><failure/></testcase>"),
            "error case" to suite("<testcase name=\"error\"><error/></testcase>"),
            "skipped-only tests" to suite(skipped),
            "unrun tests" to suite("<testcase name=\"unrun\" status=\"notrun\"/>"),
            "missing counters" to "<testsuite tests=\"1\">$passed</testsuite>",
            "invalid counters" to "<testsuite tests=\"x\" failures=\"0\" errors=\"0\"/>",
            "invalid root" to "<unrelated tests=\"1\" failures=\"0\" errors=\"0\">$passed</unrelated>",
            "malformed XML" to "<testsuite",
            "DOCTYPE" to "<!DOCTYPE testsuite [<!ENTITY x 'text'>]>${suite(passed)}"
        )
        rejected.forEach { (label, xml) ->
            report.writeText(xml)
            expectFailure(label) { DeviceTestResults.verify(directory) }
        }
        report.writeText(suite(passed))
        check(DeviceTestResults.verify(directory) == 1)
        report.writeText(suite(passed + skipped, tests = 2))
        check(DeviceTestResults.verify(directory) == 1)
        report.writeText(
            "<testsuites tests=\"1\" failures=\"0\" errors=\"0\">${suite(passed)}</testsuites>"
        )
        check(DeviceTestResults.verify(directory) == 1)

        val secondReport = directory.resolve("TEST-second-device.xml")
        secondReport.writeText(suite("", tests = 0))
        expectFailure("one passing device hiding another with no tests") {
            DeviceTestResults.verify(directory)
        }
        secondReport.writeText(suite(passed))
        check(DeviceTestResults.verify(directory) == 2)
        DeviceTestResults.clear(directory)
        check(!report.exists() && !secondReport.exists())
        expectFailure("stale reports") { DeviceTestResults.verify(directory) }
        logger.lifecycle("Device-test result guard regression checks passed.")
    }
}

tasks.named("check") { dependsOn(verifyDeviceTestResultsGuard) }

val androidAbis = listOf("arm64-v8a", "x86_64")
val splitApksByAbi =
    providers.gradleProperty("beautyxt.splitApksByAbi")
        .map(String::toBooleanStrict)
        .getOrElse(false)
val nativeApiLevel = 37
val requiredCargoNdkVersion = "4.1.2"

/** Defines Cargo's unambiguous encoded rustflags separator. */
private object RustFlagEncoding {
    const val SEPARATOR = '\u001f'
}
val rustCargoPackages =
    listOf(
        "beautyxt-editor-jni",
        "beautyxt-diagram-jni",
        "beautyxt-export-jni",
        "beautyxt-import-jni",
        "beautyxt-markdown-jni",
        "beautyxt-math-jni",
        "beautyxt-print-jni",
        "beautyxt-transfer-jni"
    )
val rustJniLibraries =
    listOf(
        "libbeautyxt_editor_jni.so",
        "libbeautyxt_diagram_jni.so",
        "libbeautyxt_export_jni.so",
        "libbeautyxt_import_jni.so",
        "libbeautyxt_markdown_jni.so",
        "libbeautyxt_math_jni.so",
        "libbeautyxt_print_jni.so",
        "libbeautyxt_transfer_jni.so"
    )

/** Builds the Rust JNI libraries consumed by one Android variant. */
@DisableCachingByDefault(because = "Cargo and the Android NDK are external toolchains")
abstract class BuildRustJniLibsTask
@Inject
constructor(
    private val execOperations: ExecOperations,
    private val fileSystemOperations: FileSystemOperations
) : DefaultTask() {
    @get:Internal
    abstract val workspaceDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustSources: ConfigurableFileCollection

    @get:Input
    abstract val cargoExecutable: Property<String>

    @get:Input
    abstract val cargoNdkVersion: Property<String>

    @get:Input
    abstract val cargoPackages: ListProperty<String>

    @get:Input
    abstract val androidPlatform: Property<Int>

    @get:Input
    abstract val androidTargets: ListProperty<String>

    @get:Input
    abstract val expectedLibraries: ListProperty<String>

    @get:Input
    abstract val ndkDirectoryPath: Property<String>

    @get:Input
    abstract val releaseBuild: Property<Boolean>

    @get:Input
    abstract val releaseRustFlags: ListProperty<String>

    @get:Input
    abstract val forbiddenBuildPaths: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:LocalState
    abstract val cargoTargetDirectory: DirectoryProperty

    /** Builds and verifies every configured Rust library. */
    @TaskAction
    fun buildLibraries() {
        verifyCargoNdkVersion()

        val output = outputDirectory.get().asFile
        fileSystemOperations.delete {
            delete(output)
        }
        require(output.mkdirs() || output.isDirectory) {
            "failed to create ${output.absolutePath}"
        }

        val arguments =
            buildList {
                add("ndk")
                add("--platform")
                add(androidPlatform.get().toString())
                androidTargets.get().forEach { target ->
                    add("-t")
                    add(target)
                }
                add("-o")
                add(output.absolutePath)
                add("build")
                add("--locked")
                cargoPackages.get().forEach { cargoPackage ->
                    add("--package")
                    add(cargoPackage)
                }
                if (releaseBuild.get()) {
                    add("--release")
                }
            }

        execOperations.exec {
            workingDir(workspaceDirectory.get().asFile)
            executable = cargoExecutable.get()
            args(arguments)
            environment("ANDROID_NDK_HOME", ndkDirectoryPath.get())
            environment(
                "CARGO_TARGET_DIR",
                cargoTargetDirectory.get().asFile.absolutePath
            )
            if (releaseBuild.get()) {
                environment(
                    "CARGO_ENCODED_RUSTFLAGS",
                    releaseRustFlags.get().joinToString(
                        RustFlagEncoding.SEPARATOR.toString()
                    )
                )
            }
        }

        val missingLibraries =
            buildList {
                androidTargets.get().forEach { target ->
                    expectedLibraries.get().forEach { library ->
                        val libraryFile = output.resolve("$target/$library")
                        if (!libraryFile.isFile) {
                            add(libraryFile)
                        }
                    }
                }
            }
        require(missingLibraries.isEmpty()) {
            "cargo-ndk did not produce: " +
                missingLibraries.joinToString { library -> library.absolutePath }
        }
        if (releaseBuild.get()) {
            verifyNoBuildPaths(output)
        }
    }

    /** Verifies that release libraries do not expose build-machine paths. */
    private fun verifyNoBuildPaths(output: File) {
        val leakedPaths =
            buildList {
                androidTargets.get().forEach { target ->
                    expectedLibraries.get().forEach { library ->
                        val libraryFile = output.resolve("$target/$library")
                        val libraryBytes = libraryFile.readBytes()
                        forbiddenBuildPaths.get().forEach { buildPath ->
                            if (libraryBytes.containsUtf8(buildPath)) {
                                add("${libraryFile.relativeTo(output)}: $buildPath")
                            }
                        }
                    }
                }
            }
        require(leakedPaths.isEmpty()) {
            "release libraries expose build paths: ${leakedPaths.joinToString()}"
        }
    }

    /** Returns whether these bytes contain the UTF-8 encoding of [text]. */
    private fun ByteArray.containsUtf8(text: String): Boolean {
        val needle = text.toByteArray(Charsets.UTF_8)
        if (needle.isEmpty() || needle.size > size) {
            return false
        }
        for (start in 0..size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                return true
            }
        }
        return false
    }

    /** Verifies that the reproducible cargo-ndk version is installed. */
    private fun verifyCargoNdkVersion() {
        val standardOutput = ByteArrayOutputStream()
        execOperations.exec {
            executable = cargoExecutable.get()
            args("ndk", "--version")
            this.standardOutput = standardOutput
        }
        val expectedVersion = "cargo-ndk ${cargoNdkVersion.get()}"
        val actualVersion = standardOutput.toString(Charsets.UTF_8).trim()
        require(actualVersion == expectedVersion) {
            "expected $expectedVersion, found $actualVersion"
        }
    }
}

/** Keeps bundle contents intact while removing filesystem-dependent entry order. */
@DisableCachingByDefault(because = "Uses the build JVM's ZIP compression implementation")
abstract class OrderBundleEntriesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputBundle: RegularFileProperty

    @get:OutputFile
    abstract val outputBundle: RegularFileProperty

    /** Streams every entry, including native symbols, in stable name order. */
    @TaskAction
    fun orderEntries() {
        val output = outputBundle.get().asFile
        ZipFile(inputBundle.get().asFile).use { source ->
            val entries = source.entries().asSequence().sortedBy { it.name }.toList()
            require(output.parentFile.mkdirs() || output.parentFile.isDirectory) {
                "failed to create ${output.parent}"
            }
            ZipOutputStream(output.outputStream().buffered()).use { destination ->
                source.comment?.let(destination::setComment)
                entries.forEach { entry ->
                    val orderedEntry = ZipEntry(entry)
                    if (orderedEntry.method == ZipEntry.DEFLATED) {
                        // Recompression must not reuse the old compressed size.
                        orderedEntry.compressedSize = -1
                    }
                    destination.putNextEntry(orderedEntry)
                    source.getInputStream(entry).use { content ->
                        content.copyTo(destination)
                    }
                    destination.closeEntry()
                }
            }
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

android {
    namespace = "dev.soupslurpr.beautyxt"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "dev.soupslurpr.beautyxt"
        minSdk = 37
        targetSdk = 37
        versionCode = 84
        versionName = versionCode.toString()
        testInstrumentationRunner =
            "dev.soupslurpr.beautyxt.document.DocumentBridgeInstrumentation"

        if (!splitApksByAbi) {
            ndk {
                // Keep ABI literals visible to ChromeOsAbiSupport lint.
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
    }

    splits {
        abi {
            isEnable = splitApksByAbi
            if (splitApksByAbi) {
                reset()
                include(*androidAbis.toTypedArray())
                isUniversalApk = false
            }
        }
    }

    bundle {
        language {
            // Avoid installing one split for every dependency locale.
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        aidl = true
        compose = true
    }

    // Share only the provider protocol; implementations run in their own test-only APK.
    sourceSets.getByName("androidTest").kotlin.directories.add(
        rootProject.file("test-providers/src/shared/kotlin").path
    )

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }

        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        checkReleaseBuilds = true
        warningsAsErrors = true
    }
}

androidComponents {
    val rustWorkspace = rootProject.layout.projectDirectory
    val userHome =
        requireNotNull(System.getProperty("user.home")).takeIf(String::isNotBlank)
            ?: error("user.home is empty")
    val cargoHome =
        System.getenv("CARGO_HOME")?.takeIf(String::isNotBlank)
            ?: File(userHome, ".cargo").absolutePath
    val rustupHome =
        System.getenv("RUSTUP_HOME")?.takeIf(String::isNotBlank)
            ?: File(userHome, ".rustup").absolutePath
    val releasePathMappings =
        listOf(
            rustWorkspace.asFile.absolutePath to "beautyxt",
            cargoHome to "cargo-home",
            rustupHome to "rustup-home",
            userHome to "build-home"
        ).distinctBy { (buildPath, _) -> buildPath }
            .sortedByDescending { (buildPath, _) -> buildPath.length }
    val encodedRustFlags =
        providers.environmentVariable("CARGO_ENCODED_RUSTFLAGS").map { flags ->
            flags.split(RustFlagEncoding.SEPARATOR).filter(String::isNotBlank)
        }
    val plainRustFlags =
        providers.environmentVariable("RUSTFLAGS").map { flags ->
            flags.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        }
    val inheritedRustFlags =
        encodedRustFlags.orElse(plainRustFlags).orElse(emptyList())
    val releasePathRemappings =
        releasePathMappings.map { (buildPath, replacement) ->
            "--remap-path-prefix=$buildPath=$replacement"
        }
    val releaseRustFlagArguments =
        inheritedRustFlags.map { flags -> flags + releasePathRemappings }
    val rustFiles =
        rootProject.fileTree(rustWorkspace) {
            include("Cargo.toml", "Cargo.lock", "rust-toolchain.toml")
            include(".cargo/**", "rust/**")
            exclude("target/**", "rust/**/target/**")
        }
    val ndkPath = sdkComponents.ndkDirectory.map { directory ->
        directory.asFile.absolutePath
    }

    onVariants(selector().all()) { variant ->
        val isRelease =
            when (variant.buildType) {
                "debug" -> false
                "release", "staging" -> true
                else -> return@onVariants
            }
        val capitalizedVariantName =
            variant.name.replaceFirstChar { character -> character.uppercaseChar() }
        val buildRustJniLibs =
            tasks.register<BuildRustJniLibsTask>(
                "build${capitalizedVariantName}RustJniLibs"
            ) {
                workspaceDirectory.set(rustWorkspace)
                rustSources.from(rustFiles)
                cargoExecutable.set("cargo")
                cargoNdkVersion.set(requiredCargoNdkVersion)
                cargoPackages.set(rustCargoPackages)
                androidPlatform.set(nativeApiLevel)
                androidTargets.set(androidAbis)
                expectedLibraries.set(rustJniLibraries)
                ndkDirectoryPath.set(ndkPath)
                releaseBuild.set(isRelease)
                releaseRustFlags.set(releaseRustFlagArguments)
                forbiddenBuildPaths.set(
                    releasePathMappings.map { (buildPath, _) -> buildPath }
                )
                outputDirectory.set(
                    layout.buildDirectory.dir("generated/rustJniLibs/${variant.name}")
                )
                cargoTargetDirectory.set(
                    layout.buildDirectory.dir("intermediates/cargoTarget/${variant.name}")
                )
            }

        val jniLibs = requireNotNull(variant.sources.jniLibs) {
            "JNI library sources are unavailable for ${variant.name}"
        }
        jniLibs.addGeneratedSourceDirectory(
            buildRustJniLibs,
            BuildRustJniLibsTask::outputDirectory
        )

        if (variant.buildType == "release") {
            // AGP 9.4 collects native symbol files without sorting them. Keep
            // every symbol and order the final unsigned bundle via the public API.
            val orderBundleEntries =
                tasks.register<OrderBundleEntriesTask>(
                    "order${capitalizedVariantName}BundleEntries"
                )
            variant.artifacts.use(orderBundleEntries)
                .wiredWithFiles(
                    OrderBundleEntriesTask::inputBundle,
                    OrderBundleEntriesTask::outputBundle
                )
                .withName("${project.name}-${variant.name}.aab")
                .toTransform(SingleArtifact.BUNDLE)
        }
    }
}

// ThirdPartyNoticesTest reads this source file directly; it is not a JVM test resource.
// Include it in the cache key so regenerating notices cannot reuse an obsolete test result.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    inputs.file(layout.projectDirectory.file("src/main/res/raw/third_party_notices.txt"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime.retain)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    androidTestUtil(project(path = ":test-providers", configuration = "testProviderApk"))

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Reviewed stable floors for libraries brought in by AndroidX and Compose.
    // Constraints update existing dependencies without adding unused libraries.
    constraints {
        listOf(
            "androidx.annotation:annotation-experimental:1.6.0",
            "androidx.appcompat:appcompat:1.8.0",
            "androidx.autofill:autofill:1.3.0",
            "androidx.camera.viewfinder:viewfinder-core:1.6.2",
            "androidx.concurrent:concurrent-futures-ktx:1.3.0",
            "androidx.customview:customview:1.2.0",
            "androidx.customview:customview-poolingcontainer:1.1.0",
            "androidx.drawerlayout:drawerlayout:1.2.0",
            "androidx.emoji2:emoji2:1.6.0",
            "androidx.fragment:fragment:1.9.0",
            "androidx.graphics:graphics-shapes:1.1.0",
            "androidx.loader:loader:1.2.0",
            "androidx.media3:media3-muxer:1.11.1",
            "androidx.navigationevent:navigationevent-compose:1.1.2",
            "androidx.profileinstaller:profileinstaller:1.4.1",
            "androidx.startup:startup-runtime:1.2.0",
            "androidx.tracing:tracing-ktx:2.0.2",
            "androidx.vectordrawable:vectordrawable-animated:1.2.0",
            "androidx.versionedparcelable:versionedparcelable:1.2.1",
            "androidx.viewpager:viewpager:1.1.0",
            "androidx.window:window:1.5.1",
            "com.google.auto.value:auto-value-annotations:1.11.1",
            "com.google.dagger:dagger:2.60.1",
            "com.google.errorprone:error_prone_annotations:2.50.0",
            "com.google.guava:failureaccess:1.0.3",
            "com.google.guava:guava:33.7.1-android",
            "com.google.j2objc:j2objc-annotations:3.1",
            "org.jetbrains:annotations:26.1.0",
            "org.jetbrains.kotlinx:atomicfu:0.33.0",
            "org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0",
            "org.jspecify:jspecify:1.0.1"
        ).forEach { implementation(it) }
        testImplementation("org.hamcrest:hamcrest-core:3.0")
    }
}
