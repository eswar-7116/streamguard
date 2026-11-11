plugins {
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("me.champeau.jmh") version "0.7.2" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

allprojects {
    group = "io.github.eswar-7116"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}
