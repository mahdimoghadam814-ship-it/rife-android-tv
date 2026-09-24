plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rife.androidtv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rife.androidtv"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }

        externalNativeBuild {
            cmake {
                cppFlags.addAll(listOf("-std=c++17", "-frtti", "-fexceptions"))
                arguments.addAll(listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DNCNN_VULKAN=ON",
                    "-DNCNN_BUILD_BENCHMARK=OFF",
                    "-DNCNN_BUILD_EXAMPLES=OFF",
                    "-DNCNN_BUILD_TOOLS=OFF"
                ))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }

    ndkVersion = "26.3.11579264"
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
