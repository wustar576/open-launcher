/*
 * Open Launcher Feed - 獨立的 Gradle 專案。
 *
 * 這個 build 刻意「不」被根專案的 settings.gradle 納入：外掛 APK 必須是
 * android:debuggable="true" 的獨立套件（applicationId app.openlauncher.feed），
 * 與啟動器本體的建置完全分離，避免不小心把 debuggable 設定帶進啟動器。
 */

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "open-launcher-feed"
