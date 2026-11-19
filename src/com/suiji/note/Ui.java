package com.suiji.note;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import java.util.Calendar;

/**
 * 一些没有地方放的小工具。
 */
final class Ui {

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static void showKeyboard(final View v) {
        if (v == null) return;
        try {
            InputMethodManager imm = (InputMethodManager) v.getContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                v.requestFocus();
                imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
            }
        } catch (Exception ignored) {
        }
    }

    static void hideKeyboard(View v) {
        if (v == null) return;
        try {
            InputMethodManager imm = (InputMethodManager) v.getContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
    }

    static void haptic(View v) {
        if (v == null) return;
        try {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        } catch (Exception ignored) {
        }
    }

    static int systemBarHeight(Context c, String name, int fallbackDp) {
        try {
            int id = c.getResources().getIdentifier(name, "dimen", "android");
            if (id > 0) return c.getResources().getDimensionPixelSize(id);
        } catch (Exception ignored) {
        }
        return dp(c, fallbackDp);
    }

    static int statusBarHeight(Context c) {
        return systemBarHeight(c, "status_bar_height", 26);
    }

    static int navBarHeight(Context c) {
        return systemBarHeight(c, "navigation_bar_height", 48);
    }

    /** 顶部小标题：今天 14:56 / 昨天 21:03 / 11月18日 */
    static String timeLabel(long time) {
        if (time <= 0) return "新的记录";
        Calendar now = Calendar.getInstance();
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(time);

        if (sameDay(now, t)) {
            return String.format("今天 %02d:%02d", t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE));
        }
        Calendar y = Calendar.getInstance();
        y.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(y, t)) {
            return String.format("昨天 %02d:%02d", t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE));
        }
        if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR)) {
            return (t.get(Calendar.MONTH) + 1) + "月" + t.get(Calendar.DAY_OF_MONTH) + "日";
        }
        return t.get(Calendar.YEAR) + "年" + (t.get(Calendar.MONTH) + 1) + "月"
                + t.get(Calendar.DAY_OF_MONTH) + "日";
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /** 抽屉预览用：压掉多余空白，只留一行可读的开头 */
    static String preview(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        while (t.contains("  ")) t = t.replace("  ", " ");
        return t;
    }
}
