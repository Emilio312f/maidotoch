application {
    mainClass.set("ia.PrincipalKt")
}

dependencies {
    // Proveedor de IA (SDK oficial de Anthropic para la JVM). Solo este servicio lo conoce.
    implementation("com.anthropic:anthropic-java:2.34.0")
}
