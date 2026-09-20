import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// A separate test-only APK gives provider processes their own Kotlin runtime.
// androidTest dependencies shared with the target app are otherwise stripped
// from the instrumentation APK and are unavailable outside the test runner.
android {
    namespace = "dev.soupslurpr.beautyxt.testproviders"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.soupslurpr.beautyxt.debug.test.providers"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "1"
    }

    sourceSets.getByName("main").kotlin.directories.add("src/shared/kotlin")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        warningsAsErrors = true
    }
}

val testProviderApk = configurations.consumable("testProviderApk") {
    description = "Standalone provider APK installed alongside instrumentation tests"
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "debug"
    }
    onVariants(selector().withBuildType("debug")) { variant ->
        val apkName = "${project.name}-${variant.name}.apk"
        val prepareTestProviderApk = tasks.register<Sync>("prepareTestProviderApk") {
            from(variant.artifacts.get(SingleArtifact.APK))
            include("*.apk")
            into(layout.buildDirectory.dir("testProviderApk"))
        }
        artifacts.add(
            testProviderApk.name,
            layout.buildDirectory.file("testProviderApk/$apkName")
        ) {
            builtBy(prepareTestProviderApk)
        }
    }
}
