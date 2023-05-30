plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.soupslurpr.beautyxt"
    compileSdkPreview = "UpsideDownCake"

    defaultConfig {
        applicationId = "dev.soupslurpr.beautyxt"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"

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
        kotlinCompilerExtensionVersion = "1.4.7"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
    buildToolsVersion = "34.0.0 rc4"
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0-alpha04")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation("androidx.activity:activity-compose:1.8.0-alpha04")
    implementation("androidx.compose.ui:ui:1.5.0-beta01")
    implementation("androidx.compose.ui:ui-graphics:1.5.0-beta01")
    implementation("androidx.compose.ui:ui-tooling-preview:1.5.0-beta01")
    implementation("androidx.compose.material3:material3:1.2.0-alpha02")
    implementation("com.google.android.material:material:1.10.0-alpha03")
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.31.3-beta")
    implementation("androidx.navigation:navigation-compose:2.7.0-alpha01")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.compose.material:material:1.5.0-beta01")
}