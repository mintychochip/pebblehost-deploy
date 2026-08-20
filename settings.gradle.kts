rootProject.name = "pebblehost-deploy"

pluginManagement {
    repositories {
        maven {
            url = uri("plugin-repo")
        }
        gradlePluginPortal()
    }
}

include(":plugin", ":test-plugin")
