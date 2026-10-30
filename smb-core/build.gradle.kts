import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

// `./gradlew :smb-core:run --args="<dir> <user> <pass> [port]"` で Mac 上でも単体起動できる
application {
    mainClass.set("dev.andsmb.smb.MainKt")
}

dependencies {
    testImplementation(libs.junit)
}
