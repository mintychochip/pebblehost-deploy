plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "dev.pebblehost"
version = "0.1.0"

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
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
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
            name = "localPluginRepo"
            url = uri("${rootProject.projectDir}/plugin-repo")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
