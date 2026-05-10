package com.xiaomei.assistant.settings.dsl

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableString
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.AbsoluteSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.res.ResourcesCompat
import com.xiaomei.assistant.R

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

private fun View.dp(value: Int): Int = context.dp(value)

class TitleValueCell(context: Context) : FrameLayout(context) {
  private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val dividerColor = ResourcesCompat.getColor(resources, R.color.divideColor, context.theme)
  private val dip1 = context.resources.displayMetrics.density
  private var drawDivider = true
  private val titleView = TextView(context)
  private val summaryView = TextView(context)
  private val valueView = TextView(context)
  private val switchView = SwitchCompat(context)

  init {
    minimumHeight = dp(50)
    setWillNotDraw(false)
    isClickable = true
    isFocusable = true
    foreground = selectableItemBackground(context)
    titleView.apply {
      setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
      setTextColor(ResourcesCompat.getColor(resources, R.color.firstTextColor, context.theme))
      gravity = Gravity.CENTER_VERTICAL or Gravity.START
      setSingleLine(true)
      maxLines = 1
      ellipsize = TextUtils.TruncateAt.MARQUEE
      marqueeRepeatLimit = -1
      isSelected = true
    }
    summaryView.apply {
      setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
      setTextColor(ResourcesCompat.getColor(resources, R.color.thirdTextColor, context.theme))
      gravity = Gravity.START
      visibility = GONE
    }
    valueView.apply {
      setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
      setTextColor(ResourcesCompat.getColor(resources, R.color.colorAccent, context.theme))
      visibility = GONE
    }
    switchView.apply {
      visibility = GONE
      isClickable = false
      textOn = ""
      textOff = ""
    }
    addView(titleView)
    addView(summaryView)
    addView(valueView)
    addView(switchView)
  }

  fun bind(title: String, summary: String?, value: String?, hasSwitch: Boolean, checked: Boolean, divider: Boolean) {
    titleView.text = title
    summaryView.text = summary.orEmpty()
    summaryView.visibility = if (summary.isNullOrEmpty()) GONE else VISIBLE
    valueView.text = value.orEmpty()
    valueView.visibility = if (value.isNullOrEmpty() || hasSwitch) GONE else VISIBLE
    switchView.visibility = if (hasSwitch) VISIBLE else GONE
    switchView.isChecked = checked
    drawDivider = divider
    requestLayout()
    invalidate()
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val width = MeasureSpec.getSize(widthMeasureSpec)
    val titleRight = if (valueView.visibility == VISIBLE || switchView.visibility == VISIBLE) dp(118) else dp(21)
    val titleWidth = width - dp(21) - titleRight
    titleView.measure(
      MeasureSpec.makeMeasureSpec(titleWidth.coerceAtLeast(0), MeasureSpec.AT_MOST),
      MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
    )
    summaryView.measure(
      MeasureSpec.makeMeasureSpec((width - dp(21) - dp(70)).coerceAtLeast(0), MeasureSpec.AT_MOST),
      MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
    )
    valueView.measure(
      MeasureSpec.makeMeasureSpec((width - dp(44)).coerceAtLeast(0), MeasureSpec.AT_MOST),
      MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
    )
    switchView.measure(
      MeasureSpec.makeMeasureSpec((width - dp(44)).coerceAtLeast(0), MeasureSpec.AT_MOST),
      MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
    )
    val contentHeight = if (summaryView.visibility == VISIBLE) {
      dp(10) + titleView.measuredHeight + dp(4) + summaryView.measuredHeight + dp(6)
    } else {
      titleView.measuredHeight
    }
    setMeasuredDimension(width, maxOf(dp(50), contentHeight))
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    val titleLeft = dp(21)
    if (summaryView.visibility == VISIBLE) {
      val titleTop = dp(10)
      titleView.layout(titleLeft, titleTop, titleLeft + titleView.measuredWidth, titleTop + titleView.measuredHeight)
      val summaryTop = titleTop + titleView.measuredHeight + dp(4)
      summaryView.layout(titleLeft, summaryTop, titleLeft + summaryView.measuredWidth, summaryTop + summaryView.measuredHeight)
    } else {
      val titleTop = (height - titleView.measuredHeight) / 2
      titleView.layout(titleLeft, titleTop, titleLeft + titleView.measuredWidth, titleTop + titleView.measuredHeight)
    }
    if (valueView.visibility == VISIBLE) {
      val valueTop = (height - valueView.measuredHeight) / 2
      val valueRight = width - dp(22)
      valueView.layout(valueRight - valueView.measuredWidth, valueTop, valueRight, valueTop + valueView.measuredHeight)
    }
    if (switchView.visibility == VISIBLE) {
      val switchTop = (height - switchView.measuredHeight) / 2
      val switchRight = width - dp(22)
      switchView.layout(switchRight - switchView.measuredWidth, switchTop, switchRight, switchTop + switchView.measuredHeight)
    }
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (drawDivider) {
      dividerPaint.strokeWidth = dip1
      dividerPaint.color = dividerColor
      canvas.drawLine(0f, measuredHeight.toFloat(), measuredWidth.toFloat(), measuredHeight.toFloat(), dividerPaint)
    }
  }
}

class HeaderCell(context: Context) : FrameLayout(context) {
  private val titleView = TextView(context)

  init {
    minimumHeight = dp(40)
    titleView.apply {
      setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
      setTextColor(ResourcesCompat.getColor(resources, R.color.colorAccent, context.theme))
      gravity = Gravity.CENTER_VERTICAL or Gravity.START
      minHeight = dp(25)
    }
    addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
      marginStart = dp(21)
      topMargin = dp(15)
      marginEnd = dp(21)
    })
  }

  fun bind(title: String) {
    titleView.text = title
  }
}

class TextInfoCell(context: Context, padding: Int = 21) : FrameLayout(context) {
  private val textView = AppCompatTextView(context)
  private var textValue: CharSequence? = null

  init {
    textView.apply {
      setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
      gravity = Gravity.START
      setPadding(0, dp(10), 0, dp(17))
      movementMethod = LinkMovementMethod.getInstance()
      importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
      setTextColor(ResourcesCompat.getColor(resources, R.color.thirdTextColor, context.theme))
      setLinkTextColor(ResourcesCompat.getColor(resources, R.color.colorAccent, context.theme))
    }
    addView(textView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.TOP).apply {
      marginStart = dp(padding)
      marginEnd = dp(padding)
    })
  }

  fun bind(text: CharSequence?) {
    if (TextUtils.equals(text, textValue)) return
    textValue = text
    textView.setPadding(0, if (text == null) dp(2) else dp(10), 0, if (text == null) 0 else dp(17))
    var spannableString: SpannableString? = null
    if (text != null) {
      var i = 0
      while (i < text.length - 1) {
        if (text[i] == '\n' && text[i + 1] == '\n') {
          if (spannableString == null) spannableString = SpannableString(text)
          spannableString.setSpan(AbsoluteSizeSpan(10, true), i + 1, i + 2, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        i++
      }
    }
    textView.text = spannableString ?: text
  }
}

class SpacerCell(context: Context) : FrameLayout(context) {
  var spacerHeight: Int = dp(12)
    set(value) {
      field = value
      requestLayout()
    }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), spacerHeight)
  }
}

private fun selectableItemBackground(context: Context): android.graphics.drawable.Drawable? {
  val out = TypedValue()
  context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
  return AppCompatResources.getDrawable(context, out.resourceId)
}
