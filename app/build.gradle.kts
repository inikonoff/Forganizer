plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

fun secret(name: String): String =
    (System.getenv(name) ?: (project.findProperty(name) as String?) ?: "").trim()

val keystorePath = secret("KEYSTORE_FILE")

android {
    namespace = "com.forganizer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.forganizer.app"
        minSdk = 26
        targetSdk = 35
        versionCode = secret("VERSION_CODE").toIntOrNull() ?: 1
        versionName = secret("VERSION_NAME").ifEmpty { "0.1.0" }
        val serverUrl = secret("SERVER_URL").ifEmpty { "https://forganizer-api.onrender.com" }
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "APP_TOKEN", "\"${secret("APP_TOKEN")}\"")
    }

    signingConfigs {
        if (keystorePath.isNotEmpty() && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = secret("KEYSTORE_PASSWORD")
                keyAlias = secret("KEY_ALIAS")
                keyPassword = secret("KEY_PASSWORD")
            }
        }
    }

    flavorDimensions += "store"
    productFlavors {
        create("full") {
            dimension = "store"
            buildConfigField("boolean", "ALL_FILES_ACCESS", "true")
        }
        create("play") {
            dimension = "store"
            applicationIdSuffix = ".play"
            buildConfigField("boolean", "ALL_FILES_ACCESS", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release keystore the APK is signed with the debug key so it is still installable.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}


dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
