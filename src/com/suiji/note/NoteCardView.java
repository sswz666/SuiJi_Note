package com.suiji.note;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ClickableSpan;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一张卡片：顶部（时间 / 编号）、正文（浏览态 TextView + 编辑态 EditText 叠放）、底部（提示 / 清除）。
 * 卡片只负责渲染与命中判定，翻页、上浮、保存等由 MainActivity 控制。
 */
public class NoteCardView extends LinearLayout {

    public interface Listener {
        void onCardTap(NoteCardView card, String linkUrl);

        void onClear(NoteCardView card);

        /** 长按清除按钮：直接删掉当前卡片 */
        void onDeleteNote(NoteCardView card);

        /** 点击正文里的网址 */
        void onLinkTap(NoteCardView card, String url);

        void onEditFocusLost(NoteCardView card);
    }

    /**
     * 触摸观察者。子 View（ScrollView / TextView）会吃掉 DOWN 之后的 UP，
     * 所以在 dispatchTouchEvent 这一层统一观察，主界面才能收到完整的点击与滑动。
     */
    public interface TouchObserver {
        void onCardTouch(MotionEvent event);
    }

    private static final Pattern URL = Pattern.compile(
            "(?:https?://|www\\.)[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+",
            Pattern.CASE_INSENSITIVE);

    // 一次手势只允许一个方向：横向翻页，或者纵向滚正文。
    // 两者同时生效时，卡片一边横移、正文一边上下滚，看着就是卡片「抽一下」。
    private static final int AXIS_NONE = 0;
    private static final int AXIS_H = 1;
    private static final int AXIS_V = 2;
    private int axis = AXIS_NONE;
    private float axisDownX, axisDownY;
    private int axisSlop;

    private TextView label;
    private TextView indexView;
    private BodyTextView view;
    private TextView hintView;
    private View clearView;
    private EditText edit;
    private ScrollView scroll;
    private android.view.ViewGroup body;
    private android.view.ViewGroup.LayoutParams editLp;
    private int boundIdx = -1;   // 当前正文绑定的是第几张卡

    private Listener listener;
    private TouchObserver touchObserver;
    private int accent;
    private int ink;
    private int hint;
    private int muted;

    public NoteCardView(Context c) {
        this(c, null);
    }

    public NoteCardView(Context c, AttributeSet a) {
        super(c, a);
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.bg_card);
        setElevation(Ui.dp(c, 14));
        int p = Ui.dp(c, 22);
        setPadding(p, Ui.dp(c, 20), p, Ui.dp(c, 15));

        accent = getResources().getColor(R.color.accent);
        ink = getResources().getColor(R.color.ink);
        hint = getResources().getColor(R.color.hint);
        muted = getResources().getColor(R.color.muted);

        LayoutInflater.from(c).inflate(R.layout.card, this, true);
        // card.xml 的根是 <merge>，merge 分支不会触发 onFinishInflate，
        // 所以代码里 new NoteCardView() 时不会自动绑定子控件，这里必须补一次
        init();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        init();      // XML 布局方式下由系统回调
    }

    /** 幂等：XML 与代码两条创建路径都会调，只绑定一次。 */
    private void init() {
        if (label != null) return;
        label = (TextView) findViewById(R.id.label);
        indexView = (TextView) findViewById(R.id.index);
        view = (BodyTextView) findViewById(R.id.view);
        hintView = (TextView) findViewById(R.id.hint);
        clearView = (View) findViewById(R.id.clear);
        edit = (EditText) findViewById(R.id.edit);
        scroll = (ScrollView) findViewById(R.id.scroll);
        body = (android.view.ViewGroup) edit.getParent();
        editLp = edit.getLayoutParams();
        axisSlop = Math.max(1, ViewConfiguration.get(getContext()).getScaledTouchSlop());

        clearView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onClear(NoteCardView.this);
            }
        });
        clearView.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (listener != null) listener.onDeleteNote(NoteCardView.this);
                return true;
            }
        });

        edit.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus && listener != null) listener.onEditFocusLost(NoteCardView.this);
            }
        });

        edit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refreshFoot(s.toString());
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setTouchObserver(TouchObserver o) {
        touchObserver = o;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        // UP / CANCEL 必须在子 View 处理完之后再通知：
        // 可选中 TextView 会在抬手时把焦点抢过去，若我们先 enterEdit，
        // EditText 会立刻失焦并触发「编辑结束」，表现为点了没反应。
        boolean after = action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL;

        // 方向必须先定，观察者（主界面）拿到的判定才不会和子 View 打架
        if (action == MotionEvent.ACTION_DOWN) {
            axisDownX = event.getRawX();
            axisDownY = event.getRawY();
            axis = AXIS_NONE;
        } else if (action == MotionEvent.ACTION_MOVE) {
            updateAxis(event);
        }

        if (touchObserver != null && !after) {
            notifyTouch(event);
        }

        boolean handled;
        if (axis == AXIS_H && !isEditing()) {
            // 已经判定为横向翻页：事件到此为止，不再往下发给 ScrollView。
            // 否则用户在横滑时手指轻微上下偏一点，正文就会跟着滚，
            // 卡片一边位移一边滚字，看起来就是莫名其妙地抽一下。
            handled = true;
        } else {
            handled = super.dispatchTouchEvent(event);
        }

        if (touchObserver != null && after) {
            notifyTouch(event);
        }
        if (after) axis = AXIS_NONE;
        return handled;
    }

    /**
     * 定方向：超过一个 touchSlop 时，看横向位移是不是明显占优。
     * 斜着起步一律算纵向（滚正文），宁可少翻一页，也不要横竖一起动。
     */
    private void updateAxis(MotionEvent event) {
        if (axis != AXIS_NONE) return;
        // 编辑态交给输入框；正在拖选字手柄时交给系统，这两种都不能被抢
        if (isEditing() || isTextSelecting()) {
            axis = AXIS_V;
            return;
        }
        float dx = Math.abs(event.getRawX() - axisDownX);
        float dy = Math.abs(event.getRawY() - axisDownY);
        if (dx < axisSlop && dy < axisSlop) return;
        if (dx > dy * 1.3f) {
            axis = AXIS_H;
            cancelChildGesture(event);
        } else {
            axis = AXIS_V;
        }
    }

    /** 给子 View 补一个 CANCEL，让 ScrollView 立刻停手（否则它会保持按下/惯性状态）。 */
    private void cancelChildGesture(MotionEvent event) {
        try {
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
        } catch (Throwable ignored) {
        }
    }

    /** 本次手势是否已锁定为横向翻页。 */
    public boolean isHorizontalGesture() {
        return axis == AXIS_H;
    }

    /** 本次手势是否已锁定为纵向滚动正文。 */
    public boolean isVerticalGesture() {
        return axis == AXIS_V;
    }

    private void notifyTouch(MotionEvent event) {
        try {
            touchObserver.onCardTouch(event);
        } catch (Throwable t) {
            // 不再静默吞掉：手势逻辑出错必须留痕，否则界面会"没反应"却查不出原因
            android.util.Log.e("Suiji", "touch handler failed", t);
        }
    }

    public boolean isEditing() {
        return edit != null && edit.getVisibility() == VISIBLE;
    }

    /** 正文是否真的选中了一段文字（折叠光标不算）。 */
    public boolean isTextSelecting() {
        try {
            return view != null && view.hasRangeSelection();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 这一下点击是不是用来「取消选区」的。
     * 是则清掉选区并返回 true，主界面不该把这下当成「点卡片进编辑」。
     */
    public boolean consumeSelectionTap() {
        if (!isTextSelecting()) return false;
        clearBodySelection();
        return true;
    }

    /** 清掉正文选区（供主界面在新手势开始时调用）。 */
    public void clearSelection() {
        clearBodySelection();
    }

    /** 清掉正文选区：进入编辑前调用，避免退出编辑后还残留高亮。 */
    private void clearBodySelection() {
        try {
            if (view != null) view.clearSelectionQuietly();
        } catch (Throwable ignored) {
        }
    }

    public EditText editText() {
        return edit;
    }

    /** 浏览态渲染。 */
    public void bind(String text, String labelText, int idx) {
        init();      // 兜底：任何创建路径都保证控件已绑定
        // 换到另一张卡片时先把正文滚回顶部：ScrollView 会保留上一张的滚动位置，
        // 新内容先按旧位置布局再被夹回边界，看起来就是文字上下抽动一下。
        if (idx != boundIdx) {
            boundIdx = idx;
            resetBodyScroll();
        }
        label.setText(labelText);
        indexView.setText(String.valueOf(idx));

        boolean blank = TextUtils.isEmpty(text) || text.trim().length() == 0;
        if (blank) {
            // 占位提示必须走 hint：用 setText 写进去就成了真实文本，
            // 会被长按选中、复制，还会被当成「有内容」
            view.setSelectable(false);
            view.setTextColor(hint);
            view.setHintTextColor(hint);
            view.setHint("记下此刻…");
            view.setText("");
            hintView.setText("");
        } else {
            view.setHint(null);
            view.setTextColor(ink);
            view.setText(linkify(text));
            view.setSelectable(true);     // 有正文才允许长按选中
            hintView.setText(count(text) + " 字");
        }
    }

    /** 正文滚回顶部（布局前后各做一次，防止测量完成后又被旧位置带偏）。 */
    private void resetBodyScroll() {
        try {
            scroll.scrollTo(0, 0);
            scroll.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        scroll.scrollTo(0, 0);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public void beginEdit(String text) {
        clearBodySelection();
        if (edit.getParent() == null) body.addView(edit, editLp);
        edit.setText(text);
        if (!TextUtils.isEmpty(text)) edit.setSelection(text.length());
        edit.setHint("记下此刻…");
        scroll.setVisibility(GONE);
        edit.setVisibility(VISIBLE);
        edit.requestFocus();
        refreshFoot(text);
    }

    public void endEdit() {
        try {
            edit.clearComposingText();
        } catch (Exception ignored) {
        }
        edit.setText("");
        edit.setVisibility(GONE);
        // 直接从视图树摘掉输入框：某些 ROM 上它转为 GONE 会在原地留下一层残影
        if (edit.getParent() != null) {
            editLp = edit.getLayoutParams();
            body.removeView(edit);
        }
        scroll.setVisibility(VISIBLE);
        // 焦点留在可选中正文上会触发 Android 8+ 的默认焦点高亮，整块正文变灰
        view.clearFocus();
        scroll.clearFocus();
        hintView.setTextColor(hint);
        body.invalidate();
        invalidate();
    }

    public void refreshFoot(String text) {
        hintView.setTextColor(isEditing() ? muted : hint);
        String t = text == null ? "" : text;
        hintView.setText(t.trim().isEmpty() ? "" : count(t) + " 字");
    }

    /**
     * 是否点上了正文里的网址；是则弹窗询问并返回 true。
     *
     * 只靠 getOffsetForHorizontal 会把范围放得很大：
     * - 点在正文下方的空白处，getLineForVertical 仍会返回最后一行；
     * - 点在某行文字右侧的空白处，光标会吸附到行末，而 off == 链接末尾时
     *   getSpans(off, off) 依然能命中该链接。
     * 所以这里补三道判定：竖向在行内、横向在行内、且落在链接字符的横向范围内。
     */
    public boolean handleLinkTap(float screenX, float screenY) {
        if (isEditing()) return false;          // 编辑态不拦点击，交给输入框
        Layout l = view.getLayout();
        if (l == null || l.getLineCount() <= 0) return false;

        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        float x = screenX - loc[0] - view.getTotalPaddingLeft();
        float y = screenY - loc[1] - view.getTotalPaddingTop();
        if (x < 0 || y < 0) return false;

        int line = l.getLineForVertical((int) y);
        if (line < 0 || line >= l.getLineCount()) return false;

        float tol = Ui.dp(getContext(), 6);
        // 1) 竖向必须真的落在这一行的高度内，正文下方的大片空白不算
        if (y < l.getLineTop(line) - tol || y > l.getLineBottom(line) + tol) return false;
        // 2) 横向必须落在这一行文字的宽度内，行尾右侧的空白不算
        float lineLo = Math.min(l.getLineLeft(line), l.getLineRight(line));
        float lineHi = Math.max(l.getLineLeft(line), l.getLineRight(line));
        if (x < lineLo - tol || x > lineHi + tol) return false;

        int off = l.getOffsetForHorizontal(line, x);
        CharSequence cs = view.getText();
        if (!(cs instanceof Spanned)) return false;
        Spanned sp = (Spanned) cs;
        ClickableSpan[] spans = sp.getSpans(off, off, ClickableSpan.class);
        for (ClickableSpan span : spans) {
            int s = sp.getSpanStart(span);
            int e = sp.getSpanEnd(span);
            if (off < s || off > e) continue;
            // 3) 光标吸附到链接末尾时，用字符的横向范围再确认一次。
            //    链接跨行时只取「落在当前行」的那一段，避免用到另一行的坐标。
            int ls = l.getLineStart(line);
            int le = l.getLineEnd(line);
            int vs = Math.max(s, ls);
            int ve = Math.min(e, le);
            if (vs >= ve) continue;         // 这一行没有链接的字符，换下一个 span
            float x1 = l.getPrimaryHorizontal(vs);
            // 关键：链接跨行时 ve 正好是本行末尾，而 getPrimaryHorizontal(ve)
            // 拿到的是「下一行行首」的坐标（≈0）。直接拿它当右边界，
            // 区间会变成 [0, 链接起点]，于是第一行的链接怎么点都不中，
            // 只有第二行能用。这里对「落到行末」的情况改用本行文字右边界。
            float x2 = ve >= le ? l.getLineRight(line) : l.getPrimaryHorizontal(ve);
            x1 = Math.max(x1, lineLo);
            x2 = Math.min(x2, lineHi);
            float lo = Math.min(x1, x2);
            float hi = Math.max(x1, x2);
            if (x < lo - tol || x > hi + tol) continue;

            if (span instanceof LinkSpan && listener != null) {
                listener.onLinkTap(this, ((LinkSpan) span).url);
            } else {
                span.onClick(view);
            }
            return true;
        }
        return false;
    }

    public boolean isClearHit(float screenX, float screenY) {
        return hit(clearView, screenX, screenY);
    }

    public boolean containsPoint(float screenX, float screenY) {
        return hit(this, screenX, screenY);
    }

    private static boolean hit(View v, float sx, float sy) {
        if (v == null || v.getVisibility() != VISIBLE) return false;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return sx >= loc[0] && sx <= loc[0] + v.getWidth()
                && sy >= loc[1] && sy <= loc[1] + v.getHeight();
    }

    private int count(String s) {
        return s.replaceAll("\\s", "").length();
    }

    private CharSequence linkify(String text) {
        SpannableString ss = new SpannableString(text);
        Matcher m = URL.matcher(text);
        while (m.find()) {
            int s = m.start();
            int e = m.end();
            while (e > s && ".,;:!?，。；：！？、）)】」』".indexOf(text.charAt(e - 1)) >= 0) e--;
            String raw = text.substring(s, e);
            String href = raw.startsWith("http") ? raw : "http://" + raw;
            ss.setSpan(new LinkSpan(href), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    private class LinkSpan extends ClickableSpan {
        private final String url;

        LinkSpan(String url) {
            this.url = url;
        }

        @Override
        public void updateDrawState(TextPaint ds) {
            ds.setColor(accent);
            ds.setUnderlineText(true);
        }

        @Override
        public void onClick(View widget) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            } catch (Exception ignored) {
            }
        }
    }
}
