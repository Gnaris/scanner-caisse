import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Charge keystore.properties (git-ignored) si présent. En CI, les valeurs
// viennent soit de -P... (gradle properties) soit de variables d'environnement.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

// Charge version.properties (committed) : contient versionName/versionCode de
// la dernière release publiée. Utilisé comme fallback quand Gradle est lancé
// SANS -PversionName (ex. Android Studio ▶ Run) → l'APK local a la même
// version que la Release GitHub la plus récente, donc l'updater ne propose
// pas une MAJ qu'on vient d'installer. release-local.ps1 bump ce fichier
// avant chaque release.
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { load(it) }
    }
}

fun resolveProp(name: String): String? =
    (findProperty(name) as? String)
        ?: keystoreProps.getProperty(name)
        ?: versionProps.getProperty(name)
        ?: System.getenv(name)

android {
    namespace = "com.belijolie.scanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.belijolie.scanner"
        minSdk = 26
        targetSdk = 34
        // versionCode / versionName peuvent être overridés par la CI
        // via -PversionCode=... -PversionName=...
        versionCode = (resolveProp("versionCode") ?: "1").toInt()
        versionName = resolveProp("versionName") ?: "1.0.0"
        vectorDrawables { useSupportLibrary = true }

        // Repo GitHub consulté par l'updater pour trouver la dernière Release.
        buildConfigField("String", "GITHUB_REPO", "\"Gnaris/scanner-caisse\"")
    }

    signingConfigs {
        create("release") {
            val keyFile = resolveProp("keystoreFile")?.let { rootProject.file(it) }
            if (keyFile != null && keyFile.exists()) {
                storeFile = keyFile
                storePassword = resolveProp("keystorePassword")
                keyAlias = resolveProp("keyAlias")
                keyPassword = resolveProp("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signe en release si le keystore est présent, sinon fallback debug
            // (permet de builder localement sans credentials ; l'APK obtenu est
            // installable via adb mais ne doit JAMAIS être publié en release).
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile?.exists() == true) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
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
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room (SQLite ORM)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DataStore (preferences for settings + open channels)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Kotlin serialization (for channel persistence)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // ESC/POS thermal printer (TCP / Bluetooth / USB)
    implementation("com.github.DantSu:ESCPOS-ThermalPrinter-Android:3.3.0")

    // QR code generation for side-by-side bitmap composition on the receipt
    implementation("com.google.zxing:core:3.5.1")

    // Scan QR codes depuis la caméra pour la synchronisation catalogue PDA↔PDA.
    // Note : exclude zxing core transitif car on utilise déjà notre version.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") {
        exclude(group = "com.google.zxing", module = "core")
    }

    // Debug / preview
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
