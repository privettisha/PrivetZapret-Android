package ru.privetdns.zapret

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.*
import android.text.InputType
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import ru.privetdns.zapret.net.*
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val paper = Color.rgb(244, 246, 245)
    private val ink = Color.rgb(16, 24, 32)
    private val blue = Color.rgb(49, 87, 213)
    private val mint = Color.rgb(186, 216, 197)
    private val muted = Color.rgb(82, 97, 106)
    private lateinit var settings: Settings
    private lateinit var pages: FrameLayout
    private lateinit var power: Button
    private lateinit var headline: TextView
    private lateinit var statusLine: TextView
    private lateinit var healthLine: TextView
    private lateinit var connectionTest: Button
    private lateinit var telegramConnect: Button
    private lateinit var telegramWebProxyInput: EditText
    private lateinit var mode: Switch
    private lateinit var dnsInput: EditText
    private lateinit var fallbackInput: EditText
    private lateinit var bootstrapInput: EditText
    private lateinit var save: Button
    private lateinit var dnsTest: Button
    private lateinit var dnsResult: TextView
    private lateinit var strategyGroup: RadioGroup
    private lateinit var quic: Switch
    private lateinit var desktopQuic: Switch
    private val nav = ArrayList<TextView>()
    private val pageViews = ArrayList<View>()
    private var currentTab = 0
    private var state = "off"
    private var telegramStatus = ""
    private var storageError = false
    private var saving = false
    private var lastDetail = "Готов к подключению"
    private var lastDns = ""
    private var lastHealth = ""
    private var refreshing = false
    private val io = Executors.newSingleThreadExecutor()
    private val storageIo = Executors.newSingleThreadExecutor()
    @Volatile private var activeTest: EncryptedDns? = null
    private var testing = false
    private val handler = Handler(Looper.getMainLooper())
    private val pollStatus = object : Runnable {
        override fun run() {
            if (state != "off") startService(Intent(this@MainActivity, TunnelService::class.java).setAction(TunnelService.QUERY))
            handler.postDelayed(this, 4000)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            state = intent.getStringExtra("state") ?: "off"
            telegramStatus = intent.getStringExtra("telegram") ?: ""
            renderState(intent.getStringExtra("detail") ?: "", intent.getStringExtra("dns") ?: "",
                intent.getStringExtra("health") ?: "")
        }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(4).toFloat(); stroke?.let { setStroke(dp(1), it) }
    }
    private fun text(value: String, size: Int = 16, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun LinearLayout.space(n: Int = 16) { addView(View(this@MainActivity), LinearLayout.LayoutParams(1, dp(n))) }
    private fun LinearLayout.label(value: String) { space(20); addView(text(value, 13, muted, true)); space(8) }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE); background = shape(blue); minHeight = dp(52); setOnClickListener { action() }
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText; textSize = 15f; setTextColor(ink); setHintTextColor(muted)
        background = shape(Color.WHITE, Color.rgb(219, 225, 224)); setPadding(dp(14), dp(12), dp(14), dp(12))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        setSingleLine(true); minHeight = dp(52)
    }
    private fun page(title: String, subtitle: String, build: LinearLayout.() -> Unit): View {
        val content = column().apply {
            setPadding(dp(24), dp(24), dp(24), dp(32))
            if (title.isNotBlank()) { addView(text(title, 30, ink, true)); space(24) }
            if (subtitle.isNotBlank()) { addView(text(subtitle, 15, muted)); space(24) }
            build()
        }
        return ScrollView(this).apply { isFillViewport = true; addView(content) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = try { Settings.load(this) } catch (_: Exception) { storageError = true; Settings() }
        window.statusBarColor = paper; window.navigationBarColor = paper
        val root = column().apply { setBackgroundColor(paper) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        val brand = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(12))
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_launcher); contentDescription = "" }, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(text("  PrivetZapret", 19, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(brand)
        pages = FrameLayout(this); root.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        pageViews.add(homePage()); pageViews.add(dnsPage()); pageViews.add(optionsPage())
        pageViews.forEach { pages.addView(it) }
        val navigation = LinearLayout(this).apply { setPadding(dp(12), dp(4), dp(12), dp(8)); background = shape(paper) }
        listOf("Главная", "DNS", "Настройки").forEachIndexed { index, title ->
            val tab = text(title, 14, muted, true).apply {
                gravity = Gravity.CENTER; minHeight = dp(56); isClickable = true; isFocusable = true
                setOnClickListener { selectTab(index) }
            }
            nav.add(tab); navigation.addView(tab, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(navigation); setContentView(root)
        selectTab(savedInstanceState?.getInt("tab") ?: 0); refreshSettings(); renderState("Готов к подключению", "", "")
        if (storageError) message("Не удалось прочитать сохранённый профиль. Введите DNS заново и сохраните его; старые настройки не были заменены.")
    }
    private fun homePage() = page("", "") {
        val hero = column().apply {
            background = shape(blue); setPadding(dp(22), dp(22), dp(22), dp(22))
            headline = text("Можно начинать", 25, Color.WHITE, true); addView(headline); space(10)
            space(14)
            power = button("Подключить") { toggle() }.apply { background = shape(mint); setTextColor(ink) }
            addView(power, LinearLayout.LayoutParams(-1, -2))
        }
        addView(hero); space(16)
        statusLine = text("Готов к подключению", 14, muted); addView(statusLine)
        space(8)
        healthLine = text("", 14, muted); addView(healthLine)
        space(10)
        connectionTest = button("Проверить HTTPS") {
            startService(Intent(this@MainActivity, TunnelService::class.java).setAction(TunnelService.CHECK))
        }.apply { background = shape(Color.WHITE, blue); setTextColor(blue) }
        addView(connectionTest, LinearLayout.LayoutParams(-1, -2))
        label("РЕЖИМ")
        mode = Switch(this@MainActivity).apply {
            text = "Только DNS"; textSize = 17f; setTextColor(ink); minHeight = dp(48)
            setOnCheckedChangeListener { _, _ -> if (!refreshing) saveOptions() }
        }
        addView(mode)
        space(18)
        val services = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER }
        listOf(
            "YouTube" to R.drawable.ic_youtube,
            "Telegram" to R.drawable.ic_telegram
        ).forEachIndexed { index, (name, icon) ->
            val card = column().apply {
                gravity = Gravity.CENTER; background = shape(Color.WHITE)
                setPadding(dp(8), dp(12), dp(8), dp(10))
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(icon); contentDescription = name
                }, LinearLayout.LayoutParams(dp(38), dp(38)))
                space(7); addView(text(name, 12, ink, true).apply { gravity = Gravity.CENTER })
            }
            services.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index < 2) marginEnd = dp(8)
            })
        }
        addView(services)
        space(12)
        telegramConnect = button("Локальный прокси Telegram") {
            if (!openLink("tg://socks?server=127.0.0.1&port=14430"))
                message("Откройте Telegram → Настройки → Данные и память → Прокси: SOCKS5, 127.0.0.1, порт 14430.")
        }.apply { background = shape(Color.WHITE, blue); setTextColor(blue) }
        addView(telegramConnect, LinearLayout.LayoutParams(-1, -2))
        space(16)
        addView(text("Если оператор блокирует серверы Telegram, используйте зарубежный WEB Proxy из кабинета PrivetDNS.", 14, muted))
        space(8)
        telegramWebProxyInput = field("https://t.me/webproxy?…").apply {
            contentDescription = "Ссылка Telegram WEB Proxy"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        addView(telegramWebProxyInput)
        space(8)
        addView(button("Добавить WEB Proxy в Telegram") {
            val link = try { TelegramWebProxyLink.parse(telegramWebProxyInput.text.toString()) }
            catch (e: IllegalArgumentException) { message(e.message ?: "Проверьте ссылку WEB Proxy"); return@button }
            if (!openLink(link)) message("Telegram не открыл WEB Proxy. Обновите Telegram или добавьте прокси типа WEB вручную: сервер и ключ указаны в вашей ссылке.")
        }.apply { background = shape(Color.WHITE, blue); setTextColor(blue) }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun dnsPage() = page("DNS", "Личный DoH из кабинета PrivetDNS можно вставить как основной сервер. Если оператор блокирует DoT/853, добавьте DoH как основной или резервный адрес.") {
        addView(text("ОСНОВНОЙ СЕРВЕР", 12, muted, true)); space(8)
        dnsInput = field("https://…/dns-query или tls://…"); dnsInput.contentDescription = "Основной DNS"; addView(dnsInput)
        label("РЕЗЕРВНЫЙ DOH · НЕОБЯЗАТЕЛЬНО")
        fallbackInput = field("https://…/dns-query"); fallbackInput.contentDescription = "Резервный DoH"; addView(fallbackInput)
        val advanced = column().apply {
            visibility = View.GONE
            label("IP ОСНОВНОГО СЕРВЕРА")
            bootstrapInput = field("IP именно вашего DNS-сервера"); bootstrapInput.contentDescription = "IP DNS-сервера"; addView(bootstrapInput)
        }
        space(12)
        addView(text("Дополнительно: начальный IP  ▾", 14, blue, true).apply {
            minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL; isFocusable = true
            setOnClickListener { advanced.visibility = if (advanced.visibility == View.GONE) View.VISIBLE else View.GONE }
        })
        addView(advanced)
        space(24); save = button("Сохранить DNS") { saveDns() }; addView(save, LinearLayout.LayoutParams(-1, -2))
        space(10); dnsTest = button("Проверить DNS") { testDns() }.apply { background = shape(Color.WHITE, blue); setTextColor(blue) }; addView(dnsTest, LinearLayout.LayoutParams(-1, -2))
        space(12); dnsResult = text("", 13, muted); addView(dnsResult)
    }
    private fun optionsPage() = page("Настройки", "") {
        addView(text("ПРОФИЛЬ ОБХОДА", 12, muted, true)); space(8)
        strategyGroup = RadioGroup(this@MainActivity)
        listOf("Автоматический", "Разделение пакетов", "Разделение TLS").forEachIndexed { i, name ->
            strategyGroup.addView(RadioButton(this@MainActivity).apply { id = 300 + i; text = name; textSize = 16f; minHeight = dp(48); setTextColor(ink) })
        }
        strategyGroup.setOnCheckedChangeListener { _, _ -> if (!refreshing) saveOptions() }; addView(strategyGroup)
        space(12); quic = Switch(this@MainActivity).apply {
            text = "Принудительно использовать TCP"; textSize = 17f; setTextColor(ink); minHeight = dp(52)
            setOnCheckedChangeListener { _, _ -> if (!refreshing) saveOptions() }
        }; addView(quic)
        space(12)
        desktopQuic = Switch(this@MainActivity).apply {
            text = "Улучшенный QUIC"; textSize = 17f; setTextColor(ink); minHeight = dp(52)
            setOnCheckedChangeListener { _, _ -> if (!refreshing) saveOptions() }
        }; addView(desktopQuic)
        label("О ПРОЕКТЕ")
        addView(text("PrivetZapret Android · ${BuildConfig.VERSION_NAME}", 14, muted)); space(12)
        addView(button("Обновить приложение") {
            if (!openLink("https://github.com/privettisha/PrivetZapret-Android/releases"))
                message("Не удалось открыть страницу обновления")
        }, LinearLayout.LayoutParams(-1, -2))
        space(10)
        addView(button("GitHub") { if (!openLink("https://github.com/privettisha/PrivetZapret-Android")) message("Не удалось открыть GitHub") }.apply {
            background = shape(Color.WHITE, blue); setTextColor(blue)
        }, LinearLayout.LayoutParams(-1, -2))
        space(10)
        addView(button("Telegram") { if (!openLink("https://t.me/PRIVET_DNS")) message("Не удалось открыть Telegram") }.apply {
            background = shape(Color.WHITE, blue); setTextColor(blue)
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun selectTab(index: Int) {
        currentTab = index.coerceIn(0, 2)
        pageViews.forEachIndexed { i, view -> view.visibility = if (i == currentTab) View.VISIBLE else View.GONE }
        nav.forEachIndexed { i, view -> view.setTextColor(if (i == currentTab) blue else muted); view.background = shape(if (i == currentTab) Color.WHITE else paper); view.isSelected = i == currentTab }
    }
    private fun refreshSettings() {
        refreshing = true
        mode.isChecked = settings.dnsOnly; quic.isChecked = settings.blockQuic
        desktopQuic.isChecked = settings.desktopQuic
        strategyGroup.check(300 + when (settings.strategy) { "split" -> 1; "tls" -> 2; else -> 0 })
        dnsInput.setText(settings.dns); fallbackInput.setText(settings.fallback); bootstrapInput.setText(settings.bootstrap)
        refreshing = false
    }
    private fun saveOptions() {
        if (state != "off" || saving) return
        val next = Settings(settings.dns, settings.fallback, settings.bootstrap, mode.isChecked,
            when (strategyGroup.checkedRadioButtonId) { 301 -> "split"; 302 -> "tls"; else -> "auto" },
            quic.isChecked, desktopQuic.isChecked)
        if (storageError) { refreshSettings(); return }
        saving = true; renderState(lastDetail, lastDns, lastHealth)
        storageIo.execute {
            val saved = runCatching { Settings.save(applicationContext, next) }.isSuccess
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (saved) settings = next else { message("Не удалось сохранить настройки"); refreshSettings() }
                saving = false; renderState(lastDetail, lastDns, lastHealth)
            }
        }
    }
    private fun form() = Settings(dnsInput.text.toString().trim(), fallbackInput.text.toString().trim(),
        bootstrapInput.text.toString().trim(), settings.dnsOnly, settings.strategy, settings.blockQuic,
        settings.desktopQuic)
    private fun saveDns() {
        val next = try { form().also { it.profile() } }
        catch (e: IllegalArgumentException) { dnsResult.text = e.message ?: "Проверьте адрес DNS"; return }
        saving = true; dnsResult.text = "Сохраняем DNS…"; renderState(lastDetail, lastDns, lastHealth)
        storageIo.execute {
            val saved = runCatching { Settings.save(applicationContext, next) }.isSuccess
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (saved) {
                    settings = next; storageError = false
                    dnsResult.text = "DNS сохранён. Можно подключаться на главной."; dnsResult.setTextColor(blue)
                } else dnsResult.text = "Не удалось сохранить профиль"
                saving = false; renderState(lastDetail, lastDns, lastHealth)
            }
        }
    }
    private fun testDns() {
        val profile = try { form().profile() } catch (e: IllegalArgumentException) { dnsResult.text = e.message; return }
        testing = true; dnsTest.isEnabled = false; dnsResult.text = "Проверяем DNS…"
        io.execute {
            val started = android.os.SystemClock.elapsedRealtime()
            var transport = ""
            val resolver = EncryptedDns(profile, status = { transport = it }); activeTest = resolver
            val result = try {
                if (DnsWire.hasAnswer(resolver.resolve(DnsWire.query("example.com"))))
                    "DNS отвечает · $transport · ${android.os.SystemClock.elapsedRealtime() - started} мс. Это проверка DNS, а не доступности видео или звонков."
                else "DNS ответил, но не вернул адрес example.com."
            } catch (_: Exception) { "DNS не ответил. Проверьте ссылку и сеть. Если порт DoT закрыт, добавьте резервный DoH." }
            finally { resolver.close(); activeTest = null }
            runOnUiThread { if (!isDestroyed) { testing = false; dnsResult.text = result; dnsTest.isEnabled = state == "off" && !saving } }
        }
    }
    private fun toggle() {
        if (saving) return
        if (state == "on" || state == "starting") {
            try { startService(Intent(this, TunnelService::class.java).setAction(TunnelService.STOP)) }
            catch (_: Exception) { message("Не удалось остановить VPN. Отключите его в настройках Android.") }
            return
        }
        if (storageError) { selectTab(1); message("Сначала сохраните DNS-профиль"); return }
        try { settings.profile() } catch (_: Exception) { selectTab(1); message("Проверьте и сохраните DNS"); return }
        try {
            val prepare = VpnService.prepare(this)
            if (prepare != null) startActivityForResult(prepare, 42) else connect()
        } catch (_: Exception) { message("Android не разрешил запросить VPN. Проверьте настройки устройства.") }
    }
    private fun connect() {
        state = "starting"; renderState("Подключаем защищённый DNS", "", "")
        try { startForegroundService(Intent(this, TunnelService::class.java).setAction(TunnelService.START)) }
        catch (_: Exception) {
            state = "off"; renderState("Не удалось запустить VPN-службу", "", "")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 43)
    }
    @Deprecated("Legacy activity result supported on API 26+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK) connect()
    }
    private fun renderState(detail: String, dns: String, health: String) {
        lastDetail = detail; lastDns = dns; lastHealth = health
        val off = state == "off"
        headline.text = when (state) { "on" -> "Вы подключены"; "starting" -> "Подключаем…"; "stopping" -> "Отключаем…"; else -> "Можно начинать" }
        power.text = if (off) "Подключить" else if (state == "starting") "Отменить" else "Отключить"
        power.isEnabled = state != "stopping" && !saving
        statusLine.text = listOf(detail, dns).filter { it.isNotBlank() }.joinToString(" · ")
        healthLine.text = health
        connectionTest.isEnabled = state == "on" && !health.startsWith("Проверяем")
        telegramConnect.isEnabled = state == "on" && telegramStatus == "ready"
        mode.isEnabled = off && !saving; quic.isEnabled = off && !saving; desktopQuic.isEnabled = off && !saving
        for (i in 0 until strategyGroup.childCount) strategyGroup.getChildAt(i).isEnabled = off && !saving
        listOf(dnsInput, fallbackInput, bootstrapInput, save).forEach { it.isEnabled = off && !saving }
        dnsTest.isEnabled = off && !testing && !saving
    }
    private fun openLink(url: String): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        true
    } catch (_: Exception) { false }
    private fun message(value: String) { AlertDialog.Builder(this).setMessage(value).setPositiveButton("Понятно", null).show() }
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // API <33 uses our signature-only permission instead.
    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(TunnelService.STATUS), "ru.privetdns.zapret.INTERNAL", null, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, IntentFilter(TunnelService.STATUS), "ru.privetdns.zapret.INTERNAL", null)
        startService(Intent(this, TunnelService::class.java).setAction(TunnelService.QUERY))
        handler.postDelayed(pollStatus, 4000)
    }
    override fun onPause() { handler.removeCallbacks(pollStatus); unregisterReceiver(receiver); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("tab", currentTab); super.onSaveInstanceState(outState) }
    override fun onDestroy() { activeTest?.close(); io.shutdownNow(); storageIo.shutdown(); handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
