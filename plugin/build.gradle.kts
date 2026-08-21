plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "dev.pebblehost"
version = "2026.08.21"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(gradleTestKit())
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("pebblehostDeploy") {
            id = "dev.pebblehost.deploy"
            implementationClass = "dev.pebblehost.deploy.PebbleHostPlugin"
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/mintychochip/pebblehost-deploy")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user")?.toString()
                password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.key")?.toString()
            }
        }
    }
    publications.withType<MavenPublication> {
        pom {
            name.set("pebblehost-deploy")
            description.set("Gradle plugin that deploys Minecraft plugin/mod jars to PebbleHost servers")
            url.set("https://github.com/mintychochip/pebblehost-deploy")
            licenses {
                license {
                    name.set("MIT")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
        }
    }
}
tasks.test {
    useJUnitPlatform()
}
