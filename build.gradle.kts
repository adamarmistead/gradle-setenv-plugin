plugins {
    // Apply the Java Gradle plugin development plugin to add support for developing Gradle plugins
    `java-gradle-plugin`

    // Apply the Kotlin JVM plugin to add support for Kotlin.
    alias(libs.plugins.kotlin.jvm)

    // Code coverage
    jacoco

    // Version derived from git tags (e.g. v0.1.0 → 0.1.0)
    id("com.palantir.git-version") version "3.4.0"
}

// Wire the git-derived version into the project
// The palantir git-version plugin stores a Closure in extra; invoke it to get the version string.
@Suppress("UNCHECKED_CAST")
version = (extra["gitVersion"] as groovy.lang.Closure<String>)()

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

dependencies {
    implementation(libs.commons.text)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    // Define the plugin
    plugins.create("setenv") {
        id = "io.github.adamarmistead.setenv"
        implementationClass = "io.github.adamarmistead.setenv.SetEnvPlugin"
        description = "Generate .env files from Gradle properties, OS environment variables, and cloud secrets managers (AWS, GCP, Azure)"
        tags = listOf("environment", "env", "secrets", "cloud", "configuration")
    }
}

// Add a source set for the functional test suite
val functionalTestSourceSet = sourceSets.create("functionalTest") {
}

configurations["functionalTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["functionalTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

// Add a task to run the functional tests
val functionalTest = tasks.register<Test>("functionalTest") {
    testClassesDirs = functionalTestSourceSet.output.classesDirs
    classpath = functionalTestSourceSet.runtimeClasspath
    useJUnitPlatform()
}

gradlePlugin.testSourceSets.add(functionalTestSourceSet)

tasks.named<Task>("check") {
    // Run the functional tests as part of `check`
    dependsOn(functionalTest)
}

tasks.named<Test>("test") {
    // Use JUnit Jupiter for unit tests.
    useJUnitPlatform()
}

tasks.withType<Test> {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
