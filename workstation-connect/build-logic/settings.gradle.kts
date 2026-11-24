// Gradle plugins of the fork, included from the root settings.gradle.kts (workstation-connect hook).
// Applied in the app's plugins {} block, so they share AGP's classloader: AGP's API is compileOnly here.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}
rootProject.name = "workstation-connect-build-logic"
