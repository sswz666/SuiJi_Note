package com.suiji.note;

import android.app.Application;
import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 启动时挂一个兜底异常处理器：任何未捕获异常都会写一份文本日志到应用私有目录。
 * 这样即使界面没能起来，也能从文件里看出原因（也用于界面上显示诊断信息）。
 */
public class SuijiApp extends Application {

    static final String CRASH_FILE = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                writeCrash(SuijiApp.this, e);
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    static void writeCrash(Context c, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date()));
            pw.println("device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                    + " / Android " + android.os.Build.VERSION.RELEASE
                    + " (API " + android.os.Build.VERSION.SDK_INT + ")");
            pw.println();
            e.printStackTrace(pw);
            pw.flush();
            File f = new File(c.getFilesDir(), CRASH_FILE);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(sw.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    static String readCrash(Context c) {
        try {
            File f = new File(c.getFilesDir(), CRASH_FILE);
            if (!f.exists() || f.length() <= 0) return null;
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int n = in.read(b);
            in.close();
            if (n <= 0) return null;
            return new String(b, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    static void clearCrash(Context c) {
        try {
            new File(c.getFilesDir(), CRASH_FILE).delete();
        } catch (Throwable ignored) {
        }
    }
}
