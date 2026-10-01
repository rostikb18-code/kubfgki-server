package com.rostik.touchbot

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.*
import android.util.LruCache
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlin.math.roundToInt

/**
 * Rostik AI overlay UI.
 *
 * Design goals for 31.2 UI:
 *  - no Bot/Queue/Size tabs;
 *  - real 108/108 brawler buttons with names and scroll;
 *  - selecting a brawler opens its current/target trophy editor;
 *  - custom in-panel numeric keypad, so trophy editing does not depend on the
 *    device keyboard/theme;
 *  - queue has its own scroll area;
 *  - width and height are real horizontal SeekBars and resize the WindowManager
 *    panel live;
 *  - START starts the supervisor, PAUSE pauses/resumes it, STOP stops the
 *    supervisor and removes the AI overlay completely.
 */
class OverlayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var panel: View? = null
    private var expanded = false
    private var bubbleX = 24
    private var bubbleY = 220

    private var widthPct = 78
    private var heightPct = 86
    private var brawlerScrollY = 0
    private var queueScrollY = 0
    private var brawlerSearch = ""
    private var lastSelectedId: String? = null
    private var editorField: EditorField? = null
    private var brawlerGridHost: LinearLayout? = null
    private var brawlerCountView: TextView? = null
    private var brawlerGridScroll: ScrollView? = null

    private val selected = linkedSetOf<String>()
    private val draft = linkedMapOf<String, Pair<Int, Int>>()
    private val avatarMap = Properties()
    private val avatarDownloads = ConcurrentHashMap.newKeySet<String>()
    private val avatarExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RostikAvatarLoader").apply { isDaemon = true }
    }
    private val avatarCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var avatarCacheDir: File

    private enum class EditorField { CURRENT, TARGET }

    override fun onCreate() {
        super.onCreate()
        BotRuntime.ensureInitialized(this)
        StatsStore.init(this)
        DiscordLogger.init(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        avatarCacheDir = File(filesDir, "brawler_avatar_cache").apply { mkdirs() }
        loadAvatarMap()
        loadDraft()
        loadSize()
        preloadAllAvatars()
        preloadMissingRemoteAvatars()
        startForegroundCompat()
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try { bubble?.let(wm::removeView) } catch (_: Throwable) {}
        try { panel?.let(wm::removeView) } catch (_: Throwable) {}
        avatarExecutor.shutdownNow()
        synchronized(avatarCache) { avatarCache.evictAll() }
        bubble = null
        panel = null
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val channelId = "overlay_v311"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Rostik AI overlay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
                .setContentTitle("Rostik AI 31.1")
                .setContentText("AI overlay active")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Rostik AI 31.1")
                .setContentText("AI overlay active")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(31, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(31, notification)
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    private fun bubbleParams() = WindowManager.LayoutParams(
        dp(58), dp(58), overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = bubbleX
        y = bubbleY
    }

    private fun showBubble() {
        if (bubble != null) return
        var moved = false
        bubble = TextView(this).apply {
            text = "AI"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = circle(Color.rgb(0, 188, 245), dp(29f))
            elevation = dp(8f).toFloat()
            setOnClickListener { if (!moved) togglePanel() }
        }
        bubble!!.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0
            private var downY = 0
            private var startX = 0
            private var startY = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        moved = false
                        downX = event.rawX.roundToInt()
                        downY = event.rawY.roundToInt()
                        startX = bubbleX
                        startY = bubbleY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX.roundToInt() - downX
                        val dy = event.rawY.roundToInt() - downY
                        if (kotlin.math.abs(dx) > dp(5) || kotlin.math.abs(dy) > dp(5)) moved = true
                        bubbleX = (startX + dx).coerceIn(0, resources.displayMetrics.widthPixels - dp(58))
                        bubbleY = (startY + dy).coerceIn(0, resources.displayMetrics.heightPixels - dp(58))
                        try { wm.updateViewLayout(bubble, bubbleParams()) } catch (_: Throwable) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) v.performClick()
                        return true
                    }
                }
                return true
            }
        })
        try { wm.addView(bubble, bubbleParams()) } catch (_: Throwable) { bubble = null }
    }

    private fun togglePanel() {
        DiscordLogger.info(DiscordLogger.Category.UI, if (expanded) "Панель свернута" else "Панель открыта", "overlay")
        if (expanded) collapse() else expand()
    }

    private fun expand() {
        if (expanded) return
        expanded = true
        try { bubble?.let(wm::removeView) } catch (_: Throwable) {}
        bubble = null
        panel = buildPanel()
        try { wm.addView(panel, panelParams()) }
        catch (_: Throwable) {
            panel = null
            expanded = false
            showBubble()
        }
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        hideKeyboard()
        try { panel?.let(wm::removeView) } catch (_: Throwable) {}
        panel = null
        showBubble()
    }

    private fun panelParams() = WindowManager.LayoutParams(
        panelWidthPx(), panelHeightPx(), overlayType(),
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.CENTER
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    private fun panelWidthPx(): Int {
        val w = resources.displayMetrics.widthPixels
        val desired = (w * widthPct / 100f).roundToInt()
        val min = if (w >= dp(900)) dp(680) else dp(320)
        return desired.coerceIn(min.coerceAtMost(w - dp(10)), w - dp(8))
    }

    private fun panelHeightPx(): Int {
        val h = resources.displayMetrics.heightPixels
        val desired = (h * heightPct / 100f).roundToInt()
        val min = if (h >= dp(700)) dp(560) else dp(420)
        return desired.coerceIn(min.coerceAtMost(h - dp(12)), h - dp(8))
    }

    private fun buildPanel(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.rgb(5, 34, 72), dp(22f), Color.rgb(20, 190, 255), dp(2))
            elevation = dp(14f).toFloat()
            isClickable = true
        }

        outer.addView(buildHeader(), LinearLayout.LayoutParams(-1, dp(50)))
        outer.addView(buildSearchBar(), LinearLayout.LayoutParams(-1, dp(50)).apply {
            setMargins(0, dp(8), 0, dp(8))
        })
        outer.addView(buildContent(), LinearLayout.LayoutParams(-1, 0, 1f))
        outer.addView(buildControls(), LinearLayout.LayoutParams(-1, dp(60)).apply {
            setMargins(0, dp(8), 0, 0)
        })
        return outer
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "AI"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = circle(Color.rgb(0, 177, 239), dp(24f))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        })
        header.addView(TextView(this).apply {
            text = "  ROSTIK AI 31.1"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        })
        header.addView(Button(this).apply {
            text = "−"
            textSize = 24f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(20, 61, 105), dp(13f))
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(46))
            setOnClickListener { collapse() }
        })
        return header
    }

    private fun buildSearchBar(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.rgb(15, 59, 103), dp(12f))
            setPadding(dp(10), 0, dp(10), 0)
        }
        box.addView(TextView(this).apply {
            text = "⌕"
            textSize = 31f
            setTextColor(Color.rgb(80, 174, 239))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(42), -1)
        })
        val search = EditText(this).apply {
            hint = "Поиск бойца..."
            textSize = 16f
            setSingleLine(true)
            setText(brawlerSearch)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(137, 180, 219))
            background = null
            setPadding(0, 0, 0, 0)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        box.addView(search, LinearLayout.LayoutParams(0, -1, 1f))
        box.addView(TextView(this).apply {
            text = "${BrawlerCatalog.all.size} / ${BrawlerCatalog.all.size}"
            textSize = 11f
            setTextColor(Color.rgb(170, 215, 242))
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(dp(78), -1)
        })
        search.addTextChangedListener(simpleWatcher {
            brawlerSearch = it
            brawlerScrollY = 0
            refreshBrawlerGrid()
        })
        return box
    }

    private fun buildContent(): View {
        val compact = resources.displayMetrics.widthPixels < dp(700)
        val split = LinearLayout(this).apply {
            orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(4, 42, 78), dp(14f), Color.rgb(20, 108, 170), dp(1))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }

        brawlerCountView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), 0, 0, dp(7))
        }
        left.addView(brawlerCountView, LinearLayout.LayoutParams(-1, dp(27)))

        brawlerGridHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        brawlerGridScroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            addView(brawlerGridHost)
            setOnScrollChangeListener { _, _, sy, _, _ -> brawlerScrollY = sy }
        }
        left.addView(brawlerGridScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        refreshBrawlerGrid()

        if (compact) {
            right.addView(buildQueueCard(), LinearLayout.LayoutParams(-1, dp(220)))
            right.addView(buildEditorCard(), LinearLayout.LayoutParams(-1, dp(190)).apply {
                setMargins(0, dp(8), 0, 0)
            })
            right.addView(buildSizeCard(), LinearLayout.LayoutParams(-1, dp(184)).apply {
                setMargins(0, dp(8), 0, 0)
            })

            val rightScroll = ScrollView(this).apply {
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                isVerticalScrollBarEnabled = true
                addView(right)
            }
            split.addView(left, LinearLayout.LayoutParams(-1, 0, 1.12f))
            split.addView(rightScroll, LinearLayout.LayoutParams(-1, 0, 1.00f).apply {
                setMargins(0, dp(8), 0, 0)
            })
        } else {
            right.addView(buildQueueCard(), LinearLayout.LayoutParams(-1, 0, 1.0f))
            right.addView(buildEditorCard(), LinearLayout.LayoutParams(-1, dp(190)).apply {
                setMargins(0, 0, 0, 0)
            })
            right.addView(buildSizeCard(), LinearLayout.LayoutParams(-1, dp(184)).apply {
                setMargins(0, dp(8), 0, 0)
            })
            split.addView(left, LinearLayout.LayoutParams(0, -1, 1.08f))
            split.addView(right, LinearLayout.LayoutParams(0, -1, 0.92f))
        }
        return split
    }

    private fun refreshBrawlerGrid() {
        val host = brawlerGridHost ?: return
        val scroll = brawlerGridScroll ?: return
        val filter = brawlerSearch.trim().lowercase()
        val entries = if (filter.isBlank()) {
            BrawlerCatalog.all
        } else {
            BrawlerCatalog.all.filter { entry ->
                entry.name.lowercase().contains(filter) ||
                    entry.id.lowercase().contains(filter) ||
                    entry.aliases.any { it.lowercase().contains(filter) }
            }
        }

        brawlerCountView?.text =
            "ВЫБОР БОЙЦОВ   ${entries.size}/${BrawlerCatalog.all.size}"

        host.removeAllViews()
        val columns = when {
            resources.displayMetrics.widthPixels < dp(700) -> 4
            resources.displayMetrics.widthPixels < dp(1000) -> 5
            else -> 6
        }

        entries.chunked(columns).forEach { rowEntries ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(columns) { index ->
                val entry = rowEntries.getOrNull(index)
                if (entry == null) {
                    row.addView(Space(this), LinearLayout.LayoutParams(0, dp(92), 1f).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    })
                } else {
                    row.addView(brawlerCell(entry), LinearLayout.LayoutParams(0, dp(92), 1f).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    })
                }
            }
            host.addView(row)
        }

        scroll.post { scroll.scrollTo(0, brawlerScrollY.coerceAtLeast(0)) }
    }

    private fun buildQueueCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            background = rounded(Color.rgb(5, 45, 84), dp(14f), Color.rgb(19, 116, 180), dp(1))
        }
        card.addView(TextView(this).apply {
            text = "🏆  ОЧЕРЕДЬ  •  КУБКИ   ${selected.size}"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(2), 0, 0, dp(6))
        }, LinearLayout.LayoutParams(-1, dp(29)))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (selected.isEmpty()) {
            rows.addView(TextView(this).apply {
                text = "Нажмите на бойца слева — он появится здесь"
                textSize = 11f
                setTextColor(Color.rgb(143, 181, 214))
                setPadding(dp(5), dp(14), dp(5), 0)
            })
        } else {
            selected.forEachIndexed { i, id -> rows.addView(queueRow(id, i + 1)) }
        }
        scroll.addView(rows)
        scroll.setOnScrollChangeListener { _, _, sy, _, _ -> queueScrollY = sy }
        scroll.post { scroll.scrollTo(0, queueScrollY.coerceAtLeast(0)) }
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return card
    }

    private fun queueRow(id: String, number: Int): View {
        val v = draft[id] ?: (0 to 0)
        val chosen = lastSelectedId == id
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(dp(5), dp(4), dp(4), dp(4))
            background = rounded(
                if (chosen) Color.rgb(16, 77, 119) else Color.rgb(10, 55, 99),
                dp(10f),
                if (chosen) Color.rgb(38, 205, 255) else Color.rgb(22, 101, 158),
                if (chosen) 2 else 1
            )
            layoutParams = LinearLayout.LayoutParams(-1, dp(58)).apply { setMargins(0, dp(2), 0, dp(2)) }
        }
        row.addView(TextView(this).apply {
            text = "$number"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(24), -1)
        })
        row.addView(avatarView(id, BrawlerCatalog.name(id), dp(46), dp(46)), LinearLayout.LayoutParams(dp(46), dp(46)).apply { setMargins(dp(3), 0, dp(7), 0) })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            addView(TextView(this@OverlayService).apply {
                text = BrawlerCatalog.name(id)
                textSize = 11f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(this@OverlayService).apply {
                text = "🏆 ${v.first}  →  ${v.second}"
                textSize = 9f
                setTextColor(Color.rgb(72, 210, 255))
            })
        })
        row.addView(Button(this).apply {
            text = "×"
            textSize = 20f
            setTextColor(Color.WHITE)
            background = circle(Color.rgb(231, 61, 103), dp(18f))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener {
                selected.remove(id)
                draft.remove(id)
                DiscordLogger.info(DiscordLogger.Category.UI, "Боец удалён из очереди", BrawlerCatalog.name(id), mapOf("queueSize" to selected.size.toString()))
                if (lastSelectedId == id) lastSelectedId = selected.lastOrNull()
                editorField = null
                saveDraft()
                rebuildPanel()
            }
        })
        row.setOnClickListener {
            lastSelectedId = id
            editorField = null
            DiscordLogger.info(DiscordLogger.Category.UI, "Открыт редактор бойца", BrawlerCatalog.name(id))
            rebuildPanel()
        }
        return row
    }

    private fun buildEditorCard(): View {
        val id = lastSelectedId ?: selected.firstOrNull()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            background = rounded(Color.rgb(5, 45, 84), dp(14f), Color.rgb(19, 116, 180), dp(1))
        }
        if (id == null) {
            card.addView(TextView(this).apply {
                text = "🏆  КУБКИ\n\nВыберите бойца слева"
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -1))
            return card
        }

        val v = draft[id] ?: (0 to 0)
        val title = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        title.addView(
            avatarView(id, BrawlerCatalog.name(id), dp(48), dp(48)),
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(0, 0, dp(8), 0) }
        )
        title.addView(TextView(this).apply {
            text = BrawlerCatalog.name(id)
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        })
        title.addView(Button(this).apply {
            text = "×"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(20, 85, 130), dp(9f))
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            setOnClickListener {
                lastSelectedId = null
                editorField = null
                hideKeyboard()
                rebuildPanel()
            }
        })
        card.addView(title, LinearLayout.LayoutParams(-1, dp(50)))

        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fields.addView(
            trophyField("ТЕКУЩИЕ КУБКИ", v.first, EditorField.CURRENT, id),
            LinearLayout.LayoutParams(0, dp(74), 1f)
        )
        fields.addView(TextView(this).apply {
            text = "→"
            textSize = 20f
            setTextColor(Color.rgb(85, 214, 255))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(74))
        })
        fields.addView(
            trophyField("ЦЕЛЕВЫЕ КУБКИ", v.second, EditorField.TARGET, id),
            LinearLayout.LayoutParams(0, dp(74), 1f)
        )
        card.addView(fields, LinearLayout.LayoutParams(-1, dp(78)).apply {
            setMargins(0, dp(4), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = "Нажмите поле → напишите число с клавиатуры → Готово"
            textSize = 9f
            setTextColor(Color.rgb(125, 181, 215))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        return card
    }

    private fun trophyField(label: String, value: Int, field: EditorField, id: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), 0, dp(3), 0)
        }
        box.addView(TextView(this).apply {
            text = label
            textSize = 8f
            setTextColor(Color.rgb(103, 215, 239))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(22)))

        val input = EditText(this).apply {
            setText(value.toString())
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(125, 181, 215))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rounded(
                if (editorField == field) Color.rgb(20, 89, 133) else Color.rgb(19, 70, 119),
                dp(9f),
                if (editorField == field) Color.rgb(38, 210, 255) else Color.rgb(30, 108, 165),
                1
            )
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setSelectAllOnFocus(false)
            setPadding(dp(4), 0, dp(4), 0)
        }

        var updating = false
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (updating) return
                val n = s?.toString()?.filter(Char::isDigit)?.toIntOrNull()?.coerceIn(0, 10000) ?: 0
                val old = draft[id] ?: (0 to 0)
                draft[id] = if (field == EditorField.CURRENT) n to old.second else old.first to n
                saveDraft()
            }
            override fun afterTextChanged(s: Editable?) {
                val digits = s?.toString()?.filter(Char::isDigit) ?: ""
                if (digits.length > 5 || (digits.toIntOrNull() ?: 0) > 10000) {
                    val n = (digits.toIntOrNull() ?: 10000).coerceIn(0, 10000)
                    updating = true
                    input.setText(n.toString())
                    input.setSelection(input.text.length)
                    updating = false
                }
            }
        })
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                lastSelectedId = id
                editorField = field
                input.post {
                    input.requestFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            ) {
                saveFocusedTrophy(id, field, input)
                true
            } else false
        }
        input.setOnClickListener {
            lastSelectedId = id
            editorField = field
            input.post {
                input.requestFocus()
                input.setSelection(input.text.length)
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        box.addView(input, LinearLayout.LayoutParams(-1, dp(43)))
        return box
    }

    private fun saveFocusedTrophy(id: String, field: EditorField, input: EditText) {
        val n = input.text.toString().filter(Char::isDigit).toIntOrNull()?.coerceIn(0, 10000) ?: 0
        val old = draft[id] ?: (0 to 0)
        draft[id] = if (field == EditorField.CURRENT) n to old.second else old.first to n
        saveDraft()
        editorField = null
        input.clearFocus()
        hideKeyboard()
        DiscordLogger.info(
            DiscordLogger.Category.UI,
            "Кубки сохранены",
            "${BrawlerCatalog.name(id)} ${if (field == EditorField.CURRENT) "current" else "target"}=$n"
        )
        rebuildPanel()
    }

    private fun buildSizeCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(6))
            background = rounded(Color.rgb(5, 45, 84), dp(14f), Color.rgb(19, 116, 180), dp(1))
        }
        card.addView(TextView(this).apply {
            text = "↗  РАЗМЕР ПАНЕЛИ"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, dp(25)))
        card.addView(sizeSlider("ШИРИНА", widthPct, 55, 96) { n ->
            widthPct = n.coerceIn(55, 96)
            saveSize()
            updatePanelSize()
        }, LinearLayout.LayoutParams(-1, dp(72)))
        card.addView(sizeSlider("ВЫСОТА", heightPct, 55, 96) { n ->
            heightPct = n.coerceIn(55, 96)
            saveSize()
            updatePanelSize()
        }, LinearLayout.LayoutParams(-1, dp(72)))
        return card
    }

    private fun sizeSlider(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(1), 0, dp(1))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = label
            textSize = 10f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, dp(24), 1f)
        })
        val percent = TextView(this).apply {
            text = "${value.coerceIn(min, max)}%"
            textSize = 11f
            setTextColor(Color.rgb(73, 215, 255))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(24))
        }
        top.addView(percent)
        row.addView(top)

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(Button(this).apply {
            text = "−"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = circle(Color.rgb(20, 91, 138), dp(18f))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener {
                val current = if (label == "ШИРИНА") widthPct else heightPct
                val n = (current - 1).coerceIn(min, max)
                percent.text = "$n%"
                onChange(n)
                DiscordLogger.info(DiscordLogger.Category.UI, "Размер панели изменён", "$label=$n%")
            }
        })

        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = (value.coerceIn(min, max) - min)
            splitTrack = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val n = (min + p).coerceIn(min, max)
                    percent.text = "$n%"
                    if (fromUser) onChange(n)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        controls.addView(seek, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
            setMargins(dp(5), 0, dp(5), 0)
        })
        controls.addView(Button(this).apply {
            text = "+"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = circle(Color.rgb(20, 91, 138), dp(18f))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener {
                val current = if (label == "ШИРИНА") widthPct else heightPct
                val n = (current + 1).coerceIn(min, max)
                percent.text = "$n%"
                onChange(n)
                DiscordLogger.info(DiscordLogger.Category.UI, "Размер панели изменён", "$label=$n%")
            }
        })
        row.addView(controls, LinearLayout.LayoutParams(-1, dp(40)))
        return row
    }

    private fun buildControls(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(actionButton("▶  СТАРТ", Color.rgb(0, 205, 120)) { startBot() }, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(0, 0, dp(4), 0) })
        row.addView(actionButton("Ⅱ  ПАУЗА", Color.rgb(247, 173, 26)) {
            val status = BotRuntime.supervisor.status()
            if (status.contains("PAUSED")) {
                BotRuntime.supervisor.resume()
                toast("Бот продолжен")
            } else {
                BotRuntime.supervisor.pause()
                toast("Бот на паузе")
            }
        }, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        row.addView(actionButton("■  СТОП", Color.rgb(246, 61, 104)) {
            BotRuntime.supervisor.stop()
            DiscordLogger.info(DiscordLogger.Category.BOT, "STOP из overlay", "Пользователь остановил бота")
            toast("Бот остановлен")
            stopSelf()
        }, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(4), 0, 0, 0) })
        return row
    }

    private fun actionButton(text: String, color: Int, click: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        setTypeface(null, Typeface.BOLD)
        background = rounded(color, dp(12f))
        isAllCaps = false
        isClickable = true
        isFocusable = false
        setOnClickListener { click() }
    }

    private fun updatePanelSize() {
        if (!expanded) return
        val view = panel ?: return
        try {
            val params = panelParams()
            wm.updateViewLayout(view, params)
            view.requestLayout()
        } catch (t: Throwable) {
            DiscordLogger.error(DiscordLogger.Category.UI, "Не удалось изменить размер панели", t)
        }
    }

    private fun refreshQueueOnly() { rebuildPanel() }

    private fun rebuildPanel() {
        if (!expanded) return
        try { panel?.let(wm::removeView) } catch (_: Throwable) {}
        panel = buildPanel()
        try { wm.addView(panel, panelParams()) }
        catch (_: Throwable) {
            panel = null
            expanded = false
            showBubble()
        }
    }

    private fun startBot() {
        if (selected.isEmpty()) {
            toast("Сначала выбери хотя бы одного бойца")
            return
        }
        if (BotAccessibilityService.instance == null) {
            toast("Включи Accessibility в настройках Android")
            return
        }
        if (!BotRuntime.captureActive) {
            toast("Сначала включи захват экрана")
            return
        }
        val invalid = selected.firstOrNull { id ->
            val v = draft[id] ?: (0 to 0)
            v.first !in 0..10000 || v.second !in 0..10000 || v.second <= v.first
        }
        if (invalid != null) {
            toast("Проверь кубки: 0–10000, цель должна быть выше текущих")
            lastSelectedId = invalid
            editorField = null
            rebuildPanel()
            return
        }
        val items = selected.map { id ->
            val v = draft[id] ?: (0 to 0)
            QueueItem(id, v.first, v.second)
        }.filter { !it.reached }
        if (items.isEmpty()) {
            toast("Все выбранные цели уже достигнуты")
            return
        }
        BotRuntime.queue.replace(items)
        saveDraft()
        BotRuntime.supervisor.start()
        DiscordLogger.info(DiscordLogger.Category.BOT, "START из overlay", BotRuntime.supervisor.status(), mapOf("queue" to items.joinToString(",") { "${it.brawlerId}:${it.currentCups}->${it.targetCups}" }))
        toast("Бот запущен")
    }

    private fun loadDraft() {
        val sp = getSharedPreferences("v311", MODE_PRIVATE)
        sp.getString("queue", "")?.takeIf { it.isNotBlank() }?.split(';')?.forEach { item ->
            val p = item.split(':')
            if (p.size == 3) {
                val c = p[1].toIntOrNull()
                val t = p[2].toIntOrNull()
                if (c != null && t != null && BrawlerCatalog.all.any { it.id == p[0] }) {
                    selected.add(p[0])
                    draft[p[0]] = c to t
                }
            }
        }
        lastSelectedId = selected.firstOrNull()
    }

    private fun saveDraft() {
        getSharedPreferences("v311", MODE_PRIVATE).edit()
            .putString("queue", selected.joinToString(";") { id ->
                val v = draft[id] ?: (0 to 0)
                "$id:${v.first}:${v.second}"
            }).apply()
    }

    private fun loadSize() {
        val sp = getSharedPreferences("v311", MODE_PRIVATE)
        widthPct = sp.getInt("width_pct", 78).coerceIn(55, 96)
        heightPct = sp.getInt("height_pct", 86).coerceIn(55, 96)
    }

    private fun saveSize() {
        getSharedPreferences("v311", MODE_PRIVATE).edit()
            .putInt("width_pct", widthPct)
            .putInt("height_pct", heightPct)
            .apply()
    }

    private fun loadAvatarMap() {
        try {
            assets.open("brawler_avatar_map.properties").use { avatarMap.load(it) }
            val local = avatarMap.stringPropertyNames().count { !(avatarMap.getProperty(it) ?: "").startsWith("REMOTE|") }
            val remote = avatarMap.stringPropertyNames().count { (avatarMap.getProperty(it) ?: "").startsWith("REMOTE|") }
            DiscordLogger.info(DiscordLogger.Category.UI, "Аватары загружены", "avatarMap=${avatarMap.size}", mapOf("local" to local.toString(), "remote" to remote.toString()))
        } catch (t: Throwable) {
            avatarMap.clear()
            DiscordLogger.error(DiscordLogger.Category.UI, "Не удалось загрузить карту аватаров", t)
        }
    }

    private fun avatarSpec(id: String): String? = avatarMap.getProperty(id)

    private fun loadAvatar(id: String): Bitmap? {
        synchronized(avatarCache) {
            avatarCache.get(id)?.let { return it }
        }
        val spec = avatarSpec(id)
        if (spec?.startsWith("REMOTE|") == true) {
            val cached = File(avatarCacheDir, "$id.png")
            if (cached.exists() && cached.length() > 0L) {
                try {
                    BitmapFactory.decodeFile(cached.absolutePath)?.let { bmp ->
                        synchronized(avatarCache) { avatarCache.put(id, bmp) }
                        return bmp
                    }
                } catch (_: Throwable) {}
            }
            ensureRemoteAvatar(id, spec.removePrefix("REMOTE|"))
            return try {
                assets.open("brawler_icons/$id.png").use { BitmapFactory.decodeStream(it) }
            } catch (_: Throwable) { null }
        }
        val filename = spec ?: "$id.png"
        val paths = listOf("apk_port/brawler_icons/$filename", "brawler_icons/$filename")
        for (path in paths) {
            try {
                assets.open(path).use { stream ->
                    BitmapFactory.decodeStream(stream)?.let { bmp ->
                        synchronized(avatarCache) { avatarCache.put(id, bmp) }
                        return bmp
                    }
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun preloadAllAvatars() {
        avatarExecutor.execute {
            var loaded = 0
            BrawlerCatalog.all.forEach { entry ->
                try {
                    if (loadAvatar(entry.id) != null) loaded++
                } catch (_: Throwable) {}
            }
            DiscordLogger.info(DiscordLogger.Category.UI, "Предзагрузка аватаров завершена", "loaded=$loaded/${BrawlerCatalog.all.size}")
            mainHandler.post { if (expanded) rebuildPanel() }
        }
    }

    private fun avatarView(id: String, name: String, width: Int, height: Int): View {
        val frame = FrameLayout(this).apply {
            contentDescription = name
            isClickable = false
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = rounded(Color.rgb(4, 34, 63), dp(8f))
            contentDescription = name
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        val fallback = TextView(this).apply {
            text = name.take(3)
            textSize = 10f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = circle(avatarColor(id), dp(22f))
            visibility = View.GONE
        }
        frame.addView(fallback, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
        val bmp = loadAvatar(id)
        if (bmp != null) {
            image.setImageBitmap(bmp)
            fallback.visibility = View.GONE
        } else {
            image.setImageDrawable(null)
            fallback.visibility = View.VISIBLE
        }
        // Do not assign FrameLayout.LayoutParams to the returned view: the caller
        // may add this frame to a LinearLayout. A wrong parent LayoutParams type
        // can crash the overlay during layout and make all avatars appear missing.
        return frame
    }

    private fun preloadMissingRemoteAvatars() {
        listOf("cosmo", "vince").forEach { id ->
            val spec = avatarSpec(id) ?: return@forEach
            if (spec.startsWith("REMOTE|")) ensureRemoteAvatar(id, spec.removePrefix("REMOTE|"))
        }
    }

    private fun ensureRemoteAvatar(id: String, url: String) {
        val target = File(avatarCacheDir, "$id.png")
        if (target.exists() && target.length() > 0L) return
        if (!avatarDownloads.add(id)) return
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 12000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "RostikAI/31.1")
                    instanceFollowRedirects = true
                }
                connection.connect()
                if (connection.responseCode in 200..299) {
                    val temp = File(avatarCacheDir, "$id.tmp")
                    connection.inputStream.use { input -> FileOutputStream(temp).use { output -> input.copyTo(output) } }
                    if (temp.length() > 0L) temp.renameTo(target)
                }
            } catch (_: Throwable) {
            } finally {
                connection?.disconnect()
                avatarDownloads.remove(id)
            }
            if (target.exists() && target.length() > 0L) {
                DiscordLogger.info(DiscordLogger.Category.UI, "Удалённый аватар загружен", id)
                mainHandler.post { if (expanded) rebuildPanel() }
            } else {
                DiscordLogger.error(DiscordLogger.Category.UI, "Удалённый аватар недоступен", message = id, fields = mapOf("url" to url.take(180)))
                mainHandler.post { if (expanded) rebuildPanel() }
            }
        }.start()
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(panel?.windowToken, 0)
        } catch (_: Throwable) {}
    }

    private fun simpleWatcher(after: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, afterCount: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { after(s?.toString() ?: "") }
        override fun afterTextChanged(s: Editable?) = Unit
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun rounded(color: Int, radius: Int, strokeColor: Int = Color.TRANSPARENT, strokeWidth: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeWidth > 0) setStroke(dp(strokeWidth), strokeColor)
        }

    private fun circle(color: Int, radius: Int) =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun avatarColor(id: String): Int {
        val palette = intArrayOf(
            Color.rgb(25, 118, 210), Color.rgb(156, 39, 176), Color.rgb(0, 150, 136),
            Color.rgb(239, 108, 0), Color.rgb(63, 81, 181), Color.rgb(0, 121, 107)
        )
        return palette[(id.hashCode() and Int.MAX_VALUE) % palette.size]
    }

}
