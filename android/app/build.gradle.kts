plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

val releaseSigningVariables = mapOf(
    "ANDROID_KEYSTORE_PATH" to System.getenv("ANDROID_KEYSTORE_PATH"),
    "ANDROID_KEYSTORE_PASSWORD" to System.getenv("ANDROID_KEYSTORE_PASSWORD"),
    "ANDROID_KEY_ALIAS" to System.getenv("ANDROID_KEY_ALIAS"),
    "ANDROID_KEY_PASSWORD" to System.getenv("ANDROID_KEY_PASSWORD"),
)
val missingReleaseSigningVariables = releaseSigningVariables
    .filterValues { it.isNullOrBlank() }
    .keys

android {
    namespace = "com.xiaoshuo.yijianhuanming"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xiaoshuo.yijianhuanming"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (missingReleaseSigningVariables.isEmpty()) {
                storeFile = file(releaseSigningVariables.getValue("ANDROID_KEYSTORE_PATH")!!)
                storePassword = releaseSigningVariables.getValue("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningVariables.getValue("ANDROID_KEY_ALIAS")
                keyPassword = releaseSigningVariables.getValue("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    sourceSets["main"].assets.directories.add(
        layout.buildDirectory.dir("generated/assets/webRuntime").get().asFile.absolutePath,
    )
    sourceSets["androidTest"].assets.directories.add(
        file("$projectDir/schemas").absolutePath,
    )

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.hilt.android)
    implementation(libs.icu4j)
    implementation(libs.jsoup)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.json)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

val repositoryRoot = rootProject.projectDir.parentFile
val generatedRuntime = layout.buildDirectory.file(
    "generated/assets/webRuntime/name-replacer.js",
)

val buildAndroidRuntime by tasks.registering(Exec::class) {
    workingDir(repositoryRoot)
    commandLine("npm", "run", "build:android-runtime")
    inputs.dir(repositoryRoot.resolve("src/android-runtime"))
    inputs.file(repositoryRoot.resolve("src/content/textEngine.ts"))
    inputs.file(repositoryRoot.resolve("src/content/domFilter.ts"))
    outputs.file(generatedRuntime)
}

tasks.matching {
    it.name == "mergeDebugAssets" ||
        it.name == "mergeReleaseAssets" ||
        it.name.contains("lint", ignoreCase = true)
}.configureEach {
    dependsOn(buildAndroidRuntime)
}

tasks.configureEach {
    if (
        name == "preReleaseBuild" ||
        name.matches(Regex("(assemble|bundle).*Release"))
    ) {
        doFirst {
            if (missingReleaseSigningVariables.isNotEmpty()) {
                throw GradleException(
                    "Release signing is required. Missing environment variables: " +
                        missingReleaseSigningVariables.joinToString(", "),
                )
            }
        }
    }
}
