package com.draco.libretrowrapper

import android.app.Service
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.swordfish.libretrodroid.GLRetroView
import io.reactivex.disposables.CompositeDisposable
import java.io.File
import java.util.concurrent.CountDownLatch

class GameActivity : AppCompatActivity() {
    private lateinit var parent: FrameLayout
    private lateinit var privateData: PrivateData

    private lateinit var leftGamePadContainer: FrameLayout
    private lateinit var rightGamePadContainer: FrameLayout

    private var retroView: GLRetroView? = null
    private var leftGamePad: GamePad? = null
    private var rightGamePad: GamePad? = null

    private val retroViewReadyLatch = CountDownLatch(1)

    private val compositeDisposable = CompositeDisposable()

    private val validKeyCodes = listOf(
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_L2,
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT
    )

    private val validAssets = listOf(
        "rom",      /* ROM file itself */
        "save",     /* SRAM dump */
        "state"     /* Save state dump */
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        /* Initialize layout variables */
        parent = findViewById(R.id.parent)
        leftGamePadContainer = findViewById(R.id.left_container)
        rightGamePadContainer = findViewById(R.id.right_container)

        /* Setup private data handler */
        privateData = PrivateData(this)

        /* Copy assets */
        initAssets()

        /* Create GLRetroView */
        initRetroView()

        /* Create GamePads */
        if (resources.getBoolean(R.bool.rom_gamepad_visible))
            initGamePads()

        /* Consider loading state if we died from a configuration change */
        if (savedInstanceState != null) {
            val stateBytes = savedInstanceState.getByteArray("state")
            if (stateBytes != null) {
                /* Wait for the first frame to be rendered and some extra delay passes */
                Thread {
                    retroViewReadyLatch.await()
                    retroView?.unserializeState(stateBytes)
                }.start()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putByteArray("state", retroView?.serializeState())
        super.onSaveInstanceState(outState)
    }

    private fun initAssets() {
        for (asset in validAssets) {
            try {
                val assetInputStream = assets.open(asset)
                val assetBytes = assetInputStream.readBytes()
                val assetFile = File("${filesDir.absolutePath}/$asset")

                if (!assetFile.exists())
                    assetFile.writeBytes(assetBytes)
            } catch (_: Exception) {}
        }
    }

    private fun initRetroView() {
        /* Initialize save data */
        val saveBytes = if (privateData.save.exists())
            privateData.save.readBytes()
        else
            byteArrayOf()

        /* Create GLRetroView */
        retroView = GLRetroView(
            this,
            "${getString(R.string.rom_core)}_libretro_android.so",
            privateData.rom.absolutePath,
            saveRAMState = saveBytes,
            shader = GLRetroView.SHADER_SHARP
        )
        lifecycle.addObserver(retroView!!)
        parent.addView(retroView)

        /* Center view */
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        params.gravity = Gravity.CENTER
        retroView!!.layoutParams = params

        /* Start tracking the frame state of the GLRetroView */
        val renderDisposable = retroView!!
            .getGLRetroEvents()
            .takeUntil { retroViewReadyLatch.count == 0L }
            .subscribe {
                if (it == GLRetroView.GLRetroEvents.FrameRendered)
                    Handler(Looper.getMainLooper()).postDelayed({ retroViewReadyLatch.countDown() }, 100)
            }
        compositeDisposable.add(renderDisposable)
    }

    private fun initGamePads() {
        /* Must be called after GLRetroView is initialized */
        if (retroView == null)
            return

        /* Initialize GamePads */
        val gamePadConfig = GamePadConfig(resources)
        leftGamePad = GamePad(this, gamePadConfig.left, retroView!!, privateData)
        rightGamePad = GamePad(this, gamePadConfig.right, retroView!!, privateData)

        /* Initialize input handlers */
        leftGamePad!!.subscribe()
        rightGamePad!!.subscribe()

        /* Add GamePads to the activity */
        leftGamePadContainer.addView(leftGamePad!!.pad)
        rightGamePadContainer.addView(rightGamePad!!.pad)

        /* Configure GamePad sizes */
        leftGamePad!!.pad.offsetX = -1f
        rightGamePad!!.pad.offsetX = 1f

        val density = resources.displayMetrics.density
        val gamePadSize = resources.getDimension(R.dimen.rom_gamepad_size) / density

        leftGamePad!!.pad.primaryDialMaxSizeDp = gamePadSize
        rightGamePad!!.pad.primaryDialMaxSizeDp = gamePadSize

        /* Check if we should show or hide controls */
        updateGamePadVisibility()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus)
            return

        /* Apply immersive mode */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            with (window.insetsController!!) {
                hide(
                    WindowInsets.Type.statusBars() or
                    WindowInsets.Type.navigationBars()
                )
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    private fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode !in validKeyCodes)
            return false

        retroView?.sendKeyEvent(event.action, keyCode)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return handleKeyEvent(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return handleKeyEvent(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        when (retroView?.id) {
            GLRetroView.MOTION_SOURCE_DPAD -> retroView?.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_DPAD,
                event.getAxisValue(MotionEvent.AXIS_HAT_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            )
            GLRetroView.MOTION_SOURCE_ANALOG_LEFT -> retroView?.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_ANALOG_LEFT,
                event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y)
            )
            GLRetroView.MOTION_SOURCE_ANALOG_RIGHT -> retroView?.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_ANALOG_RIGHT,
                event.getAxisValue(MotionEvent.AXIS_Z),
                event.getAxisValue(MotionEvent.AXIS_RZ)
            )
        }
        return super.onGenericMotionEvent(event)
    }

    private fun getCurrentDisplayId(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            display!!.displayId
        else {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.defaultDisplay.displayId
        }
    }

    private fun shouldShowGamePads(): Boolean {
        /* Do not show if we hardcoded the boolean */
        if (!resources.getBoolean(R.bool.rom_gamepad_visible))
            return false

        /* Do not show if the device lacks a touch screen */
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN))
            return false

        /* Do not show if the current display is external (i.e. wireless cast) */
        val dm = getSystemService(Service.DISPLAY_SERVICE) as DisplayManager
        if (dm.getDisplay(getCurrentDisplayId())
                .flags.and(Display.FLAG_PRESENTATION) == Display.FLAG_PRESENTATION) {
            return false
        }

        /* Do not show if the device has a controller connected */
        for (id in InputDevice.getDeviceIds()) {
            InputDevice.getDevice(id).apply {
                if (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                    sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
                    return false
            }
        }

        /* Otherwise, show */
        return true
    }

    private fun updateGamePadVisibility() {
        val visibility = if (shouldShowGamePads())
            View.VISIBLE
        else
            View.GONE

        leftGamePadContainer.visibility = visibility
        rightGamePadContainer.visibility = visibility
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateGamePadVisibility()
    }

    override fun onPause() {
        if (retroView != null)
            privateData.save.writeBytes(retroView!!.serializeSRAM())

        super.onPause()
    }

    override fun onDestroy() {
        leftGamePad?.unsubscribe()
        rightGamePad?.unsubscribe()
        compositeDisposable.dispose()
        super.onDestroy()
    }
}