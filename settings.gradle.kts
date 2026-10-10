pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "BYD Sniffer"
include(":app")
include(":bydsdk-stubs")   // ★BYD SDK 스텁(compileOnly 전용, APK 미포함)
