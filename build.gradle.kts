plugins {
    java
    kotlin("jvm") version "1.3.72"
    id("com.github.johnrengelman.shadow") version "6.1.0"
}

tasks {
    named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        archiveBaseName.set("shadow")
        mergeServiceFiles()
        manifest {
            attributes(mapOf("Main-Class" to "MainKt"))
        }
    }
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.github.kittinunf.fuel:fuel:2.3.1")
    implementation("com.github.kittinunf.fuel:fuel-gson:2.3.1")
    implementation("com.google.code.gson:gson:2.8.6")
    implementation("com.zaxxer:HikariCP:4.0.3")
    implementation("org.jetbrains.exposed", "exposed-core", "0.30.1")
    implementation("org.jetbrains.exposed", "exposed-dao", "0.30.1")
    implementation("org.jetbrains.exposed", "exposed-jdbc", "0.30.1")
    implementation("org.jetbrains.exposed", "exposed-java-time", "0.30.1")
    implementation("org.postgresql:postgresql:42.2.19")
    implementation("org.slf4j", "slf4j-api", "1.7.30")
    implementation("ch.qos.logback", "logback-classic", "1.2.3")
    implementation("ch.qos.logback", "logback-core", "1.2.3")
    implementation("io.github.cdimascio:dotenv-kotlin:6.2.2")

    testCompile("junit", "junit", "4.12")
}
