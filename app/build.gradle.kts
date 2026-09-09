import com.google.protobuf.gradle.proto
import java.util.Properties

// Keep in step with app/src/main/proto/README.md when the vendored protos are bumped.
val protobufVersion = "3.25.5"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
}

// Release signing. keystore.properties lives at the project root and is gitignored:
//   storeFile=/home/you/keys/meshdroid.jks
//   storePassword=...
//   keyAlias=meshdroid
//   keyPassword=...
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "studio.multitool.meshdroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "studio.multitool.meshdroid"
        minSdk = 28
        targetSdk = 35
        versionCode = 7
        versionName = "1.2.2"
        ndk { abiFilters += listOf("arm64-v8a") }

        // Shown at the bottom of the Node tab. The commit is the one pinned in
        // firmware/build-meshtasticd-android.sh as FW_COMMIT_DEFAULT; keep the two
        // in step when the daemon is rebuilt against a newer firmware.
        buildConfigField("String", "DAEMON_FIRMWARE_COMMIT", "\"fdb6730\"")
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    // libmeshtasticd.so is really an executable; legacy packaging extracts it to
    // nativeLibraryDir, the only place an unrooted app may exec() from.
    packaging { jniLibs { useLegacyPackaging = true } }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }

    // The generated protobuf sources land under build/generated and are compiled with
    // the rest of the app; nothing is checked in but the .proto files themselves.
    sourceSets["main"].proto { srcDir("src/main/proto") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// javalite is the Android runtime: no reflection, no descriptors, a fraction of the
// size of full protobuf-java. protoc is downloaded by the plugin at configure time, so
// a clean build machine needs network access for it.
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
    generateProtoTasks {
        all().forEach { task ->
            // Imports resolve from here, and nothing in it is compiled. See
            // app/src/main/protoinclude/README.md for why nanopb.proto and
            // descriptor.proto have to stay out of the compiled source set.
            task.addIncludeDir(files("src/main/protoinclude"))
            // create(), not named(): on Android the plugin does not pre-register the
            // java builtin, so it has to be added rather than looked up.
            task.builtins {
                create("java") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation("com.google.protobuf:protobuf-javalite:$protobufVersion")
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    // DaemonState and the service use coroutines directly, so the dependency is
    // declared instead of inherited from the Compose and lifecycle artifacts.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
