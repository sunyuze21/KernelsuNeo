package me.weishu.kernelsu.magica;

import android.app.ZygotePreload;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;

/**
 * App Zygote 预加载入口：在应用进程 fork 前把 ksud 以独立进程拉起，
 * 用于 late-load / “magica” 场景在没有内核模块的情况下获得 root。
 */
public class AppZygotePreload implements ZygotePreload {
    public static final String TAG = "KernelSUMagica";

    /** 由 native 层实现：fork 一个不关心结果的子进程并 exec 指定 ksud。 */
    private static native void forkDontCareAndExecKsud(String ksudPath, String packageName);

    /**
     * 预加载回调。必须先在当前进程加载 libkernelsu，再调用 native 方法，
     * 否则符号未解析会直接失败；异常只记录日志，不影响应用启动。
     */
    @Override
    public void doPreload(@NonNull ApplicationInfo appInfo) {
        File f = new File(appInfo.nativeLibraryDir, "libksud.so");
        try {
            System.loadLibrary("kernelsu");
            Log.d(TAG, "executing magica ...");
            forkDontCareAndExecKsud(f.getAbsolutePath(), appInfo.packageName);
        } catch (Throwable t) {
            Log.e(TAG, "failed to late load", t);
        }
    }
}
