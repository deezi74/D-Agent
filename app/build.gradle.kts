import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val buildNumber = (System.getenv("DAGENT_BUILD") ?: "1").toInt()

// OAuth client IDs for the GitHub and Google connectors (see README, "Connectors and sign-in").
// Read from local.properties (dagent.githubClientId / dagent.googleClientId) or the environment
// (DAGENT_GITHUB_CLIENT_ID / DAGENT_GOOGLE_CLIENT_ID, handy as CI secrets). Client IDs aren't
// secrets, but none is built in: blank means "not set up", and the app says so.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun oauthId(prop: String, env: String): String =
    (localProps.getProperty(prop) ?: System.getenv(env) ?: "").trim().replace("\"", "")

android {
    namespace = "dev.dagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.dagent"
        minSdk = 28
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"${oauthId("dagent.githubClientId", "DAGENT_GITHUB_CLIENT_ID")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${oauthId("dagent.googleClientId", "DAGENT_GOOGLE_CLIENT_ID")}\"")
        ndk {
            // The Linux computer and the on-phone model server are built for 64-bit ARM.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val path = System.getenv("DAGENT_KEYSTORE")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("DAGENT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DAGENT_KEY_ALIAS")
                keyPassword = System.getenv("DAGENT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("DAGENT_KEYSTORE") != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
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

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        // Programs shipped as lib*.so must be extracted to disk so they can be executed.
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE.md", "/META-INF/NOTICE.md", "/META-INF/LICENSE.txt", "/META-INF/NOTICE.txt", "/META-INF/LICENSE", "/META-INF/NOTICE",
            )
        }
    }
}

dependencies {
    implementation(project(":core"))
    // sherpa-onnx (Apache-2.0): on-phone voices and listening. CI downloads the AAR (scripts/fetch-sherpa.sh).
    implementation(files("libs/sherpa-onnx.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.android.mail)
    implementation(libs.android.activation)
}
