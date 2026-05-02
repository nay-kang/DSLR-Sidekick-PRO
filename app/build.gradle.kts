import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").inputStream().use(::load)
}
val sentryDsn = localProperties.getProperty("SENTRY_DSN", "")

configure<ApplicationExtension> {
    namespace = "net.codeedu.dslrsidekickpro"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.codeedu.dslrsidekickpro"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "0.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["SENTRY_DSN"] = sentryDsn
        manifestPlaceholders["SENTRY_ENVIRONMENT"] = "production"

        // Use property access instead of the incubating function
        externalNativeBuild.cmake {
            cppFlags.addAll(listOf("", "-Wl,-z,max-page-size=16384"))
            arguments("-DANDROID_STL=c++_shared", "-DANDROID_EXT_MEM_ALIGNMENT=16384")
        }

        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("armeabi-v7a")
        }
    }

    // Use property access instead of the incubating function
    externalNativeBuild.cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["SENTRY_ENVIRONMENT"] = "debug"
        }
        
        getByName("release") {
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
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        // Suppress version warnings due to AGP 9.1.1 internal conflicts
        freeCompilerArgs.add("-Xsuppress-version-warnings")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    implementation(libs.androidx.exifinterface)
    implementation(libs.google.mlkit.face.detection)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.preference.ktx)
    
    implementation(libs.glide)
    ksp(libs.glide.ksp)
    
    implementation(libs.photoview)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.nanohttpd)
    implementation(libs.sentry.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
