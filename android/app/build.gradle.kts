import java.util.Properties
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Backend on the demo LAN. Override per machine, in precedence order:
        //   1. -PBACKEND_URL=http://host:8000/ on the Gradle command line
        //   2. BACKEND_URL=http://192.168.1.42:8000/ in local.properties (not committed)
        //   3. the emulator default below
        // (local.properties is not a Gradle property source, so read it explicitly.)
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val backendUrl = (project.findProperty("BACKEND_URL") as String?)
            ?: localProps.getProperty("BACKEND_URL")
            ?: "http://10.0.2.2:8000/"
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")

        // Public deployment (Render). Placeholders live in gradle.properties; put the real URL and
        // key in local.properties (gitignored), which wins here.
        val publicBackendUrl = localProps.getProperty("PUBLIC_BACKEND_URL")
            ?: (project.findProperty("PUBLIC_BACKEND_URL") as String?)
            ?: "https://proofpad-backend.onrender.com"
        buildConfigField("String", "PUBLIC_BACKEND_URL", "\"$publicBackendUrl\"")
        val proofpadApiKey = localProps.getProperty("PROOFPAD_API_KEY")
            ?: (project.findProperty("PROOFPAD_API_KEY") as String?)
            ?: ""
        buildConfigField("String", "PROOFPAD_API_KEY", "\"$proofpadApiKey\"")
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
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.timber)
    implementation(libs.zxing.embedded)
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
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    // Compose UI tests (the first-launch flow). The BOM pins the version.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// The connected (on-device) test tasks install the debug build over the real app and UNINSTALL
// it when they finish, which deletes every note, folder and handwriting sample on that phone.
// Refuse to run them unless that is asked for explicitly:
//   ./gradlew :app:connectedDebugAndroidTest -PallowDeviceWipe
val allowDeviceWipe = providers.gradleProperty("allowDeviceWipe").isPresent
tasks.matching { it.name.startsWith("connected") && it.name.endsWith("AndroidTest") }.configureEach {
    doFirst {
        if (!allowDeviceWipe) {
            throw GradleException(
                "Refusing to run $name: it uninstalls ProofPad from the connected device when it finishes, " +
                    "deleting all notes on it. Use an emulator or a spare phone and pass -PallowDeviceWipe to run it."
            )
        }
    }
}
