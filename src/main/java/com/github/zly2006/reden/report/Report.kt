package com.github.zly2006.reden.report

import com.github.zly2006.reden.Reden
import com.github.zly2006.reden.Reden.LOGGER
import com.github.zly2006.reden.gui.message.ClientMessageQueue
import com.github.zly2006.reden.malilib.HiddenOption
import com.github.zly2006.reden.malilib.HiddenOption.data_BASIC
import com.github.zly2006.reden.malilib.HiddenOption.data_IDENTIFICATION
import com.github.zly2006.reden.malilib.HiddenOption.data_USAGE
import com.github.zly2006.reden.utils.isClient
import com.github.zly2006.reden.utils.isDevVersion
import com.github.zly2006.reden.utils.redenApiBaseUrl
import com.github.zly2006.reden.utils.server
import com.mojang.authlib.minecraft.UserApiService
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.MinecraftVersion
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.ServerList
import net.minecraft.server.MinecraftServer
import net.minecraft.text.Text
import net.minecraft.util.Util
import net.minecraft.util.crash.CrashMemoryReserve
import net.minecraft.util.crash.CrashReport
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.userAgent
import okio.use
import java.net.URI
import java.util.*

var key = ""

val httpClient = OkHttpClient.Builder().apply {
}.build()

inline fun <reified T> Request.Builder.json(data: T) = apply {
    header("Content-Type", "application/json")
    post(Json.encodeToString(data).toRequestBody("application/json".toMediaTypeOrNull()))
}

fun Request.Builder.ua() = apply {
    header("Authentication", "ApiKey $key")
    header("User-Agent", "RedenMC/${Reden.MOD_VERSION} Minecraft/${MinecraftVersion.create().name} (Fabric) $userAgent")
}

@Serializable
class FeatureUsageData(
    val source: String,
    val name: String,
    val time: Long,
)

fun doHeartHeat() {
    if (!data_USAGE.booleanValue || !data_BASIC.booleanValue) return
    httpClient.newCall(Request.Builder().apply {
        url("$redenApiBaseUrl/mc/heartbeat")
        @Serializable
        class Player(
            val name: String,
            val uuid: String,
            val latency: Int,
            val gamemode: String,
        )
        @Serializable
        class Req(
            val key: String,
            val usage: List<FeatureUsageData>,
            val times: Int,
            val players: List<Player>?
        )
        fun samplePlayers() = if (isClient) {
            MinecraftClient.getInstance().networkHandler?.playerList?.map { Player(
                it.profile.name,
                it.profile.id.toString(),
                it.latency,
                it.gameMode.name,
            ) }
        } else {
            server.playerManager.playerList.map {
                Player(
                    it.gameProfile.name,
                    it.gameProfile.id.toString(),
                    it.networkHandler.latency,
                    it.interactionManager.gameMode.name,
                )
            }
        }
        val req = Req(
            key,
            featureUsageData,
            usedTimes,
            if (data_IDENTIFICATION.booleanValue) samplePlayers()
            else listOf()
        )
        json(req)
        ua()
    }.build()).execute().use {
        @Serializable
        class Res(
            val status: String,
            val shutdown: Boolean,
        )

        val res = jsonIgnoreUnknown.decodeFromString(Res.serializer(), it.body!!.string())
        if (res.shutdown) {
            throw Error(res.status)
        }
        if (it.code == 200) {
            featureUsageData.clear()
            if (res.status.startsWith("set-key="))
                key = res.status.substring(8)
        }
    }
}

val featureUsageData = mutableListOf<FeatureUsageData>()
var heartbeatThread: Thread? = null
fun initHeartBeat() {
    heartbeatThread = Thread("RedenMC HeartBeat") {
        while (true) {
            Thread.sleep(1000 * 60 * 5)
            try {
                doHeartHeat()
            } catch (e: Exception) { LOGGER.debug("", e) }
        }
    }
    heartbeatThread!!.start()
}

fun Thread(name: String, function: () -> Unit) = Thread(function, name)

class ClientMetadataReq(
    val online_mode: Boolean,
    val uuid: UUID?,
    val name: String,
    val mcversion: String,
    val servers: List<Server>
) {
    class Server(
        val name: String,
        val ip: String
    )
}

private var usedTimes = 0

private fun requestFollow() {
    val mc = MinecraftClient.getInstance()
    val key = "reden:youtube"
    val buttonList = mutableListOf<ClientMessageQueue.Button>()
    val id = ClientMessageQueue.addNotification(
        key,
        Reden.LOGO,
        Text.translatable("reden.message.youtube.title"),
        Text.translatable("reden.message.youtube.desc", usedTimes),
        buttonList
    )
    buttonList.add(
        ClientMessageQueue.Button(Text.translatable("reden.message.youtube.yes")) {
            Util.getOperatingSystem().open(
                URI(
                    if (mc.languageManager.language == "zh_cn")
                        "https://space.bilibili.com/1545239761"
                    else
                        "https://www.youtube.com/@zly2006"
                )
            )
            ClientMessageQueue.dontShowAgain(key)
            ClientMessageQueue.remove(id)
        }
    )
    buttonList.add(
        ClientMessageQueue.Button(Text.translatable("reden.message.youtube.no")) {
            ClientMessageQueue.dontShowAgain(key)
            ClientMessageQueue.remove(id)
        }
    )
}

private fun requestDonate() {
}

fun onFunctionUsed(name: String) {
    featureUsageData.add(FeatureUsageData(if (isClient) MinecraftClient.getInstance().session.username else "Server", name, System.currentTimeMillis()))
    if (heartbeatThread == null || !heartbeatThread!!.isAlive) {
        initHeartBeat()
    }
    usedTimes++
    if (isClient) {
        if (usedTimes % 50 == 0 || usedTimes == 10) {
            requestFollow()
        }
        if (usedTimes % 100 == 0 || usedTimes == 20) {
            requestDonate()
        }
    }
}

private val jsonIgnoreUnknown = Json { ignoreUnknownKeys = true }

fun reportServerStart(server: MinecraftServer) {

}

fun reportException(e: Exception) {
    if (isDevVersion && data_USAGE.booleanValue) {
        try {
            CrashMemoryReserve.releaseMemory()
            val asString = CrashReport("Reden generated crash report.", e).asString()
            httpClient.newCall(Request.Builder().apply {
                url("$redenApiBaseUrl/mc/exception")
                @Serializable
                class Req(
                    val key: String,
                    val crash: String,
                )
                json(Req(key, asString))
                ua()
            }.build()).execute().use {
                @Serializable
                class Res(
                    val status: String,
                    val shutdown: Boolean,
                )

                val res = jsonIgnoreUnknown.decodeFromString(Res.serializer(), it.body!!.string())
            }
            return
        } catch (_: Exception) {
        }
    }
}

class UpdateInfo(
    val version: String,
    val url: String,
    val changelog: String,
    val type: String,
)

fun checkUpdateFromModrinth(): UpdateInfo? {
    TODO()
}

fun checkUpdateFromRedenApi(): UpdateInfo? {
    TODO()
}

fun checkAnnouncements() {
    httpClient.newCall(Request.Builder().apply {
        ua()
    }.build())
}

fun redenSetup(client: MinecraftClient) {
    Thread {
        try {
            @Serializable
            class ModData(
                val name: String,
                val version: String,
                val modid: String,
                val authors: List<String>
            )

            @Serializable
            class Req(
                val name: String,
                val early_access: Boolean,
                var online_mode: Boolean,
                val os: String,
                val cpus: Int,
                val mc_version: String,
                val reden_version: String,
                val mods: List<ModData>,
                val servers: List<Map<String, String>>
            )

            val serverList = ServerList(client)
            serverList.loadFile()
            val req = Req(
                if (data_IDENTIFICATION.booleanValue) client.session.username
                else "Anonymous",
                false,
                client.userApiService != UserApiService.OFFLINE,
                System.getProperty("os.name") + " " + System.getProperty("os.version"),
                Runtime.getRuntime().availableProcessors(),
                MinecraftVersion.create().name,
                FabricLoader.getInstance().getModContainer("reden").get().metadata.version.toString(),
                if (data_IDENTIFICATION.booleanValue) FabricLoader.getInstance().allMods.map {
                    ModData(
                        it.metadata.name,
                        it.metadata.version.toString(),
                        it.metadata.id,
                        it.metadata.authors.map { it.name + " <" + it.contact.asMap().entries.joinToString() + ">" },
                    )
                }
                else listOf(),
                if (data_IDENTIFICATION.booleanValue) (0 until serverList.size()).map { serverList[it] }.map {
                    mapOf(
                        "name" to it.name,
                        "ip" to it.address,
                    )
                }
                else listOf()
            )
            try {
                client.sessionService.joinServer(
                    client.session.uuidOrNull,
                    client.session.accessToken,
                    "3cb49a79c3af1f1dba6c56eddd760ac7d50c518a"
                )
            } catch (e: Exception) {
                LOGGER.debug("", e)
                req.online_mode = false
            }
            @Serializable
            class Res(
                val shutdown: Boolean,
                val key: String,
                val ip: String,
                val id: String? = null,
                val status: String,
                val username: String? = null,
                val desc: String,
            )

            val res = jsonIgnoreUnknown.decodeFromString(Res.serializer(), httpClient.newCall(Request.Builder().apply {
                url("$redenApiBaseUrl/mc/online")
                json(req)
                ua()
            }.build()).execute().body!!.string())
            if (res.shutdown) {
                throw Error("Client closing due to copyright reasons, please go to https://www.redenmc.com/policy/copyright gor more information")
            }
            key = res.key
            initHeartBeat()
            LOGGER.info("RedenMC: ${res.desc}")
            LOGGER.info("key=${res.key}, ip=${res.ip}, id=${res.id}, status=${res.status}, username=${res.username}")
        } catch (e: Exception) {
            LOGGER.debug("", e)
        }
    }.start()
    if (HiddenOption.iCHECK_UPDATES.booleanValue) {
        Thread {
            val updateInfo = try {
                checkUpdateFromRedenApi() ?: checkUpdateFromModrinth()
            } catch (e: Exception) {
                LOGGER.debug("", e)
                null
            }
            if (updateInfo != null) {

            }
        }.start()
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        try {
            if (featureUsageData.isNotEmpty()) doHeartHeat()
        } catch (e: Exception) {
            LOGGER.debug("", e)
        }
        try {
            @Serializable
            class Req(
                val key: String
            )
            httpClient.newCall(Request.Builder().apply {
                url("$redenApiBaseUrl/mc/offline")
                json(Req(key))
                ua()
            }.build()).execute().use {
            }
        } catch (e: Exception) {
            LOGGER.debug("", e)
        }
    })
}
