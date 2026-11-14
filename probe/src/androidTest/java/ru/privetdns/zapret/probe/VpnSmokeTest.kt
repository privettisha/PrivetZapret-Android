package ru.privetdns.zapret.probe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.net.*
import javax.net.ssl.HttpsURLConnection

/** A SEPARATE application UID: its packets really cross the VPN, unlike the excluded UI process. */
@RunWith(AndroidJUnit4::class)
class VpnSmokeTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun tap(text: String) {
        assertTrue("Missing UI: $text", device.wait(Until.hasObject(By.text(text)), 10000))
        device.findObject(By.text(text)).click(); device.waitForIdle()
    }
    private fun screenshot(name: String) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(dir, "$name.png")))
        device.executeShellCommand("mkdir -p /sdcard/Download/privetzapret")
        device.executeShellCommand("cp ${dir.absolutePath}/$name.png /sdcard/Download/privetzapret/$name.png")
    }
    private fun query(): ByteArray = byteArrayOf(0x45, 0x23, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7) +
        "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0, 0, 1, 0, 1)
    private fun checkAnswer(answer: ByteArray) {
        assertTrue(answer.size >= 12); assertEquals(0x45, answer[0].toInt()); assertEquals(0x23, answer[1].toInt())
        assertEquals(0, answer[3].toInt() and 15); assertTrue(answer[7].toInt() and 255 > 0)
    }
    private fun checkDns() {
        val q = query()
        DatagramSocket().use { socket ->
            socket.soTimeout = 15000
            socket.send(DatagramPacket(q, q.size, InetAddress.getByName("10.111.0.2"), 53))
            val reply = DatagramPacket(ByteArray(4096), 4096); socket.receive(reply)
            assertEquals("10.111.0.2", reply.address.hostAddress); checkAnswer(reply.data.copyOf(reply.length))
        }
        Socket().use { socket ->
            socket.connect(InetSocketAddress("10.111.0.2", 53), 10000); socket.soTimeout = 15000
            DataOutputStream(socket.getOutputStream()).apply { writeShort(q.size); write(q); flush() }
            val input = DataInputStream(socket.getInputStream()); val answer = ByteArray(input.readUnsignedShort())
            input.readFully(answer); checkAnswer(answer)
        }
    }
    @Test fun dnsAndHttpsAcrossRealVpnAndRepeatedStartStop() {
        org.junit.Assume.assumeTrue("Dedicated emulator only", device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1")
        device.executeShellCommand("pm clear ru.privetdns.zapret")
        device.executeShellCommand("cmd appops set ru.privetdns.zapret ACTIVATE_VPN allow")
        device.executeShellCommand("pm grant ru.privetdns.zapret android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("am start -n ru.privetdns.zapret/.MainActivity")
        assertTrue(device.wait(Until.hasObject(By.text("Подключить")), 10000))
        screenshot("01-home")
        tap("DNS"); screenshot("02-dns")
        tap("Настройки"); screenshot("03-settings")
        tap("Главная")
        repeat(2) { iteration ->
            tap("Подключить")
            assertTrue("VPN startup failed", device.wait(Until.hasObject(By.text("Вы подключены")), 40000))
            screenshot("04-connected-$iteration")
            checkDns()
            val connection = URL("https://example.com").openConnection() as HttpsURLConnection
            connection.connectTimeout = 15000; connection.readTimeout = 15000
            try { assertEquals(200, connection.responseCode); assertTrue(connection.inputStream.use { it.readBytes().size } > 100) }
            finally { connection.disconnect() }
            tap("Отключить")
            assertTrue(device.wait(Until.hasObject(By.text("Подключить")), 10000))
            Thread.sleep(700)
        }
        // The DNS-only route must also pass both UDP and TCP DNS.
        val scroll = UiScrollable(UiSelector().scrollable(true))
        scroll.scrollTextIntoView("Только DNS")
        tap("Только DNS"); scroll.scrollToBeginning(8)
        tap("Подключить")
        assertTrue(device.wait(Until.hasObject(By.text("Вы подключены")), 40000))
        checkDns(); screenshot("05-dns-only")
        tap("Отключить")
        assertTrue(device.wait(Until.hasObject(By.text("Подключить")), 10000))
    }
}
