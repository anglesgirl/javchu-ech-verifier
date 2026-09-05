plugins {
    alias(libs.plugins.com.android.application)
}
android {
    namespace = "com.test.verifier"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.test.verifier"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("org.json:json:20240303")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
