plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningProperties = listOf(
    "KOTJ_RELEASE_STORE_FILE",
    "KOTJ_RELEASE_STORE_PASSWORD",
    "KOTJ_RELEASE_KEY_ALIAS",
    "KOTJ_RELEASE_KEY_PASSWORD",
).associateWith { name ->
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orNull
}
val configuredReleaseProperties = releaseSigningProperties.values.count { !it.isNullOrBlank() }
require(configuredReleaseProperties == 0 || configuredReleaseProperties == releaseSigningProperties.size) {
    "Release signing requires all four KOTJ_RELEASE_* Gradle properties."
}
val hasReleaseSigning = configuredReleaseProperties == releaseSigningProperties.size

android {
    namespace = "com.lopleec.kotj"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.lopleec.kotj"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.2.1"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseSigningProperties["KOTJ_RELEASE_STORE_FILE"]))
                storePassword = releaseSigningProperties["KOTJ_RELEASE_STORE_PASSWORD"]
                keyAlias = releaseSigningProperties["KOTJ_RELEASE_KEY_ALIAS"]
                keyPassword = releaseSigningProperties["KOTJ_RELEASE_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    // 2.11.0 requires compileSdk 37 / AGP 9.1; this project intentionally targets Android 16.
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")

}
