package cc.ioctl.util.ui.fling

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

class SimpleFlingInterceptLayout @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

  interface SimpleOnFlingHandler {
    fun onFlingLeftToRight(): Boolean = false
    fun onFlingRightToLeft(): Boolean = false
  }

  var onFlingHandler: SimpleOnFlingHandler? = null
  var isInterceptEnabled: Boolean = true
}
