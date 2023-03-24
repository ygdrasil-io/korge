package korlibs.korge.android

import android.content.Context
import android.util.AttributeSet
import android.widget.RelativeLayout
import korlibs.kgl.*
import korlibs.graphics.gl.AGOpengl
import korlibs.event.InitEvent
import korlibs.event.RenderEvent
import korlibs.korge.Korge
import korlibs.korge.scene.Module
import korlibs.render.AndroidGameWindowNoActivity
import korlibs.render.GameWindowCreationConfig
import korlibs.io.Korio
import korlibs.io.android.withAndroidContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("unused")
open class KorgeAndroidView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    val config: GameWindowCreationConfig = GameWindowCreationConfig(),
) : RelativeLayout(context, attrs, defStyleAttr) {
    var mGLView: korlibs.render.KorgwSurfaceView? = null
    private var agOpenGl: AGOpengl? = null
    private var gameWindow: AndroidGameWindowNoActivity? = null

    private val renderEvent = RenderEvent()
    private val initEvent = InitEvent()

    var moduleLoaded = false ; private set

    fun unloadModule() {
        if (!moduleLoaded) return

        gameWindow?.dispatchDestroyEvent()
        gameWindow?.coroutineContext = null
        gameWindow?.close()
        gameWindow?.exit()
        mGLView = null
        gameWindow = null
        agOpenGl = null

        //findViewTreeLifecycleOwner()?.lifecycleScope?.launch { // @TODO: Not available in dependencies. Check if we can somehow get this other way.
        CoroutineScope(Dispatchers.Main).launch {
            mGLView?.let { removeView(it) }
        }

        moduleLoaded = false
    }

    fun loadModule(module: Module) {
        loadModule(Korge.Config(module))
    }

    fun loadModule(config: Korge.Config) {
        unloadModule() // Unload module if already loaded

        agOpenGl = AGOpengl(KmlGlAndroid { mGLView?.clientVersion ?: -1 }.checkedIf(checked = false).logIf(log = false))
        gameWindow = AndroidGameWindowNoActivity(config.windowSize?.width ?: config.finalWindowSize.width,
            config.finalWindowSize.height, agOpenGl!!, context, this.config) { mGLView!! }
        mGLView = korlibs.render.KorgwSurfaceView(this, context, gameWindow!!, this.config)

        addView(mGLView)

        gameWindow?.let { gameWindow ->
            Korio(context) {
                try {
                    withAndroidContext(context) {
                        withContext(coroutineContext + gameWindow) {
                            Korge(config)
                        }
                    }
                } finally {
                    println("${javaClass.name} completed!")
                }
            }
        }

        moduleLoaded = true
    }

    fun queueEvent(runnable: Runnable) {
        mGLView?.queueEvent(runnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unloadModule()
    }
}