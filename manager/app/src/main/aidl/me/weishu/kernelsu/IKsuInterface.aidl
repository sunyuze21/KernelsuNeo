// IKsuInterface.aidl
// 跨进程查询接口：Manager 以 root/shell 身份代其他进程读取已安装应用与用户列表，
// 避免调用方自行持有 PackageManager 权限或受 SELinux 限制。
package me.weishu.kernelsu;

import android.content.pm.PackageInfo;
import rikka.parcelablelist.ParcelableListSlice;

// 服务端实现需保证返回的 PackageInfo 与 flags 过滤条件一致，
// 且只暴露调用方有权查看的应用，防止信息泄露。
interface IKsuInterface {
    // 按 PackageManager 的 flags 返回已安装应用切片；使用切片是为了绕开 Binder 1MB 事务上限。
    ParcelableListSlice<PackageInfo> getPackages(int flags);

    // 返回设备上存在 Android 用户 ID，供多用户场景枚举工作资料等。
    int[] getUserIds();
}