package me.weishu.kernelsu.magica;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import androidx.annotation.Nullable;

/**
 * 空的占位 Service：开机广播通过 startService 唤醒进程，真正的 root 工作由
 * [AppZygotePreload] 在 zygote 预加载阶段 fork 出的 ksud 完成。
 */
public class MagicaService extends Service {
    /** 返回一个普通 Binder，仅用于满足 Service 契约；调用方不使用该接口。 */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new Binder();
    }
}
