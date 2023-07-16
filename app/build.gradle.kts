plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.soupslurpr.beautyxt"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.soupslurpr.beautyxt"
        minSdk = 21
        targetSdk = 34
        versionCode = 16
        versionName = "0.5.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.4.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug") // for testing purposes, actual releases are still signed by my key, do not worry :)
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0-alpha05")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation("androidx.activity:activity-compose:1.8.0-alpha06")
    implementation("androidx.compose.ui:ui:1.6.0-alpha01")
    implementation("androidx.compose.ui:ui-graphics:1.6.0-alpha01")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.0-alpha01")
    implementation("androidx.compose.material3:material3:1.2.0-alpha03")
    implementation("com.google.android.material:material:1.10.0-alpha05")
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.31.3-beta")
    implementation("androidx.navigation:navigation-compose:2.7.0-beta02")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.compose.material:material:1.6.0-alpha01")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
}