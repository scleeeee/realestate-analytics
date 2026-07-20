plugins {
    id("java")
}

allprojects {
    group = "com.realestate"
    version = "0.1.0"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java")
    java {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }
    tasks.withType<Test> { useJUnitPlatform() }
}
