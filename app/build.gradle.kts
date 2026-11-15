plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
}
android {
    namespace = "org.anthrobios.vespasim"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "org.anthrobios.vespasim"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.4.0-rc.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_COMMIT", "\"${System.getenv("VESPA_GIT_COMMIT")?.takeIf { it.matches(Regex("[0-9a-fA-F]{7,40}")) } ?: "unknown"}\"")
    }
    buildFeatures { buildConfig = true }
    // Language can change offline; every distribution must contain all UI languages.
    bundle { language { enableSplit = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        htmlReport = true
        xmlReport = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        animationsDisabled = true
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

