import java.net.URI

plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("org.jetbrains.dokka") version "2.2.0"
}


android {
    defaultConfig {
        minSdkVersion(26)
        compileSdkVersion(37)
        multiDexEnabled = true
        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf(
                        "room.incremental" to "true",
                        "room.schemaLocation" to "$projectDir/schemas"
                )
            }
        }

        ksp {
            arg("room.incremental", "true")
            arg("room.schemaLocation", "$projectDir/schemas")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            //proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
        debug {
           isMinifyEnabled = false
        }
    }

    namespace = "net.ballmerlabs.uscatterbrain"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    packagingOptions {
        exclude("lib/x86/libsqlite3x.so")
        exclude("lib/armeabi-v7a/libsqlite3x.so")
        resources {
            pickFirsts += listOf("META-INF/com.google.dagger_dagger.version")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    buildFeatures {
        aidl = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs(files("$projectDir/schemas"))
        }
    }

    useLibrary("android.test.base")
    useLibrary("android.test.mock")
    lint {
        abortOnError = false
        //disable("JvmStaticProvidesInObjectDetector", "FieldSiteTargetOnQualifierAnnotation", "ModuleCompanionObjects", "ModuleCompanionObjectsNotInModuleParent")
    }
    namespace = "net.ballmerlabs.uscatterbrain"
    kotlinOptions {
        freeCompilerArgs = listOf("-XXLanguage:+PropertyParamAnnotationDefaultTargetMode")
    }
    compileSdk = 37
}

apply( "../scatterbrainSDK/gradle/jacoco.gradle")

val protobuf_version = "4.30.2"
/*
configurations {
    configureEach {
        resolutionStrategy {
            force "com.goterl:lazysodium-android:5.1.0@aar"
            force "net.java.dev.jna:jna:5.12.1@aar"
        }
    }
}

 */


dependencies {
    val lifecycle_version = "2.8.2"
    val dagger_version = "2.59.2"
    implementation(libs.appcompat)
    implementation(libs.protobuf.kotlin)
    implementation(libs.protobuf.kotlin)
    implementation(libs.room.runtime)

    implementation(libs.androidx.security.crypto)

    implementation(libs.rxjava2.debug)

    //reflect for logging
    implementation(libs.kotlin.reflect)

    // websockets
    implementation(libs.ktor.server.websockets)

    implementation(project(":scatterbrainSDK"))
    androidTestImplementation(project(":scatterbrainSDK"))
    implementation(libs.rxrelay)
    implementation(libs.rxdogtag)
    api(libs.rxandroidble)
    implementation(libs.kotlinx.collections.immutable)

    testImplementation(libs.mockclient)
    androidTestImplementation(libs.mockclient)

    ksp(libs.room.compiler) // For Kotlin use kapt instead of annotationProcessor
    ksp(libs.kotlin.metadata.jvm)
    implementation(libs.androidx.lifecycle.service)
    androidTestImplementation(libs.androidx.espresso.idling.resource)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.rules)
    // Optional -- Hamcrest library
    androidTestImplementation("org.hamcrest:hamcrest-library:3.0")
    // Optional -- UI testing with Espresso
    androidTestImplementation(libs.espresso.core)
    // Optional -- UI testing with UI Automator
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(libs.room.testing)

//    //lazysodium cryptographic library
//    implementation(libs.lazysodium.android)
//    implementation libs.jna
    implementation("com.goterl:lazysodium-android:5.2.0@aar")
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore.preferences.rxjava2)


    //lazysodium cryptographic library
    testImplementation("com.goterl:lazysodium-android:5.2.0@aar")
    testImplementation("net.java.dev.jna:jna:5.19.1@aar")
    testImplementation("net.java.dev.jna:jna-platform:5.19.1")

    implementation(libs.rxjava2.extras)

    implementation(libs.dagger)
    testImplementation(libs.dagger)
    implementation(libs.dagger.android)
    implementation(libs.dagger.android.support)

    ksp("com.google.dagger:dagger-android-processor:$dagger_version")
    ksp("com.google.dagger:dagger-compiler:$dagger_version")
    kspTest("com.google.dagger:dagger-compiler:$dagger_version")


    implementation(libs.rxjava)

    implementation(libs.rxandroid)

    implementation(libs.androidx.room.rxjava2)

    implementation(libs.rxjava2.extras)
    testImplementation(libs.rxjava)

    testImplementation(libs.rxandroid)

    testImplementation(libs.androidx.room.rxjava2)

    testImplementation(libs.rxjava2.extras)

    implementation(libs.core.ktx)
    androidTestImplementation(libs.androidx.core)
    implementation(libs.kotlin.stdlib.jdk7)
    implementation(libs.google.firebase.crashlytics)
    androidTestImplementation(libs.google.firebase.crashlytics)
    implementation(libs.jetbrains.kotlinx.coroutines.core)
    // Required -- JUnit 4 framework
    testImplementation(libs.junit)
    // Optional -- Robolectric environment
    testImplementation(libs.androidx.core)
    implementation(libs.sqlite.android)
    androidTestImplementation(libs.sqlite.android)
    testImplementation(libs.sqlite.android)
    testImplementation(libs.robolectric)
    // Optional -- Mockito framework
    testImplementation(libs.mockito.core)
    // Optional -- mockito-kotlin
    testImplementation(libs.mockito.kotlin)

    // Optional -- Mockito framework
    implementation(libs.mockito.core)
    // Optional -- mockito-kotlin
    implementation(libs.mockito.kotlin)

    // Optional -- Mockito framework
    androidTestImplementation(libs.mockito.core)
    // Optional -- mockito-kotlin
    androidTestImplementation(libs.mockito.kotlin)
    // Optional -- Mockk framework
    testImplementation(libs.androidx.monitor)
    testImplementation(libs.androidx.core.ktx)
    testImplementation(libs.androidx.core)
    androidTestImplementation(libs.junit)
    val work_version = "2.10.0"

    // (Java only)
    implementation(libs.androidx.work.runtime)

    // Kotlin + coroutines
    implementation(libs.androidx.work.runtime)

    // optional - RxJava2 support
    implementation(libs.androidx.work.rxjava2)

    // optional - GCMNetworkManager support
    implementation(libs.androidx.work.gcm)

    // optional - Test helpers
    androidTestImplementation(libs.androidx.work.testing)

    // optional - Multiprocess support
    implementation(libs.androidx.work.multiprocess)

    implementation(libs.kotlinx.coroutines.rx2)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.rxjava2.extras)
    implementation(project(":meshtastic-sdk:core"))
    implementation(project(":meshtastic-sdk:transport-tcp"))
    implementation(project(":meshtastic-sdk:transport-ble"))// pick a transport
    // The core AIDL interface and Intent constants
    //implementation "com.github.meshtastic.Meshtastic-Android:meshtastic-android-api:v2.7.13-internal.6"

    // Data models (DataPacket, MeshUser, NodeInfo, etc.)
    //implementation "com.github.meshtastic.Meshtastic-Android:meshtastic-android-model:v2.7.12"

    // Protobuf definitions (PortNum, Telemetry, etc.)
  //  implementation "com.github.meshtastic.Meshtastic-Android:meshtastic-android-proto:v2.7.13-internal.6"

    implementation(project(":annotation"))
    ksp(project(":annotation"))
    implementation(project(":ser"))
}
repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
    maven { url = URI( "https://jitpack.io") }
    maven {
        url = URI("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent { snapshotsOnly() }
    }
}
