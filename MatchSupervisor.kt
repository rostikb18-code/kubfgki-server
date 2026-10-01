package com.rostik.touchbot

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Single owner of the navigation state machine.
 *
 * Critical actions are always perception -> tap -> verification. If the current
 * screen is not recognized with enough confidence, the supervisor waits instead
 * of tapping a guessed coordinate.
 */
class MatchSupervisor(private val queue: QueueEngine, private val assets: android.content.res.AssetManager): AutoCloseable {
    private val vision = MatchVision()
    private val ocr = OcrEngine()
    private val cupTracker = CupTracker()
    private val trophyReader = TrophyReader()
    private val navigation = NavigationVision()
    private val references = ReferenceMatcher(assets)
    private val selector = BrawlerSelector(assets)
    private val mapPlanner = MapPlanner(assets)
    private val controller = ReactiveBrawlBallController(mapPlanner)
    private val reward = RewardHandler()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frameSignal = Channel<Unit>(Channel.CONFLATED)
    private val latestFrame = AtomicReference<Bitmap?>(null)
    private val latestScene = AtomicReference<MatchScene?>(null)
    private val latestOcr = AtomicReference<List<OcrLine>>(emptyList())
    private val latestOcrAt = AtomicLong(0L)

    @Volatile private var state = RunState.IDLE
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var stateBeforePause = RunState.IDLE

    private var lastOcrAt = 0L
    private var stateSince = 0L
    private var actionBusyUntil = 0L
    private var workerStarted = false
    private var brawlerAttempts = 0
    private var brawlerScrolls = 0
    private var brawlerScrollDirection = 1
    private var lastBrawlerGridFingerprint: Long? = null
    private var unchangedGridCount = 0
    private var brawlerFingerprints = linkedSetOf<Long>()
    private var brawlerReverseUsed = false
    private var cupMismatchSince = 0L
    private var loadingEvidence = 0
    private var lastLoadingEvidenceAt = 0L
    private var matchStartedAt = 0L
    private var matchStartCups: Int? = null
    private var pendingDelta: Int? = null
    private var deltaConfirmations = 0
    private var matchResultRecorded = false
    private var resultSince = 0L
    private var rewardSince = 0L
    private var lastOutcome: MatchOutcome? = null
    private var advanceAfterCupVerification = false
    private var lastInputAt = 0L
    private var heartbeatFlip = false
    private var lastSceneAt = 0L
    private var activeMapName: String? = null
    private var processedFrames = 0L
    private var processingStartedAt = 0L
    private var lastProcessMs = 0L


    fun start() {
        if (running) return
        running = true; paused = false
        transition(RunState.PREPARE)
        brawlerAttempts = 0; brawlerScrolls = 0; brawlerScrollDirection = 1
        lastBrawlerGridFingerprint = null; unchangedGridCount = 0
        brawlerFingerprints.clear(); brawlerReverseUsed = false
        cupMismatchSince = 0L; matchStartCups = null
        loadingEvidence = 0; lastLoadingEvidenceAt = 0L
        pendingDelta = null; deltaConfirmations = 0
        matchResultRecorded = false; resultSince = 0L; rewardSince = 0L; advanceAfterCupVerification = false
        lastInputAt = 0L; heartbeatFlip = false; lastSceneAt = 0L; activeMapName = null; controller.setMap(null)
        processedFrames = 0L; processingStartedAt = 0L; lastProcessMs = 0L
        cupTracker.reset(); reward.reset()
        StatsStore.beginRun()
        DiscordLogger.info(DiscordLogger.Category.BOT, "Бот запущен", status())
        startWorkerIfNeeded()
    }

    fun pause() {
        if (running && !paused) {
            stateBeforePause = state
            paused = true
            transition(RunState.PAUSED)
            DiscordLogger.info(DiscordLogger.Category.BOT, "Бот на паузе", status())
        }
    }

    fun resume() {
        if (running && paused) {
            paused = false
            transition(stateBeforePause)
            DiscordLogger.info(DiscordLogger.Category.BOT, "Бот продолжен", status())
        }
    }

    fun stop() {
        running = false; paused = false; transition(RunState.IDLE)
        StatsStore.endRun()
        DiscordLogger.info(DiscordLogger.Category.BOT, "Бот остановлен", status())
        val pending = latestFrame.getAndSet(null)
        if (pending != null) safeRecycle(pending)
        latestScene.set(null); latestOcr.set(emptyList()); latestOcrAt.set(0L)
        resultSince = 0L
        actionBusyUntil = 0L
        controller.setMap(null)
    }

    fun submitLatestFrame(bitmap: Bitmap) {
        if (!running) { safeRecycle(bitmap); return }
        val previous = latestFrame.getAndSet(bitmap)
        if (previous != null && previous !== bitmap) safeRecycle(previous)
        frameSignal.trySend(Unit)
    }

    private fun startWorkerIfNeeded() {
        if (workerStarted) return
        workerStarted = true
        scope.launch {
            for (ignored in frameSignal) {
                val bitmap = latestFrame.getAndSet(null) ?: continue
                if (!running) { safeRecycle(bitmap); continue }
                try { processFrame(bitmap) }
                catch (t: Throwable) {
                    Log.w("Supervisor", "frame failed", t)
                    DiscordLogger.error(DiscordLogger.Category.ERROR, "Ошибка обработки кадра", t, fields = mapOf("state" to state.name))
                }
                finally { safeRecycle(bitmap) }
            }
        }
    }

    private suspend fun processFrame(bitmap: Bitmap) {
        if (paused) return
        val now = System.currentTimeMillis()
        val processStart = now
        val scene = vision.detect(bitmap, now)
        latestScene.set(scene)
        lastSceneAt = now

        val needOcr = when (state) {
            RunState.PLAYING -> now - lastOcrAt >= 900L
            RunState.RESULT, RunState.REWARD, RunState.PLAY_AGAIN -> now - lastOcrAt >= 160L
            else -> now - lastOcrAt >= 260L
        }
        if (needOcr) {
            lastOcrAt = now
            val lines = try { ocr.read(bitmap) } catch (t: Throwable) {
                Log.w("OCR", "failed", t)
                DiscordLogger.error(DiscordLogger.Category.VISION, "Ошибка OCR", t, fields = mapOf("state" to state.name))
                emptyList()
            }
            latestOcr.set(lines); latestOcrAt.set(now)
        }
        step(bitmap, scene, now)
        processedFrames++
        lastProcessMs = System.currentTimeMillis() - processStart
        if (processingStartedAt == 0L) processingStartedAt = now
    }

    private fun step(bitmap: Bitmap, scene: MatchScene, now: Long) {
        val obs = navigation.observe(freshOcr(now), scene)
        when (state) {
            RunState.PAUSED, RunState.IDLE, RunState.DONE, RunState.ERROR -> Unit

            RunState.PREPARE -> {
                if (queue.current() == null) transition(RunState.DONE) else transition(RunState.SELECT_BRAWLER)
            }

            RunState.SELECT_BRAWLER -> selectBrawler(bitmap, obs, now)
            RunState.VERIFY_BRAWLER -> verifyBrawler(obs, now)
            RunState.VERIFY_CUPS -> verifyCups(now)
            RunState.VERIFY_RESULT_CUPS -> verifyResultCups(obs, now)
            RunState.SELECT_MODE -> selectMode(bitmap, obs, now)
            RunState.SELECT_MAP -> selectMap(obs, now)
            RunState.PRESS_PLAY -> pressPlay(bitmap, obs, now)
            RunState.LOADING -> loading(scene, obs, now)
            RunState.PLAYING -> playing(scene, obs, now)
            RunState.RESULT -> result(obs, now)
            RunState.REWARD -> rewardStep(obs, now)
            RunState.PLAY_AGAIN -> playAgain(bitmap, obs, now)
        }

        if (state != RunState.PLAYING && state != RunState.IDLE && state != RunState.DONE && now - stateSince > stateTimeout(state)) {
            onTimeout(now)
        }
    }

    private fun selectBrawler(bitmap: Bitmap, obs: NavigationVision.Observation, now: Long) {
        val q = queue.current() ?: run { transition(RunState.DONE); return }
        val service = BotAccessibilityService.instance ?: return
        val lines = freshOcr(now)

        when (obs.type) {
            ScreenType.HOME -> {
                val target = navigation.findButton(lines, "brawlers", "бойцы", "fighters")
                if (target != null && now >= actionBusyUntil) {
                    if (service.tap(target)) {
                        DiscordLogger.info(DiscordLogger.Category.MATCH, "Открыт экран бойцов", "tap=brawlers", mapOf("confidence" to obs.confidence.toString()))
                    }
                    actionBusyUntil = now + 1000L
                    brawlerAttempts++
                    return
                }
                // On the real home screen the current brawler is the large character
                // in the center.  The old fallback tapped the left-side BRAWLERS icon,
                // which is not the user's requested flow. Authorize the center tap only
                // when the complete home reference matches strongly.
                if (now >= actionBusyUntil && references.score(bitmap, "home") >= .86f) {
                    if (service.tap(Point(.50f, .39f))) {
                        DiscordLogger.info(DiscordLogger.Category.MATCH, "Открыт профиль текущего бойца", "home reference fallback")
                        actionBusyUntil = now + 1100L
                        brawlerAttempts++
                    }
                }
            }
            ScreenType.BRAWLER_LIST -> {
                val target = selector.find(bitmap, lines, q.brawlerId)
                if (target != null && now >= actionBusyUntil && target.confidence >= .84f) {
                    service.tap(target.point)
                    actionBusyUntil = now + 1000L
                    brawlerAttempts++
                    transition(RunState.VERIFY_BRAWLER)
                    return
                }

                if (now >= actionBusyUntil && now - stateSince > 1200L && brawlerScrolls < 24 && selector.shouldScroll(now)) {
                    val before = selector.gridFingerprint(bitmap)
                    if (before != 0L) {
                        if (brawlerFingerprints.contains(before)) {
                            unchangedGridCount++
                        } else {
                            brawlerFingerprints.add(before)
                            unchangedGridCount = 0
                        }
                    }
                    if (unchangedGridCount >= 2 && !brawlerReverseUsed) {
                        // We reached the horizontal edge. Reverse once so the scan
                        // covers the complete carousel without any vertical scrolling.
                        brawlerScrollDirection *= -1
                        brawlerReverseUsed = true
                        unchangedGridCount = 0
                    }
                    lastBrawlerGridFingerprint = before
                    val (from, to) = selector.scrollPoint(brawlerScrollDirection)
                    if (service.swipe(from, to, 520L)) {
                        DiscordLogger.info(DiscordLogger.Category.MATCH, "Прокрутка списка бойцов", "scroll=$brawlerScrolls direction=$brawlerScrollDirection")
                        selector.markScroll(now)
                        actionBusyUntil = now + 1250L
                        brawlerScrolls++
                    }
                }
            }
            ScreenType.BRAWLER_PROFILE -> {
                val button = navigation.findButton(lines, "select", "выбрать", "choose")
                if (button != null && now >= actionBusyUntil) {
                    if (service.tap(button)) {
                        DiscordLogger.info(DiscordLogger.Category.MATCH, "Подтверждён выбор бойца", BrawlerCatalog.name(q.brawlerId))
                    }
                    actionBusyUntil = now + 900L
                    transition(RunState.VERIFY_BRAWLER)
                }
            }
            else -> Unit
        }

        if (brawlerScrolls >= 30 && now - stateSince > 18_000L) {
            failSafe("Не удалось найти бойца ${BrawlerCatalog.name(q.brawlerId)} по аватарке/тексту")
        }
    }

    private fun verifyBrawler(obs: NavigationVision.Observation, now: Long) {
        val q = queue.current() ?: run { transition(RunState.DONE); return }
        val lines = freshOcr(now)
        val nameSeen = lines.any { lineContainsBrawler(it.text, q.brawlerId) }
        val accepted = when (obs.type) {
            ScreenType.BRAWLER_PROFILE -> nameSeen || obs.confidence >= .90f
            ScreenType.BRAWL_BALL_LOBBY, ScreenType.HOME -> nameSeen
            else -> false
        }
        if (accepted && now - stateSince >= 500L) {
            transition(RunState.VERIFY_CUPS)
            cupMismatchSince = 0L
        } else if (now - stateSince > 3200L && brawlerAttempts < 3) {
            transition(RunState.SELECT_BRAWLER)
        }
    }

    private fun verifyCups(now: Long) {
        val q = queue.current() ?: run { transition(RunState.DONE); return }
        val actual = trophyReader.currentBrawlerCups(freshOcr(now), q.currentCups) ?: return
        val configured = q.currentCups
        if (kotlin.math.abs(actual - configured) <= 8) {
            queue.updateCups(actual)
            matchStartCups = actual
            DiscordLogger.info(DiscordLogger.Category.MATCH, "Кубки подтверждены", "${BrawlerCatalog.name(q.brawlerId)}: $actual", mapOf("configured" to configured.toString()))
            transition(RunState.SELECT_MODE)
            return
        }
        if (cupMismatchSince == 0L) cupMismatchSince = now
        if (now - cupMismatchSince > 2500L) {
            failSafe("Кубки не совпали: введено $configured, на экране $actual. Бот остановлен, чтобы не ошибиться.")
        }
    }

    private fun selectMode(bitmap: Bitmap, obs: NavigationVision.Observation, now: Long) {
        val service = BotAccessibilityService.instance ?: return
        val lines = freshOcr(now)
        val lobbyRef = references.score(bitmap, "brawl_ball_lobby")
        if ((obs.type == ScreenType.BRAWL_BALL_LOBBY && obs.brawlBall) || lobbyRef >= .86f) {
            transition(RunState.PRESS_PLAY)
            return
        }
        if (obs.type == ScreenType.HOME) {
            val current = navigation.findCurrentMode(lines)
            if (current != null && now >= actionBusyUntil) {
                if (service.tap(current)) {
                    DiscordLogger.info(DiscordLogger.Category.MATCH, "Открыт выбор режима", "currentMode tap")
                    actionBusyUntil = now + 1200L
                }
                return
            }
            if (now >= actionBusyUntil && references.score(bitmap, "home") >= .86f) {
                // In the supplied HOME reference this point is the current-mode card.
                if (service.tap(Point(.60f, .91f))) {
                    DiscordLogger.info(DiscordLogger.Category.MATCH, "Открыт выбор режима", "home reference fallback")
                    actionBusyUntil = now + 1200L
                }
            }
            return
        }
        if (obs.type != ScreenType.MODE_SELECT) return
        val ball = navigation.findBrawlBall(lines)
        if (ball != null && now >= actionBusyUntil) {
            if (service.tap(ball)) {
                DiscordLogger.info(DiscordLogger.Category.MATCH, "Выбран Brawl Ball", "mode button", mapOf("confidence" to obs.confidence.toString()))
                actionBusyUntil = now + 1200L
                transition(RunState.SELECT_MAP)
            }
        } else if (now >= actionBusyUntil && references.score(bitmap, "mode_select") >= .86f) {
            if (service.tap(Point(.375f, .35f))) {
                DiscordLogger.info(DiscordLogger.Category.MATCH, "Выбран Brawl Ball", "mode_select reference fallback")
                actionBusyUntil = now + 1200L
                transition(RunState.SELECT_MAP)
            }
        }
    }

    private fun selectMap(obs: NavigationVision.Observation, now: Long) {
        if (obs.type != ScreenType.BRAWL_BALL_LOBBY || !obs.brawlBall) return
        val detected = BrawlBallMapCatalog.findByOcr(assets, freshOcr(now))
        if (detected != null) {
            activeMapName = detected.name
            controller.setMap(activeMapName)
            Log.i("MapPlanner", "Detected Brawl Ball map: ${detected.name}")
            DiscordLogger.info(DiscordLogger.Category.MATCH, "Карта Brawl Ball распознана", detected.name)
        }
        // Map selection is deliberately evidence-driven. If the lobby exposes
        // no map name, we do not blind-tap a coordinate; the controller uses its
        // generic free-space fallback for the active rotation.
        if (now - stateSince >= 650L) transition(RunState.PRESS_PLAY)
    }

    private fun pressPlay(bitmap: Bitmap, obs: NavigationVision.Observation, now: Long) {
        val service = BotAccessibilityService.instance ?: return
        val lobbyRef = references.score(bitmap, "brawl_ball_lobby")
        if ((obs.type != ScreenType.BRAWL_BALL_LOBBY || !obs.brawlBall) && lobbyRef < .86f) return
        val p = obs.play ?: navigation.findButton(freshOcr(now), "play", "играть", "ready", "готов")
        val visualPlay = p ?: UiButtonVision.findYellowAction(bitmap)
        if (visualPlay != null && now >= actionBusyUntil) {
            if (service.tap(visualPlay)) {
                DiscordLogger.info(DiscordLogger.Category.MATCH, "Нажата PLAY", "Brawl Ball lobby confirmed")
                actionBusyUntil = now + 1500L
                matchStartCups = queue.current()?.currentCups
                matchStartedAt = now
                matchResultRecorded = false
                pendingDelta = null; deltaConfirmations = 0
                loadingEvidence = 0; lastLoadingEvidenceAt = 0L
                transition(RunState.LOADING)
            }
        }
    }

    private fun loading(scene: MatchScene, obs: NavigationVision.Observation, now: Long) {
        val evidence = obs.type == ScreenType.MATCH && scene.leftJoystick != null && scene.rightJoystick != null && scene.hudConfidence >= .42f && scene.player != null
        if (evidence) {
            if (now - lastLoadingEvidenceAt >= 180L) loadingEvidence++
            lastLoadingEvidenceAt = now
        }
        if (loadingEvidence >= 3) {
            DiscordLogger.info(DiscordLogger.Category.MATCH, "Матч подтверждён", "joysticks + player + HUD", mapOf("evidence" to loadingEvidence.toString()))
            matchStartedAt = now
            resultSince = 0L
            transition(RunState.PLAYING)
        }
    }

    private fun playing(scene: MatchScene, obs: NavigationVision.Observation, now: Long) {
        if (obs.type == ScreenType.RESULT || obs.type == ScreenType.REWARD) {
            DiscordLogger.info(DiscordLogger.Category.MATCH, "Обнаружен конец матча", obs.type.name)
            transition(if (obs.type == ScreenType.REWARD) RunState.REWARD else RunState.RESULT)
            resultSince = now
            return
        }
        if (obs.type != ScreenType.MATCH) return

        val player = scene.player
        if (player == null) {
            // Respawn/effects can hide the player for several hundred ms. Do not
            // stop input during that gap: a short movement heartbeat prevents the
            // game from treating the client as completely idle.
            if (now - resultSince > 900L) resultSince = now
            heartbeatMovement(scene, now)
            return
        }
        resultSince = 0L
        val elapsed = now - matchStartedAt
        val frame = controller.next(scene, now, elapsed)
        if (now >= actionBusyUntil && scene.hudConfidence >= .28f) {
            val service = BotAccessibilityService.instance ?: return

            // Never guess a control location for a combat action. Movement may
            // use the tracked left stick; attack/super require their own
            // freshly detected HUD controls.
            val left = scene.leftJoystick
            val right = scene.rightJoystick

            val sent = when {
                frame.fire -> {
                    if (right == null || scene.attackConfidence < .55f) false
                    else service.dispatch(frame.copy(moveX = 0f, moveY = 0f), TouchLayout.MOVE, right, 55L)
                }
                frame.useSuper -> {
                    val superPoint = frame.superPoint
                    if (superPoint == null || scene.superConfidence < .55f) false
                    else service.dispatch(frame.copy(moveX = 0f, moveY = 0f, fire = false), TouchLayout.MOVE, TouchLayout.ATTACK, 55L)
                }
                left != null -> {
                    service.dispatch(frame.copy(fire = false, useSuper = false), left, TouchLayout.ATTACK, 105L)
                }
                else -> false
            }

            if (sent) {
                lastInputAt = now
                // Leave enough time for AccessibilityService to finish the
                // previous gesture. This removes the rapid-fire dispatch loop
                // that could produce dropped/laggy touches.
                actionBusyUntil = now + if (frame.fire || frame.useSuper) 90L else 105L
            } else if (now - lastInputAt >= 650L) {
                heartbeatMovement(scene, now)
            }
        } else if (now - lastInputAt >= 650L) {
            heartbeatMovement(scene, now)
        }
    }

    private fun heartbeatMovement(scene: MatchScene, now: Long) {
        if (now - lastInputAt < 650L) return
        val service = BotAccessibilityService.instance ?: return
        if (service.isGestureBusy() || now < actionBusyUntil) return
        val left = scene.leftJoystick ?: TouchLayout.MOVE
        heartbeatFlip = !heartbeatFlip
        val frame = ControlFrame(
            moveX = if (heartbeatFlip) .34f else -.28f,
            moveY = if (heartbeatFlip) -.10f else .12f,
            fire = false, useSuper = false
        )
        if (service.dispatch(frame, left, TouchLayout.ATTACK, 105L)) {
            lastInputAt = now
            actionBusyUntil = now + 115L
        }
    }


    private fun result(obs: NavigationVision.Observation, now: Long) {
        if (obs.type != ScreenType.RESULT && obs.type != ScreenType.REWARD) return
        val q = queue.current() ?: return
        val lines = freshOcr(now)
        val delta = trophyReader.resultDelta(lines)
        if (delta != null) {
            if (pendingDelta == delta) deltaConfirmations++ else { pendingDelta = delta; deltaConfirmations = 1 }
            if (deltaConfirmations >= 3 && !matchResultRecorded) {
                applyConfirmedDelta(delta)
                matchResultRecorded = true
                lastOutcome = ResultReader.read(lines) ?: when {
                    delta > 0 -> MatchOutcome.WIN
                    delta < 0 -> MatchOutcome.LOSS
                    else -> MatchOutcome.DRAW
                }
                StatsStore.recordMatch(delta, lastOutcome!!)
                DiscordLogger.info(DiscordLogger.Category.MATCH, "Результат матча подтверждён", lastOutcome!!.name, mapOf("delta" to delta.toString(), "cups" to q.currentCups.toString()))
            }
        }
        if (obs.type == ScreenType.REWARD) {
            if (matchResultRecorded) { transition(RunState.REWARD); rewardSince = now }
            return
        }
        if (matchResultRecorded && now - resultSince > 900L) transition(RunState.PLAY_AGAIN)
    }

    private fun rewardStep(obs: NavigationVision.Observation, now: Long) {
        val service = BotAccessibilityService.instance
        if (service != null && obs.rewardAction != null && now >= actionBusyUntil) {
            if (service.tap(obs.rewardAction.second)) {
                DiscordLogger.info(DiscordLogger.Category.MATCH, "Награда открыта/продолжена", "reward action")
            }
            actionBusyUntil = now + 700L; rewardSince = now; return
        }
        if (now - rewardSince > 1200L) transition(RunState.PLAY_AGAIN)
    }

    private fun playAgain(bitmap: Bitmap, obs: NavigationVision.Observation, now: Long) {
        val service = BotAccessibilityService.instance ?: return
        val q = queue.current() ?: run { transition(RunState.DONE); return }
        if (!matchResultRecorded) return
        if (q.reached) {
            val proceed = obs.proceed ?: navigation.findButton(freshOcr(now), "proceed", "continue", "продолжить", "exit", "выйти")
            if (proceed != null && now >= actionBusyUntil && service.tap(proceed)) {
                DiscordLogger.info(DiscordLogger.Category.MATCH, "Выход после достижения цели", "verify result cups")
                actionBusyUntil = now + 1100L
                advanceAfterCupVerification = true
                transition(RunState.VERIFY_RESULT_CUPS)
            }
            return
        }
        val again = obs.playAgain ?: navigation.findButton(freshOcr(now), "play again", "играть снова", "ещё раз")
        val visualAgain = again ?: UiButtonVision.findYellowAction(bitmap)
        if (visualAgain != null && now >= actionBusyUntil && service.tap(visualAgain)) {
            DiscordLogger.info(DiscordLogger.Category.MATCH, "Нажата PLAY AGAIN", BrawlerCatalog.name(q.brawlerId))
            actionBusyUntil = now + 1400L
            matchStartCups = q.currentCups
            matchStartedAt = now
            matchResultRecorded = false
            pendingDelta = null; deltaConfirmations = 0
            loadingEvidence = 0; lastLoadingEvidenceAt = 0L
            transition(RunState.LOADING)
        }
    }

    private fun verifyResultCups(obs: NavigationVision.Observation, now: Long) {
        val q = queue.current() ?: run { transition(RunState.DONE); return }
        val actual = trophyReader.currentBrawlerCups(freshOcr(now), q.currentCups) ?: return
        if (actual != q.currentCups) {
            if (now - stateSince > 3500L) failSafe("После результата кубки не подтверждены: ожидалось ${q.currentCups}, экран показывает $actual")
            return
        }
        if (advanceAfterCupVerification) {
            advanceAfterCupVerification = false
            if (queue.next()) { resetForNextBrawler(); transition(RunState.SELECT_BRAWLER) }
            else transition(RunState.DONE)
        } else transition(RunState.PLAY_AGAIN)
    }

    private fun applyConfirmedDelta(delta: Int) {
        val q = queue.current() ?: return
        val before = q.currentCups
        val after = before + delta
        if (after in 0..10000 && kotlin.math.abs(delta) <= 100) {
            queue.updateCups(after)
            DiscordLogger.info(DiscordLogger.Category.MATCH, "Кубки обновлены", "${BrawlerCatalog.name(q.brawlerId)}: $before -> $after", mapOf("delta" to delta.toString()))
        }
    }

    private fun resetForNextBrawler() {
        brawlerAttempts = 0; brawlerScrolls = 0; brawlerScrollDirection = 1; unchangedGridCount = 0
        lastBrawlerGridFingerprint = null; cupMismatchSince = 0L
        brawlerFingerprints.clear(); brawlerReverseUsed = false
        matchStartCups = null; pendingDelta = null; deltaConfirmations = 0
        loadingEvidence = 0; lastLoadingEvidenceAt = 0L
        matchResultRecorded = false; resultSince = 0L; rewardSince = 0L; advanceAfterCupVerification = false
        lastInputAt = 0L; heartbeatFlip = false; lastSceneAt = 0L; activeMapName = null; controller.setMap(null)
        processedFrames = 0L; processingStartedAt = 0L; lastProcessMs = 0L; reward.reset()
    }

    private fun onTimeout(now: Long) {
        when (state) {
            RunState.SELECT_BRAWLER -> failSafe("Не удалось подтвердить выбор бойца")
            RunState.VERIFY_BRAWLER -> if (brawlerAttempts < 3) transition(RunState.SELECT_BRAWLER) else failSafe("Не подтверждён выбранный боец")
            RunState.VERIFY_CUPS -> failSafe("Не удалось надёжно прочитать кубки")
            RunState.SELECT_MODE, RunState.SELECT_MAP, RunState.PRESS_PLAY -> failSafe("Не удалось подтвердить Brawl Ball / PLAY")
            RunState.LOADING -> failSafe("Матч не перешёл в игровой экран")
            RunState.PLAYING -> failSafe("Игровой экран пропал без подтверждённого результата")
            RunState.RESULT, RunState.REWARD, RunState.VERIFY_RESULT_CUPS, RunState.PLAY_AGAIN -> failSafe("Результат матча не подтверждён")
            else -> Unit
        }
    }

    private fun transition(next: RunState) {
        val previous = state
        state = next; stateSince = System.currentTimeMillis()
        Log.i("Supervisor", "state=$next")
        DiscordLogger.info(DiscordLogger.Category.MATCH, "Шаг: $next", status(), mapOf("previous" to previous.name))
    }

    private fun failSafe(reason: String) {
        Log.e("Supervisor", reason)
        DiscordLogger.error(DiscordLogger.Category.ERROR, "Supervisor fail-safe", message = reason, fields = mapOf("status" to status()))
        running = false; paused = false; state = RunState.ERROR
        StatsStore.endRun()
    }

    fun status(): String {
        val q = queue.current(); val s = latestScene.get()
        val p = s?.player?.point?.let { "P=${(it.x*100).toInt()}%,${(it.y*100).toInt()}%" } ?: "P=--"
        val b = s?.ball?.point?.let { "B=${(it.x*100).toInt()}%,${(it.y*100).toInt()}%" } ?: "B=--"
        val elapsed = (System.currentTimeMillis() - processingStartedAt).coerceAtLeast(1L)
        val fps = if (processingStartedAt == 0L) 0 else ((processedFrames * 1000L) / elapsed).coerceAtMost(120L)
        return "v31.1 | $state | ${q?.brawlerId ?: "-"} ${q?.currentCups ?: 0}/${q?.targetCups ?: 0} | $p $b | MAP=${activeMapName ?: "auto"} | ${fps}fps ${lastProcessMs}ms"
    }

    private fun freshOcr(now: Long): List<OcrLine> = if (now - latestOcrAt.get() <= 2200L) latestOcr.get() else emptyList()
    private fun lineContainsBrawler(text: String, id: String): Boolean {
        val v = text.lowercase().replace('ё','е').replace(Regex("[^a-zа-я0-9]"), "")
        return BrawlerCatalog.aliases(id).any { a -> val n=a.lowercase().replace('ё','е').replace(Regex("[^a-zа-я0-9]"), ""); n.length >= 3 && (v.contains(n) || n.contains(v) && v.length >= 4) }
    }
    private fun stateTimeout(s: RunState) = when (s) {
        RunState.SELECT_BRAWLER -> 30_000L; RunState.VERIFY_BRAWLER -> 4_000L; RunState.VERIFY_CUPS -> 4_000L
        RunState.SELECT_MODE, RunState.SELECT_MAP, RunState.PRESS_PLAY -> 8_000L; RunState.LOADING -> 12_000L
        RunState.PLAYING -> 900_000L; RunState.RESULT -> 8_000L; RunState.REWARD -> 7_000L; RunState.VERIFY_RESULT_CUPS -> 5_000L; RunState.PLAY_AGAIN -> 7_000L
        else -> 30_000L
    }
    private fun drainFrames() { val pending = latestFrame.getAndSet(null); if (pending != null) safeRecycle(pending) }
    private fun safeRecycle(b: Bitmap) { try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {} }
    override fun close() {
        running = false; paused = false; StatsStore.endRun()
        frameSignal.close(); drainFrames(); scope.cancel(); vision.close(); ocr.close()
    }
}
