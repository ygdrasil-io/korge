package korlibs.korge

import korlibs.datastructure.iterators.fastForEach
import korlibs.time.DateTime
import korlibs.time.TimeProvider
import korlibs.time.TimeSpan
import korlibs.time.milliseconds
import korlibs.logger.Logger
import korlibs.memory.*
import korlibs.graphics.log.AGPrint
import korlibs.event.*
import korlibs.korge.input.Input
import korlibs.korge.internal.DefaultViewport
import korlibs.korge.internal.KorgeInternal
import korlibs.korge.logger.configureLoggerFromProperties
import korlibs.korge.render.BatchBuilder2D
import korlibs.korge.resources.ResourcesRoot
import korlibs.korge.scene.EmptyScene
import korlibs.korge.scene.Module
import korlibs.korge.scene.Scene
import korlibs.korge.scene.SceneContainer
import korlibs.korge.stat.Stats
import korlibs.korge.view.Stage
import korlibs.korge.view.Views
import korlibs.render.CreateDefaultGameWindow
import korlibs.render.GameWindow
import korlibs.render.GameWindowCreationConfig
import korlibs.render.configure
import korlibs.image.bitmap.Bitmap
import korlibs.image.color.Colors
import korlibs.image.color.RGBA
import korlibs.image.format.ImageFormat
import korlibs.image.format.ImageFormats
import korlibs.image.format.RegisteredImageFormats
import korlibs.image.format.plus
import korlibs.image.format.readBitmap
import korlibs.inject.AsyncInjector
import korlibs.inject.AsyncInjectorContext
import korlibs.io.async.delay
import korlibs.io.async.launchImmediately
import korlibs.io.dynamic.*
import korlibs.io.file.std.localCurrentDirVfs
import korlibs.io.file.std.resourcesVfs
import korlibs.io.resources.Resources
import korlibs.math.geom.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.reflect.KClass

/**
 * Entry point for games written in Korge.
 * You have to call the [Korge] method by either providing some parameters, or a [Korge.Config] object.
 */
object Korge {
	val logger = Logger("Korge")
    val DEFAULT_GAME_ID = "korlibs.korge.unknown"
    val DEFAULT_WINDOW_SIZE: SizeInt get() = DefaultViewport.SIZE

    suspend operator fun invoke(config: Config) {
        //println("Korge started from Config")
        val module = config.module
        val windowSize = module.windowSize

        Korge(
            title = config.title ?: module.title,
            windowSize = config.windowSize ?: windowSize,
            virtualSize = config.virtualSize ?: module.virtualSize,
            bgcolor = config.bgcolor ?: module.bgcolor,
            quality = config.quality ?: module.quality,
            icon = null,
            iconPath = config.icon ?: module.icon,
            //iconDrawable = module.iconImage,
            imageFormats = ImageFormats(config.imageFormats + module.imageFormats),
            targetFps = module.targetFps,
            scaleAnchor = config.scaleAnchor ?: module.scaleAnchor,
            scaleMode = config.scaleMode ?: module.scaleMode,
            clipBorders = config.clipBorders ?: module.clipBorders,
            debug = config.debug,
            fullscreen = config.fullscreen ?: module.fullscreen,
            args = config.args,
            gameWindow = config.gameWindow,
            injector = config.injector,
            timeProvider = config.timeProvider,
            blocking = config.blocking,
            gameId = config.gameId,
            settingsFolder = config.settingsFolder,
            batchMaxQuads = config.batchMaxQuads,
            multithreaded = config.multithreaded,
            forceRenderEveryFrame = config.forceRenderEveryFrame,
            entry = {
                //println("Korge views prepared for Config")
                val injector = config.injector
                injector.mapInstance(Module::class, module)
                injector.mapInstance(Config::class, config)

                module.apply { injector.configure() }

                config.constructedViews(views)

                when {
                    config.main != null -> {
                        config.main?.invoke(stage)
                    }
                    config.sceneClass != null -> {
                        val sc = SceneContainer(views, name = "rootSceneContainer")
                        views.stage += sc
                        val scene = sc.changeTo(config.sceneClass, *config.sceneInjects.toTypedArray(), time = 0.milliseconds)
                        config.constructedScene(scene, views)
                        // Se we have the opportunity to execute deinitialization code at the scene level
                        views.onClose { sc.changeTo<EmptyScene>() }
                    }
                }
            }
        )
    }

    suspend operator fun invoke(
        title: String = "Korge",
        windowSize: SizeInt = DefaultViewport.SIZE,
        virtualSize: SizeInt = windowSize,
        icon: Bitmap? = null,
        iconPath: String? = null,
        //iconDrawable: SizedDrawable? = null,
        imageFormats: ImageFormat = ImageFormats(),
        quality: GameWindow.Quality = GameWindow.Quality.AUTOMATIC,
        targetFps: Double = 0.0,
        scaleAnchor: Anchor = Anchor.MIDDLE_CENTER,
        scaleMode: ScaleMode = ScaleMode.SHOW_ALL,
        clipBorders: Boolean = true,
        bgcolor: RGBA? = Colors.BLACK,
        debug: Boolean = false,
        debugFontExtraScale: Double = 1.0,
        debugFontColor: RGBA = Colors.WHITE,
        fullscreen: Boolean? = null,
        args: Array<String> = arrayOf(),
        gameWindow: GameWindow? = null,
        timeProvider: TimeProvider = TimeProvider,
        injector: AsyncInjector = AsyncInjector(),
        debugAg: Boolean = false,
        blocking: Boolean = true,
        gameId: String = DEFAULT_GAME_ID,
        settingsFolder: String? = null,
        batchMaxQuads: Int = BatchBuilder2D.DEFAULT_BATCH_QUADS,
        multithreaded: Boolean? = null,
        forceRenderEveryFrame: Boolean = true,
        stageBuilder: (Views) -> Stage = { Stage(it) },
        entry: suspend Stage.() -> Unit
	) {
        RegisteredImageFormats.register(imageFormats)

        if (!Platform.isJsBrowser) {
            configureLoggerFromProperties(localCurrentDirVfs["klogger.properties"])
        }
        val realGameWindow = (gameWindow ?: coroutineContext[GameWindow] ?: CreateDefaultGameWindow(GameWindowCreationConfig(multithreaded = multithreaded)))
        realGameWindow.bgcolor = bgcolor ?: Colors.BLACK
        //println("Configure: ${width}x${height}")
        // @TODO: Configure should happen before loop. But we should ensure that all the korgw targets are ready for this
        //realGameWindow.configure(width, height, title, icon, fullscreen)
        realGameWindow.loop {
            val gameWindow = this
            if (Platform.isNative) println("Korui[0]")
            gameWindow.registerTime("configureGameWindow") {
                realGameWindow.configure(windowSize, title, icon, fullscreen, bgcolor ?: Colors.BLACK)
            }
            gameWindow.registerTime("setIcon") {
                try {
                    // Do nothing
                    when {
                        //iconDrawable != null -> this.icon = iconDrawable.render()
                        iconPath != null -> this.icon = resourcesVfs[iconPath!!].readBitmap(imageFormats)
                        else -> Unit
                    }
                } catch (e: Throwable) {
                    logger.error { "Couldn't get the application icon" }
                    e.printStackTrace()
                }
            }
            this.quality = quality
            if (Platform.isNative) println("CanvasApplicationEx.IN[0]")
            val input = Input()
            val stats = Stats()

            // Use this once Korgw is on 1.12.5
            //val views = Views(gameWindow.getCoroutineDispatcherWithCurrentContext() + SupervisorJob(), ag, injector, input, timeProvider, stats, gameWindow)
            val views: Views = Views(
                coroutineContext = coroutineContext + gameWindow.coroutineDispatcher + AsyncInjectorContext(injector) + SupervisorJob(),
                ag = if (debugAg) AGPrint() else ag,
                injector = injector,
                input = input,
                timeProvider = timeProvider,
                stats = stats,
                gameWindow = gameWindow,
                gameId = gameId,
                settingsFolder = settingsFolder,
                batchMaxQuads = batchMaxQuads,
                stageBuilder = stageBuilder
            ).also {
                it.init()
            }

            if (Platform.isJsBrowser) {
                Dyn.global["views"] = views
            }
            injector
                .mapInstance(ModuleArgs(args))
                .mapInstance(GameWindow::class, gameWindow)
                .mapInstance<Module>(object : Module() {
                    override val title = title
                    override val fullscreen: Boolean? = fullscreen
                    override val windowSize = windowSize
                    override val virtualSize = virtualSize
                })
            views.debugViews = debug
            views.debugFontExtraScale = debugFontExtraScale
            views.debugFontColor = debugFontColor
            views.virtualWidth = virtualSize.width
            views.virtualHeight = virtualSize.height
            views.scaleAnchor = scaleAnchor
            views.scaleMode = scaleMode
            views.clipBorders = clipBorders
            views.targetFps = targetFps
            //Korge.prepareViews(views, gameWindow, bgcolor != null, bgcolor ?: Colors.TRANSPARENT_BLACK)

            gameWindow.registerTime("prepareViews") {
                prepareViews(
                    views,
                    gameWindow,
                    bgcolor != null,
                    bgcolor ?: Colors.TRANSPARENT,
                    waitForFirstRender = true,
                    forceRenderEveryFrame = forceRenderEveryFrame
                )
            }

            gameWindow.registerTime("completeViews") {
                completeViews(views)
            }
            views.launchImmediately {
                coroutineScope {
                    //println("coroutineContext: $coroutineContext")
                    //println("GameWindow: ${coroutineContext[GameWindow]}")
                    entry(views.stage)
                    if (blocking) {
                        // @TODO: Do not complete to prevent job cancelation?
                        gameWindow.waitClose()
                    }
                }
            }
            if (Platform.isNative) println("CanvasApplicationEx.IN[1]")
            if (Platform.isNative) println("Korui[1]")

            if (blocking) {
                // @TODO: Do not complete to prevent job cancelation?
                gameWindow.waitClose()
                gameWindow.exit()
            }
        }
    }

    suspend fun GameWindow.waitClose() {
        while (running) {
            delay(100.milliseconds)
        }
    }

    @KorgeInternal
    fun prepareViewsBase(
        views: Views,
        eventDispatcher: EventListener,
        clearEachFrame: Boolean = true,
        bgcolor: RGBA = Colors.TRANSPARENT,
        fixedSizeStep: TimeSpan = TimeSpan.NIL,
        forceRenderEveryFrame: Boolean = true
    ): CompletableDeferred<Unit> {
        KorgeReload.registerEventDispatcher(eventDispatcher)

        val injector = views.injector
        injector.mapInstance(views)
        injector.mapInstance(views.ag)
        injector.mapInstance(Resources::class, views.globalResources)
        injector.mapSingleton(ResourcesRoot::class) { ResourcesRoot() }
        injector.mapInstance(views.input)
        injector.mapInstance(views.stats)
        injector.mapInstance(CoroutineContext::class, views.coroutineContext)
        injector.mapPrototype(EmptyScene::class) { EmptyScene() }
        injector.mapInstance(TimeProvider::class, views.timeProvider)

        val input = views.input
        val ag = views.ag
        val downPos = MPoint()
        val upPos = MPoint()
        var downTime = DateTime.EPOCH
        var moveTime = DateTime.EPOCH
        var upTime = DateTime.EPOCH
        var moveMouseOutsideInNextFrame = false
        val mouseTouchId = -1
        views.forceRenderEveryFrame = forceRenderEveryFrame

        val tempXY: MPoint = MPoint()

        // devicePixelRatio might change at runtime by changing the resolution or changing the screen of the window
        fun getRealXY(x: Double, y: Double, scaleCoords: Boolean, out: MPoint = tempXY): MPoint {
            return views.windowToGlobalCoords(x, y, out)
        }

        fun getRealX(x: Double, scaleCoords: Boolean): Double = if (scaleCoords) x * views.devicePixelRatio else x
        fun getRealY(y: Double, scaleCoords: Boolean): Double = if (scaleCoords) y * views.devicePixelRatio else y

        /*
        fun updateTouch(id: Int, x: Double, y: Double, start: Boolean, end: Boolean) {
            val touch = input.getTouch(id)
            val now = DateTime.now()

            touch.id = id
            touch.active = !end

            if (start) {
                touch.startTime = now
                touch.start.setTo(x, y)
            }

            touch.currentTime = now
            touch.current.setTo(x, y)

            input.updateTouches()
        }
        */

        fun mouseDown(type: String, p: Point, button: MouseButton) {
            input.toggleButton(button, true)
            input.setMouseGlobalPos(p, down = false)
            input.setMouseGlobalPos(p, down = true)
            views.mouseUpdated()
            downPos.copyFrom(input.mousePos)
            downTime = DateTime.now()
            input.mouseInside = true
        }

        fun mouseUp(type: String, p: Point, button: MouseButton) {
            //Console.log("mouseUp: $name")
            input.toggleButton(button, false)
            input.setMouseGlobalPos(p, down = false)
            views.mouseUpdated()
            upPos.copyFrom(views.input.mousePos)
        }

        fun mouseMove(type: String, p: Point, inside: Boolean) {
            views.input.setMouseGlobalPos(p, down = false)
            views.input.mouseInside = inside
            if (!inside) {
                moveMouseOutsideInNextFrame = true
            }
            views.mouseUpdated()
            moveTime = DateTime.now()
        }

        fun mouseDrag(type: String, p: Point) {
            views.input.setMouseGlobalPos(p, down = false)
            views.mouseUpdated()
            moveTime = DateTime.now()
        }

        val mouseTouchEvent = TouchEvent()

        fun dispatchSimulatedTouchEvent(
            p: Point,
            button: MouseButton,
            type: TouchEvent.Type,
            status: Touch.Status
        ) {
            mouseTouchEvent.screen = 0
            mouseTouchEvent.emulated = true
            mouseTouchEvent.currentTime = DateTime.now()
            mouseTouchEvent.scaleCoords = false
            mouseTouchEvent.startFrame(type)
            mouseTouchEvent.touch(button.id, p.xD, p.yD, status, kind = Touch.Kind.MOUSE, button = button)
            mouseTouchEvent.endFrame()
            views.dispatch(mouseTouchEvent)
        }

        eventDispatcher.onEvents(*MouseEvent.Type.ALL) { e ->
            //println("MOUSE: $e")
            logger.trace { "eventDispatcher.addEventListener<MouseEvent>:$e" }
            val p = getRealXY(e.x.toDouble(), e.y.toDouble(), e.scaleCoords).point
            when (e.type) {
                MouseEvent.Type.DOWN -> {
                    mouseDown("mouseDown", p, e.button)
                    //updateTouch(mouseTouchId, x, y, start = true, end = false)
                    dispatchSimulatedTouchEvent(p, e.button, TouchEvent.Type.START, Touch.Status.ADD)
                }

                MouseEvent.Type.UP -> {
                    mouseUp("mouseUp", p, e.button)
                    //updateTouch(mouseTouchId, x, y, start = false, end = true)
                    dispatchSimulatedTouchEvent(p, e.button, TouchEvent.Type.END, Touch.Status.REMOVE)
                }

                MouseEvent.Type.DRAG -> {
                    mouseDrag("onMouseDrag", p)
                    //updateTouch(mouseTouchId, x, y, start = false, end = false)
                    dispatchSimulatedTouchEvent(p, e.button, TouchEvent.Type.MOVE, Touch.Status.KEEP)
                }

                MouseEvent.Type.MOVE -> mouseMove("mouseMove", p, inside = true)
                MouseEvent.Type.CLICK -> Unit
                MouseEvent.Type.ENTER -> mouseMove("mouseEnter", p, inside = true)
                MouseEvent.Type.EXIT -> mouseMove("mouseExit", p, inside = false)
                MouseEvent.Type.SCROLL -> Unit
            }
            views.dispatch(e)
        }

        eventDispatcher.onEvents(*KeyEvent.Type.ALL) { e ->
            logger.trace { "eventDispatcher.addEventListener<KeyEvent>:$e" }
            views.dispatch(e)
        }
        eventDispatcher.onEvents(*GestureEvent.Type.ALL) { e ->
            logger.trace { "eventDispatcher.addEventListener<GestureEvent>:$e" }
            views.dispatch(e)
        }

        eventDispatcher.onEvents(*DropFileEvent.Type.ALL) { e -> views.dispatch(e) }
        eventDispatcher.onEvent(ResumeEvent) { e -> views.dispatch(e) }
        eventDispatcher.onEvent(PauseEvent) { e -> views.dispatch(e) }
        eventDispatcher.onEvent(StopEvent) { e -> views.dispatch(e) }
        eventDispatcher.onEvent(DestroyEvent) { e ->
            try {
                views.dispatch(e)
            } finally {
                views.launchImmediately {
                    views.close()
                }
            }
        }

        val touchMouseEvent = MouseEvent()
        eventDispatcher.onEvents(*TouchEvent.Type.ALL) { e ->
            logger.trace { "eventDispatcher.addEventListener<TouchEvent>:$e" }

            input.updateTouches(e)
            val ee = input.touch
            for (t in ee.touches) {
                val (x, y) = getRealXY(t.x, t.y, e.scaleCoords)
                t.x = x
                t.y = y
            }
            views.dispatch(ee)

            // Touch to mouse events
            if (ee.numTouches == 1) {
                val start = ee.isStart
                val end = ee.isEnd
                val t = ee.touches.first()
                val p = t.p
                val x = t.x
                val y = t.y
                val button = MouseButton.LEFT

                //updateTouch(t.id, x, y, start, end)
                when {
                    start -> mouseDown("onTouchStart", p, button)
                    end -> mouseUp("onTouchEnd", p, button)
                    else -> mouseMove("onTouchMove", p, inside = true)
                }
                views.dispatch(touchMouseEvent.also {
                    it.id = 0
                    it.button = button
                    it.buttons = if (end) 0 else 1 shl button.id
                    it.x = x.toInt()
                    it.y = y.toInt()
                    it.scaleCoords = false
                    it.emulated = true
                    it.type = when {
                        start -> MouseEvent.Type.DOWN
                        end -> MouseEvent.Type.UP
                        else -> MouseEvent.Type.DRAG
                    }
                })
                if (end) {
                    moveMouseOutsideInNextFrame = true
                }
            }

        }

        fun gamepadUpdated(e: GamePadUpdateEvent) {
            e.gamepads.fastForEach { gamepad ->
                input.gamepads[gamepad.index].copyFrom(gamepad)
            }
            input.updateConnectedGamepads()
        }

        eventDispatcher.onEvents(*GamePadConnectionEvent.Type.ALL) { e ->
            logger.trace { "eventDispatcher.addEventListener<GamePadConnectionEvent>:$e" }
            views.dispatch(e)
        }

        eventDispatcher.onEvent(GamePadUpdateEvent) { e ->
            gamepadUpdated(e)
            views.dispatch(e)
        }

        eventDispatcher.onEvent(ReshapeEvent) { e ->
            //try { throw Exception() } catch (e: Throwable) { e.printStackTrace() }
            //println("eventDispatcher.addEventListener<ReshapeEvent>: ${ag.backWidth}x${ag.backHeight} : ${e.width}x${e.height}")
            //println("resized. ${ag.backWidth}, ${ag.backHeight}")
            views.resized(ag.mainFrameBuffer.width, ag.mainFrameBuffer.height)
        }

        //println("eventDispatcher.dispatch(ReshapeEvent(0, 0, views.nativeWidth, views.nativeHeight)) : ${views.nativeWidth}x${views.nativeHeight}")
        eventDispatcher.dispatch(ReshapeEvent(0, 0, views.nativeWidth, views.nativeHeight))

        eventDispatcher.onEvent(ReloadEvent) { views.dispatch(it) }

        var renderShown = false
        views.clearEachFrame = clearEachFrame
        views.clearColor = bgcolor
        val firstRenderDeferred = CompletableDeferred<Unit>()

        fun renderBlock(event: RenderEvent) {
            //println("renderBlock: $event")
            try {
                views.frameUpdateAndRender(
                    fixedSizeStep = fixedSizeStep,
                    forceRender = views.forceRenderEveryFrame,
                    doUpdate = event.update,
                    doRender = event.render,
                )

                views.input.mouseOutside = false
                if (moveMouseOutsideInNextFrame) {
                    moveMouseOutsideInNextFrame = false
                    views.input.mouseOutside = true
                    views.input.mouseInside = false
                    views.mouseUpdated()
                }
            } catch (e: Throwable) {
                logger.error { "views.gameWindow.onRenderEvent:" }
                e.printStackTrace()
                if (views.rethrowRenderError) throw e
            }
        }

        views.gameWindow.onRenderEvent { event ->
            //println("RenderEvent: $event")
            if (!event.render) {
                renderBlock(event)
            } else {
                views.renderContext.doRender {
                    if (!renderShown) {
                        //println("!!!!!!!!!!!!! views.gameWindow.addEventListener<RenderEvent>")
                        renderShown = true
                        firstRenderDeferred.complete(Unit)
                    }
                    renderBlock(event)
                }
            }
        }

        return firstRenderDeferred
    }

    @KorgeInternal
    suspend fun prepareViews(
        views: Views,
        eventDispatcher: EventListener,
        clearEachFrame: Boolean = true,
        bgcolor: RGBA = Colors.TRANSPARENT,
        fixedSizeStep: TimeSpan = TimeSpan.NIL,
        waitForFirstRender: Boolean = true,
        forceRenderEveryFrame: Boolean = true
    ) {
        val firstRenderDeferred =
            prepareViewsBase(views, eventDispatcher, clearEachFrame, bgcolor, fixedSizeStep, forceRenderEveryFrame)
        if (waitForFirstRender) {
            firstRenderDeferred.await()
        }
    }

	data class Config(
        val module: Module = Module(),
        val args: Array<String> = arrayOf(),
        val imageFormats: ImageFormat = RegisteredImageFormats,
        val gameWindow: GameWindow? = null,
		//val eventDispatcher: EventDispatcher = gameWindow ?: DummyEventDispatcher, // Removed
        val sceneClass: KClass<out Scene>? = module.mainScene,
        val sceneInjects: List<Any> = listOf(),
        val timeProvider: TimeProvider = TimeProvider,
        val injector: AsyncInjector = AsyncInjector(),
        val debug: Boolean = false,
        val trace: Boolean = false,
        val context: Any? = null,
        val fullscreen: Boolean? = null,
        val blocking: Boolean = true,
        val gameId: String = DEFAULT_GAME_ID,
        val settingsFolder: String? = null,
        val batchMaxQuads: Int = BatchBuilder2D.DEFAULT_BATCH_QUADS,
        val virtualSize: SizeInt? = module.virtualSize,
        val windowSize: SizeInt? = module.windowSize,
        val scaleMode: ScaleMode? = null,
        val scaleAnchor: Anchor? = null,
        val clipBorders: Boolean? = null,
        val title: String? = null,
        val bgcolor: RGBA? = null,
        val quality: GameWindow.Quality? = null,
        val icon: String? = null,
        val multithreaded: Boolean? = null,
        val forceRenderEveryFrame: Boolean = true,
        val main: (suspend Stage.() -> Unit)? = module.main,
        val constructedScene: Scene.(Views) -> Unit = module.constructedScene,
        val constructedViews: (Views) -> Unit = module.constructedViews,
	) {
        val finalWindowSize: SizeInt get() = windowSize ?: module.windowSize
        val finalVirtualSize: SizeInt get() = virtualSize ?: module.virtualSize
    }

	data class ModuleArgs(val args: Array<String>)
}
