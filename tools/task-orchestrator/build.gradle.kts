plugins {
    kotlin("jvm") version "2.3.10"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "com.utbildning.orchestrator.MainKt"
}

tasks.test {
    useJUnitPlatform()
}
