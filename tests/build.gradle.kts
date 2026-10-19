val ktor: String by rootProject.extra

// Pruebas de todos los servicios (CLAUDE.md §10): dominio, integración entre servicios y soporte.
kotlin {
    sourceSets.named("test") {
        kotlin.srcDirs("dominio", "integracion", "soporte")
    }
}

dependencies {
    listOf(
        "comun", "gateway", "sesion", "catalogo", "seguridad-alimentaria", "recomendacion", "adaptador-ia",
        "pedido", "atencion", "trazabilidad", "piloto", "bus",
    ).forEach { testImplementation(project(":$it")) }
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    // Las pruebas crean una base nueva desde las migraciones reales del repositorio.
    systemProperty("maidotouch.migraciones", rootProject.layout.projectDirectory.dir("db/migrations").asFile.absolutePath)
    systemProperty("maidotouch.catalogoFicticio", rootProject.layout.projectDirectory.file("db/catalogo/ficticio.json").asFile.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
