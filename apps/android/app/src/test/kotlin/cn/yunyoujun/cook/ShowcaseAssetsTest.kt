package cn.yunyoujun.cook

import cn.yunyoujun.cook.data.local.HttpShowcaseTransfer
import cn.yunyoujun.cook.data.local.ShowcaseAsset
import cn.yunyoujun.cook.data.local.ShowcaseAssetLibrary
import cn.yunyoujun.cook.data.local.ShowcaseKind
import cn.yunyoujun.cook.data.local.ShowcasePackStore
import cn.yunyoujun.cook.data.local.ShowcaseTransfer
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ShowcaseAssetsTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test
    fun bundledModelInstallsWithoutNetworkAndInvalidUpdateKeepsOlderInstallation() = runBlocking {
        val old = "model v1".encodeToByteArray()
        val updated = "model v2".encodeToByteArray()
        val image = asset(ShowcaseKind.Image, 1, "image".encodeToByteArray())
        fun descriptor(value: ShowcaseAsset) = """
            {"kind":"${value.kind.path}","version":${value.version},"bytes":${value.bytes},"sha256":"${value.sha256}",
             "url":"https://assets.yunle.fun/cook/showcase/250/tomato-egg-clay/${value.kind.path}/${value.filename}"}
        """.trimIndent()
        fun manifest(model: ShowcaseAsset) =
            """[{"recipeID":250,"pairID":"tomato-egg-clay","packs":[${descriptor(image)}],"androidModel":${descriptor(model)}}]"""

        val initialModel = asset(ShowcaseKind.Model, 1, old)
        val original = ShowcaseAssetLibrary(manifest(initialModel), files.root, this, ShowcaseTransfer { _, _ -> old })
            .dishes.getValue(250).single { it.asset.kind == ShowcaseKind.Model }
        assertFalse(original.hasBundledModel)
        original.download()!!.join()
        val originalFile = original.state.value.installation!!.file

        var bundledData = old.copyOf(old.size + 1)
        val currentModel = asset(ShowcaseKind.Model, 2, updated)
        val current = ShowcaseAssetLibrary(
            manifest(currentModel),
            files.root,
            this,
            transfer = ShowcaseTransfer { _, _ -> throw AssertionError("Bundled install must not use the network") },
            openBundledAsset = { key ->
                assertEquals("cook/showcase/models/250-tomato-egg-clay/${currentModel.filename}", key)
                bundledData.inputStream()
            },
        )
        val packs = current.dishes.getValue(250)
        val model = packs.single { it.asset.kind == ShowcaseKind.Model }
        assertFalse(packs.single { it.asset.kind == ShowcaseKind.Image }.hasBundledModel)
        assertTrue(model.hasBundledModel)
        model.reload()!!.join()
        model.installBundled()!!.join()
        val installFailure = requireNotNull(model.state.value.error)
        model.reload()!!.join()
        assertEquals(installFailure, model.state.value.error)
        assertEquals(originalFile, model.state.value.installation?.file)
        assertArrayEquals(old, originalFile.readBytes())

        bundledData = old
        model.installBundled()!!.join()
        assertNotNull(model.state.value.error)
        assertArrayEquals(old, originalFile.readBytes())
        bundledData = updated
        model.installBundled()!!.join()
        assertNull(model.state.value.error)
        assertEquals(2, model.state.value.installation?.version)
        assertArrayEquals(updated, model.state.value.installation!!.file.readBytes())
        assertFalse(originalFile.exists())
        assertNull(model.installBundled())

        model.remove()!!.join()
        model.reload()!!.join()
        assertNull(model.state.value.installation)
        assertTrue(model.hasBundledModel)
        model.installBundled()!!.join()
        assertArrayEquals(updated, model.state.value.installation!!.file.readBytes())
    }

    @Test
    fun independentInstallationWorksOfflineAfterRestartAndDeletingModelKeepsImage() = runBlocking {
        val image = "confirmed image".encodeToByteArray()
        val model = "confirmed model".encodeToByteArray()
        var requests = 0
        val fixtures = ShowcaseTransfer { asset, progress ->
            requests++
            progress(asset.bytes)
            if (asset.kind == ShowcaseKind.Image) image else model
        }
        val imagePack = pack(ShowcaseKind.Image, 1, image, fixtures)
        val modelPack = pack(ShowcaseKind.Model, 1, model, fixtures)
        imagePack.reload()!!.join()
        modelPack.reload()!!.join()
        assertEquals(0, requests)
        assertNull(modelPack.state.value.installation)
        imagePack.download()!!.join()
        assertArrayEquals(image, imagePack.state.value.installation!!.file.readBytes())
        assertNull(modelPack.state.value.installation)
        modelPack.download()!!.join()
        assertArrayEquals(model, modelPack.state.value.installation!!.file.readBytes())
        assertEquals(2, requests)

        val offlineImage = pack(ShowcaseKind.Image, 1, image)
        val offlineModel = pack(ShowcaseKind.Model, 1, model)
        offlineImage.reload()!!.join()
        offlineModel.reload()!!.join()
        assertArrayEquals(image, offlineImage.state.value.installation!!.file.readBytes())
        assertArrayEquals(model, offlineModel.state.value.installation!!.file.readBytes())
        offlineModel.remove()!!.join()
        offlineModel.reload()!!.join()
        assertNull(offlineModel.state.value.installation)
        assertArrayEquals(image, offlineImage.state.value.installation!!.file.readBytes())
    }

    @Test
    fun cancelledOrCorruptUpdateKeepsOldVersionAndSuccessfulRetryReplacesIt() = runBlocking {
        val kind = ShowcaseKind.Model
        val old = "old model".encodeToByteArray()
        val new = "new model".encodeToByteArray()
        val original = pack(kind, 1, old, ShowcaseTransfer { _, _ -> old })
        original.download()!!.join()
        val originalFile = original.state.value.installation!!.file
        var attempt = 0
        val update = pack(kind, 2, new, ShowcaseTransfer { _, progress ->
            when (attempt++) {
                0 -> {
                    progress(1)
                    awaitCancellation()
                }
                1 -> old
                else -> new
            }
        })
        val stale = File(originalFile.parentFile, "showcase-dead.part").also { it.writeBytes(new) }
        update.reload()!!.join()
        assertFalse(stale.exists())
        assertEquals(1, update.state.value.installation!!.version)
        val pending = update.download()!!
        withTimeout(5_000) { update.state.first { it.receivedBytes == 1 } }
        assertNull(update.download())
        assertNull(update.remove())
        update.cancel()
        pending.join()
        assertNull(update.state.value.error)
        assertArrayEquals(old, originalFile.readBytes())

        update.download()!!.join()
        val transferFailure = requireNotNull(update.state.value.error)
        update.reload()!!.join()
        assertEquals(transferFailure, update.state.value.error)
        assertEquals(originalFile, update.state.value.installation!!.file)
        assertTrue(originalFile.exists())
        update.download()!!.join()
        assertNull(update.state.value.error)
        assertEquals(2, update.state.value.installation!!.version)
        assertFalse(originalFile.exists())
        val reloaded = pack(kind, 2, new)
        reloaded.reload()!!.join()
        assertArrayEquals(new, reloaded.state.value.installation!!.file.readBytes())
        reloaded.state.value.installation!!.file.writeBytes(old)
        reloaded.reload()!!.join()
        assertNull(reloaded.state.value.installation)
    }

    @Test
    fun httpDownloadAcceptsExactBytesAndRejectsRedirectsTruncationAndOverflow() = runBlocking {
        val body = "expected HTTP bytes".encodeToByteArray()
        val pack = asset(ShowcaseKind.Model, 1, body)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/correct") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/wrong-length") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong() - 1)
            exchange.responseBody.use { it.write(body, 0, body.size - 1) }
        }
        server.createContext("/truncated") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(body, 0, body.size - 1) }
        }
        server.createContext("/overlong") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use {
                it.write(body)
                it.write(1)
            }
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.set("Location", "/correct")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            fun transfer(path: String) = HttpShowcaseTransfer {
                URL("http://127.0.0.1:${server.address.port}/$path").openConnection() as HttpURLConnection
            }
            val progress = mutableListOf<Int>()
            assertArrayEquals(body, transfer("correct").fetch(pack, progress::add))
            assertEquals(listOf(body.size), progress)
            for (path in listOf("wrong-length", "truncated", "overlong", "redirect")) {
                assertTrue(path, runCatching { transfer(path).fetch(pack) {} }.exceptionOrNull() is IOException)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cancellingAStalledHttpUpdateReleasesThePackForAnImmediateRetry() = runBlocking {
        val old = "installed image".encodeToByteArray()
        val next = ByteArray(48 * 1024) { 3 }
        val original = pack(ShowcaseKind.Image, 1, old, ShowcaseTransfer { _, _ -> old })
        original.download()!!.join()
        val previous = requireNotNull(original.state.value.installation)
        val releaseStalledResponse = CountDownLatch(1)
        val requests = AtomicInteger()
        val workers = Executors.newFixedThreadPool(2)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = workers
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, next.size.toLong())
            exchange.responseBody.use { output ->
                if (requests.incrementAndGet() == 1) {
                    val prefixSize = 16 * 1024
                    output.write(next, 0, prefixSize)
                    output.flush()
                    if (releaseStalledResponse.await(10, TimeUnit.SECONDS)) {
                        runCatching { output.write(next, prefixSize, next.size - prefixSize) }
                    }
                } else {
                    output.write(next)
                }
            }
        }
        server.start()
        try {
            val transfer = HttpShowcaseTransfer {
                URL("http://127.0.0.1:${server.address.port}/").openConnection() as HttpURLConnection
            }
            val update = pack(ShowcaseKind.Image, 2, next, transfer)
            update.reload()!!.join()
            val stalled = update.download()!!
            withTimeout(5_000) { update.state.first { (it.receivedBytes ?: 0) > 0 } }
            assertNull(withTimeoutOrNull(200) { stalled.join() })
            update.cancel()
            assertNotNull(withTimeoutOrNull(2_000) { stalled.join() })
            assertFalse(update.state.value.busy)
            assertNull(update.state.value.receivedBytes)
            assertNull(update.state.value.error)
            assertEquals(previous, update.state.value.installation)
            assertArrayEquals(old, previous.file.readBytes())

            withTimeout(5_000) { requireNotNull(update.download()).join() }
            assertEquals(2, requests.get())
            assertArrayEquals(next, requireNotNull(update.state.value.installation).file.readBytes())
            assertFalse(previous.file.exists())
        } finally {
            releaseStalledResponse.countDown()
            server.stop(0)
            workers.shutdownNow()
        }
    }

    private fun CoroutineScope.pack(
        kind: ShowcaseKind,
        version: Int,
        data: ByteArray,
        transfer: ShowcaseTransfer = ShowcaseTransfer { _, _ -> throw AssertionError("Offline read made a request") },
    ): ShowcasePackStore =
        ShowcasePackStore(asset(kind, version, data), File(files.root, kind.path), this, transfer)

    private fun asset(kind: ShowcaseKind, version: Int, data: ByteArray): ShowcaseAsset {
        val digest = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        return ShowcaseAsset(kind, version, "https://example.invalid", data.size, digest)
    }
}
