import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

/*
 * RELEASE SIGNING, AND THE KEYSTORE IS NOT IN THIS REPO.
 *
 * `keystore.properties` sits beside this file, is gitignored, and holds four
 * values. Create the key yourself — the password is yours and must not pass
 * through anything that gets committed, logged or shared:
 *
 *     keytool -genkeypair -v -keystore digitalpet-release.jks \
 *             -alias digitalpet -keyalg RSA -keysize 4096 -validity 10000
 *
 *     # android/keystore.properties
 *     storeFile=digitalpet-release.jks
 *     storePassword=...
 *     keyAlias=digitalpet
 *     keyPassword=...
 *
 * **Back the .jks up somewhere you will still have in five years.** Losing it
 * means you can never update the app on Play under the same listing again —
 * unless you enrol in Play App Signing, which is worth doing for exactly this
 * reason and makes the upload key replaceable.
 *
 * MISSING IS NOT AN ERROR. Without the file the release build is simply
 * unsigned, so `assembleRelease` still works for anyone checking that R8 has
 * not broken the JNI — which is the reason to build it far more often than the
 * reason to ship it.
 */
val keystorePropsFile = rootProject.file("app/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasSigning = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.digitalpet"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.digitalpet"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    testOptions {
        unitTests {
            // Classes under test log through DiagnosticLogger, which calls
            // android.util.Log. Unmocked that throws, so a logging line would
            // fail a test about prompt construction. Returning defaults keeps
            // the failure where the logic is.
            isReturnDefaultValues = true
        }
    }

    /*
     * THREE TESTS READ FILES, AND GRADLE HAD NO IDEA.
     *
     * `TokenSyncTest` reads `design-system/tokens/`, `ComponentRosterTest` reads
     * `design-system/components.txt`, and `PetLiteralsTest` reads the UI sources.
     * None of those is a compile input, so the test task's up-to-date check did
     * not see them — and `testDebugUnitTest` was skipped as UP-TO-DATE whenever
     * only those files changed.
     *
     * **Which is the one case the sync test exists for.** Edit a token in the
     * design system, run the build, get a green result from cache: the checker
     * silently absent at the exact moment it was supposed to speak. Found by
     * mutating the CSS seven ways and watching all seven "pass".
     *
     * `design-system/` sits outside the Gradle build entirely, two levels up
     * from this module, so nothing was ever going to infer it.
     */
    val watched = listOf(
        rootProject.file("../design-system"),
        file("src/main/kotlin/com/digitalpet/ui"),
    )
    /*
     * AND THE MANIFEST, for the same reason and a sharper one.
     *
     * `PermissionCopyTest` checks the Permissions page's promises against it —
     * that the app declares no INTERNET permission, and that BLUETOOTH_SCAN is
     * still `neverForLocation`. Adding INTERNET is exactly the change that would
     * touch no Kotlin at all, so without this the test task would be UP-TO-DATE
     * and the page would go on claiming nothing can leave the phone.
     */
    val watchedFiles = listOf(file("src/main/AndroidManifest.xml"))

    tasks.withType<Test>().configureEach {
        watched.forEachIndexed { i, dir ->
            inputs.dir(dir)
                .withPropertyName("readByTests$i")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
        watchedFiles.forEachIndexed { i, f ->
            inputs.file(f)
                .withPropertyName("readByTestsFile$i")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }

        /*
         * PRINT THE ASSERTION MESSAGE ON FAILURE.
         *
         * Gradle's default says only which test failed, which for most of this
         * suite is enough — the name is the claim. It is not enough for the
         * three that compare files: `TokenSyncTest` builds a list of every role
         * that disagrees and what each side says, and that list going only to an
         * XML report is the difference between a fix and a hunt.
         *
         * Stack traces stay off. Every failure in this suite is an assertion
         * with a written message, so the trace is thirty lines of JUnit
         * internals between the reader and the answer.
         */
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStackTraces = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_NATIVE=OFF"
                )
            }
        }
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = rootProject.file("app/" + keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Null when there is no keystore.properties, which leaves the build
            // unsigned rather than failing. See the note at the top.
            signingConfig = if (hasSigning) signingConfigs.getByName("release") else null
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)

    // Unit tests for the pure logic only — parsing, framing and DSP. Nothing
    // here touches Android, a device or the model files.
    testImplementation(libs.junit)
}
