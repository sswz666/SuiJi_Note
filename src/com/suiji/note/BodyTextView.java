package com.suiji.note;

import android.content.Context;
import android.text.Selection;
import android.text.Spannable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.TextView;

/**
 * 浏览态正文。
 *
 * 两个必须处理的系统行为：
 * 1) 开启 textIsSelectable 后，TextView 在按下时会 requestDisallowInterceptTouchEvent(true)，
 *    父级 ScrollView 收不到滑动，长文本就无法滚动。这里在「还没有选中一段文字」时
 *    把拦截权交还给父容器，普通滑动照常；一旦真的选中了文字，就保持拦截，允许拖选择手柄。
 * 2) 可选中 TextView 只要拿到焦点，内部就可能存在一个「折叠光标」（start == end），
 *    此时 TextView.hasSelection() 也返回 true。若拿它当「用户正在选字」的判断，
 *    会把所有普通点击都当成选字操作吞掉。所以这里只认「真正选中的区间」（start != end）。
 */
public class BodyTextView extends TextView {

    private boolean rangeSelected;   // 是否真正选中了一段文字（start != end）

    public BodyTextView(Context c) {
        super(c);
        disableFocusHighlight();
    }

    /** 关闭系统默认焦点高亮：可选中时会整块变灰，且并无实际意义。 */
    private void disableFocusHighlight() {
        try {
            setDefaultFocusHighlightEnabled(false);
        } catch (Throwable ignored) {
        }
    }

    public BodyTextView(Context c, AttributeSet attrs) {
        super(c, attrs);
        disableFocusHighlight();
    }

    public BodyTextView(Context c, AttributeSet attrs, int defStyleAttr) {
        super(c, attrs, defStyleAttr);
        disableFocusHighlight();
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        // 只有非空区间才算「用户选中了文字」，折叠光标不算
        rangeSelected = selStart >= 0 && selEnd >= 0 && selStart != selEnd;
    }

    /** 是否真正选中了一段文字。 */
    public boolean hasRangeSelection() {
        if (!rangeSelected) return false;
        CharSequence t = getText();
        if (!(t instanceof Spannable)) return false;
        int s = Selection.getSelectionStart(t);
        int e = Selection.getSelectionEnd(t);
        return s >= 0 && e >= 0 && s != e;
    }

    /** 清掉选区（连同折叠光标），进入编辑或点空白处时调用。 */
    public void clearSelectionQuietly() {
        try {
            CharSequence t = getText();
            if (t instanceof Spannable) {
                Selection.removeSelection((Spannable) t);
            }
        } catch (Throwable ignored) {
        }
        rangeSelected = false;
    }

    /** 按有无正文决定是否可选中：空白卡不需要选中能力，也不该出现可选中的占位文字。 */
    public void setSelectable(boolean selectable) {
        if (isTextSelectable() == selectable) return;
        if (!selectable) clearSelectionQuietly();
        try {
            setTextIsSelectable(selectable);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !hasRangeSelection()) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
        }
        return super.onTouchEvent(event);
    }
}
