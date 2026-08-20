plugins {
    `base`
}

tasks.register("publishLocalPlugin") {
    group = "build"
    description = "Publishes the deploy plugin to plugin-repo for test-plugin consumption"
    dependsOn(":plugin:publishAllPublicationsToLocalPluginRepoRepository")
}

tasks.named("build") {
    dependsOn("publishLocalPlugin", ":plugin:build", ":test-plugin:build")
}

tasks.named("check") {
    dependsOn(":plugin:check", ":test-plugin:check")
}
