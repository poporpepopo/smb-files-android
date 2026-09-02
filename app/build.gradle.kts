plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.poporpepopo.smbdocs"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.poporpepopo.smbdocs"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // SMB2/3 client
    implementation("com.hierynomus:smbj:0.13.0")
    // smbj needs a real crypto provider on Android for SMB3 signing/encryption
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // slf4j binding that writes to logcat (smbj logs through slf4j)
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("uk.uuid.slf4j:slf4j-android:1.7.30-0")
}
