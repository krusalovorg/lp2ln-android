plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

android {
    namespace = "lp2ln_android.krusalov.org"
    compileSdk = 34
    ndkVersion = providers.gradleProperty("androidNdkVersion").getOrElse("27.3.13750724")

    defaultConfig {
        applicationId = providers.gradleProperty("applicationIdOverride").getOrElse("lp2ln_android.krusalov.org")
        manifestPlaceholders["appLabel"] = providers.gradleProperty("appLabel").getOrElse("@string/app_name")
        val voicePreview = providers.gradleProperty("voicePreview").orNull == "true"
        buildConfigField("boolean", "VOICE_PREVIEW", voicePreview.toString())
        manifestPlaceholders["voicePreview"] = voicePreview.toString()
        minSdk = 29
        targetSdk = 34
        versionCode = 8
        versionName = "1.3.1"
        // Inject a private endpoint at build time; never commit deployment addresses.
        val bootstrap = providers.gradleProperty("lp2lnBootstrap")
            .orElse(providers.environmentVariable("LP2LN_BOOTSTRAP"))
            .getOrElse("")
        require(bootstrap.none { it == '"' || it == '\\' || it.isISOControl() }) {
            "Invalid lp2lnBootstrap value"
        }
        buildConfigField("String", "LP2LN_BOOTSTRAP", "\"$bootstrap\"")
        ndk {
            abiFilters += providers.gradleProperty("rustAbis")
                .getOrElse("arm64-v8a,armeabi-v7a,x86_64").split(",").map { it.trim() }
        }

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

    if (providers.gradleProperty("voicePreview").orNull == "true") {
        sourceSets["main"].assets.srcDir("src/voicePreview/assets")
    }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/lp2ln/jniLibs"))
}

// ponytail: cargo is often not on Gradle's PATH (rustup installs to ~/.cargo/bin)
val cargoExecutable: String = run {
    val exe = if (System.getProperty("os.name").startsWith("Windows")) "cargo.exe" else "cargo"
    val home = System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo"
    val local = File(home, "bin/$exe")
    if (local.exists()) local.absolutePath else "cargo"
}

val usingVoicePreview = providers.gradleProperty("voicePreview").orNull == "true"
val rustDirectory = rootProject.layout.projectDirectory.dir(if (usingVoicePreview) "rust-preview" else "rust")
val rustJniDirectory = layout.buildDirectory.dir("generated/lp2ln/jniLibs")

fun registerRustBuild(name: String, release: Boolean) = tasks.register<Exec>(name) {
    group = "build"
    description = "Builds the LP2LN Rust bridge for Android ABIs"
    workingDir(rustDirectory)
    environment("CARGO_TARGET_DIR", layout.buildDirectory.dir("rust-target").get().asFile.absolutePath)
    val args = mutableListOf(
        cargoExecutable, "ndk",
        *providers.gradleProperty("rustAbis").getOrElse("arm64-v8a,armeabi-v7a,x86_64")
            .split(",").flatMap { listOf("-t", it.trim()) }.toTypedArray(),
        "-o", rustJniDirectory.get().asFile.absolutePath,
        "build", "--locked",
    )
    if (usingVoicePreview) {
        val prefix = providers.environmentVariable("OPUS_LIB_DIR")
        doFirst {
            require(providers.gradleProperty("rustAbis").orNull == "arm64-v8a" &&
                prefix.isPresent && File(prefix.get(), "lib/libopus.a").isFile) {
                "Build the arm64 Opus prefix and set OPUS_LIB_DIR (docs/M2_ANDROID.md)"
            }
        }
        environment("OPUS_STATIC", "1")
        environment("OPUS_NO_PKG", "1")
        inputs.files(rootProject.fileTree("rust/src"), rootProject.fileTree("../lp2ln-realtime/crates") {
            include("**/*.rs", "**/Cargo.toml")
        })
        if (prefix.isPresent) inputs.file(File(prefix.get(), "lib/libopus.a"))
        args.addAll(args.indexOf("-o"), listOf("-p", "29"))
    }
    if (release) args += "--release"
    commandLine(args)
    inputs.files(fileTree(rustDirectory) {
        include("src/**", "Cargo.toml", "Cargo.lock", "rust-toolchain.toml", ".cargo/**")
    })
    inputs.property("rustAbis", providers.gradleProperty("rustAbis").getOrElse("arm64-v8a,armeabi-v7a,x86_64"))
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
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-video:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

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
