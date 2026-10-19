import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
}

val ktor = "3.5.0"
extra["ktor"] = ktor

// Convenciones comunes: Kotlin 2.4 sobre JVM 21, código en src/ de cada módulo y salida en build/<módulo>.
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    repositories {
        mavenCentral()
    }

    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir(name))

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
        if (project.name != "pruebas") {
            sourceSets.named("main") { kotlin.srcDir("src") }
        }
    }

    // Logs de cada proceso.
    dependencies {
        "runtimeOnly"("org.slf4j:slf4j-simple:2.0.17")
    }
}

/** Cada servicio es una aplicación propia: `installDist` deja build/<servicio>/install/maidotouch-<servicio>. */
val servicios = listOf(
    "gateway", "sesion", "catalogo", "seguridad-alimentaria", "recomendacion", "adaptador-ia",
    "pedido", "atencion", "trazabilidad", "piloto", "bus",
)

configure(servicios.map { project(":$it") } + project(":herramientas")) {
    apply(plugin = "application")
    dependencies {
        "implementation"(project(":comun"))
    }
    extensions.configure<JavaApplication> {
        applicationName = "maidotouch-${project.name}"
        // Procesamiento de fotos sin pantalla (servidor y CI).
        applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
    }
    tasks.named<JavaExec>("run") {
        workingDir = rootDir
    }
}
