import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
    id("me.champeau.jmh")
}

description = "Real-time traffic monitoring, anomaly detection and threat signals for Spring Boot web applications"

val springBootVersion = "4.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
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

mavenPublishing {
    configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = SourcesJar.Sources()))
    publishToMavenCentral()
    signAllPublications()

    coordinates("io.github.eswar-7116", "streamguard-spring-boot-starter", version.toString())

    pom {
        name = "StreamGuard Spring Boot Starter"
        description = project.description
        inceptionYear = "2026"
        url = "https://github.com/eswar-7116/StreamGuard"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "eswar-7116"
                name = "Eswar Dudi"
                url = "https://github.com/eswar-7116"
            }
        }
        scm {
            url = "https://github.com/eswar-7116/StreamGuard"
            connection = "scm:git:git://github.com/eswar-7116/StreamGuard.git"
            developerConnection = "scm:git:ssh://git@github.com/eswar-7116/StreamGuard.git"
        }
    }
}

// Dependencies are declared without versions (the Spring Boot BOM supplies them), so write the
// resolved versions into the POM
publishing {
    publications.withType<MavenPublication>().configureEach {
        versionMapping {
            usage("java-api") { fromResolutionOf("runtimeClasspath") }
            usage("java-runtime") { fromResolutionResult() }
        }
    }
}

// JMH microbenchmarks live in src/jmh/java; run with ./gradlew :streamguard-spring-boot-starter:jmh
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
