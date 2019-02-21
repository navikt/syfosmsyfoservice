package no.nav.syfo

import java.nio.file.Path
import java.nio.file.Paths

val vaultApplicationPropertiesPath: Path = Paths.get("/var/run/secrets/nais.io/vault/credentials.json")

data class ApplicationConfig(
    val applicationThreads: Int = 1,
    val applicationPort: Int = 8080,
    val mqHostname: String,
    val mqPort: Int,
    val mqGatewayName: String,
    val mqChannelName: String,
    val smSyfoserviceQueue: String
)

data class VaultCredentials(
    val mqUsername: String,
    val mqPassword: String
)
