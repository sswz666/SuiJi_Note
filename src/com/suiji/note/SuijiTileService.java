package com.suiji.note;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 控制中心（下拉快捷设置）里的「随记」入口。
 *
 * 磁贴是一次性动作，不是开关：点一下就把卡片叫到桌面上来。
 * 手动添加路径：下拉通知栏 → 编辑磁贴 → 把「随记」拖进常用区。
 */
public class SuijiTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        // 部分 ROM 会沿用上一次的状态，这里强制回到「可点击」的常态
        try {
            Tile t = getQsTile();
            if (t != null) {
                t.setState(Tile.STATE_INACTIVE);
                t.updateTile();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        // 锁屏状态下直接拉起透明窗口会被系统拦掉，先请用户解锁再打开
        if (isLocked()) {
            try {
                unlockAndRun(new Runnable() {
                    @Override
                    public void run() {
                        launch();
                    }
                });
            } catch (Throwable t) {
                launch();       // 个别 ROM 上解锁流程不可用，退化成直接拉起
            }
        } else {
            launch();
        }
    }

    private void launch() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14 起 Intent 版本已废弃，改用 PendingIntent
                PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pi);
            } else {
                startActivityAndCollapse(i);
            }
        } catch (Throwable t) {
            // 个别 ROM 的磁贴权限收得紧：退化成普通启动，至少别点了没反应
            try {
                startActivity(i);
            } catch (Throwable ignored) {
            }
        }
    }
}
