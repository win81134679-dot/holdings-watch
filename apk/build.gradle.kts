plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "tw.personal.holdings.apk"
  compileSdk = 37
  defaultConfig {
    minSdk = 31
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  api(project(":core"))
  implementation(libs.kotlinx.coroutines.core)
}
