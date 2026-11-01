package dev.dagent.core.tools

/**
 * A minimal Kotlin + Jetpack Compose app that builds on GitHub Actions with no
 * Gradle wrapper jar (the workflow provisions Gradle), signs the APK with one
 * cached key so updates install over each other, and publishes each build as a
 * release.
 */
object AndroidAppTemplate {
    const val AGP = "8.7.3"
    const val KOTLIN = "2.1.0"
    const val GRADLE = "8.11.1"
    const val COMPOSE_BOM = "2024.12.01"

    fun mainActivityPath(appId: String) = "app/src/main/java/${appId.replace('.', '/')}/MainActivity.kt"

    fun files(appName: String, appId: String): Map<String, String> {
        val label = appName.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "\\\"").replace("'", "\\'")
        return linkedMapOf(
            ".gitignore" to """
                .gradle/
                build/
                local.properties
                *.iml
                .idea/
                *.jks
            """.trimIndent() + "\n",
            "settings.gradle.kts" to """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        google()
                        mavenCentral()
                    }
                }
                rootProject.name = "${appName.replace("\"", "")}"
                include(":app")
            """.trimIndent() + "\n",
            "build.gradle.kts" to """
                plugins {
                    id("com.android.application") version "$AGP" apply false
                    id("org.jetbrains.kotlin.android") version "$KOTLIN" apply false
                    id("org.jetbrains.kotlin.plugin.compose") version "$KOTLIN" apply false
                }
            """.trimIndent() + "\n",
            "gradle.properties" to """
                org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
                android.useAndroidX=true
                kotlin.code.style=official
                android.nonTransitiveRClass=true
            """.trimIndent() + "\n",
            "app/build.gradle.kts" to """
                plugins {
                    id("com.android.application")
                    id("org.jetbrains.kotlin.android")
                    id("org.jetbrains.kotlin.plugin.compose")
                }

                android {
                    namespace = "$appId"
                    compileSdk = 35

                    defaultConfig {
                        applicationId = "$appId"
                        minSdk = 26
                        targetSdk = 35
                        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
                        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
                    }

                    signingConfigs {
                        create("release") {
                            storeFile = System.getenv("APP_KEYSTORE")?.let { file(it) }
                            storePassword = System.getenv("APP_KEYSTORE_PASSWORD")
                            keyAlias = System.getenv("APP_KEY_ALIAS")
                            keyPassword = System.getenv("APP_KEYSTORE_PASSWORD")
                        }
                    }

                    buildTypes {
                        release {
                            isMinifyEnabled = false
                            signingConfig = if (System.getenv("APP_KEYSTORE") != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
                        }
                    }
                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_17
                        targetCompatibility = JavaVersion.VERSION_17
                    }
                    kotlinOptions { jvmTarget = "17" }
                    buildFeatures { compose = true }
                }

                dependencies {
                    val bom = platform("androidx.compose:compose-bom:$COMPOSE_BOM")
                    implementation(bom)
                    implementation("androidx.core:core-ktx:1.15.0")
                    implementation("androidx.activity:activity-compose:1.9.3")
                    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
                    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
                    implementation("androidx.compose.ui:ui")
                    implementation("androidx.compose.ui:ui-tooling-preview")
                    implementation("androidx.compose.material3:material3")
                    implementation("androidx.compose.material:material-icons-extended")
                }
            """.trimIndent() + "\n",
            "app/src/main/AndroidManifest.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application
                        android:allowBackup="true"
                        android:icon="@android:drawable/sym_def_app_icon"
                        android:label="@string/app_name"
                        android:theme="@style/AppTheme">
                        <activity
                            android:name=".MainActivity"
                            android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
            """.trimIndent() + "\n",
            "app/src/main/res/values/strings.xml" to """
                <resources>
                    <string name="app_name">$label</string>
                </resources>
            """.trimIndent() + "\n",
            "app/src/main/res/values/themes.xml" to """
                <resources>
                    <style name="AppTheme" parent="android:Theme.Material.NoActionBar" />
                </resources>
            """.trimIndent() + "\n",
            mainActivityPath(appId) to """
                package $appId

                import android.os.Bundle
                import androidx.activity.ComponentActivity
                import androidx.activity.compose.setContent
                import androidx.activity.enableEdgeToEdge
                import androidx.compose.foundation.layout.Arrangement
                import androidx.compose.foundation.layout.Column
                import androidx.compose.foundation.layout.fillMaxSize
                import androidx.compose.foundation.layout.padding
                import androidx.compose.material3.Button
                import androidx.compose.material3.MaterialTheme
                import androidx.compose.material3.Scaffold
                import androidx.compose.material3.Text
                import androidx.compose.material3.darkColorScheme
                import androidx.compose.runtime.Composable
                import androidx.compose.runtime.getValue
                import androidx.compose.runtime.mutableIntStateOf
                import androidx.compose.runtime.saveable.rememberSaveable
                import androidx.compose.runtime.setValue
                import androidx.compose.ui.Alignment
                import androidx.compose.ui.Modifier
                import androidx.compose.ui.unit.dp

                class MainActivity : ComponentActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        enableEdgeToEdge()
                        setContent {
                            MaterialTheme(colorScheme = darkColorScheme()) {
                                Scaffold { padding -> HomeScreen(Modifier.padding(padding)) }
                            }
                        }
                    }
                }

                @Composable
                fun HomeScreen(modifier: Modifier = Modifier) {
                    var taps by rememberSaveable { mutableIntStateOf(0) }
                    Column(
                        modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("${appName.replace("\"", "").replace("$", "")}", style = MaterialTheme.typography.headlineMedium)
                        Text("Built with D-Agent. Tapped ${'$'}taps times.")
                        Button(onClick = { taps++ }) { Text("Tap me") }
                    }
                }
            """.trimIndent() + "\n",
            ".github/workflows/build.yml" to """
                name: Build APK

                on:
                  push:
                    branches: [main, master]
                  workflow_dispatch:

                permissions:
                  contents: write

                jobs:
                  build:
                    runs-on: ubuntu-latest
                    timeout-minutes: 30
                    steps:
                      - uses: actions/checkout@v4
                      - uses: actions/setup-java@v4
                        with:
                          distribution: temurin
                          java-version: "17"
                      - uses: android-actions/setup-android@v3
                      - uses: gradle/actions/setup-gradle@v4
                        with:
                          gradle-version: "$GRADLE"

                      - name: Restore signing key
                        uses: actions/cache@v4
                        with:
                          path: ~/.app-signing
                          key: app-signing-v1

                      - name: Prepare signing key
                        run: |
                          mkdir -p ~/.app-signing
                          if [ ! -f ~/.app-signing/app.jks ]; then
                            keytool -genkeypair -keystore ~/.app-signing/app.jks -alias app -keyalg RSA -keysize 2048 \
                              -validity 10000 -dname "CN=${appName.replace("\"", "").replace("$", "")}" -storepass app-signing -keypass app-signing
                          fi
                          echo "APP_KEYSTORE=${'$'}HOME/.app-signing/app.jks" >> "${'$'}GITHUB_ENV"
                          echo "APP_KEYSTORE_PASSWORD=app-signing" >> "${'$'}GITHUB_ENV"
                          echo "APP_KEY_ALIAS=app" >> "${'$'}GITHUB_ENV"

                      - name: Build
                        run: gradle --no-daemon :app:assembleRelease

                      - name: Collect APK
                        id: apk
                        run: |
                          OUT="${repoSafe(appName)}-${'$'}{GITHUB_RUN_NUMBER}.apk"
                          cp app/build/outputs/apk/release/*.apk "${'$'}OUT"
                          echo "file=${'$'}OUT" >> "${'$'}GITHUB_OUTPUT"

                      - uses: softprops/action-gh-release@v2
                        with:
                          tag_name: build-${'$'}{{ github.run_number }}
                          name: Build ${'$'}{{ github.run_number }}
                          body: Built by D-Agent from ${'$'}{{ github.sha }}.
                          files: ${'$'}{{ steps.apk.outputs.file }}
                          prerelease: true
            """.trimIndent() + "\n",
            "README.md" to "# $appName\n\nAndroid app built with D-Agent. Every push to main builds an APK in GitHub Actions and publishes it under Releases.\n"
        )
    }

    private fun repoSafe(name: String): String = name.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifBlank { "app" }
}
