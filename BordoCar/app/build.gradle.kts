plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bordocar"
    compileSdk = 36

    defaultConfig {
        // only the chips phones use (the Mapbox map brings native code for each); the emulator test adds its own with -Px86
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") + (if (project.hasProperty("x86")) listOf("x86_64") else emptyList()) }
        applicationId = "com.bordocar.app"
        minSdk = 26
        targetSdk = 34
        // each GitHub build gets a higher version number, so Android always treats a new APK as an update
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 100 + run
        // 2.0.0 from build 55 on
        versionName = if (run >= 55) "2.0.${run - 55}" else "1.0.$run"
        resValue("string", "app_name", "Bordo Car")
        // the repo this is built in (GitHub sets it in Actions): where the app looks for new versions
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: ""}\"")
    }

    // signed with the same key as Horizon Nav BR (horizon-br.keystore, which the GitHub build takes from that repo; it is not kept
    // here); its password only lives in the repo secret KEYSTORE_PASSWORD
    val repoKeystore = generateSequence(rootDir) { it.parentFile }.take(6).map { File(it, "horizon-br.keystore") }.firstOrNull { it.isFile }
    val keystorePassword = (System.getenv("KEYSTORE_PASSWORD") ?: providers.gradleProperty("KEYSTORE_PASSWORD").orNull ?: "").trim()
    signingConfigs {
        if (repoKeystore != null && keystorePassword.isNotEmpty()) create("stable") {
            storeFile = repoKeystore
            storeType = "PKCS12"
            storePassword = keystorePassword
            keyAlias = "horizonbr"
            keyPassword = keystorePassword
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("stable") ?: signingConfigs.getByName("debug")
        }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    // the map of the next turn (Estrada), the same map as Horizon Nav
    implementation("com.mapbox.maps:android:11.32.1")
    val bom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Android Auto: Google's car app library (the screen layout and the drawing surface)
    implementation("androidx.car.app:app:1.4.0")
}

// the APK with a clear name (BordoCar.apk), where the GitHub build picks it up
val collectApks by tasks.registering(Copy::class) {
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*.apk"); rename { "BordoCar.apk" } }
    into(layout.buildDirectory.dir("outputs/bordo"))
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(collectApks) }
