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

    private TextView label;
    private TextView indexView;
    private TextView view;
    private TextView hintView;
    private View clearView;
    private EditText edit;
    private ScrollView scroll;
    private android.view.ViewGroup body;
    private android.view.ViewGroup.LayoutParams editLp;

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
        view = (TextView) findViewById(R.id.view);
        hintView = (TextView) findViewById(R.id.hint);
        clearView = (View) findViewById(R.id.clear);
        edit = (EditText) findViewById(R.id.edit);
        scroll = (ScrollView) findViewById(R.id.scroll);
        body = (android.view.ViewGroup) edit.getParent();
        editLp = edit.getLayoutParams();

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
        if (touchObserver != null) {
            try {
                touchObserver.onCardTouch(event);
            } catch (Throwable t) {
                // 不再静默吞掉：手势逻辑出错必须留痕，否则界面会"没反应"却查不出原因
                android.util.Log.e("Suiji", "touch handler failed", t);
            }
        }
        return super.dispatchTouchEvent(event);
    }

    public boolean isEditing() {
        return edit != null && edit.getVisibility() == VISIBLE;
    }

    public EditText editText() {
        return edit;
    }

    /** 浏览态渲染。 */
    public void bind(String text, String labelText, int idx) {
        init();      // 兜底：任何创建路径都保证控件已绑定
        label.setText(labelText);
        indexView.setText(String.valueOf(idx));

        if (TextUtils.isEmpty(text) || text.trim().length() == 0) {
            view.setTextColor(hint);
            view.setText("记下此刻…");
            hintView.setText("");
        } else {
            view.setTextColor(ink);
            view.setText(linkify(text));
            hintView.setText(count(text) + " 字");
        }
    }

    public void beginEdit(String text) {
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
        hintView.setTextColor(hint);
        body.invalidate();
        invalidate();
    }

    public void refreshFoot(String text) {
        hintView.setTextColor(isEditing() ? muted : hint);
        String t = text == null ? "" : text;
        hintView.setText(t.trim().isEmpty() ? "" : count(t) + " 字");
    }

    /** 是否点上了正文里的网址；是则直接打开并返回 true。 */
    public boolean handleLinkTap(float screenX, float screenY) {
        if (isEditing()) return false;          // 编辑态不拦点击，交给输入框
        if (view.getLayout() == null) return false;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        float x = screenX - loc[0] - view.getTotalPaddingLeft();
        float y = screenY - loc[1] - view.getTotalPaddingTop();
        if (x < 0 || y < 0) return false;

        Layout l = view.getLayout();
        int line = l.getLineForVertical((int) y);
        if (line < 0 || line >= l.getLineCount()) return false;
        int off = l.getOffsetForHorizontal(line, x);
        CharSequence cs = view.getText();
        if (!(cs instanceof Spanned)) return false;
        Spanned sp = (Spanned) cs;
        ClickableSpan[] spans = sp.getSpans(off, off, ClickableSpan.class);
        if (spans.length == 0) return false;
        if (spans[0] instanceof LinkSpan && listener != null) {
            listener.onLinkTap(this, ((LinkSpan) spans[0]).url);
        } else {
            spans[0].onClick(view);
        }
        return true;
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
