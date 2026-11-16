import java.util.Properties

// Legge local.properties (escluso da git): contiene la chiave Google Maps.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.edoppiap.orarilido"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.edoppiap.orarilido"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "0.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // La chiave finisce nel manifest al posto di ${MAPS_API_KEY}.
        manifestPlaceholders["MAPS_API_KEY"] = localProperties.getProperty("MAPS_API_KEY", "")
    }

    // Chiave di firma della release. Il file .jks e la sua password NON sono nel
    // progetto: il percorso e la password stanno in local.properties (escluso da git).
    // Il telefono accetta un aggiornamento solo se firmato con la STESSA chiave:
    // perdere il file .jks = non poter più aggiornare l'app già installata.
    val releaseStoreFile = localProperties.getProperty("RELEASE_STORE_FILE")
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Senza la chiave (es. su un altro PC) si firma con quella di debug,
            // così il progetto compila comunque.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            optimization {
                // R8 ottimizza e riduce tutto il codice (nostro + librerie).
                // NB: limitarlo ad alcuni pacchetti (packageScope, come nel modello
                // di Android Studio) faceva crashare l'app all'avvio.
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.maps.compose)
    implementation(libs.play.services.location)
    constraints {
        // play-services-location porta con sé un androidx.fragment molto vecchio,
        // incompatibile con la richiesta moderna dei permessi: ne forziamo uno recente.
        implementation("androidx.fragment:fragment:1.9.1")
    }
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}