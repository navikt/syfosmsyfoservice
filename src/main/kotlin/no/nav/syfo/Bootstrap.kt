package no.nav.syfo

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.application.Application
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import no.nav.syfo.api.registerNaisApi
import no.nav.syfo.util.connectionFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.jms.MessageConsumer
import javax.jms.Session
import javax.jms.TextMessage

data class ApplicationState(var running: Boolean = true, var initialized: Boolean = false)

val log: Logger = LoggerFactory.getLogger("no.nav.syfo.smsyfoservice")
val objectMapper: ObjectMapper = ObjectMapper().apply {
    registerKotlinModule()
    registerModule(JavaTimeModule())
    configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
}

fun main(args: Array<String>) = runBlocking(Executors.newFixedThreadPool(2).asCoroutineDispatcher()) {
    val config: ApplicationConfig = objectMapper.readValue(File(System.getenv("CONFIG_FILE")))
    val credentials: VaultCredentials = objectMapper.readValue(vaultApplicationPropertiesPath.toFile())
    val applicationState = ApplicationState()

    val applicationServer = embeddedServer(Netty, config.applicationPort) {
        initRouting(applicationState)
    }.start(wait = false)

    connectionFactory(config).createConnection(credentials.mqUsername, credentials.mqPassword).use { connection ->
        connection.start()

        try {
            val listeners = (1..config.applicationThreads).map {
                launch {

                    val session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
                    val syfoserviceQueue = session.createQueue(config.smSyfoserviceQueue)
                    val syfoserviceConsumer = session.createConsumer(syfoserviceQueue)

                    blockingApplicationLogic(applicationState, syfoserviceConsumer)
                }
            }.toList()

            applicationState.initialized = true

            Runtime.getRuntime().addShutdownHook(Thread {
                applicationServer.stop(10, 10, TimeUnit.SECONDS)
            })
            runBlocking { listeners.forEach { it.join() } }
        } finally {
            applicationState.running = false
        }
    }
}

suspend fun blockingApplicationLogic(applicationState: ApplicationState, syfoserviceConsumer: MessageConsumer) {
    while (applicationState.running) {
        val Message = syfoserviceConsumer.receiveNoWait()
        if (Message == null) {
            delay(100)
            continue
        }

        try {
            val inputMessageText = when (Message) {
                is TextMessage -> Message.text
                else -> throw RuntimeException("Incoming message needs to be a byte message or text message")
            }
            log.info("message is here")
            // TODO unmarshell message
        } catch (e: Exception) {
            log.error("Exception caught while handling message", e)
        }

        delay(100)
    }
}

fun Application.initRouting(applicationState: ApplicationState) {
    routing {
        registerNaisApi(readynessCheck = { applicationState.initialized }, livenessCheck = { applicationState.running })
    }
}