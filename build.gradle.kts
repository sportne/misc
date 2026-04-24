plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

sourceSets {
    main {
        java {
            setSrcDirs(listOf("src"))
            exclude("test/**")
        }
    }
    test {
        java {
            setSrcDirs(listOf("src/test/java"))
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 11
}
