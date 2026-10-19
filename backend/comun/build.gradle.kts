plugins {
    `java-library`
}

val ktor: String by rootProject.extra

dependencies {
    // Acceso a SQLite por JDBC, siempre mediante comun.datos.BaseDatos.conectar().
    api("org.xerial:sqlite-jdbc:3.50.3.0")

    // Servidor HTTP de cada servicio. Netty permite TLS en el tráfico interno (Diseño v1.1 §9).
    api("io.ktor:ktor-server-core:$ktor")
    api("io.ktor:ktor-server-netty:$ktor")
    api("io.ktor:ktor-server-content-negotiation:$ktor")
    api("io.ktor:ktor-server-status-pages:$ktor")
    api("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
