package nostr.relay

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonSyntaxException
import io.javalin.Javalin
import io.javalin.http.staticfiles.Location
import io.javalin.websocket.WsContext
import io.javalin.websocket.WsMessageContext
import nostr.postr.*
import nostr.postr.events.Event
import nostr.relay.Events.createdAt
import nostr.relay.Events.hash
import nostr.relay.Events.kind
import nostr.relay.Events.pubKey
import nostr.relay.Events.raw
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.sql.Connection
import java.util.*

val gson: Gson = GsonBuilder().create()

/**
 * Per socket there can be multiple channels with multiple filters each.
 */
val subscribers = mutableMapOf<WsContext, MutableMap<String, List<ProbabilisticFilter>>>()
val featureList = mapOf(
    "id" to "ws://localhost:7070/",
    "name" to "NostrPostrRelay",
    "description" to "Relay running NostrPostr.",
    "pubkey" to "46fcbe3065eaf1ae7811465924e48923363ff3f526bd6f73d7c184b16bd8ce4d",
    "supported_nips" to listOf(1, 2, 9, 11, 12, 15, 16),
    "software" to "https://github.com/Giszmo/NostrPostr",
    "version" to "0"
)

fun main() {
    val rt = Runtime.getRuntime()
    val memTotal = rt.totalMemory() / 1024 / 1024
    val memBase = memTotal - rt.freeMemory() / 1024 / 1024

    Database.connect("jdbc:sqlite:events.db", "org.sqlite.JDBC")
    TransactionManager.manager.defaultIsolationLevel =
        Connection.TRANSACTION_SERIALIZABLE
    // or Connection.TRANSACTION_READ_UNCOMMITTED
    transaction {
        addLogger(StdOutSqlLogger)
        SchemaUtils.createMissingTablesAndColumns(Events, Tags)
    }
    Javalin.create {
        it.maxRequestSize = 1 * 1024 * 1024
        it.asyncRequestTimeout = 5L * 60L * 60L * 1_000L
        it.wsFactoryConfig {
            it.policy.maxTextMessageSize = 10 * 1024 * 1024
        }
        it.addStaticFiles { staticFiles ->
            staticFiles.hostedPath = "/test"
            staticFiles.directory = "/public"
            staticFiles.location = Location.CLASSPATH
        }
    }.apply {
        get("/") {
            if (it.header("Accept") == "application/nostr+json") {
                it.json(featureList)
            } else {
                it.redirect("/test/")
            }
        }
        ws("/") { ws ->
            ws.onConnect { ctx ->
                subscribers[ctx] = subscribers[ctx] ?: mutableMapOf()
            }
            ws.onMessage { ctx ->
                val msg = ctx.message()
                try {
                    val jsonArray = gson.fromJson(msg, JsonArray::class.java)
                    when (val cmd = jsonArray[0].asString) {
                        "REQ" -> {
                            val channel = jsonArray[1].asString
                            val filters = jsonArray
                                .filterIndexed { index, _ -> index > 1 }
                                .mapIndexed { index, it ->
                                    try {
                                        Filter.fromJson(it.asJsonObject)
                                    } catch (e: Exception) {
                                        ctx.send("""["NOTICE","Something went wrong with filter $index on channel $channel. Ignoring request."]""")
                                        println("Something went wrong with filter $index. Ignoring request.\n${it}")
                                        return@onMessage
                                    }
                                }
                            subscribers[ctx]!![channel] = filters.map { ProbabilisticFilter.fromFilter(it) }
                            sendEvents(channel, filters, ctx)
                            ctx.send("""["EOSE","$channel"]""")
                        }
                        "EVENT" -> {
                            try {
                                val eventJson = jsonArray[1].asJsonObject
                                val event = Event.fromJson(eventJson)
                                println("WS received kind ${event.kind} event. $eventJson")
                                processEvent(event, event.toJson(), ctx)
                            } catch (e: Exception) {
                                println("Something went wrong with Event: ${gson.toJson(jsonArray[1])}")
                            }
                        }
                        "CLOSE" -> {
                            val channel = jsonArray[1].asString
                            subscribers[ctx]!!.remove(channel)
                            println("Channel $channel closed.")
                        }
                        else -> ctx.send("""["NOTICE","Could not handle $cmd"]""")
                    }
                } catch (e: JsonSyntaxException) {
                    ctx.send("""["NOTICE","No valid JSON: ${gson.toJson(msg)}"]""")
                } catch (e: Exception) {
                    ctx.send("""["NOTICE","Exceptions were thrown: ${gson.toJson(msg)}"]""")
                    println(e.message)
                }
            }
            ws.onClose { ctx ->
                println("Session closing. ${ctx.reason()}")
                subscribers.remove(ctx)
            }
        }
    }.start("127.0.0.1", 7070)
    // get some recent and all future Events from other relays
    Client.subscribe(object : Client.Listener() {
        override fun onNewEvent(event: Event) {
            processEvent(event, event.toJson())
        }

        override fun onRelayStateChange(type: Relay.Type, relay: Relay) {
            println("${relay.url}: ${type.name}")
        }
    })
    Client.connect(mutableListOf(Filter(
        since = Calendar.getInstance().apply {
            add(Calendar.HOUR, -24)
        }.time.time / 1000)))
    // HACK: This is an attempt at measuring the resources used and should be removed
    while (true) {
        val queries = subscribers.values.flatMap { it.values }.flatten().map { it.toString() }
        val queryUse = queries
            .distinct()
            .map { it to Collections.frequency(queries, it) }
            .sortedBy { - it.second }
            .joinToString("\n") { "${it.second} times ${it.first}" }
        println("${Date()}: pinging all sockets. ${rt.freeMemory() / 1024 / 1024}MB / ${rt.totalMemory() / 1024 / 1024}MB free. " +
                "${subscribers.size} subscribers are monitoring these queries:\n$queryUse")
        Thread.sleep(20_000)
    }
}

private fun sendEvents(channel: String, filters: List<Filter>, ctx: WsContext) {
    val rawEvents = mutableSetOf<String>()
    transaction {
        filters.forEach { filter ->
            val query = Events.select { Events.hidden eq false }.orderBy(createdAt to SortOrder.DESC)
            filter.ids?.let { query.andWhere { hash inList it } }
            filter.kinds?.let { query.andWhere { kind inList it } }
            filter.authors?.let { query.andWhere { pubKey inList it } }
            filter.since?.let { query.andWhere { createdAt greaterEq it } }
            filter.until?.let { query.andWhere { createdAt lessEq it } }
            filter.tags?.let {
                query.adjustColumnSet { innerJoin(Tags, { Events.id }, { event }) }
                it.forEach { query.andWhere { (Tags.key eq it.key) and (Tags.value inList it.value) } }
            }
            filter.limit?.let { query.limit(it) }
            val raws = query.map { it[raw] }
            rawEvents.addAll(raws)
        }
    }
    val t = System.currentTimeMillis()
    rawEvents.forEach {
        ctx.send("""["EVENT","$channel",$it]""")
    }
    println("${rawEvents.size} Events sent in ${System.currentTimeMillis() - t}ms.")
}

private fun processEvent(e: Event, eventJson: String, sender: WsMessageContext? = null): Boolean {
    e.checkSignature()
    // a bit hacky: Make sure to get our clients' events (having a sender) to other relays  ...
    sender?.let { Client.send(e) }
    // ephemeral events get sent and forgotten
    if (e.kind in 20_000..29_999) {
        return forward(e, eventJson, sender)
    }
    return store(e, eventJson, sender)
            // forward if storing succeeds
            && forward(e, eventJson, sender)
}

private fun store(
    e: Event,
    eventJson: String,
    sender: WsMessageContext?
): Boolean = transaction {
    try {
        if (!DbEvent.find { hash eq e.id.toHex() }.empty()) {
            return@transaction false
        }
        e.checkSignature()
        DbEvent.new {
            hash = e.id.toHex()
            raw = eventJson
            kind = e.kind
            publicKey = e.pubKey.toHex()
            createdAt = e.createdAt
        }
        if (e.kind in listOf(0, 3) || e.kind in 10_000..19_999) {
            // set all but "last" to "hidden"
            DbEvent.find {
                (pubKey eq e.pubKey.toHex()) and (kind eq e.kind) and (Events.hidden eq false)
            }.forEach { it.hidden = true }
            DbEvent.find {
                (pubKey eq e.pubKey.toHex()) and (kind eq e.kind)
            }.orderBy(createdAt to SortOrder.DESC).first().hidden = false
        }
        e.tags.forEach { list ->
            if (list.size >= 2) {
                DbTag.new {
                    event = DbEvent.find { hash eq e.id.toHex() }.first().id.value
                    key = list[0]
                    value = list[1]
                }
            }
        }
        true
    } catch (ex: Exception) {
        println("Something went wrong with event ${e.id.toHex()}: ${ex.message}")
        false
    }
}

private fun forward(
    event: Event,
    eventJson: String,
    ctx: WsMessageContext?
): Boolean {
    subscribers
        .filter { it.key.sessionId != ctx?.sessionId }
        .forEach { (wsContext, channelFilters) ->
            channelFilters.forEach { (channel, filters) ->
                if (filters.any { it.match(event) }) {
                    wsContext.send("""["EVENT","$channel",$eventJson]""")
                }
            }
        }
    return true
}

fun getSize(ser: Serializable): Int {
    val baos = ByteArrayOutputStream()
    val oos = ObjectOutputStream(baos)
    oos.writeObject(ser)
    oos.close()
    return baos.size()
}
