plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cncat.maotv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cncat.maotv"
        minSdk = 23
        targetSdk = 34
        versionCode = 400
        versionName = "4.0"
    }

    // 按 CPU 架构分别打包（不生成通用包）
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            // 不配置签名：输出的就是未签名 APK，用 MT 管理器签名即可
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // 让 .so 以压缩方式存放、安装时解压：这样无论用什么工具重新签名都不会出对齐问题
            useLegacyPackaging = true
            pickFirsts += "**/libc++_shared.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        // 字库已经是压缩过的 TTF，不再二次压缩
        noCompress += listOf("ttf")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")

    // 播放内核一：libVLC
    implementation("org.videolan.android:libvlc-all:3.6.0")

    // 播放内核二：Media3（ExoPlayer）+ FFmpeg 音频扩展（用来解 MP2 / AC3 等）
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.5.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.0")
    implementation("androidx.media3:media3-ui:1.5.0")
    // Jellyfin 预编译的 FFmpeg 解码扩展（Maven Central）。代码里是用反射加载的：
    // 如果这一行解析不到，删掉它也能编译，只是 Media3 引擎就不能解 MP2/AC3 了。
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
