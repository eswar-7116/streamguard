plugins {
    `java-library`
    `maven-publish`
    id("me.champeau.jmh")
}

description = "Real-time traffic monitoring, anomaly detection and threat signals for Spring Boot web applications"

val springBootVersion = "4.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))

    api("org.springframework.boot:spring-boot-autoconfigure")
    // The host application brings Spring MVC; StreamGuard only activates when it is present
    compileOnly("org.springframework.boot:spring-boot-starter-webmvc")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The configuration processor merges META-INF/additional-spring-configuration-metadata.json, so resources go first
tasks.compileJava {
    inputs.files(tasks.processResources)
}

tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("benchmark")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            // Write resolved versions into the POM, since dependencies are declared without them
            versionMapping {
                usage("java-api") { fromResolutionOf("runtimeClasspath") }
                usage("java-runtime") { fromResolutionResult() }
            }
            pom {
                name = "StreamGuard Spring Boot Starter"
                description = project.description
                url = "https://github.com/eswar-7116/StreamGuard"
                scm {
                    url = "https://github.com/eswar-7116/StreamGuard"
                }
            }
        }
    }
}

// JMH microbenchmarks live in src/jmh/java; run with ./gradlew jmh
jmh {
    jmhVersion = "1.37"
    includeTests = false
    warmupIterations = 3
    warmup = "2s"
    iterations = 5
    timeOnIteration = "2s"
    fork = 2
    timeUnit = "us"
}

// ./gradlew benchmark [-Dbench.seconds=30 -Dbench.threads=N]
tasks.register<Test>("benchmark") {
    description = "Runs the SlidingWindow throughput / query-latency benchmark."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("benchmark")
    }
    maxHeapSize = "1g"
    systemProperties(System.getProperties().filterKeys { it.toString().startsWith("bench.") }.mapKeys { it.key.toString() })
    testLogging {
        showStandardStreams = true
    }
    outputs.upToDateWhen { false }
}
