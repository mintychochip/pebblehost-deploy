plugins {
    `base`
}

val pluginBuild = gradle.includedBuild("plugin")

tasks.named("build") {
    dependsOn(pluginBuild.task(":build"), ":test-plugin:build")
}

tasks.named("check") {
    dependsOn(pluginBuild.task(":check"), ":test-plugin:check")
}
