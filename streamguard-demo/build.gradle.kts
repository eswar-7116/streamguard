plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "Sample shop API instrumented with StreamGuard"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(project(":streamguard-spring-boot-starter"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
