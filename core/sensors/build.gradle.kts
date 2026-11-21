plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.buckcast.sensors"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    lint {
        targetSdk = 35
    }

    testOptions {
        targetSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:domain"))
}
