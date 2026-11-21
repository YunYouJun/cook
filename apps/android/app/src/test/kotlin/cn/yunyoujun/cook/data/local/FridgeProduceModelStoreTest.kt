package cn.yunyoujun.cook.data.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class FridgeProduceModelStoreTest {
    @get:Rule
    val storage = TemporaryFolder()

    private val modelBytes = byteArrayOf(1, 2, 3, 4)
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(modelBytes).toHexString()
    private val manifest = FridgeProduceModel("classifier-$sha256.onnx", modelBytes.size.toLong(), sha256)

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
    fun partialOversizedAndWrongHashResponsesDoNotInstallAndCanBeRetried() = runBlocking {
        val directory = storage.newFolder()
        var payload = modelBytes.copyOf(2)
        val store = FridgeProduceModelStore(directory, manifest) { url -> response(url, payload) }

        for (invalid in listOf(modelBytes.copyOf(2), modelBytes.copyOf(5), byteArrayOf(9, 2, 3, 4))) {
            payload = invalid
            val failure = runCatching { store.download() }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertNull(store.installedModel())
            assertFalse(directory.resolve("classifier.onnx.part").exists())
        }

        payload = modelBytes
        val installed = store.download()
        assertArrayEquals(modelBytes, installed.readBytes())
        installed.writeBytes(byteArrayOf(9, 2, 3, 4))
        assertNull(store.installedModel())
        assertArrayEquals(modelBytes, store.download().readBytes())
    }

    private fun response(url: URL, body: ByteArray) = object : HttpURLConnection(url) {
        override fun getResponseCode() = HTTP_OK
        override fun getInputStream() = body.inputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
}
