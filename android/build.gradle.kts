
import com.unciv.build.AndroidImagePacker
import com.unciv.build.BuildConfig
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    compileSdk = 36
    sourceSets {
        getByName("main").apply {
            manifest.srcFile("AndroidManifest.xml")
            java.srcDirs("src")
            aidl.srcDirs("src")
            renderscript.srcDirs("src")
            res.srcDirs("res")
            assets.srcDirs("assets")
            jniLibs.srcDirs("libs")
        }
    }
    packaging {
        resources.excludes += "META-INF/robovm/ios/robovm.xml"
        // part of kotlinx-coroutines-android, should not go into the apk
        resources.excludes += "DebugProbesKt.bin"
    }
    defaultConfig {
        namespace = BuildConfig.identifier
        // UncivGC: 独立应用ID, 与原版 Unciv (com.unciv.app) 可共存不覆盖
        applicationId = "com.uncivgc.app"
        minSdk = 21
        targetSdk = 35
        versionCode = BuildConfig.appCodeNumber
        versionName = BuildConfig.appVersion

        base.archivesName.set("Unciv")
    }

    // necessary for Android Work lib
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_1_8
        }
    }

    // Had to add this crap for Travis to build, it wanted to sign the app
    // but couldn't create the debug keystore for some reason

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("android/debug.keystore")
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storePassword = "android"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.openclaw/workspace/keystores/uncivgc-release.jks")
            storePassword = (project.findProperty("UNIVGC_STORE_PASS") ?: "") as String
            keyAlias = "uncivgc"
            keyPassword = (project.findProperty("UNIVGC_KEY_PASS") ?: "") as String
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            // If you make this true you get a version of the game that just flat-out doesn't run
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
            isDebuggable = false
            // UncivGC: 正式签名 (密钥在仓库外, 密码在 ~/.gradle/gradle.properties)
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        disable += "MissingTranslation"   // see res/values/strings.xml
    }
    compileOptions {
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }
    androidResources {
        // Don't add local save files and fonts to release, obviously
        ignoreAssetsPattern = "!SaveFiles"  // UncivGC: 模组内置 (自带 fonts/maps/music 子目录), 只防误打包本地存档
    }
    buildFeatures {
        renderScript = true
        aidl = true
    }
}

tasks.register("texturePacker") {
    doFirst {
        logger.info("Calling TexturePacker")
        AndroidImagePacker.packImages(projectDir.path)
    }
}

// called every time gradle gets executed, takes the native dependencies of
// the natives configuration, and extracts them to the proper libs/ folders
// so they get packed with the APK.
tasks.register("copyAndroidNatives") {
    val natives: Configuration by configurations

    doFirst {
        val rx = Regex(""".*natives-([^.]+)\.jar$""")
        natives.forEach { jar ->
            if (rx.matches(jar.name)) {
                val outputDir = file(rx.replace(jar.name) { "libs/" + it.groups[1]!!.value })
                outputDir.mkdirs()
                copy {
                    from(zipTree(jar))
                    into(outputDir)
                    include("*.so")
                }
            }
        }
    }
    dependsOn("texturePacker")
}

tasks.whenTaskAdded {
    // See https://github.com/yairm210/Unciv/issues/4842
    if ("package" in name || "assemble" in name || "bundleRelease" in name) {
        dependsOn("copyAndroidNatives")
    }
}

private fun getSdkPath(): String? {
    val localProperties = project.file("../local.properties")
    return if (localProperties.exists()) {
        val properties = Properties()
        localProperties.inputStream().use { properties.load(it) }

        properties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
    } else {
        System.getenv("ANDROID_HOME")
    }
}

tasks.register<Exec>("run") {
    standardOutput = System.out
    errorOutput = System.err
    isIgnoreExitValue = false

    val path = getSdkPath()
    val adb = "$path/platform-tools/adb"

    commandLine(adb, "shell", "am", "start", "-n", "com.unciv.app/AndroidLauncher")
}

dependencies {
    implementation(libs.android.ktx.core)
    implementation(libs.android.ktx.runtime)
    // Needed to convert e.g. Android 26 API calls to Android 21
    // If you remove this run `./gradlew :android:lintDebug` to ensure everything's okay.
    // If you want to upgrade this, check it's working by building an apk,
    //   or by running `./gradlew :android:assembleRelease` which does that
    coreLibraryDesugaring(libs.android.desugar)
}
