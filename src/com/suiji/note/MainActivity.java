package com.suiji.note;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 「随记」主界面。
 *
 * 窗口用系统 Dialog 主题（theme 里 windowIsFloating=true），因此：
 *   · 打开时不走全屏 Activity 的启动缩放，桌面背景完全不收缩；
 *   · 背景不调暗（backgroundDimEnabled=false），壁纸和其他图标都看得见；
 *   · 卡片只是一个悬浮在桌面上的白色圆角面板。
 *
 * 卡片有两种位置：静息在下半屏，编辑时升到上半屏（键盘之上）。
 */
public class MainActivity extends Activity implements NoteCardView.Listener {

    private static final float IDLE_RATIO = 0.44f;        // 静息卡片高度占比（竖屏）
    private static final float IDLE_RATIO_LAND = 0.70f;   // 横屏纵向空间紧，卡片占满大半屏

    private static final String PREF = "suiji_prefs";
    private static final String KEY_SP = "body_sp";

    private FrameLayout root;
    private NoteCardView card;
    private View pill;
    private View scrim;
    private LinearLayout panel;
    private LinearLayout panelList;
    private ScrollView panelScroll;
    private TextView panelCount;
    private TextView panelAdd;

    private NoteStore store;
    private boolean editing;
    private boolean drawerOpen;
    private boolean imeShown;
    private boolean destroying;

    private float geo;           // 0 = 下半屏，1 = 上半屏
    private android.animation.ValueAnimator geoAnim;

    private int cardSide, idleTop, idleHeight, bottomReserve;
    private int fullH;           // 未弹键盘时的可用高度
    private int insetTop, insetBottom;
    private int imeHeight;       // 键盘高度（API30+ 由 insets 给出）
    private long editStartedAt;
    private float bodySp = 17f;  // 用户设定/捏合出来的正文字号

    /** 键盘收回后延迟收卡：避免键盘弹出/收回过程中的抖动误判。 */
    private final Runnable endOnImeHidden = new Runnable() {
        @Override
        public void run() {
            if (editing && imeHeight == 0 && !imeShown) endEdit();
        }
    };

    private float downX, downY, lastX;
    private long lastMoveT;
    private long downT;
    private float vx;
    private boolean swiping;
    /** 本次按下已经达到长按阈值：抬手时不再当成「点卡片进编辑」。 */
    private boolean longPressFired;
    private final Runnable longPressMark = new Runnable() {
        @Override
        public void run() {
            longPressFired = true;
        }
    };
    private int touchSlop;

    private float outX, outY;

    // ---- 水平列表式翻页：相邻卡片以真实视图挂在屏幕外，跟手一起滑动 ----
    private NoteCardView peer;   // 目标卡片的替身
    private int pagerDir;        // 当前翻页方向：0 = 无，+1 下一张，-1 上一张
    private int lastIme = -1;    // 上一帧键盘高度：用于判断弹出 / 收起方向

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        noTransition(true);   // 冷启动不播系统「图标展开」缩放，窗口直接出现
        try {
            store = new NoteStore(this);
            setupWindow();
            setContentView(R.layout.activity_main);
            applyWindowSize();
            bindViews();
            bodySp = getSharedPreferences(PREF, MODE_PRIVATE).getFloat(KEY_SP, 17f);
            card.setBodyTextSize(bodySp);
            computeMetrics();
            bindCurrent();
            setupTouch();
            root.post(new Runnable() {
                @Override
                public void run() {
                    layoutIdle();
                    styleSystemBars();
                    // 稍等一下再淡入：给系统的启动转场留出时间走完，
                    // 这段时间窗口是透明的，画面上只有桌面，看不到任何缩放。
                    root.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            fadeInWindow();
                        }
                    }, 40);
                }
            });
            // 兜底：万一淡入没跑起来，一秒后强制显形
            root.postDelayed(new Runnable() {
                @Override
                public void run() {
                    showWindowNow();
                }
            }, 1000);
        } catch (Throwable t) {
            SuijiApp.writeCrash(this, t);
            showWindowNow();
            showFallback(t);
        }
    }

    /**
     * 关掉这个 Activity 的系统级进出转场（主题里也已经把 activity/task/wallpaper
     * 转场置空，这里是按调用时机再兜一层）。窗口内容自己的 card_in / card_out
     * 淡入淡出不受影响。
     */
    private void noTransition(boolean opening) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(
                        opening ? OVERRIDE_TRANSITION_OPEN : OVERRIDE_TRANSITION_CLOSE, 0, 0);
            } else {
                overridePendingTransition(0, 0);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- window

    private void setupWindow() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0f);

        if (Build.VERSION.SDK_INT >= 30) {
            // 高版本：自己接管系统栏与键盘占位，窗口不再被系统压缩，
            // 键盘高度从 WindowInsets 里稳定拿到，卡片位置可精确计算。
            try {
                w.setDecorFitsSystemWindows(false);
                w.setStatusBarColor(Color.TRANSPARENT);
                w.setNavigationBarColor(Color.TRANSPARENT);
            } catch (Throwable ignored) {
            }
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        } else {
            // 低版本：交给系统压缩窗口（adjustResize），用根布局高度当可用高度。
            // 不要加 STATE_ALWAYS_HIDDEN，否则点开卡片后键盘弹不出来。
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        // 窗口先以全透明出现：冷启动时系统不管播什么转场（图标展开 / 窗口缩放 /
        // 壁纸拉近），这期间画面上都看不见任何卡片，等转场走完再由我们自己淡入。
        try {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.alpha = 0f;
            w.setAttributes(lp);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 卡片淡入。窗口级 alpha（不是 View 的），系统的窗口转场也会乘这个值，
     * 所以哪怕系统还在缩放窗口，看到的也只是透明。
     */
    private void fadeInWindow() {
        final Window w = getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        if (lp.alpha >= 1f) return;
        android.animation.ValueAnimator a =
                android.animation.ValueAnimator.ofFloat(lp.alpha, 1f);
        a.setDuration(190);
        a.setInterpolator(new DecelerateInterpolator(1.8f));
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator anim) {
                try {
                    WindowManager.LayoutParams p = w.getAttributes();
                    p.alpha = (Float) anim.getAnimatedValue();
                    w.setAttributes(p);
                } catch (Throwable ignored) {
                }
            }
        });
        a.start();
    }

    /** 兜底：任何情况下都不能让窗口停在透明状态。 */
    private void showWindowNow() {
        try {
            WindowManager.LayoutParams p = getWindow().getAttributes();
            if (p.alpha != 1f) {
                p.alpha = 1f;
                getWindow().setAttributes(p);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 编辑态卡片的顶端（根布局坐标）。 */
    private int editTopPx() {
        return insetTop + Ui.dp(this, 10);
    }

    /**
     * 必须放在 setContentView 之后：Dialog 主题的 installDecor 会把窗口尺寸重置成
     * wrap_content，如果在那之前设成 MATCH_PARENT 会被覆盖，窗口就只剩内容那么大。
     */
    private void applyWindowSize() {
        Window w = getWindow();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.width = dm.widthPixels;
        lp.height = dm.heightPixels;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.dimAmount = 0f;
        lp.horizontalMargin = 0f;
        lp.verticalMargin = 0f;
        lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        try {
            w.setAttributes(lp);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 状态栏图标颜色跟着壁纸走：浅色壁纸用深色图标，深色壁纸用白色图标。
     * 全程 try/catch —— 任何一步失败都不能影响启动。
     */
    private void styleSystemBars() {
        if (Build.VERSION.SDK_INT < 30) return;
        int appearance = 0;
        try {
            if (isLightWallpaper()) {
                appearance = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
            }
        } catch (Throwable ignored) {
        }
        try {
            View decor = getWindow().getDecorView();
            android.view.WindowInsetsController c = decor.getWindowInsetsController();
            if (c != null) {
                c.setSystemBarsAppearance(appearance,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isLightWallpaper() {
        if (Build.VERSION.SDK_INT < 27) return false;
        try {
            android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(this);
            android.app.WallpaperColors wc = wm.getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM);
            if (wc == null || wc.getPrimaryColor() == null) return false;
            int c = wc.getPrimaryColor().toArgb();
            double lum = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0;
            return lum > 0.65;
        } catch (Throwable t) {
            return false;
        }
    }

    private void showFallback(Throwable t) {
        try {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundColor(0xFFFDFCFF);
            box.setPadding(Ui.dp(this, 24), Ui.dp(this, 60), Ui.dp(this, 24), Ui.dp(this, 24));
            TextView tv = new TextView(this);
            tv.setTextColor(0xFF1B1B1F);
            tv.setTextSize(13);
            tv.setText("随记启动失败\n\n" + Build.MANUFACTURER + " " + Build.MODEL
                    + " / Android " + Build.VERSION.RELEASE
                    + "\n\n" + android.util.Log.getStackTraceString(t));
            box.addView(tv);
            setContentView(box);
        } catch (Throwable ignored) {
        }
    }

    // ----------------------------------------------------------------- views

    private void bindViews() {
        root = (FrameLayout) findViewById(R.id.root);
        card = (NoteCardView) findViewById(R.id.card);
        pill = (View) findViewById(R.id.pill);
        scrim = findViewById(R.id.scrim);
        panel = (LinearLayout) findViewById(R.id.panel);
        panelList = (LinearLayout) findViewById(R.id.panel_list);
        panelScroll = (ScrollView) findViewById(R.id.panel_scroll);
        panelCount = (TextView) findViewById(R.id.panel_count);
        panelAdd = (TextView) findViewById(R.id.panel_add);
        panelAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addFromDrawer();
            }
        });
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        card.setListener(this);
    }

    private void computeMetrics() {
        cardSide = Ui.dp(this, 16);
        // 竖屏下方要留出桌面图标的位置，胶囊抬高 84dp；横屏高度只有一半，
        // 再照搬这套留白就是白白浪费三分之一屏，所以胶囊贴底、卡片紧跟着往下长。
        boolean land = isLandscape();
        int pillBottom = insetBottom + Ui.dp(this, land ? 16 : 84);
        int pillGap = Ui.dp(this, land ? 10 : 12);
        bottomReserve = pillBottom + Ui.dp(this, 38) + pillGap;

        FrameLayout.LayoutParams plp = (FrameLayout.LayoutParams) pill.getLayoutParams();
        plp.bottomMargin = pillBottom;
        pill.setLayoutParams(plp);

        // 横屏 / 平板上抽屉不拉满整屏，居中限宽更像一张面板
        if (panel != null) {
            int w = root.getWidth();
            if (w <= 0) w = getResources().getDisplayMetrics().widthPixels;
            int pw = Math.min(w, Ui.dp(this, 560));
            FrameLayout.LayoutParams panelLp = (FrameLayout.LayoutParams) panel.getLayoutParams();
            if (panelLp.width != pw || panelLp.gravity != (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL)) {
                panelLp.width = pw;
                panelLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                panel.setLayoutParams(panelLp);
            }
        }
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    /** 静息态的几何：可用高度、卡片高度、卡片顶边。 */
    private void computeIdleGeometry(int h) {
        fullH = h;
        boolean land = isLandscape();
        // 横屏纵向空间本来就紧，顶部不必再留 40dp 的呼吸位
        int minTop = Ui.dp(this, land ? 20 : 40);
        // 横屏高度本就紧张：先保证卡片不压到底部胶囊，再谈高度占比。
        // 不这么夹一下，idleTop 会被 minTop 顶下去，卡片底边直接盖住胶囊。
        int room = h - bottomReserve - minTop;
        int want = Math.round(h * (land ? IDLE_RATIO_LAND : IDLE_RATIO));
        idleHeight = Math.max(Ui.dp(this, 120), Math.min(want, room));
        idleTop = Math.max(minTop, h - bottomReserve - idleHeight);
    }

    /**
     * 卡片横向摆放：竖屏几乎占满宽度；横屏把宽度收在 640dp 以内并居中，
     * 否则一张卡会被拉成横贯整屏的长条，一行几十个字，很难读。
     */
    private void applyCardBox(FrameLayout.LayoutParams lp) {
        int w = root.getWidth();
        if (w <= 0) w = getResources().getDisplayMetrics().widthPixels;
        // 横屏可以再宽一点：一行字数仍在舒适区间，卡片也不至于缩在屏中间
        int maxW = Ui.dp(this, isLandscape() ? 640 : 560);
        int cw = Math.max(1, Math.min(w - cardSide * 2, maxW));
        lp.gravity = Gravity.TOP;
        lp.width = cw;
        lp.leftMargin = Math.max(0, (w - cw) / 2);
        lp.rightMargin = 0;
    }

    private void applyInsets(int top, int bottom) {
        insetTop = top;
        insetBottom = Math.min(bottom, Ui.navBarHeight(this));
        computeMetrics();
    }

    private int availableH() {
        int h = root.getHeight();
        if (h > 0) return h;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return Math.max(320, dm.heightPixels - insetTop - insetBottom
                - Ui.statusBarHeight(this) - Ui.navBarHeight(this));
    }

    /** 静息位置：卡片贴在下半屏，下方留出「全部记录」和桌面图标的位置。 */
    private void layoutIdle() {
        if (editing) return;
        int h = availableH();
        if (h <= 0) return;
        computeIdleGeometry(h);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        applyCardBox(lp);
        lp.topMargin = idleTop;
        lp.height = idleHeight;
        card.setLayoutParams(lp);
        card.setTranslationY(0f);
        geo = 0f;
    }

    /**
     * 旋转 / 分屏尺寸变化后重新摆一遍（configChanges 自己处理，不重建 Activity，
     * 所以正在输入的文字和键盘都不会丢）。
     */
    private void relayoutForConfig() {
        if (card == null) return;
        int h = availableH();
        if (h <= 0) return;
        computeIdleGeometry(h);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        applyCardBox(lp);
        lp.topMargin = idleTop;
        if (editing) {
            card.setCompactEdit(isLandscape());
            // 编辑态保持「顶到状态栏下、底边贴键盘」，宽度跟着新屏幕重算
            int room = h - editTopPx() - Ui.dp(this, 20);
            lp.height = Math.max(Ui.dp(this, 180), Math.min(lp.height, room));
            card.setLayoutParams(lp);
            card.setTranslationY(editTopPx() - idleTop);
            geo = 1f;
            clampHeight(h);
        } else {
            lp.height = idleHeight;
            card.setLayoutParams(lp);
            card.setTranslationY(0f);
            geo = 0f;
        }
        computeMetrics();
    }

    /** 编辑态高度：卡片顶到状态栏下方，底边贴着键盘上沿。 */
    private void clampHeight(int h) {
        if (idleHeight <= 0 || h <= 0) return;
        int top = editTopPx();
        int usable = h - imeHeight;                       // 键盘以上的可用高度
        boolean land = isLandscape();
        // 横屏时键盘占掉大半屏，不能再用竖屏那套下限，否则卡片会被顶到键盘底下
        int minH = Ui.dp(this, land ? 110 : 180);
        int gap = Ui.dp(this, land ? 10 : 40);
        int maxH = Math.max(minH, usable - top - gap);
        int target;
        if (imeHeight > 0) {
            // 知道键盘高度：铺满键盘上方，像一张真正的书写纸
            target = Math.min(Math.round(fullH * 0.7f), maxH);
        } else {
            // 拿不到键盘高度（旧系统）：保持原高度，只上移
            target = Math.min(idleHeight, maxH);
        }

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        if (Math.abs(lp.height - target) > 2) {
            lp.height = target;
            card.setLayoutParams(lp);
        }
    }

    private boolean busy() {
        return geoAnim != null && geoAnim.isRunning();
    }

    private void animateGeo(final float to) {
        if (geoAnim != null) geoAnim.cancel();
        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        final int fromH = lp.height;
        final int toH = (to < 0.5f && idleHeight > 0) ? idleHeight : fromH;
        geoAnim = android.animation.ValueAnimator.ofFloat(geo, to);
        geoAnim.setDuration(to > geo ? 330 : 250);
        geoAnim.setInterpolator(to > geo
                ? new DecelerateInterpolator(1.7f)
                : new AccelerateInterpolator(1.2f));
        geoAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                geo = (Float) a.getAnimatedValue();
                card.setTranslationY((editTopPx() - idleTop) * geo);
                // 下落途中同步收高度，缩小与位移是同一段动画，视觉才连贯
                if (fromH != toH) {
                    int h = Math.round(fromH + (toH - fromH) * (1f - geo));
                    if (lp.height != h) {
                        lp.height = h;
                        card.setLayoutParams(lp);
                    }
                }
            }
        });
        geoAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (editing && imeHeight > 0) clampHeight(availableH());
            }
        });
        geoAnim.start();
    }

    // ----------------------------------------------------------------- state

    private void bindCurrent() {
        NoteStore.Note n = store.current();
        boolean blank = NoteStore.isBlank(n);
        String label = blank ? "新的记录" : Ui.timeLabel(n.updated);
        card.bind(n.text, label, store.getIndex() + 1);
    }

    private void enterEdit() {
        if (editing) return;
        editing = true;
        imeShown = false;
        card.removeCallbacks(endOnImeHidden);
        editStartedAt = System.currentTimeMillis();
        card.setTranslationX(0f);
        card.animate().cancel();
        card.setAlpha(1f);
        card.setCompactEdit(isLandscape());     // 横屏键盘吃掉半屏，收起页眉页脚
        card.beginEdit(store.current().text);
        card.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (editing) Ui.showKeyboard(card.editText());
            }
        }, 70);
        card.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (editing && imeHeight == 0) Ui.showKeyboard(card.editText());
            }
        }, 650);
        animateGeo(1f);
        pill.animate().cancel();
        pill.animate().alpha(0f).translationY(Ui.dp(this, 10)).setDuration(150).start();
    }

    /** 把编辑框里的内容写回内存 + 落盘（不改变编辑状态）。 */
    private void saveEdit() {
        if (!editing) return;
        String txt = card.editText().getText().toString();
        NoteStore.Note n = store.current();
        if (!txt.equals(n.text)) {
            n.text = txt;
            if (!txt.trim().isEmpty()) n.updated = System.currentTimeMillis();
            store.save();
        }
    }

    /** 失去焦点 / 收起：保存并落回静息态（空白卡保留，不删除）。 */
    private void endEdit() {
        if (!editing) return;
        card.removeCallbacks(endOnImeHidden);
        String txt = card.editText().getText().toString();
        NoteStore.Note n = store.current();
        n.text = txt;
        if (!txt.trim().isEmpty()) n.updated = System.currentTimeMillis();
        store.save();

        editing = false;
        card.editText().clearFocus();
        card.endEdit();
        card.setCompactEdit(false);
        bindCurrent();          // 浏览态重新渲染（网址下划线、时间、字数）

        // 先启动下落动画：此时 busy()=true，insets 变化不会触发 layoutIdle 把卡片瞬移，
        // 高度收缩也在动画里逐帧完成，不再"先突然缩小再掉下"
        animateGeo(0f);
        Ui.hideKeyboard(card.editText());
        pill.animate().cancel();
        pill.animate().alpha(1f).translationY(0f).setDuration(200).start();
    }

    private void clearCurrent() {
        if (editing) {
            card.editText().setText("");
            return;
        }
        store.current().text = "";
        store.save();
        bindCurrent();
        Ui.haptic(card);
    }

    // ------------------------------------------------------------ navigation

    /** 边界回弹：卡片带着跟手位移弹回原位。 */
    private void bounceBack() {
        card.animate().cancel();
        destroyPeer();
        card.animate().translationX(0f)
                .setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .start();
    }

    /** 出屏所需位移：卡片宽度 + 侧边距，保证整张卡完全离开屏幕。 */
    private int pageOff() {
        return Math.max(1, card.getWidth()) + cardSide;
    }

    /** 翻页方向 dir 上是否存在可滑向的相邻卡片（末尾非空白时新建也算存在）。 */
    private boolean hasNeighbor(int dir) {
        int i = store.getIndex();
        if (dir < 0) return i > 0;
        if (i + 1 < store.size()) return true;
        return !NoteStore.isBlank(store.current());   // 末尾空白卡：拒绝再新建
    }

    /** 创建相邻卡片的替身视图，摆在屏幕外 dir 侧，等待跟手滑入。 */
    private void startPager(int dir) {
        if (peer != null || pagerDir != 0 || !hasNeighbor(dir)) return;
        try {
            int i = store.getIndex();
            peer = new NoteCardView(this);
            peer.setBodyTextSize(bodySp);          // 相邻卡与当前卡字号一致
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    card.getWidth(), card.getHeight());
            plp.leftMargin = card.getLeft();       // 卡片可能居中（横屏），不能用固定边距
            plp.topMargin = card.getTop() + Math.round(card.getTranslationY());
            peer.setLayoutParams(plp);
            if (dir > 0 && i + 1 >= store.size()) {
                peer.bind("", "新的记录", i + 2);          // 即将新建的空白卡预览
            } else {
                NoteStore.Note t = store.notes().get(i + dir);
                peer.bind(t.text, NoteStore.isBlank(t) ? "新的记录" : Ui.timeLabel(t.updated),
                        i + dir + 1);
            }
            peer.setTranslationX(dir * pageOff());
            root.addView(peer, 1, plp);                    // 真卡之上、胶囊之下
            pagerDir = dir;
        } catch (Throwable t) {
            android.util.Log.e("Suiji", "startPager failed", t);
            destroyPeer();                                  // 不留下半初始化的替身
        }
    }

    private void destroyPeer() {
        if (peer != null) {
            root.removeView(peer);
            peer = null;
        }
        pagerDir = 0;
    }

    /** 松手后两卡同步滑到终点（commit）或滑回原位（回弹）。 */
    private void settle(final int dir, final boolean commit) {
        final int off = pageOff();
        card.animate().cancel();
        if (peer != null) peer.animate().cancel();
        long dur = commit ? 230 : 220;
        card.animate()
                .translationX(commit ? -dir * off : 0f)
                .setDuration(dur)
                .setInterpolator(commit
                        ? new DecelerateInterpolator(1.0f)
                        : new DecelerateInterpolator(1.8f))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (commit) {
                            commitPage(dir);
                        } else {
                            destroyPeer();
                            card.setTranslationX(0f);
                        }
                    }
                }).start();
        if (peer != null) {
            peer.animate()
                    .translationX(commit ? 0f : dir * off)
                    .setDuration(dur)
                    .setInterpolator(commit
                            ? new DecelerateInterpolator(1.0f)
                            : new DecelerateInterpolator(1.8f))
                    .start();
        }
    }

    /** 数据层落位：切换索引 / 新建 / 删除滑走的空白卡，然后刷新真卡内容。 */
    private void commitPage(int dir) {
        final int i = store.getIndex();
        final boolean blank = NoteStore.isBlank(store.current());
        final boolean isNewNote = dir > 0 && i + 1 >= store.size() && !blank;
        int target;
        if (isNewNote) {
            store.addNote();
            target = store.size() - 1;
        } else if (blank && store.size() > 1) {
            store.removeAt(i);                         // 空白卡滑走时删除
            target = Math.max(0, Math.min(dir > 0 ? i : i - 1, store.size() - 1));
        } else {
            target = Math.max(0, Math.min(i + dir, store.size() - 1));
        }
        store.setIndex(target);
        store.save();
        destroyPeer();
        card.setTranslationX(0f);
        card.setAlpha(1f);
        bindCurrent();
        // 新建后停在浏览态，等用户自己点进来写
    }

    // ----------------------------------------------------------------- touch

    private void setupTouch() {
        card.setTouchObserver(new NoteCardView.TouchObserver() {
            @Override
            public void onCardTouch(MotionEvent e) {
                handleCardTouch(e);
            }
        });


        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (drawerOpen) return true;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        // 必须在 DOWN 就消费掉，root 才会成为触摸目标，
                        // 否则后面的 UP 永远派发不到这里，退出逻辑收不到事件
                        outX = e.getRawX();
                        outY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP:
                        boolean moved = Math.abs(e.getRawX() - outX) > touchSlop
                                || Math.abs(e.getRawY() - outY) > touchSlop;
                        if (moved) return true;
                        if (card.containsPoint(e.getRawX(), e.getRawY())) return true;
                        if (hitView(pill, e.getRawX(), e.getRawY())) return true;
                        // 点在卡片外的桌面上：编辑态收卡，静息态直接退出
                        if (editing) {
                            endEdit();
                        } else {
                            noTransition(false);   // 退出时也不播窗口缩回的转场
                            finish();
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                applyInsets(top, bottom);
                int ime = 0;
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                    } catch (Throwable ignored) {
                    }
                }
                if (ime != imeHeight) {
                    boolean wasUp = imeHeight > 0;
                    imeHeight = ime;
                    if (editing) {
                        if (ime > 0) {
                            imeShown = true;
                            // 键盘收起过程中（高度递减）不拉高卡片：
                            // 否则卡片会在下落动画开始前先"长高"，看起来像先变形再掉落
                            if (lastIme < 0 || ime >= lastIme) clampHeight(availableH());
                        } else if ((wasUp || imeShown)
                                && System.currentTimeMillis() - editStartedAt > 350) {
                            // 键盘被收回（按返回键等）：等同于失去焦点，收卡保存
                            imeShown = false;
                            card.removeCallbacks(endOnImeHidden);
                            card.postDelayed(endOnImeHidden, 150);
                        }
                    }
                }
                lastIme = ime;
                if (!editing && !busy()) layoutIdle();
                return insets;
            }
        });

        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int orr, int ob) {
                int h = b - t;
                if (h <= 0) return;
                if (!editing) {
                    if (h != fullH && !busy()) layoutIdle();
                    return;
                }
                clampHeight(h);
                boolean nowIme = h < fullH - Ui.dp(MainActivity.this, 80);
                if (nowIme) {
                    imeShown = true;
                    card.removeCallbacks(endOnImeHidden);
                } else if (imeShown && System.currentTimeMillis() - editStartedAt > 350) {
                    imeShown = false;
                    card.removeCallbacks(endOnImeHidden);
                    card.postDelayed(endOnImeHidden, 150);
                }
            }
        });

        pill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDrawer();
            }
        });

        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDrawer();
            }
        });

        findViewById(R.id.panel_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDrawer();
            }
        });
    }

    /** 卡片上的手势：水平列表式翻页（跟手），轻点进入编辑。 */
    private void handleCardTouch(MotionEvent e) {
        if (drawerOpen) return;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (editing) {
                    downT = 0;
                    return;
                }
                // 新手势先清掉上一次留下的文字选区：
                // 否则 MOVE 会一直命中「正在选字」分支，卡片从此滑不动
                card.clearSelection();
                downX = e.getRawX();
                downY = e.getRawY();
                lastX = downX;
                lastMoveT = e.getEventTime();
                downT = System.currentTimeMillis();
                vx = 0f;
                swiping = false;
                longPressFired = false;
                card.removeCallbacks(longPressMark);
                card.postDelayed(longPressMark, 420);   // 超过 420ms 视为长按（选字）
                destroyPeer();                      // 清掉上一次翻页可能残留的替身
                card.animate().cancel();
                // 上一次翻页动画被这一下按下打断时，卡片可能停在半路。
                // 不归位的话，后面的位移是相对这个歪掉的位置算的，看起来就是"抖一下"。
                card.setTranslationX(0f);
                card.setAlpha(1f);
                return;

            case MotionEvent.ACTION_MOVE:
                if (editing || downT == 0) return;
                if (card.isTextSelecting()) {       // 真正选中了文字：不翻页、不拖卡片
                    return;
                }
                float mx = e.getRawX();
                float dx = mx - downX;
                float dy = e.getRawY() - downY;
                long dt = Math.max(1, e.getEventTime() - lastMoveT);
                vx = (mx - lastX) / dt;
                lastX = mx;
                lastMoveT = e.getEventTime();
                if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                    card.removeCallbacks(longPressMark);
                }

                // 方向锁：一次手势只做一件事。
                // 已经锁定为纵向（用户想看正文的上下部分）就不再翻页，
                // 否则卡片会带着横向位移跟手滑，正文又同时在滚，看着像卡片抽了一下。
                if (card.isVerticalGesture()) return;
                if (!swiping && card.isHorizontalGesture()) {
                    swiping = true;
                }
                if (!swiping) return;
                int want = dx > 0 ? -1 : 1;     // 右拖看上一张，左拖看下一张
                if (pagerDir != 0 && want != pagerDir && Math.abs(dx) > touchSlop) {
                    destroyPeer();              // 拖动中途反向：换目标卡
                }
                if (pagerDir == 0) startPager(want);
                float f = pagerDir != 0 ? 1f : 0.3f;   // 没有相邻卡时边界阻尼
                card.setTranslationX(dx * f);
                if (peer != null) {
                    peer.setTranslationX(dx * f + pagerDir * pageOff());
                }
                return;

            case MotionEvent.ACTION_UP:
                card.removeCallbacks(longPressMark);
                if (editing || downT == 0) return;
                float ddx = e.getRawX() - downX;
                float ddy = e.getRawY() - downY;
                downT = 0;

                if (swiping) {
                    int w = Math.max(1, card.getWidth());
                    boolean fast = Math.abs(vx) > 0.55f;
                    final int dir = pagerDir != 0 ? pagerDir : (ddx < 0 ? 1 : -1);
                    if (peer != null) {
                        boolean complete = Math.abs(ddx) > w * 0.3f || fast;
                        settle(dir, complete);
                    } else {
                        bounceBack();               // 边界：没有相邻卡可滑
                    }
                } else if (!longPressFired
                        && Math.abs(ddx) < touchSlop && Math.abs(ddy) < touchSlop) {
                    // 短按一律进编辑：不再因为「存在选区」而吞掉这次点击。
                    // 长按（>=420ms）已由 longPressFired 标记，交给系统做选字。
                    handleTap(e.getRawX(), e.getRawY());
                }
                return;

            case MotionEvent.ACTION_CANCEL:
                card.removeCallbacks(longPressMark);
                downT = 0;
                if (peer != null) {
                    settle(pagerDir, false);
                } else if (swiping) {
                    bounceBack();
                }
                return;
        }
    }

    private void handleTap(float x, float y) {
        if (busy()) return;                     // 落卡 / 翻页动画途中不响应，避免状态错乱
        if (card.isClearHit(x, y)) return;      // 让清除按钮自己处理
        if (card.handleLinkTap(x, y)) return;   // 点到网址：弹窗询问
        Ui.haptic(card);
        enterEdit();
    }

    private static boolean hitView(View v, float sx, float sy) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return sx >= loc[0] && sx <= loc[0] + v.getWidth()
                && sy >= loc[1] && sy <= loc[1] + v.getHeight();
    }

    // ---------------------------------------------------------------- drawer

    private void openDrawer() {
        if (drawerOpen) return;
        if (editing) endEdit();
        buildTiles(true);
        drawerOpen = true;
        panel.animate().cancel();
        int ph = panel.getHeight();
        if (ph <= 0) {
            int w = Math.max(1, root.getWidth());
            panel.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            ph = panel.getMeasuredHeight();
        }
        panel.setTranslationY(ph);
        panel.setVisibility(View.VISIBLE);
        panel.animate().translationY(0f).setDuration(270)
                .setInterpolator(new DecelerateInterpolator(1.7f)).start();
        scrim.setVisibility(View.VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(210).start();
        pill.animate().cancel();
        pill.animate().alpha(1f).setDuration(150).start();
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        panel.animate().cancel();
        panel.animate().translationY(panel.getHeight()).setDuration(200)
                .setInterpolator(new AccelerateInterpolator(1.2f))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        panel.setVisibility(View.INVISIBLE);
                    }
                }).start();
        scrim.animate().alpha(0f).setDuration(180).withEndAction(new Runnable() {
            @Override
            public void run() {
                scrim.setVisibility(View.GONE);
            }
        }).start();
    }

    private void buildTiles(boolean scrollToCurrent) {
        panelList.removeAllViews();
        int n = store.size();
        panelCount.setText(n + " 条");
        for (int i = 0; i < n; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = Ui.dp(this, 12);
            row.setLayoutParams(rlp);
            row.addView(makeTile(i), tileLp(true));
            if (i + 1 < n) {
                row.addView(makeTile(i + 1), tileLp(false));
            } else {
                View gap = new View(this);
                row.addView(gap, tileLp(false));
            }
            panelList.addView(row);
        }
        final int curRow = store.getIndex() / 2;
        final int keepScroll = panelScroll.getScrollY();
        panelScroll.post(new Runnable() {
            @Override
            public void run() {
                if (panelScroll.getHeight() <= 0) return;
                if (scrollToCurrent) {
                    View row = panelList.getChildAt(curRow);
                    if (row != null) {
                        panelScroll.scrollTo(0, Math.max(0, row.getTop() - Ui.dp(MainActivity.this, 6)));
                    }
                } else {
                    panelScroll.scrollTo(0, keepScroll);
                }
            }
        });
        panelScroll.post(new Runnable() {
            @Override
            public void run() {
                int max = Math.round(availableH() * 0.62f);
                int header = Ui.dp(MainActivity.this, 14 + 4 + 16 + 30);
                if (panel.getHeight() > max) {
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)
                            panelScroll.getLayoutParams();
                    lp.height = Math.max(Ui.dp(MainActivity.this, 160), max - header);
                    panelScroll.setLayoutParams(lp);
                }
            }
        });
    }

    private LinearLayout.LayoutParams tileLp(boolean left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, Ui.dp(this, 108), 1f);
        if (left) lp.rightMargin = Ui.dp(this, 6);
        else lp.leftMargin = Ui.dp(this, 6);
        return lp;
    }

    private View makeTile(final int idx) {
        NoteStore.Note n = store.notes().get(idx);
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackgroundResource(idx == store.getIndex() ? R.drawable.bg_tile_now : R.drawable.bg_tile);
        int p = Ui.dp(this, 14);
        tile.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 12));
        tile.setClickable(true);
        tile.setFocusable(true);

        TextView num = new TextView(this);
        num.setText(String.valueOf(idx + 1));
        num.setTextSize(11);
        num.setTextColor(getResources().getColor(
                idx == store.getIndex() ? R.color.accent : R.color.faint));
        num.setLetterSpacing(0.16f);
        num.setIncludeFontPadding(false);
        tile.addView(num);

        TextView body = new TextView(this);
        body.setText(NoteStore.isBlank(n) ? "空白记录" : Ui.preview(n.text));
        body.setTextSize(13);
        body.setTextColor(getResources().getColor(
                NoteStore.isBlank(n) ? R.color.hint : R.color.ink));
        body.setMaxLines(3);
        body.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        blp.topMargin = Ui.dp(this, 7);
        tile.addView(body, blp);

        tile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                confirmDeleteTile(idx);
                return true;
            }
        });
        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDrawer();
                // 切换走时当前卡仍空白：自动删除（索引会因删除而偏移，需要修正）
                int target = idx;
                int cur = store.getIndex();
                if (target != cur && NoteStore.isBlank(store.current())
                        && store.size() > 1) {
                    store.removeAt(cur);
                    if (target > cur) target--;
                }
                if (target != store.getIndex()) {
                    store.setIndex(target);
                    store.save();
                    bindCurrent();
                    card.setAlpha(0f);
                    card.animate().alpha(1f).setDuration(220).start();
                }
            }
        });
        return tile;
    }

    /** 抽屉里长按某条记录：确认后直接删除该卡片。 */
    private void confirmDeleteTile(final int idx) {
        if (store.size() <= 1) {
            android.widget.Toast.makeText(this, "只剩这一条了", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("删除第 " + (idx + 1) + " 条记录？")
                    .setMessage("删除后无法恢复。")
                    .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            deleteTile(idx);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            deleteTile(idx);
        }
    }

    private void deleteTile(int idx) {
        int cur = store.getIndex();
        boolean deletingCurrent = (idx == cur);
        store.removeAt(idx);
        if (deletingCurrent) {
            store.setIndex(Math.max(0, Math.min(idx - 1, store.size() - 1)));
        } else if (idx < cur) {
            store.setIndex(cur - 1);
        }
        store.save();
        bindCurrent();
        Ui.haptic(card);
        android.widget.Toast.makeText(this, "已删除", android.widget.Toast.LENGTH_SHORT).show();
        if (deletingCurrent) {
            card.setAlpha(0f);
            card.animate().alpha(1f).setDuration(200).start();
        }
        if (drawerOpen) buildTiles(false);
        else panelCount.setText(store.size() + " 条");
    }

    /** 抽屉里的「新建」：末尾追加一张空白卡并跳过去。 */
    private void addFromDrawer() {
        closeDrawer();
        if (NoteStore.isBlank(store.current())) {
            // 已经站在一张空白卡上：只剩这一张就什么都不做，否则先按规则清掉它
            if (store.size() <= 1) return;
            store.removeAt(store.getIndex());
        }
        store.addNote();
        store.setIndex(store.size() - 1);
        store.save();
        bindCurrent();
        card.setAlpha(0f);
        card.animate().alpha(1f).setDuration(220).start();
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void onCardTap(NoteCardView c, String linkUrl) {
        if (linkUrl == null) enterEdit();
    }

    @Override
    public void onClear(NoteCardView c) {
        clearCurrent();
    }

    /** 长按清除按钮：删掉当前卡片并退到上一张；只剩一张时忽略。 */
    @Override
    public void onDeleteNote(NoteCardView c) {
        if (editing || store.size() <= 1) return;
        int i = store.getIndex();
        store.removeAt(i);
        store.setIndex(Math.max(0, i - 1));
        store.save();
        bindCurrent();
        Ui.haptic(card);
        card.setAlpha(0f);
        card.animate().alpha(1f).setDuration(180).start();
    }

    /** 点正文里的网址：先问一句，不直接跳浏览器。 */
    @Override
    public void onLinkTap(NoteCardView c, final String url) {
        final String[] items = {"复制链接", "在浏览器中打开"};
        try {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(url)
                    .setItems(items, new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int which) {
                            if (which == 0) copyLink(url);
                            else openLink(url);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            openLink(url);          // 弹窗失败兜底：直接打开
        }
    }

    private void copyLink(String url) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("链接", url));
            android.widget.Toast.makeText(this, "已复制链接", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private void openLink(String url) {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onEditFocusLost(NoteCardView c) {
        if (editing) endEdit();
    }

    // ------------------------------------------------------------ 双指缩放

    @Override
    public void onPinchStart() {
        // 第二根手指落下时，单指手势可能已经走了几步：把翻页、长按全部收掉
        card.removeCallbacks(longPressMark);
        downT = 0;
        swiping = false;
        card.clearSelection();
        if (peer != null) {
            settle(pagerDir, false);
        } else if (Math.abs(card.getTranslationX()) > 1f) {
            bounceBack();
        }
    }

    @Override
    public void onPinchEnd(float sp) {
        bodySp = sp;
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putFloat(KEY_SP, sp).apply();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            if (drawerOpen) closeDrawer();
            if (geoAnim != null) geoAnim.cancel();
            // 旧方向下的键盘高度对新方向没有意义，先清掉，等 insets 重发
            imeHeight = 0;
            lastIme = -1;
            if (editing) editStartedAt = System.currentTimeMillis();
            applyWindowSize();
            // 新尺寸要等这一帧布局完才生效，推到下一帧再摆
            if (root != null) {
                root.post(new Runnable() {
                    @Override
                    public void run() {
                        relayoutForConfig();
                    }
                });
            }
        } catch (Throwable t) {
            android.util.Log.e("Suiji", "config change failed", t);
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        if (editing) {
            endEdit();
            return;
        }
        if (card.isTextSelecting()) {   // 正在选字：这一下只取消选区，不退出应用
            card.clearSelection();
            return;
        }
        // 不用 super.onBackPressed()：那走的是系统 back 导航，会连带把 task 一起移除，
        // 下次点图标就是全新 task，系统必播启动展开动画。这里用应用自己的 finish()，
        // 与「点卡片外空白」完全同一条路径——那种退出方式本来就没有动画。
        noTransition(false);
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveEdit();
        store.save();
    }

    @Override
    protected void onDestroy() {
        destroying = true;
        if (geoAnim != null) geoAnim.cancel();
        saveEdit();
        store.save();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) saveEdit();
    }
}
