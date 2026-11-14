plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "tw.personal.holdings.wear"
  compileSdk = 37
  defaultConfig {
    applicationId = "tw.personal.holdings"
    minSdk = 33
    targetSdk = 37
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(project(":core"))
  implementation(project(":apk"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.wear.tiles)
  implementation(libs.wear.protolayout)
  implementation(libs.wear.protolayout.expression)
  implementation(libs.wear.protolayout.material3)
  implementation(libs.wear.complications.data.source.ktx)
  implementation(libs.androidx.concurrent.futures.ktx)
  implementation(libs.play.services.wearable)
  implementation(libs.kotlinx.coroutines.play.services)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
