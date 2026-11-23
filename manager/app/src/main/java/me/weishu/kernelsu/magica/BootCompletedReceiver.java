package me.weishu.kernelsu.magica;

import static me.weishu.kernelsu.magica.AppZygotePreload.TAG;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import me.weishu.kernelsu.ui.util.KsuCliKt;

/**
 * 开机/解锁广播接收器：在没有 root 时启动 [MagicaService]，尝试以 late-load 方式拉起 ksud。
 * 该组件由设置项 autoJailbreak 控制启用状态。
 */
public class BootCompletedReceiver extends BroadcastReceiver {

    /** 只处理开机完成、加密存储解锁完成和内部显式 LAUNCH 广播，其余直接忽略。 */
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        var action = intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"me.weishu.kernelsu.magica.LAUNCH".equals(action)) {
            return;
        }
        // 已有 root（内核模块生效）时不需要再启动 magica 服务。
        if (KsuCliKt.rootAvailable()) return;
        try {
            context.startService(new Intent(context, MagicaService.class));
            Log.i(TAG, "MagicaService started from boot action: " + action);
        } catch (Throwable e) {

            Log.e(TAG, "Failed to start MagicaService from boot action: " + action, e);
        }
    }
}
