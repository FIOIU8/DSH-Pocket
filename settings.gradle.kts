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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云镜像优先：dl.google.com 的大 AAR（如 material-icons-extended）
        // 在本机网络下直连超时；镜像未命中时回落 google() 官方仓库。
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        google()
        mavenCentral()
        // Termux terminal-emulator/view (PTY 终端用)：JitPack 按 GitHub tag 构建。
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.termux.termux-app")
            }
        }
    }
}

rootProject.name = "DSH Pocket"
include(":app")
