plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zdycomp.p3companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zdycomp.p3companion"
        minSdk = 30
        targetSdk = 35
        versionCode = 8
        versionName = "3.5.0"
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
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // No libraries: the app uses only Android's own framework.
    testImplementation("junit:junit:4.13.2")
}
