plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.kotlin.serialization) apply false
}

// The JVM test launcher reads Gradle's classpath @argfile in the OS code page (MS950 here),
// so build outputs under a non-ASCII project path are unreadable to test workers.
val asciiBuildRoot: File? =
  rootDir.absolutePath
    .takeIf { path -> path.any { it.code > 127 } }
    ?.let { File(System.getProperty("user.home"), ".holdings-build") }

// Release key lives outside the repo; without it, builds fall back to the per-machine debug key.
// A relative storeFile is resolved against the properties file, so moving the project folder keeps signing intact.
val signingPropsFile: File? =
  (findProperty("holdings.signing") as String?)?.let(::file)
    .let { it ?: rootDir.resolve("../signing/keystore.properties") }
    .takeIf { it.isFile }
val signingProps: java.util.Properties? =
  signingPropsFile?.let { f -> java.util.Properties().apply { f.reader(Charsets.UTF_8).use(::load) } }

subprojects {
  asciiBuildRoot?.let { root -> layout.buildDirectory.set(File(root, project.name)) }

  plugins.withId("com.android.application") {
    extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
      // Phone and watch ship as one release: the watch's "you are behind" check compares their codes directly.
      defaultConfig {
        versionCode = providers.gradleProperty("holdings.versionCode").get().toInt()
        versionName = providers.gradleProperty("holdings.versionName").get()
      }
      // The watch APK travels phone→watch over Data Layer (~150 KB/s), so release size is update time.
      buildTypes.getByName("release") {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
      }
      val props = signingProps ?: return@configure
      val release = signingConfigs.create("holdingsRelease") {
        storeFile = signingPropsFile!!.parentFile.resolve(props.getProperty("storeFile"))
        storePassword = props.getProperty("storePassword")
        keyAlias = props.getProperty("keyAlias")
        keyPassword = props.getProperty("keyPassword")
      }
      // Debug builds share the release key so a dev install can upgrade a GitHub release in place.
      buildTypes.getByName("debug").signingConfig = release
      buildTypes.getByName("release").signingConfig = release
    }
  }
}
