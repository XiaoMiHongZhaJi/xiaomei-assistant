plugins {
  id("com.android.library")
}

android {
  namespace = "io.github.libxposed.service"
  compileSdk = 34

  defaultConfig {
    minSdk = 28
  }

  sourceSets {
    getByName("main").manifest.srcFile("AndroidManifest.xml")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    buildConfig = false
  }
}

dependencies {
  api(files("prebuilt/service-classes.jar"))
  api(files("prebuilt/interface-classes.jar"))
}
