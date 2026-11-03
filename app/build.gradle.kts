import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.chronosrevived"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.chronosrevived"
        minSdk = 26
        targetSdk = 35
        // CI sets these on every release; local builds fall back to a dev version.
        versionCode = providers.environmentVariable("VERSION_CODE").orNull?.toInt() ?: 1
        versionName = providers.environmentVariable("VERSION_NAME").orNull ?: "dev"
    }
    // Release key lives outside the repo; keystore.properties is git-ignored.
    val ks = rootProject.file("keystore.properties")
    if (ks.exists()) {
        val p = Properties().apply { ks.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug { applicationIdSuffix = ".debug" }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("no.nordicsemi.android:dfu:2.5.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
