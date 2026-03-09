plugins {
    `java-library`
    id("me.champeau.jmh") version "0.7.3"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":lib"))
    implementation(libs.xerial.sqlite.jdbc)
    jmh(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.generator.annprocess)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--enable-preview"))
}

jmh {
    jvmArgs.addAll("--enable-preview", "--enable-native-access=ALL-UNNAMED")
    fork.set(1)
    warmupIterations.set(2)
    warmup.set("3s")
    iterations.set(3)
    timeOnIteration.set("5s")
}
