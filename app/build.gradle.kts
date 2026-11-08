plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.beomsun0829.handsonguide"
    compileSdk = 35
    useLibrary("android.car", false)

    defaultConfig {
        applicationId = "io.github.beomsun0829.handsonguide"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            // R8 shrinks the release from ~47 MB; Pleos SDK and app classes are kept whole (proguard-rules.pro).
            isMinifyEnabled = true
            // No resource shrinking: the Pleos HVAC service looks up this app's registration resources by name.
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        // `-PrenderOut=<dir>` writes road scenes and whole screens as PNGs (RoadSceneRenderTest, ScreenRenderTest), to look at them without an emulator.
        unitTests.all { test ->
            mapOf("renderOut" to "render.out", "renderOnly" to "render.only", "renderCards" to "render.cards").forEach { (property, key) ->
                project.findProperty(property)?.let { test.systemProperty(key, it) }
            }
        }
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.datastore:datastore-core:1.1.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
