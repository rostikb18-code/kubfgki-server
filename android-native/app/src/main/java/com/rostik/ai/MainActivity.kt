package com.rostik.ai

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.widget.*
import android.graphics.drawable.GradientDrawable
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private data class BrawlerEntry(val id: Int, val name: String, val imageUrl: String)
    private val starterNames = listOf(
        "Shelly","Nita","Colt","Bull","Brock","El Primo","Barley","Poco","Rosa",
        "Jessie","Dynamike","Tick","8-Bit","Rico","Darryl","Penny","Carl","Jacky",
        "Gus","Bo","Emz","Stu","Max","Bibi","Bea","Nani","Edgar","Griff","Grom",
        "Bonnie","Hank","Pearl","Larry & Lawrie","Angelo","Berry","Shade","Meeple"
    )
    private val brawlers = mutableListOf<BrawlerEntry>().apply {
        addAll(starterNames.map { BrawlerEntry(-1, it, "") })
    }
    private val imageCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
    private val ioExecutor = java.util.concurrent.Executors.newFixedThreadPool(3)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private lateinit var selectedLabel: TextView
    private lateinit var status: TextView
    private lateinit var currentTrophies: EditText
    private lateinit var targetTrophies: EditText
    private lateinit var queueView: LinearLayout
    private lateinit var widthValue: TextView
    private lateinit var heightValue: TextView
    private lateinit var widthSlider: SeekBar
    private lateinit var heightSlider: SeekBar
    private var selected = "Shelly"
    private val queue = linkedMapOf<String, Pair<Int, Int>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(13, 17, 29)
        window.navigationBarColor = Color.rgb(13, 17, 29)
        loadQueue()
        buildUi()
        renderBrawlers("")
        renderQueue()
        refreshBrawlerCatalog()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            setBackgroundColor(Color.rgb(13, 17, 29))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(label("ROSTIKAI", 28, Color.WHITE, true))
        content.addView(label("ANDROID • CONTROL PANEL", 11, Color.rgb(135, 153, 185), true))
        content.addView(space(16))
        val card = panel()
        card.addView(label("БОЕЦ И КУБКИ", 15, Color.WHITE, true))
        search = EditText(this).apply {
            hint = "Поиск бойца…"
            setSingleLine()
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(Color.rgb(28, 35, 52), 12)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderBrawlers(s.toString()) }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        card.addView(search, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })
        val listScroll = ScrollView(this).apply { isFillViewport = false }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listScroll.addView(list)
        card.addView(listScroll, LinearLayout.LayoutParams(-1, dp(190)).apply { topMargin = dp(8) })
        selectedLabel = label("Выбран: $selected", 13, Color.LTGRAY, false)
        card.addView(selectedLabel)

        currentTrophies = trophyInput("Текущие кубки")
        targetTrophies = trophyInput("Целевые кубки")
        val trophyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        trophyRow.addView(currentTrophies, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6); topMargin = dp(8) })
        trophyRow.addView(targetTrophies, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6); topMargin = dp(8) })
        card.addView(trophyRow)
        card.addView(actionButton("ДОБАВИТЬ В ОЧЕРЕДЬ", Color.rgb(46, 130, 180)) {
            val current = currentTrophies.text.toString().toIntOrNull()
            val target = targetTrophies.text.toString().toIntOrNull()
            if (current == null || target == null || current < 0 || target < 0) {
                Toast.makeText(this, "Введи текущие и целевые кубки", Toast.LENGTH_SHORT).show()
            } else {
                queue[selected] = current to target
                saveQueue()
                renderQueue()
                Toast.makeText(this, "$selected добавлен в очередь", Toast.LENGTH_SHORT).show()
            }
        }.also { it.isAllCaps = false }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })
        content.addView(card)

        content.addView(space(12))
        val queueCard = panel()
        queueCard.addView(label("ОЧЕРЕДЬ", 15, Color.WHITE, true))
        queueView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        queueCard.addView(queueView)
        queueCard.addView(actionButton("ОЧИСТИТЬ ОЧЕРЕДЬ", Color.rgb(100, 68, 79)) {
            queue.clear(); saveQueue(); renderQueue()
        }.also { it.isAllCaps = false }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })
        content.addView(queueCard)
        content.addView(space(12))
        val controlCard = panel()
        controlCard.addView(label("УПРАВЛЕНИЕ", 15, Color.WHITE, true))
        status = label("Состояние: готов к настройке", 13, Color.rgb(255, 205, 95), false)
        controlCard.addView(status)
        controlCard.addView(actionButton("НАСТРОИТЬ ЖЕСТЫ ANDROID", Color.rgb(70, 88, 135)) {
            status.text = "Включи RostikAI в настройках специальных возможностей Android"
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.also { it.isAllCaps = false }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
        val prefs = getSharedPreferences("rostikai", MODE_PRIVATE)
        val savedWidth = prefs.getInt("overlay_width", 64).coerceIn(44, 120)
        val savedHeight = prefs.getInt("overlay_height", 64).coerceIn(44, 120)
        widthValue = label("Ширина кнопки AI: ${savedWidth} dp", 13, Color.LTGRAY, false)
        controlCard.addView(widthValue)
        widthSlider = SeekBar(this).apply {
            max = 76
            progress = savedWidth - 44
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress + 44
                    widthValue.text = "Ширина кнопки AI: ${value} dp"
                    saveOverlaySize(value, heightSlider.progress + 44)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        controlCard.addView(widthSlider, LinearLayout.LayoutParams(-1, dp(42)))
        heightValue = label("Высота кнопки AI: ${savedHeight} dp", 13, Color.LTGRAY, false)
        controlCard.addView(heightValue)
        heightSlider = SeekBar(this).apply {
            max = 76
            progress = savedHeight - 44
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress + 44
                    heightValue.text = "Высота кнопки AI: ${value} dp"
                    saveOverlaySize(widthSlider.progress + 44, value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        controlCard.addView(heightSlider, LinearLayout.LayoutParams(-1, dp(42)))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(actionButton("START", Color.rgb(46, 180, 117)) {
            if (!Settings.canDrawOverlays(this)) {
                status.text = "Разреши отображение поверх других приложений"
                startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), 401)
            } else {
                requestScreenCapture()
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(4); topMargin = dp(10) })
        buttons.addView(actionButton("PAUSE", Color.rgb(73, 103, 168)) {
            stopService(Intent(this, ScreenCaptureService::class.java))
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_PAUSE))
            status.text = "Пауза: захват экрана остановлен, кнопка скрыта; игровой ИИ ещё не подключён"
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(4); rightMargin = dp(4); topMargin = dp(10) })
        buttons.addView(actionButton("STOP", Color.rgb(191, 69, 82)) {
            stopService(Intent(this, ScreenCaptureService::class.java))
            stopService(Intent(this, OverlayService::class.java))
            status.text = "Состояние: захват экрана и кнопка AI остановлены"
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(4); topMargin = dp(10) })
        controlCard.addView(buttons)
        controlCard.addView(label("Кнопка-пузырь и её размер работают. Автономное распознавание и управление матчем ещё не подключены.", 12, Color.rgb(174,184,205), false))
        content.addView(controlCard)
        setContentView(root)
    }

    private fun saveOverlaySize(width: Int, height: Int) {
        getSharedPreferences("rostikai", MODE_PRIVATE).edit()
            .putInt("overlay_width", width).putInt("overlay_height", height).apply()
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_RESIZE))
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        status.text = "Подтверди системный запрос на захват экрана"
        startActivityForResult(manager.createScreenCaptureIntent(), 402)
    }

    private fun startCaptureAndOverlay(resultCode: Int, data: Intent) {
        val captureIntent = Intent(this, ScreenCaptureService::class.java)
            .setAction(ScreenCaptureService.ACTION_START)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(captureIntent)
        else startService(captureIntent)
        startOverlay()
        status.text = "Захват экрана запущен; анализ матча и управление ещё не подключены"
        Toast.makeText(this, "Захват экрана активен. Автономная игра пока не реализована.", Toast.LENGTH_LONG).show()
    }

    private fun startOverlay() {
        startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START))
    }

    @Deprecated("Deprecated in Android API; retained for compatibility with the current no-AndroidX build")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 401) {
            if (Settings.canDrawOverlays(this)) requestScreenCapture()
            else status.text = "Разрешение на отображение поверх приложений не выдано"
        } else if (requestCode == 402) {
            if (resultCode == RESULT_OK && data != null) {
                startCaptureAndOverlay(resultCode, data)
            } else {
                status.text = "Захват экрана отменён; AI не запущен"
                Toast.makeText(this, "Для захвата экрана нужно разрешение Android.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderBrawlers(query: String) {
        if (!::list.isInitialized) return
        list.removeAllViews()
        brawlers.filter { it.name.contains(query.trim(), ignoreCase = true) }.forEach { entry ->
            val name = entry.name
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = if (name == selected) rounded(Color.rgb(35, 57, 60), 9) else null
                val portrait = ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(10) }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(android.R.drawable.ic_menu_gallery)
                    tag = entry.imageUrl
                }
                addView(portrait)
                addView(TextView(this@MainActivity).apply {
                    text = (if (name == selected) "●  " else "○  ") + name
                    textSize = 15f
                    setTextColor(if (name == selected) Color.rgb(107, 231, 177) else Color.WHITE)
                    gravity = Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(0, -1, 1f))
                if (entry.imageUrl.isNotBlank()) loadPortrait(entry.imageUrl, portrait)
                setOnClickListener {
                    selected = name
                    selectedLabel.text = "Выбран: $selected"
                    queue[selected]?.let { (current, target) ->
                        currentTrophies.setText(current.toString())
                        targetTrophies.setText(target.toString())
                    } ?: run {
                        currentTrophies.setText("")
                        targetTrophies.setText("")
                    }
                    renderBrawlers(search.text.toString())
                }
            }
            list.addView(row, LinearLayout.LayoutParams(-1, dp(44)))
        }
        if (list.childCount == 0) list.addView(label("Ничего не найдено", 13, Color.LTGRAY, false))
    }

    private fun refreshBrawlerCatalog() {
        ioExecutor.execute {
            try {
                val connection = java.net.URL("https://api.brawlapi.com/v1/brawlers").openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 7000
                connection.readTimeout = 9000
                connection.setRequestProperty("Accept", "application/json")
                val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                connection.disconnect()
                val source = JSONObject(payload).getJSONArray("list")
                val loaded = mutableListOf<BrawlerEntry>()
                for (i in 0 until source.length()) {
                    val item = source.getJSONObject(i)
                    val name = item.optString("name").trim()
                    val id = item.optInt("id", -1)
                    val image = item.optString("imageUrl2").ifBlank { item.optString("imageUrl") }
                    if (name.isNotBlank() && id > 0) loaded.add(BrawlerEntry(id, name, image))
                }
                if (loaded.isEmpty()) throw IllegalStateException("Brawler catalog was empty")
                mainHandler.post {
                    val previousSelection = selected
                    brawlers.clear()
                    brawlers.addAll(loaded.distinctBy { it.id })
                    if (brawlers.none { it.name == previousSelection }) selected = brawlers.first().name
                    selectedLabel.text = "Выбран: $selected"
                    renderBrawlers(search.text.toString())
                    status.text = "Каталог бойцов обновлён: ${brawlers.size}; изображения загружаются из сети"
                }
            } catch (error: Exception) {
                android.util.Log.w("RostikCatalog", "Online brawler catalog unavailable; keeping offline fallback", error)
                mainHandler.post { if (::status.isInitialized) status.text = "Каталог офлайн: ${brawlers.size} имён; проверь интернет для полного списка" }
            }
        }
    }

    private fun loadPortrait(url: String, target: ImageView) {
        imageCache[url]?.let { target.setImageBitmap(it); return }
        ioExecutor.execute {
            try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 7000
                connection.setRequestProperty("User-Agent", "RostikAI-Android")
                val bitmap = connection.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                connection.disconnect()
                if (bitmap != null) {
                    imageCache[url] = bitmap
                    mainHandler.post { if (target.tag == url) target.setImageBitmap(bitmap) }
                }
            } catch (error: Exception) { android.util.Log.d("RostikCatalog", "Portrait load failed", error) }
        }
    }

    private fun renderQueue() {
        if (!::queueView.isInitialized) return
        queueView.removeAllViews()
        if (queue.isEmpty()) {
            queueView.addView(label("Очередь пока пустая", 13, Color.LTGRAY, false))
            return
        }
        queue.forEach { (name, trophies) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(label("$name  •  ${trophies.first} → ${trophies.second}", 14, Color.WHITE, false),
                LinearLayout.LayoutParams(0, dp(42), 1f))
            row.addView(actionButton("×", Color.rgb(100, 68, 79)) {
                queue.remove(name); saveQueue(); renderQueue()
            }, LinearLayout.LayoutParams(dp(44), dp(38)))
            queueView.addView(row)
        }
    }

    private fun saveQueue() {
        val array = JSONArray()
        queue.forEach { (name, values) ->
            array.put(JSONObject().put("name", name).put("current", values.first).put("target", values.second))
        }
        getSharedPreferences("rostikai", MODE_PRIVATE).edit().putString("queue", array.toString()).apply()
    }

    private fun loadQueue() {
        val raw = getSharedPreferences("rostikai", MODE_PRIVATE).getString("queue", "[]") ?: "[]"
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                queue[item.getString("name")] = item.getInt("current") to item.getInt("target")
            }
        } catch (_: Exception) { queue.clear() }
    }

    private fun trophyInput(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        background = rounded(Color.rgb(28,35,52), 12); setPadding(dp(12),0,dp(12),0)
    }
    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(Color.rgb(21, 27, 43), 18)
    }
    private fun label(text: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(3), 0, dp(3))
    }
    private fun actionButton(text: String, color: Int, click: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 12f; setTextColor(Color.WHITE)
        background = rounded(color, 12); setOnClickListener { click() }
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun space(height: Int) = android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
