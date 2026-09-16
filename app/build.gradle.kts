plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningVariables = listOf(
    "DUO_RELEASE_STORE_FILE",
    "DUO_RELEASE_STORE_PASSWORD",
    "DUO_RELEASE_KEY_ALIAS",
    "DUO_RELEASE_KEY_PASSWORD",
)
val releaseSigningValues = releaseSigningVariables.associateWith { name ->
    System.getenv(name)?.takeIf { it.isNotBlank() }
}
val suppliedReleaseSigningVariables = releaseSigningValues.filterValues { it != null }.keys
check(suppliedReleaseSigningVariables.isEmpty() || suppliedReleaseSigningVariables.size == releaseSigningVariables.size) {
    val missing = releaseSigningVariables.filterNot(suppliedReleaseSigningVariables::contains)
    "Release signing is only configured when all four DUO_RELEASE_* variables are set. Missing: ${missing.joinToString()}"
}

val releaseStoreFile = releaseSigningValues["DUO_RELEASE_STORE_FILE"]?.let { configuredPath ->
    rootProject.file(configuredPath).canonicalFile.also { storeFile ->
        val repositoryRoot = rootProject.projectDir.canonicalFile.toPath()
        check(!storeFile.toPath().startsWith(repositoryRoot)) {
            "DUO_RELEASE_STORE_FILE must point outside the repository."
        }
        check(storeFile.isFile && storeFile.canRead()) {
            "DUO_RELEASE_STORE_FILE does not point to a readable file."
        }
    }
}

android {
    namespace = "com.jake.duolauncher"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.jake.duolauncher"
        minSdk = 31
        targetSdk = 36
        versionCode = 50
        versionName = "1.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseSigningValues.getValue("DUO_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("DUO_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("DUO_RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    packaging.resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    // Isolate GPU projection checks from legacy Discover tests while that removed
    // feature's instrumentation suite is being migrated.
    if (providers.gradleProperty("duoProjectionTestsOnly").orNull == "true") {
        sourceSets.getByName("androidTest").java.setSrcDirs(listOf("src/foldProjectionTest/java"))
        sourceSets.getByName("androidTest").manifest.srcFile("src/foldProjectionTest/AndroidManifest.xml")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.81")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Debug FoldProbeActivity queries the public dual-screen WindowArea API.
    // Keep the dependency in debug until the Xiaomi capability is device-proven.
    debugImplementation("androidx.window:window:1.5.1")
    // Last Haze 1.x release built for the project's Kotlin 2.1 / Compose 1.8 line.
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
