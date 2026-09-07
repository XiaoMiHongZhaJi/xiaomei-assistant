plugins {
  id("com.android.application")
  kotlin("android")
}

android {
  namespace = "com.xiaomei.assistant"
  compileSdk = 34

  defaultConfig {
    applicationId = "com.xiaomei.assistant"
    minSdk = 28
    targetSdk = 34
    versionCode = 3021
    versionName = "0.2.1"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    multiDexKeepProguard = file("multidex-keep.pro")
    vectorDrawables {
      useSupportLibrary = true
    }
  }

  buildTypes {
    debug {
      isMinifyEnabled = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
    }
    release {
      isMinifyEnabled = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
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
    buildConfig = true
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }

  packagingOptions.dex.useLegacyPackaging = true

  testOptions {
    unitTests.isIncludeAndroidResources = true
  }
}

dependencies {
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.fragment:fragment-ktx:1.8.2")
  implementation("androidx.activity:activity-ktx:1.9.1")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
  implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
  implementation("androidx.recyclerview:recyclerview:1.3.2")
  implementation("com.google.android.material:material:1.12.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
  compileOnly(project(":libs:stub"))
  implementation(project(":libs:libxposed:service"))
  implementation(project(":runtime:host"))

  testImplementation("junit:junit:4.13.2")
  testImplementation("com.google.truth:truth:1.4.2")
  testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
}

