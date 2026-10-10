import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.mathnotes.solver"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mathnotes.solver"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        // Backend on the demo LAN. Override per machine in local.properties:
        //   BACKEND_URL=http://192.168.1.42:8000/
        val backendUrl = project.findProperty("BACKEND_URL") as String? ?: "http://10.0.2.2:8000/"
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi.kotlin)
    implementation(libs.coroutines.android)

    implementation(libs.opencv)       // org.opencv:opencv from Maven Central (4.9+)
    implementation(libs.jlatexmath)   // LaTeX rendering
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
}
