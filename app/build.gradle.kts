plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.xmbreborn"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.xmbreborn"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "0.4.0"
    }

    // Release signing comes from ~/.gradle/gradle.properties (never the repo). Without it, release
    // builds fall back to the debug key so anyone can still build and sideload.
    val keystore = providers.gradleProperty("XMB_KEYSTORE").orNull
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = providers.gradleProperty("XMB_KEYSTORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("XMB_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("XMB_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // A release build plus the debug-only Widget Lab, signed like release so it installs over
        // a phone's release install without wiping its data. For trying things out; never published.
        create("lab") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
    }

    sourceSets {
        getByName("lab") {
            java.srcDir("src/debug/java")
            manifest.srcFile("src/debug/AndroidManifest.xml")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
