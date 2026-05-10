plugins {
  `java-library`
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.named<Jar>("jar") {
  exclude("android/**")
}

dependencies {
  compileOnly("androidx.annotation:annotation:1.8.2")
}
