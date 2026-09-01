plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.grrrrshadow.ulzvu"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.grrrrshadow.ulzvu"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
