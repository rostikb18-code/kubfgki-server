package com.rostik.ai

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import android.graphics.drawable.GradientDrawable

class MainActivity : Activity() {
    private val brawlers = listOf(
        "Shelly","Nita","Colt","Bull","Brock","El Primo","Barley","Poco","Rosa",
        "Jessie","Dynamike","Tick","8-Bit","Rico","Darryl","Penny","Carl","Jacky",
        "Gus","Bo","Emz","Stu","Max","Bibi","Bea","Nani","Edgar","Griff","Grom",
        "Bonnie","Hank","Pearl","Larry & Lawrie","Angelo","Berry","Shade","Meeple"
    )
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private lateinit var status: TextView
    private var selected = "Shelly"
    private var running = false
    private var paused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(13, 17, 29)
        window.navigationBarColor = Color.rgb(13, 17, 29)
        buildUi()
        renderBrawlers("")
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
        card.addView(label("Выбран: ", 13, Color.LTGRAY, false).also { it.tag = "selectedLabel" })
        val current = EditText(this).apply {
            hint = "Текущие кубки"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            background = rounded(Color.rgb(28,35,52), 12); setPadding(dp(12),0,dp(12),0)
        }
        val target = EditText(this).apply {
            hint = "Целевые кубки"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            background = rounded(Color.rgb(28,35,52), 12); setPadding(dp(12),0,dp(12),0)
        }
        val trophyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        trophyRow.addView(current, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6); topMargin = dp(8) })
        trophyRow.addView(target, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6); topMargin = dp(8) })
        card.addView(trophyRow)
        content.addView(card)

        content.addView(space(12))
        val queueCard = panel()
        queueCard.addView(label("ОЧЕРЕДЬ", 15, Color.WHITE, true))
        queueCard.addView(label("Выбери бойца выше. Список очереди будет добавлен в следующей итерации.", 13, Color.LTGRAY, false))
        content.addView(queueCard)
        content.addView(space(12))
        val controlCard = panel()
        controlCard.addView(label("УПРАВЛЕНИЕ", 15, Color.WHITE, true))
        status = label("Состояние: готов к настройке", 13, Color.rgb(255, 205, 95), false)
        controlCard.addView(status)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(actionButton("START", Color.rgb(46, 180, 117)) {
            status.text = "Состояние: интерфейс готов; игровой AI ещё не подключён"
            status.setTextColor(Color.rgb(255,205,95))
            Toast.makeText(this@MainActivity, "Автоигра пока не подключена — не буду имитировать запуск", Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(4); topMargin = dp(10) })
        buttons.addView(actionButton("PAUSE", Color.rgb(73, 103, 168)) {
            paused = !paused
            status.text = if (paused) "Состояние: пауза интерфейса" else "Состояние: готов к настройке"
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(4); rightMargin = dp(4); topMargin = dp(10) })
        buttons.addView(actionButton("STOP", Color.rgb(191, 69, 82)) {
            running = false; paused = false
            status.text = "Состояние: остановлено"
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(4); topMargin = dp(10) })
        controlCard.addView(buttons)
        controlCard.addView(label("Важно: эта сборка — новая основа приложения. Автоматическое распознавание и управление матчем ещё не реализованы.", 12, Color.rgb(174,184,205), false))
        content.addView(controlCard)
        setContentView(root)
    }

    private fun renderBrawlers(query: String) {
        if (!::list.isInitialized) return
        list.removeAllViews()
        brawlers.filter { it.contains(query.trim(), ignoreCase = true) }.forEach { name ->
            val row = TextView(this).apply {
                text = (if (name == selected) "●  " else "○  ") + name
                textSize = 15f
                setTextColor(if (name == selected) Color.rgb(107, 231, 177) else Color.WHITE)
                setPadding(dp(12), dp(10), dp(8), dp(10))
                background = if (name == selected) rounded(Color.rgb(35, 57, 60), 9) else null
                setOnClickListener {
                    selected = name
                    renderBrawlers(search.text.toString())
                    findViewWithTag<TextView>("selectedLabel")?.text = "Выбран: $selected"
                }
            }
            list.addView(row, LinearLayout.LayoutParams(-1, dp(42)))
        }
        if (list.childCount == 0) list.addView(label("Ничего не найдено", 13, Color.LTGRAY, false))
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
