plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "tw.personal.holdings.phone"
  compileSdk = 37
  defaultConfig {
    applicationId = "tw.personal.holdings"
    minSdk = 31
    targetSdk = 37
    buildConfigField(
      "String",
      "UPDATE_MANIFEST_URL",
      "\"https://github.com/win81134679-dot/holdings-watch/releases/latest/download/update.json\"",
    )
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests.isReturnDefaultValues = true
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(project(":core"))
  implementation(project(":quotes"))
  implementation(project(":apk"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.datastore)
  implementation(libs.play.services.wearable)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.kotlinx.serialization.json)
  debugImplementation(libs.androidx.compose.ui.tooling)
  testImplementation(libs.junit)
}
