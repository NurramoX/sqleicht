plugins {
    `java-library`
    id("com.diffplug.spotless") version "8.3.0"
    id("me.champeau.jmh") version "0.7.3"
}

spotless {
    java {
        removeUnusedImports()
        forbidWildcardImports()
        googleJavaFormat()
        formatAnnotations()
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

tasks.named<Test>("test") {
    useJUnitPlatform()
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED", "-Xmx64m")
}

jmh {
    jvmArgs.addAll("--enable-preview", "--enable-native-access=ALL-UNNAMED")
    fork.set(1)
    warmupIterations.set(2)
    warmup.set("5s")
    iterations.set(3)
    timeOnIteration.set("5s")
}
