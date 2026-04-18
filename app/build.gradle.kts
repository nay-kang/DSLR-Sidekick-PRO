import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "net.codeedu.dslrsidekickpro"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.codeedu.dslrsidekickpro"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags("")
                arguments("-DANDROID_STL=c++_shared", "-DANDROID_EXT_MEM_ALIGNMENT=16384")
                cppFlags("-Wl,-z,max-page-size=16384")
            }
        }

        packaging {
            jniLibs {
                useLegacyPackaging = true
            }
        }
        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // 针对 IDE 的兼容性配置
    @Suppress("DEPRECATION")
    kotlinOptions {
        jvmTarget = "11"
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        // 忽略由于 AGP 9.1.1 内部冲突导致的重复定义的扩展
        freeCompilerArgs.add("-Xsuppress-version-warnings")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    implementation("androidx.exifinterface:exifinterface:1.3.6")
    implementation("com.google.mlkit:face-detection:16.1.7")
    
    // Glide 核心及编译器
    implementation("com.github.bumptech.glide:glide:4.16.0")
    // 使用 add("kapt", ...) 语法可以完美避开 IDE 对 kapt 关键字的解析错误
    add("kapt", "com.github.bumptech.glide:compiler:4.16.0")
    
    implementation("com.github.chrisbanes:PhotoView:2.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}