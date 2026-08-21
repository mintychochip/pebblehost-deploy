plugins {
    `java-gradle-plugin`
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

tasks.test {
    useJUnitPlatform()
}
