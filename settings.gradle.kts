pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        ivy {
            url = uri("https://github.com/element-hq/element-call-android/releases/download")
            patternLayout { artifact("v[revision]/[artifact]-[revision](-[classifier]).[ext]") }
            metadataSources { gradleMetadata() }
            content {
                includeModule("io.element.android", "element-call-bom")
                includeModule("io.element.android", "element-call-api")
                includeModule("io.element.android", "element-call")
                includeModule("io.element.android", "element-call-ui")
                includeModule("io.element.android", "element-call-matrix")
            }
        }
        ivy {
            url = uri("https://github.com/element-hq/matrix-rust-rtc/releases/download")
            patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeModule("io.element.android", "matrix-rtc-android") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "Send"
include(":app")
