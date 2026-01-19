import com.google.protobuf.gradle.*

plugins {
    java
    application
    id("com.google.protobuf") version "0.9.4"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

val grpcVersion = "1.78.0"
val protobufVersion = "3.25.5"

dependencies {
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")

    compileOnly("javax.annotation:javax.annotation-api:1.3.2")

    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.24")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc") { }
            }
        }
    }
}

// Default `./gradlew run` will start the server
application {
    mainClass.set("org.example.tabu.TabuServer")
}

// Convenience task to run the client:
// ./gradlew runClient --args="localhost 50051 50 200 123"
tasks.register<JavaExec>("runClient") {
    group = "application"
    description = "Run the gRPC client"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.example.tabu.TabuClient")
}