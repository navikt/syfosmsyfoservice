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
import net.logstash.logback.argument.StructuredArguments
import no.kith.xmlstds.apprec._2004_11_21.XMLAppRec
import no.kith.xmlstds.apprec._2004_11_21.XMLInst
import no.nav.syfo.api.registerNaisApi
import no.nav.syfo.util.connectionFactory
import no.trygdeetaten.xml.eiff._1.XMLEIFellesformat
import no.trygdeetaten.xml.eiff._1.XMLMottakenhetBlokk
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.StringReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.jms.MessageConsumer
import javax.jms.Session
import javax.jms.TextMessage

data class ApplicationState(var running: Boolean = true, var initialized: Boolean = false)

val log: Logger = LoggerFactory.getLogger("no.nav.syfo.smmqmock")
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
                    val infotrygdOppdateringQueue = session.createQueue("queue:///${config.infotrygdOppdateringQueue}?targetClient=1")
                    val infotrygdOppdateringConsumer = session.createConsumer(infotrygdOppdateringQueue)

                    val apprecQueue = session.createQueue(config.apprecQueue)
                    val apprecConsumer = session.createConsumer(apprecQueue)

                    blockingApplicationLogic(applicationState, apprecConsumer, infotrygdOppdateringConsumer)
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

suspend fun blockingApplicationLogic(applicationState: ApplicationState, apprecConsumer: MessageConsumer, receiptConsumer: MessageConsumer) {
    while (applicationState.running) {
        val message = apprecConsumer.receiveNoWait()
        if (message == null) {
            delay(100)
            continue
        }

        try {
            val inputMessageText = when (message) {
                is TextMessage -> message.text
                else -> throw RuntimeException("Incoming message needs to be a byte message or text message")
            }
            val fellesformat = fellesformatUnmarshaller.unmarshal(StringReader(inputMessageText)) as XMLEIFellesformat
            val apprec: XMLAppRec = fellesformat.get()
            val mottakEnhetBlokk: XMLMottakenhetBlokk = fellesformat.get()
            val logValues = arrayOf(
                    StructuredArguments.keyValue("smId", mottakEnhetBlokk.ediLoggId),
                    StructuredArguments.keyValue("Id", apprec.originalMsgId.id),
                    StructuredArguments.keyValue("orgNr", apprec.receiver.hcp.inst.extractOrganizationNumber())
            )
            val logKeys = logValues.joinToString(prefix = "(", postfix = ")", separator = ",") { "{}" }

            log.info("Message is read $logKeys", *logValues)
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

inline fun <reified T> XMLEIFellesformat.get(): T = any.find { it is T } as T

fun XMLInst.extractOrganizationNumber(): String? =
        if (typeId.v == "ENH") {
            id
        } else {
            additionalId.find { it.type.v == "ENH" }?.id
        }
