tasks.register("buildAll") {
    group = "build"
    description = "Builds the plugin and test-plugin subprojects"
    dependsOn(":test-plugin:build")
    dependsOn(gradle.includedBuild("plugin").task(":build"))
}

tasks.register("checkAll") {
    group = "verification"
    description = "Runs checks for all subprojects"
    dependsOn(gradle.includedBuild("plugin").task(":check"))
    dependsOn(":test-plugin:check")
}
