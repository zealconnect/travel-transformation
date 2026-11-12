package com.example.voiceagent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the main screen to a PNG so the design can be checked without a phone. Not a pass/fail test. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp-xxhdpi")
class ScreenshotTest {

    private fun shoot(name: String) {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root: View = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1233, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(4800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1233, 4800)
        val bmp = Bitmap.createBitmap(1233, 4800, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val out = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(qualifiers = "+notnight") fun light() = shoot("main_light")
    @Test @Config(qualifiers = "+night") fun dark() = shoot("main_dark")
}
