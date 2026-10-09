plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    application
}

repositories {
    mavenCentral()
}

val ktor = "3.5.0"

dependencies {
    // Acceso a SQLite por JDBC.
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")

    // API REST.
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Adaptador del proveedor de IA (SDK oficial de Anthropic para la JVM).
    implementation("com.anthropic:anthropic-java:2.34.0")

    // Logs del servidor.
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
}

kotlin {
    jvmToolchain(21)
}

// Estructura del repositorio definida en CLAUDE.md: código en backend/, pruebas en tests/.
sourceSets {
    main { kotlin.srcDir("backend") }
    test { kotlin.srcDir("tests") }
}

application {
    mainClass.set("PrincipalKt")
    applicationName = "maidotouch"
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

tasks.test {
    useJUnitPlatform()
    // Las pruebas crean una base nueva desde las migraciones reales del repositorio.
    systemProperty("maidotouch.migraciones", layout.projectDirectory.dir("db/migrations").asFile.absolutePath)
    systemProperty("maidotouch.catalogoFicticio", layout.projectDirectory.file("db/catalogo/ficticio.json").asFile.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
