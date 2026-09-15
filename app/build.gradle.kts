plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

android {
    namespace = "lp2ln_android.krusalov.org"
    compileSdk = 34

    defaultConfig {
        applicationId = "lp2ln_android.krusalov.org"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/lp2ln/jniLibs"))
}

val rustDirectory = rootProject.layout.projectDirectory.dir("rust")
val rustJniDirectory = layout.buildDirectory.dir("generated/lp2ln/jniLibs")

fun registerRustBuild(name: String, release: Boolean) = tasks.register<Exec>(name) {
    group = "build"
    description = "Builds the LP2LN Rust bridge for Android ABIs"
    workingDir(rustDirectory)
    environment("CARGO_TARGET_DIR", layout.buildDirectory.dir("rust-target").get().asFile.absolutePath)
    val args = mutableListOf(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-o", rustJniDirectory.get().asFile.absolutePath,
        "build",
    )
    if (release) args += "--release"
    commandLine(args)
    inputs.dir(rustDirectory)
    outputs.dir(rustJniDirectory)
    onlyIf { !providers.gradleProperty("skipRustBuild").isPresent }
}

val buildRustDebug = registerRustBuild("buildRustDebug", release = false)
val buildRustRelease = registerRustBuild("buildRustRelease", release = true)

tasks.matching { it.name == "mergeDebugJniLibFolders" }.configureEach {
    dependsOn(buildRustDebug)
}
tasks.matching { it.name == "mergeReleaseJniLibFolders" }.configureEach {
    dependsOn(buildRustRelease)
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
