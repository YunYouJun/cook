package cn.yunyoujun.cook.data.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FridgeProduceModelStoreTest {
    @get:Rule
    val storage = TemporaryFolder()

    private val modelBytes = byteArrayOf(1, 2, 3, 4)
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(modelBytes).toHexString()
    private val manifest = FridgeProduceModel("classifier-$sha256.onnx", modelBytes.size.toLong(), sha256)

    @Test
    fun preparesAndRepairsTheBundledModelWithoutNetworkAccess() = runBlocking {
        val directory = storage.newFolder()
        var assetReads = 0
        val store = FridgeProduceModelStore(directory, manifest, bundledModel = {
            assetReads++
            modelBytes.inputStream()
        }) { error("Offline model preparation accessed the network") }

        val installed = requireNotNull(store.prepareOfflineModel())
        assertArrayEquals(modelBytes, installed.readBytes())
        assertEquals(installed, store.prepareOfflineModel())
        assertEquals(1, assetReads)

        installed.writeBytes(byteArrayOf(7, 2, 3, 4))
        assertNull(store.installedModel())
        assertEquals(installed, store.prepareOfflineModel())
        assertArrayEquals(modelBytes, installed.readBytes())
        assertEquals(2, assetReads)
        assertEquals(listOf("classifier.onnx"), directory.list()?.toList())
    }

    @Test
    fun invalidBundledModelLeavesTheNetworkFallbackAndPartialDownloadAvailable() = runBlocking {
        val directory = storage.newFolder()
        val partial = directory.resolve("${manifest.file}.part")
        partial.writeBytes(modelBytes.copyOf(2))
        var assetBytes = byteArrayOf()
        val store = FridgeProduceModelStore(directory, manifest, bundledModel = { assetBytes.inputStream() }) { url ->
            response(url, modelBytes.copyOfRange(2, 4), 206, "bytes 2-3/4") { assertEquals("bytes=2-", it) }
        }
        for (invalid in listOf(modelBytes.copyOf(3), modelBytes.copyOf(5), byteArrayOf(7, 2, 3, 4))) {
            assetBytes = invalid
            assertNull(store.prepareOfflineModel())
            assertEquals(FridgeProduceModelStore.Status.Resumable, store.status())
            assertEquals(listOf(partial.name), directory.list()?.toList())
        }
        assertArrayEquals(modelBytes, store.download().readBytes())
    }

    @Test
    fun onlyExplicitDownloadConnectsAndNewStoreCanReadModelOffline() = runBlocking {
        val directory = storage.newFolder()
        var requested: URL? = null
        val store = FridgeProduceModelStore(directory, manifest) { url ->
            requested = url
            response(url, modelBytes)
        }

        assertNull(store.installedModel())
        assertNull(requested)
        val installed = store.download()
        assertArrayEquals(modelBytes, installed.readBytes())
        assertEquals("https://assets.yunle.fun/cook/android/${manifest.file}", requested.toString())

        val reopened = FridgeProduceModelStore(directory, manifest) { error("Installed model must work offline") }
        assertEquals(installed, reopened.installedModel())
        assertEquals(installed, reopened.download())
    }

    @Test
    fun incompleteDownloadResumesInANewStoreAndInstallsOnlyTheVerifiedModel() = runBlocking {
        val directory = storage.newFolder()
        val first = FridgeProduceModelStore(directory, manifest) { url -> response(url, modelBytes.copyOf(2)) }
        assertTrue(runCatching { first.download() }.exceptionOrNull() is IOException)
        assertNull(first.installedModel())
        assertArrayEquals(modelBytes.copyOf(2), directory.resolve("${manifest.file}.part").readBytes())

        var requestedRange: String? = null
        val resumed = FridgeProduceModelStore(directory, manifest) { url ->
            response(url, modelBytes.copyOfRange(2, 4), 206, "bytes 2-3/4") { requestedRange = it }
        }
        assertEquals(FridgeProduceModelStore.Status.Resumable, resumed.status())
        assertNull(requestedRange)
        val progress = mutableListOf<Long>()
        assertArrayEquals(modelBytes, resumed.download { progress.add(it) }.readBytes())
        assertEquals("bytes=2-", requestedRange)
        assertEquals(2L, progress.first())
        assertEquals(4L, progress.last())
        assertFalse(directory.resolve("${manifest.file}.part").exists())
    }

    @Test
    fun onlyTheCurrentModelPrefixOrVerifiedCompleteFileIsResumable() = runBlocking {
        val directory = storage.newFolder()
        val partial = directory.resolve("${manifest.file}.part")
        val store = FridgeProduceModelStore(directory, manifest) { error("Model inspection must work offline") }
        directory.resolve("classifier-${"0".repeat(64)}.onnx.part").writeBytes(modelBytes.copyOf(2))
        assertEquals(FridgeProduceModelStore.Status.Missing, store.status())

        for (bytes in listOf(byteArrayOf(), modelBytes.copyOf(5), byteArrayOf(7, 2, 3, 4))) {
            partial.writeBytes(bytes)
            assertEquals(FridgeProduceModelStore.Status.Missing, store.status())
        }

        partial.writeBytes(modelBytes)
        assertEquals(FridgeProduceModelStore.Status.Resumable, store.status())
        val installed = store.download()
        assertEquals(FridgeProduceModelStore.Status.Installed(installed), store.status())
    }

    @Test
    fun completeResponseAfterRangeRequestReplacesThePartialFile() = runBlocking {
        val directory = storage.newFolder()
        directory.resolve("${manifest.file}.part").writeBytes(byteArrayOf(7, 7))
        val store = FridgeProduceModelStore(directory, manifest) { url ->
            response(url, modelBytes) { assertEquals("bytes=2-", it) }
        }
        assertArrayEquals(modelBytes, store.download().readBytes())
        assertEquals(listOf("classifier.onnx"), directory.list()?.toList())
    }

    @Test
    fun aFailedFullResponseKeepsTheSavedPrefixForTheNextResume() = runBlocking {
        val failedRestarts = listOf(
            200 to byteArrayOf(1),
            200 to byteArrayOf(9, 2, 3, 4),
            206 to byteArrayOf(1),
            416 to byteArrayOf(1),
        )
        for ((firstStatus, fallbackBody) in failedRestarts) {
            val directory = storage.newFolder()
            val partial = directory.resolve("${manifest.file}.part")
            val prefix = modelBytes.copyOf(3)
            partial.writeBytes(prefix)
            val requests = mutableListOf<String?>()
            val store = FridgeProduceModelStore(directory, manifest) { url ->
                if (requests.isEmpty() && firstStatus != 200) {
                    response(url, byteArrayOf(), firstStatus, "bytes 1-3/4") { requests.add(it) }
                } else {
                    response(url, fallbackBody) { requests.add(it) }
                }
            }

            assertTrue(runCatching { store.download() }.exceptionOrNull() is IOException)
            assertEquals(if (firstStatus == 200) listOf("bytes=3-") else listOf("bytes=3-", null), requests)
            assertNull(store.installedModel())
            assertEquals(FridgeProduceModelStore.Status.Resumable, store.status())
            assertArrayEquals(prefix, partial.readBytes())
            assertEquals(listOf(partial.name), directory.list()?.toList())

            val resumed = FridgeProduceModelStore(directory, manifest) { url ->
                response(url, modelBytes.copyOfRange(3, 4), 206, "bytes 3-3/4") { assertEquals("bytes=3-", it) }
            }
            assertArrayEquals(modelBytes, resumed.download().readBytes())
        }
    }

    @Test
    fun anOversizedRangeDoesNotReplaceOrPoisonTheSavedPrefix() = runBlocking {
        val directory = storage.newFolder()
        val partial = directory.resolve("${manifest.file}.part")
        val prefix = modelBytes.copyOf(2)
        partial.writeBytes(prefix)
        val store = FridgeProduceModelStore(directory, manifest) { url ->
            response(url, byteArrayOf(3, 4, 9), 206, "bytes 2-3/4") { assertEquals("bytes=2-", it) }
        }
        assertTrue(runCatching { store.download() }.exceptionOrNull() is IOException)
        assertArrayEquals(prefix, partial.readBytes())
        assertNull(store.installedModel())
    }

    @Test
    fun rejectedOrMismatchedRangeIsRetriedWithoutAppendingIt() = runBlocking {
        for (status in listOf(206, 416)) {
            val directory = storage.newFolder()
            directory.resolve("${manifest.file}.part").writeBytes(modelBytes.copyOf(2))
            val requests = mutableListOf<String?>()
            val store = FridgeProduceModelStore(directory, manifest) { url ->
                if (requests.isEmpty()) response(url, byteArrayOf(9, 9), status, "bytes 0-1/4") { requests.add(it) }
                else response(url, modelBytes) { requests.add(it) }
            }
            assertArrayEquals(modelBytes, store.download().readBytes())
            assertEquals(listOf("bytes=2-", null), requests)
        }
    }

    @Test
    fun cancellingABlockedReadDisconnectsAndPreservesTheReceivedPrefix() = runBlocking {
        val directory = storage.newFolder()
        val waiting = CompletableDeferred<Unit>()
        val disconnected = CountDownLatch(1)
        val store = FridgeProduceModelStore(directory, manifest) { url ->
            object : HttpURLConnection(url) {
                override fun getResponseCode() = HTTP_OK
                override fun getInputStream() = object : InputStream() {
                    private var sentPrefix = false

                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        if (!sentPrefix) {
                            modelBytes.copyInto(bytes, offset, endIndex = 2)
                            sentPrefix = true
                            return 2
                        }
                        waiting.complete(Unit)
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
                        while (System.nanoTime() < deadline) {
                            try {
                                if (disconnected.await(100, TimeUnit.MILLISECONDS)) throw IOException("Disconnected")
                            } catch (_: InterruptedException) {
                                // Simulate a blocking network read that requires closing the socket.
                            }
                        }
                        throw IOException("Test network read timed out")
                    }

                    override fun read(): Int = error("Expected buffered read")
                }
                override fun connect() = Unit
                override fun disconnect() = disconnected.countDown()
                override fun usingProxy() = false
            }
        }
        val download = launch { store.download() }
        withTimeout(3_000) { waiting.await() }
        download.cancel()
        withTimeout(3_000) { download.join() }
        assertEquals(0L, disconnected.count)
        assertNull(store.installedModel())
        assertArrayEquals(modelBytes.copyOf(2), directory.resolve("${manifest.file}.part").readBytes())
    }

    @Test
    fun oversizedAndWrongHashResponsesDoNotInstallAndCanBeRetried() = runBlocking {
        val directory = storage.newFolder()
        var payload = modelBytes
        val store = FridgeProduceModelStore(directory, manifest) { url -> response(url, payload) }

        for (invalid in listOf(modelBytes.copyOf(5), byteArrayOf(9, 2, 3, 4))) {
            payload = invalid
            val failure = runCatching { store.download() }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertNull(store.installedModel())
            assertFalse(directory.resolve("${manifest.file}.part").exists())
        }

        payload = modelBytes
        val installed = store.download()
        assertArrayEquals(modelBytes, installed.readBytes())
        installed.writeBytes(byteArrayOf(9, 2, 3, 4))
        assertNull(store.installedModel())
        assertArrayEquals(modelBytes, store.download().readBytes())
    }

    private fun response(
        url: URL,
        body: ByteArray,
        status: Int = 200,
        range: String? = null,
        onRequest: (String?) -> Unit = {},
    ) = object : HttpURLConnection(url) {
        override fun getResponseCode(): Int {
            onRequest(getRequestProperty("Range"))
            return status
        }
        override fun getHeaderField(name: String?) = if (name.equals("Content-Range", ignoreCase = true)) range else null
        override fun getInputStream() = body.inputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
}
