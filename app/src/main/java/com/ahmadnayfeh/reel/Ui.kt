package com.ahmadnayfeh.reel

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.math.roundToInt

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

/** 16:9 box. */
class RatioFrame(c: Context, a: AttributeSet?) : FrameLayout(c, a) {
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY))
    }
}

/** Square box that fits the smaller side of its parent. */
class SquareFrame(c: Context, a: AttributeSet?) : FrameLayout(c, a) {
    override fun onMeasure(w: Int, h: Int) {
        val ws = MeasureSpec.getSize(w)
        val hs = MeasureSpec.getSize(h)
        val side = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED || hs == 0) ws else minOf(ws, hs)
        val spec = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
    }
}

/** Small builder for clean bottom-sheet menus (title + rows with icon, label, value/check). */
class Sheet(private val ctx: Context, title: CharSequence?) {
    val dialog = BottomSheetDialog(ctx)
    val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, ctx.dp(8), 0, ctx.dp(16))
    }

    init {
        if (title != null) body.addView(TextView(ctx).apply {
            text = title
            textSize = 18f
            setTextColor(ContextCompat.getColor(ctx, R.color.ink))
            paint.isFakeBoldText = true
            setPadding(ctx.dp(24), ctx.dp(12), ctx.dp(24), ctx.dp(10))
        })
        dialog.setContentView(ScrollView(ctx).apply { addView(body) })
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
    }

    fun row(icon: Int?, label: CharSequence, value: CharSequence? = null, checked: Boolean = false,
            dismiss: Boolean = true, onClick: () -> Unit): Sheet {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(52)
            setPadding(ctx.dp(24), 0, ctx.dp(20), 0)
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { if (dismiss) dialog.dismiss(); onClick() }
        }
        if (icon != null) r.addView(ImageView(ctx).apply {
            setImageResource(icon)
            setColorFilter(ContextCompat.getColor(ctx, if (checked) R.color.accent else R.color.muted))
        }, LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)).apply { marginEnd = ctx.dp(18) })
        r.addView(TextView(ctx).apply {
            text = label
            textSize = 15.5f
            setTextColor(ContextCompat.getColor(ctx, if (checked) R.color.accent else R.color.ink))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) r.addView(TextView(ctx).apply {
            text = value
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.muted))
        })
        if (checked) r.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_check)
            setColorFilter(ContextCompat.getColor(ctx, R.color.accent))
        }, LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)).apply { marginStart = ctx.dp(12) })
        body.addView(r)
        return this
    }

    fun text(label: CharSequence, value: CharSequence): Sheet {
        body.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), ctx.dp(8))
            addView(TextView(ctx).apply { text = label; textSize = 12.5f; setTextColor(ContextCompat.getColor(ctx, R.color.muted)) })
            addView(TextView(ctx).apply { text = value; textSize = 15f; setTextColor(ContextCompat.getColor(ctx, R.color.ink)); setTextIsSelectable(true) })
        })
        return this
    }

    fun view(v: View): Sheet { body.addView(v); return this }
    fun divider(): Sheet {
        body.addView(View(ctx).apply { setBackgroundColor(ContextCompat.getColor(ctx, R.color.line)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, ctx.dp(6), 0, ctx.dp(6)) })
        return this
    }
    fun show(): Sheet { dialog.show(); return this }
}
