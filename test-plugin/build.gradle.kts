plugins {
    java
    id("dev.pebblehost.deploy")
}

group = "dev.pebblehost"
version = "1.0.0"

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    jar {
        archiveBaseName.set("pebblehost-test")
    }
}

pebblehost {
    jar = file("build/libs/pebblehost-test-${version}.jar")
    targetDir = "plugins"
    strategy = "groups"
    canaryGate = true
    continueAfterCanary = false
    restart = true
    verifyState = "running"
    verifyTimeoutMs = 180_000
    rollback = "abort"

    val raw = (project.findProperty("pebblehostTargets") as? String) ?: ""
    pbBinary = (project.findProperty("pebblehostPbBinary") as? String) ?: "pb"
    val configuredToken = project.findProperty("pebblehostToken") as? String
    if (!configuredToken.isNullOrBlank()) {
        token = configuredToken
    }
    if (raw.isNotBlank()) {
        raw.split(",").filter { it.isNotBlank() }.forEach { spec ->
            val parts = spec.split(":", limit = 2)
            val targetServerId = parts[0]
            val targetGroup = parts.getOrElse(1) { "default" }
            targets.add(objects.newInstance(dev.pebblehost.deploy.Target::class.java).apply {
                serverId.set(targetServerId)
                group.set(targetGroup)
            })
        }
    }
}