rootProject.name = "maidotouch"

// Diseño Arquitectónico v1.1: un módulo por servicio, cada uno con su código y su despliegue.
val servicios = listOf(
    "gateway",
    "sesion",
    "catalogo",
    "seguridad-alimentaria",
    "recomendacion",
    "adaptador-ia",
    "pedido",
    "atencion",
    "trazabilidad",
    "piloto",
    "bus",
)

// Biblioteca compartida: infraestructura de SQLite, servidor y cliente HTTP internos y contratos de las APIs.
include("comun")
project(":comun").projectDir = file("backend/comun")

servicios.forEach {
    include(it)
    project(":$it").projectDir = file("backend/$it")
}

// Comandos de operación (migrar, crear cuentas, importar el catálogo, verificar, demo).
include("herramientas")
project(":herramientas").projectDir = file("backend/herramientas")

// Pruebas en tests/ (CLAUDE.md §4): dominio, integración entre servicios y soporte.
include("pruebas")
project(":pruebas").projectDir = file("tests")
