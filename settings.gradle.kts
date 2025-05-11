rootProject.name = "sugar-next-mybatis-boot"

pluginManagement {
    repositories {
        mavenLocal()
        maven {
            name = "Sonatype-Snapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        }
        gradlePluginPortal()
    }
}

plugins {
    id("team.idealstate.glass") version "0.1.0-SNAPSHOT"
}
