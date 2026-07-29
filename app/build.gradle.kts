/*
 * Podroid — Rootless Podman for Android
 *
 * A headless AArch64 QEMU micro-VM running Alpine Linux with Podman,
 * accessed via built-in serial terminal.
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt.android)
}

val podroidQemuVersion = providers.gradleProperty("podroidQemuVersion").get()

val guestRootfs = rootProject.file("app/src/main/assets/alpine-rootfs.squashfs")

val verifyGuestCredentialSources by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks packageable guest credential sources."
    workingDir(rootProject.projectDir)
    commandLine("python3", rootProject.file("tests/verify_guest_credentials.py"))
    inputs.files(
        rootProject.fileTree("build-rootfs"),
        rootProject.fileTree("app/src/main/assets") { exclude("alpine-rootfs.squashfs") },
        rootProject.file("tests/verify_guest_credentials.py"),
        rootProject.file("README.md"),
        rootProject.file("CLAUDE.md"),
        rootProject.fileTree("docs/baseline") { include("*.md") },
        rootProject.fileTree("docs/guide") { include("*.html") },
        rootProject.fileTree("app/src/main/res") { include("values*/strings.xml") }
    )
}

val verifyGuestCredentialArtifact by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks the generated guest rootfs when it is present."
    workingDir(rootProject.projectDir)
    commandLine("python3", rootProject.file("tests/verify_guest_credentials.py"), guestRootfs)
    inputs.file(guestRootfs).withPropertyName("guestRootfs").optional()
    onlyIf("the generated guest rootfs exists") { task -> task.inputs.files.singleFile.isFile }
}

val testGuestCredentialVerifier by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs guest credential verifier regression tests."
    workingDir(rootProject.projectDir)
    commandLine("python3", "-m", "unittest", "-v", "tests/test_verify_guest_credentials.py")
    inputs.files(
        rootProject.file("tests/test_verify_guest_credentials.py"),
        rootProject.file("tests/verify_guest_credentials.py"),
        rootProject.file("build-rootfs/generate-root-password-hash.sh")
    )
}

val requireGuestRootfsForRelease by tasks.registering(Exec::class) {
    group = "verification"
    description = "Requires a verified guest rootfs for release packaging."
    dependsOn(verifyGuestCredentialSources, verifyGuestCredentialArtifact)
    workingDir(rootProject.projectDir)
    commandLine(
        "sh",
        "-c",
        "test -f app/src/main/assets/alpine-rootfs.squashfs || " +
            "{ echo 'Release packaging requires app/src/main/assets/alpine-rootfs.squashfs' >&2; exit 1; }"
    )
}

tasks.named("preBuild") {
    dependsOn(verifyGuestCredentialSources, verifyGuestCredentialArtifact)
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(requireGuestRootfsForRelease)
}

tasks.named("check") {
    dependsOn(verifyGuestCredentialSources, verifyGuestCredentialArtifact, testGuestCredentialVerifier)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(testGuestCredentialVerifier)
}

android {
    namespace = "com.excp.podroid"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.excp.podroid"
        minSdk = 26
        targetSdk = 36
        versionCode = 30
        versionName = "1.2.6"
        buildConfigField("String", "QEMU_VERSION", "\"$podroidQemuVersion\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only build for arm64-v8a — we target AArch64 Android devices exclusively
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            val storePath = (project.findProperty("PODROID_RELEASE_STORE_FILE") as? String)
            if (storePath != null && file(storePath).exists()) {
                storeFile     = file(storePath)
                storePassword = project.findProperty("PODROID_RELEASE_STORE_PASSWORD") as? String
                keyAlias      = project.findProperty("PODROID_RELEASE_KEY_ALIAS")      as? String
                keyPassword   = project.findProperty("PODROID_RELEASE_KEY_PASSWORD")   as? String
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isJniDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Suppress Kotlin future-compat warning about annotation targets (KT-73255)
    // and silence hiltViewModel deprecation until Hilt updates its own docs.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-Xannotation-default-target=param-property",
                "-nowarn"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // QEMU is an ELF executable packaged as libqemu-system-aarch64.so.
        // It must be extracted to disk so ProcessBuilder can execute it.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Lifecycle & ViewModel
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Compose BOM — pins all Compose library versions
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowsizeclass)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // DataStore (app settings)
    implementation(libs.androidx.datastore.preferences)

    // Vendored Termux terminal emulator & view (MatanZ/termux-app:sixel4 — Sixel + iTerm2 image support)
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))

    // HiddenApiBypass — exempts our process from Android 14+ reflection filtering
    // so we can call the @SystemApi VirtualMachineManager constructors via
    // reflection on devices where the dev-grant path holds (Pixel 8+ etc.).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
