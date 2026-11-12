package moe.shizuku.manager.xion

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import moe.shizuku.manager.R

object XionUi {

    enum class Tone { POSITIVE, NEGATIVE, CAUTION, NEUTRAL, BRAND }

    fun AppCompatActivity.applyScreenInsets(view: View, extraTop: Int = 8, extraBottom: Int = 24) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                bars.top + dp(this, extraTop),
                v.paddingRight,
                bars.bottom + dp(this, extraBottom)
            )
            insets
        }
    }

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun TextView.applyTone(tone: Tone) {
        val (bg, fg) = when (tone) {
            Tone.POSITIVE -> R.drawable.xion_chip_positive to R.color.xion_positive
            Tone.NEGATIVE -> R.drawable.xion_chip_negative to R.color.xion_negative
            Tone.CAUTION -> R.drawable.xion_chip_caution to R.color.xion_caution
            Tone.BRAND -> R.drawable.xion_chip to R.color.xion_brand_hi
            Tone.NEUTRAL -> R.drawable.xion_chip_neutral to R.color.xion_text_body
        }
        setBackgroundResource(bg)
        setTextColor(ContextCompat.getColor(context, fg))
    }

    fun View.tint(colorRes: Int) {
        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, colorRes))
    }
}
