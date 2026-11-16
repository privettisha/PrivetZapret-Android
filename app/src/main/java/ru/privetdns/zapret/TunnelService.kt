package ru.privetdns.zapret

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.net.*
import java.net.Proxy
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import ru.privetdns.zapret.net.*
import org.flowseal.tgwsproxy.proxy.OutboundSocket
import org.flowseal.tgwsproxy.proxy.ProxyServer

class TunnelService : VpnService() {
    companion object {
        const val START = "ru.privetdns.zapret.START"
        const val STOP = "ru.privetdns.zapret.STOP"
        const val QUERY = "ru.privetdns.zapret.QUERY"
        const val CHECK = "ru.privetdns.zapret.CHECK"
        const val STATUS = "ru.privetdns.zapret.STATUS"
        const val DNS_IP = "10.111.0.2"
    }
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopping = AtomicBoolean(false)
    private val startupWatchdog = Runnable {
        if (state == "starting" && !stopping.get())
            stopEngine("Подключение заняло слишком много времени. Проверьте DNS и сеть.")
    }
    private val shutdownWatchdog = Runnable {
        // A native close may hang. This service runs in :engine, separate from the UI.
        if (state == "stopping") android.os.Process.killProcess(android.os.Process.myPid())
    }
    private val probeQueued = AtomicBoolean(false)
    @Volatile private var state = "off"
    @Volatile private var detail = "Готов к подключению"
    @Volatile private var dnsStatus = ""
    @Volatile private var health = ""
    @Volatile private var telegramStatus = ""
    @Volatile private var nativePort = 0
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var dns: EncryptedDns? = null
    @Volatile private var gateway: SocksGateway? = null
    @Volatile private var telegramProxy: ProxyServer? = null
    @Volatile private var nativeUsed = false
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> stopEngine()
            QUERY -> { publish(); if (state == "off" && !nativeUsed) stopSelf() }
            CHECK -> if (state == "on") requestProbe()
            START -> {
                if (state != "off") { publish(); return START_NOT_STICKY }
                state = "starting"; detail = "Подключаем защищённый DNS"; publish()
                try {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.createNotificationChannel(NotificationChannel("connection", "Подключение", NotificationManager.IMPORTANCE_LOW))
                    val notification = notification()
                    if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
                    else startForeground(1, notification)
                } catch (_: Exception) {
                    stopEngine("Android не разрешил запустить VPN-службу.")
                    return START_NOT_STICKY
                }
                mainHandler.postDelayed(startupWatchdog, 45_000)
                worker.execute { try { startEngine() } catch (_: Exception) {
                    if (!stopping.get()) stopEngine("Не удалось подключиться. Проверьте DNS, резервный адрес и сеть.")
                } }
            }
        }
        return START_NOT_STICKY
    }
    private fun startEngine() {
        val config = Settings.load(this)
        val profile = config.profile()
        nativePort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val args = mutableListOf("byedpi", "--ip", "127.0.0.1", "--port", nativePort.toString(), "--max-conn", "256", "--no-domain")
        if (!config.dnsOnly) {
            // ByeDPI otherwise sends decoys for every UDP packet. Only the first QUIC
            // datagram needs them; subsequent media packets pass unchanged.
            args.addAll(listOf("--proto", "udp", "--pf", "443", "--round", "1"))
            if (config.desktopQuic && !config.blockQuic) {
                val fake = File(filesDir, "quic_initial_www_google_com.bin")
                assets.open(fake.name).use { input -> fake.outputStream().use { input.copyTo(it) } }
                args.addAll(listOf("--udp-fake", "6", "--fake-data", fake.absolutePath))
            } else {
                args.addAll(listOf("--udp-fake", "2"))
            }
            args.add("--auto=none")
            when (config.strategy) {
                "split" -> args.addAll(listOf("--split", "1"))
                "tls" -> args.addAll(listOf("--tlsrec", "1+s", "--split", "1+s"))
                else -> args.addAll(listOf("--disorder", "1", "--auto=torst", "--timeout", "3", "--tlsrec", "1+s", "--split", "1+s"))
            }
        }
        nativeUsed = true
        Thread({
            NativeBridge.startProxy(args.toTypedArray())
            if (!stopping.get()) stopEngine("Сетевой модуль остановился. Подключитесь заново.")
        }, "byedpi").start()
        var ready = false
        repeat(100) {
            if (stopping.get()) return
            if (!ready) {
                ready = runCatching {
                    Socket().use { s ->
                        s.connect(InetSocketAddress("127.0.0.1", nativePort), 100); s.soTimeout = 200
                        s.getOutputStream().write(byteArrayOf(5, 1, 0))
                        s.getInputStream().read() == 5 && s.getInputStream().read() == 0
                    }
                }.getOrDefault(false)
                if (!ready) Thread.sleep(30)
            }
        }
        check(ready && !stopping.get())
        OutboundSocket.byeDpiPort = nativePort
        val resolver = EncryptedDns(profile, Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", nativePort)), { s ->
            if (dnsStatus != s) { dnsStatus = s; publish() }
        })
        dns = resolver
        OutboundSocket.resolveName = { host ->
            val query = DnsWire.query(host)
            DnsWire.addresses(query, resolver.resolve(query)).firstOrNull()
                ?: throw java.net.UnknownHostException(host)
        }
        check(DnsWire.hasAnswer(resolver.resolve(DnsWire.query("example.com"))))
        if (stopping.get()) return
        val password = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val bridge = SocksGateway(nativePort, password, resolver::resolve, config.blockQuic && !config.dnsOnly)
        gateway = bridge; bridge.start()
        if (!config.dnsOnly) {
            telegramProxy = runCatching { ProxyServer(port = 14430).also { it.start() } }.getOrNull()
            telegramStatus = if (telegramProxy == null) "Не удалось запустить Telegram-прокси" else "ready"
        }
        val builder = Builder().setSession("PrivetZapret").setMtu(1500)
            .addAddress("10.111.0.1", 32).addDnsServer(DNS_IP)
            .addDisallowedApplication(packageName)
        if (config.dnsOnly) {
            builder.addRoute(DNS_IP, 32)
            // Other IP families stay on the underlying network in DNS-only mode.
            builder.allowFamily(android.system.OsConstants.AF_INET6)
        } else {
            builder.addRoute("0.0.0.0", 0).addAddress("fd71:707a:7000::1", 128).addRoute("::", 0)
        }
        val descriptor = builder.establish() ?: error("VPN permission missing")
        tun = descriptor
        val yaml = """
            tunnel:
              mtu: 1500
              ipv4: 10.111.0.1
              ipv6: 'fd71:707a:7000::1'
            socks5:
              address: 127.0.0.1
              port: ${bridge.port}
              username: 'privet'
              password: '$password'
              udp: 'udp'
            misc:
              task-stack-size: 86016
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-level: error
        """.trimIndent()
        Thread({
            val result = NativeBridge.startTunnel(yaml, descriptor.fd)
            if (!stopping.get()) stopEngine("VPN-модуль остановился ($result). Подключитесь заново.")
        }, "tun2socks").start()
        // A null underlying-network list follows Android's default network on Wi-Fi/mobile handover.
        setUnderlyingNetworks(null)
        Thread.sleep(200)
        if (!stopping.get()) {
            synchronized(this) {
                if (stopping.get()) return
                state = "on"
                detail = if (config.dnsOnly) "Защищённый DNS подключён" else "Локальный обход и DNS подключены"
                mainHandler.removeCallbacks(startupWatchdog)
                publish()
            }
            getSystemService(NotificationManager::class.java).notify(1, notification())
            val cm = getSystemService(ConnectivityManager::class.java)
            val activeNetwork = AtomicReference<Network?>()
            val watcher = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val previous = activeNetwork.getAndSet(network)
                    if (previous != null && previous != network) {
                        dns?.onNetworkChanged()
                        gateway?.onNetworkChanged()
                        telegramProxy?.onNetworkChanged()
                    }
                    requestProbe(2)
                }
                override fun onLost(network: Network) {
                    if (activeNetwork.compareAndSet(network, null)) {
                        dns?.onNetworkChanged()
                        gateway?.onNetworkChanged()
                        telegramProxy?.onNetworkChanged()
                    }
                    requestProbe(2)
                }
            }
            runCatching {
                cm.registerDefaultNetworkCallback(watcher)
                callback = watcher
            }
            requestProbe()
            worker.scheduleWithFixedDelay({ if (state == "on") requestProbe() }, 120, 120, TimeUnit.SECONDS)
        }
    }

    private fun requestProbe(delaySeconds: Long = 0) {
        if (state != "on" || stopping.get() || !probeQueued.compareAndSet(false, true)) return
        try {
            worker.schedule({
                try { checkConnectivity() } finally { probeQueued.set(false) }
            }, delaySeconds, TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) { probeQueued.set(false) }
    }

    private fun checkConnectivity() {
        if (state != "on" || stopping.get()) return
        health = "Проверяем HTTPS через локальный движок…"; publish()
        val resolver = dns ?: return
        val probe = ReachabilityProbe(resolver, nativePort)
        val result = runCatching { probe.check() }
        if (state != "on" || stopping.get()) return
        health = if (result.isSuccess) {
            val youtube = runCatching { probe.checkYoutube() }.getOrDefault(false)
            if (youtube) "HTTPS и YouTube API/CDN отвечают"
            else "HTTPS отвечает; YouTube API/CDN пока недоступны"
        } else "Проверка HTTPS не прошла — проверьте доступ в приложениях"
        publish(); getSystemService(NotificationManager::class.java).notify(1, notification())
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TunnelService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "connection").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("PrivetZapret").setContentText(if (health.isBlank()) detail else health).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Отключить", stop).build()).build()
    }
    private fun publish() {
        sendBroadcast(Intent(STATUS).setPackage(packageName).putExtra("state", state).putExtra("detail", detail)
            .putExtra("dns", dnsStatus).putExtra("health", health).putExtra("telegram", telegramStatus),
            "ru.privetdns.zapret.INTERNAL")
    }
    private fun stopEngine(error: String? = null) {
        synchronized(this) {
            if (!stopping.compareAndSet(false, true)) return
            mainHandler.removeCallbacks(startupWatchdog)
            state = "stopping"; detail = "Отключение"; publish()
            mainHandler.postDelayed(shutdownWatchdog, 10_000)
        }
        // Native shutdown and socket closes can block. Never run them on Android's main thread.
        Thread({ finishStop(error) }, "engine-stop").start()
    }
    private fun finishStop(error: String?) {
        callback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        if (nativeUsed) { runCatching { NativeBridge.stopTunnel() }; runCatching { NativeBridge.stopProxy() } }
        runCatching { tun?.close() }; runCatching { telegramProxy?.stop() }
        runCatching { gateway?.close() }; runCatching { dns?.close() }
        OutboundSocket.byeDpiPort = 0
        OutboundSocket.resolveName = null
        worker.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        mainHandler.removeCallbacks(shutdownWatchdog)
        state = "off"; detail = error ?: "Готов к подключению"; dnsStatus = ""; health = ""
        telegramStatus = ""; publish(); stopSelf()
        // ByeDPI has process-global state. Only the isolated :engine worker is terminated.
        if (nativeUsed) android.os.Process.killProcess(android.os.Process.myPid())
    }
    override fun onRevoke() { stopEngine(); super.onRevoke() }
    override fun onDestroy() { if (!stopping.get()) stopEngine(); super.onDestroy() }
}
