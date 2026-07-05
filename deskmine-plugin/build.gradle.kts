plugins {
    java
}

group = "dev.deskmine"
version = "0.4.0"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
}

// Paper 26.1 targets Java 25; compile for 25 with any JDK >= 25.
tasks.compileJava {
    options.release.set(25)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveBaseName.set("deskmine")
}
