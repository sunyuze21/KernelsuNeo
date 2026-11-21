#!/system/bin/sh
############################################
# KernelSU 安装脚本
# 主要来自 module_installer.sh
# 以及 Magisk 中的 util_functions.sh
############################################

umask 022

# customize.sh 可设置 MODULE_HOT_INSTALL_REQUEST=true 来跳过重启
export KSU_HAS_HOT_INSTALL=true

ui_print() {
  if $BOOTMODE; then
    echo "$1"
  else
    echo -e "ui_print $1\nui_print" >> /proc/self/fd/$OUTFD
  fi
}

toupper() {
  echo "$@" | tr '[:lower:]' '[:upper:]'
}

grep_cmdline() {
  local REGEX="s/^$1=//p"
  { echo $(cat /proc/cmdline)$(sed -e 's/[^"]//g' -e 's/""//g' /proc/cmdline) | xargs -n 1; \
    sed -e 's/ = /=/g' -e 's/, /,/g' -e 's/"//g' /proc/bootconfig; \
  } 2>/dev/null | sed -n "$REGEX"
}

grep_prop() {
  local REGEX="s/$1=//p"
  shift
  local FILES=$@
  [ -z "$FILES" ] && FILES='/system/build.prop'
  cat $FILES 2>/dev/null | dos2unix | sed -n "$REGEX" | head -n 1 | xargs
}

grep_get_prop() {
  local result=$(grep_prop $@)
  if [ -z "$result" ]; then
    # 退回到 getprop
    getprop "$1"
  else
    echo $result
  fi
}

is_mounted() {
  grep -q " $(readlink -f $1) " /proc/mounts 2>/dev/null
  return $?
}

abort() {
  ui_print "$1"
  $BOOTMODE || recovery_cleanup
  [ ! -z $MODPATH ] && rm -rf $MODPATH
  rm -rf $TMPDIR
  exit 1
}

print_title() {
  local len line1len line2len bar
  line1len=$(echo -n $1 | wc -c)
  line2len=$(echo -n $2 | wc -c)
  len=$line2len
  [ $line1len -gt $line2len ] && len=$line1len
  len=$((len + 2))
  bar=$(printf "%${len}s" | tr ' ' '*')
  ui_print "$bar"
  ui_print " $1 "
  [ "$2" ] && ui_print " $2 "
  ui_print "$bar"
}

check_sepolicy() {
    /data/adb/ksud sepolicy check "$1"
    return $?
}

######################
# 环境相关
######################

setup_flashable() {
  ensure_bb
  $BOOTMODE && return
  if [ -z $OUTFD ] || readlink /proc/$$/fd/$OUTFD | grep -q /tmp; then
    # 只能手动找出 OUTFD
    for FD in `ls /proc/$$/fd`; do
      if readlink /proc/$$/fd/$FD | grep -q pipe; then
        if ps | grep -v grep | grep -qE " 3 $FD |status_fd=$FD"; then
          OUTFD=$FD
          break
        fi
      fi
    done
  fi
  recovery_actions
}

ensure_bb() {
  :
}

recovery_actions() {
  :
}

recovery_cleanup() {
  :
}

#######################
# 安装相关
#######################

# find_block [分区名...]
find_block() {
  local BLOCK DEV DEVICE DEVNAME PARTNAME UEVENT
  for BLOCK in "$@"; do
    DEVICE=`find /dev/block \( -type b -o -type c -o -type l \) -iname $BLOCK | head -n 1` 2>/dev/null
    if [ ! -z $DEVICE ]; then
      readlink -f $DEVICE
      return 0
    fi
  done
  # 退回到解析 sysfs uevent 的方式
  for UEVENT in /sys/dev/block/*/uevent; do
    DEVNAME=`grep_prop DEVNAME $UEVENT`
    PARTNAME=`grep_prop PARTNAME $UEVENT`
    for BLOCK in "$@"; do
      if [ "$(toupper $BLOCK)" = "$(toupper $PARTNAME)" ]; then
        echo /dev/block/$DEVNAME
        return 0
      fi
    done
  done
  # 只在 /dev 下查找，以应对没有 /dev/block 设备/链接的 MTD/NAND
  for DEV in "$@"; do
    DEVICE=`find /dev \( -type b -o -type c -o -type l \) -maxdepth 1 -iname $DEV | head -n 1` 2>/dev/null
    if [ ! -z $DEVICE ]; then
      readlink -f $DEVICE
      return 0
    fi
  done
  return 1
}

# setup_mntpoint <挂载点>
setup_mntpoint() {
  local POINT=$1
  [ -L $POINT ] && mv -f $POINT ${POINT}_link
  if [ ! -d $POINT ]; then
    rm -f $POINT
    mkdir -p $POINT
  fi
}

# mount_name <分区名> <挂载点> <标志>
mount_name() {
  local PART=$1
  local POINT=$2
  local FLAG=$3
  setup_mntpoint $POINT
  is_mounted $POINT && return
  # 先尝试按 fstab 挂载
  mount $FLAG $POINT 2>/dev/null
  if ! is_mounted $POINT; then
    local BLOCK=$(find_block $PART)
    mount $FLAG $BLOCK $POINT || return
  fi
  ui_print "- Mounting $POINT"
}

# mount_ro_ensure <分区名> <挂载点>
mount_ro_ensure() {
  # 只在 recovery 中处理只读分区
  $BOOTMODE && return
  local PART=$1
  local POINT=$2
  mount_name "$PART" $POINT '-o ro'
  is_mounted $POINT || abort "! Cannot mount $POINT"
}

mount_partitions() {
  # 检查 A/B 槽位
  SLOT=`grep_cmdline androidboot.slot_suffix`
  if [ -z $SLOT ]; then
    SLOT=`grep_cmdline androidboot.slot`
    [ -z $SLOT ] || SLOT=_${SLOT}
  fi
  [ -z $SLOT ] || ui_print "- Current boot slot: $SLOT"

  # 挂载只读分区
  if is_mounted /system_root; then
    umount /system 2&>/dev/null
    umount /system_root 2&>/dev/null
  fi
  mount_ro_ensure "system$SLOT app$SLOT" /system
  if [ -f /system/init -o -L /system/init ]; then
    SYSTEM_ROOT=true
    setup_mntpoint /system_root
    if ! mount --move /system /system_root; then
      umount /system
      umount -l /system 2>/dev/null
      mount_ro_ensure "system$SLOT app$SLOT" /system_root
    fi
    mount -o bind /system_root/system /system
  else
    SYSTEM_ROOT=false
    grep ' / ' /proc/mounts | grep -qv 'rootfs' || grep -q ' /system_root ' /proc/mounts && SYSTEM_ROOT=true
  fi
  # /vendor 只在部分旧设备的 recovery AVBv1 签名中用到，挂载失败不影响主要流程
  [ -L /system/vendor ] && mount_name vendor$SLOT /vendor '-o ro'
  $SYSTEM_ROOT && ui_print "- Device is system-as-root"

  # 在 recovery 中挂载 sepolicy 规则目录（尽力而为）
  if ! $BOOTMODE; then
    mount_name "cache cac" /cache
    mount_name metadata /metadata
    mount_name persist /persist
  fi
}

api_level_arch_detect() {
  API=$(grep_get_prop ro.build.version.sdk)
  ABI=$(grep_get_prop ro.product.cpu.abi)
  if [ "$ABI" = "x86" ]; then
    ARCH=x86
    ABI32=x86
    IS64BIT=false
  elif [ "$ABI" = "arm64-v8a" ]; then
    ARCH=arm64
    ABI32=armeabi-v7a
    IS64BIT=true
  elif [ "$ABI" = "x86_64" ]; then
    ARCH=x64
    ABI32=x86
    IS64BIT=true
  elif [ "$ABI" = "riscv64" ]; then
    ARCH=riscv64
    ABI32=
    IS64BIT=true
  else
    ARCH=arm
    ABI=armeabi-v7a
    ABI32=armeabi-v7a
    IS64BIT=false
  fi
}

#################
# 模块相关
#################

check_managed_features() {
  local PROP_FILE=$1
  local MANAGED_FEATURES=$(grep_prop managedFeatures "$PROP_FILE")

  [ -z "$MANAGED_FEATURES" ] && return 0

  ui_print "- Checking managed features: $MANAGED_FEATURES"

  # 按逗号拆分特性
  echo "$MANAGED_FEATURES" | tr ',' '\n' | while read -r feature; do
    # 去掉首尾空白
    feature=$(echo "$feature" | xargs)
    [ -z "$feature" ] && continue

    # 通过 ksud 检查特性状态
    local status=$(/data/adb/ksud feature check "$feature" 2>/dev/null)

    case "$status" in
      "unsupported")
        ui_print "! WARNING: Feature '$feature' is NOT SUPPORTED by kernel"
        ui_print "!          This module may not work correctly!"
        ;;
      "managed")
        ui_print "! WARNING: Feature '$feature' is already MANAGED by another module"
        ui_print "!          Feature conflicts may occur!"
        ;;
      "supported")
        ui_print "- Feature '$feature' is supported and available"
        ;;
      *)
        ui_print "! WARNING: Unable to check feature '$feature' status"
        ;;
    esac
  done
}

set_perm() {
  chown $2:$3 $1 || return 1
  chmod $4 $1 || return 1
  local CON=$5
  [ -z $CON ] && CON=u:object_r:system_file:s0
  chcon $CON $1 || return 1
}

set_perm_recursive() {
  find $1 -type d 2>/dev/null | while read dir; do
    set_perm $dir $2 $3 $4 $6
  done
  find $1 -type f -o -type l 2>/dev/null | while read file; do
    set_perm $file $2 $3 $5 $6
  done
}

mktouch() {
  mkdir -p ${1%/*} 2>/dev/null
  [ -z $2 ] && touch $1 || echo $2 > $1
  chmod 644 $1
}

mark_remove() {
  mkdir -p ${1%/*} 2>/dev/null
  mknod $1 c 0 0
  chmod 644 $1
}

request_size_check() {
  reqSizeM=`du -ms "$1" | cut -f1`
}

request_zip_size_check() {
  reqSizeM=`unzip -l "$1" | tail -n 1 | awk '{ print int(($1 - 1) / 1048576 + 1) }'`
}

boot_actions() { return; }

# 要求已设置 ZIPFILE
is_legacy_script() {
  unzip -l "$ZIPFILE" install.sh | grep -q install.sh
  return $?
}

handle_partition() {
    # 若 /system/vendor 是符号链接，需要把它移出 $MODPATH/system
    # 若 /system/vendor 是普通目录，则无需特殊处理。
    if [ ! -e $MODPATH/system/$1 ]; then
        # 没有找到该分区
        return;
    fi

    # 只有当它是真实目录（而非符号链接）时，才把它移动到 /
    if [ -d "/$1" ] && [ ! -L "/$1" ]; then
        ui_print "- Handle partition /$1"
        # 模块若想访问 $MODPATH/system/$1，需要创建符号链接
        # 但这并不总是可行（例如在 post-fs-data.sh 中写入会因只读而失败）
        mv -f $MODPATH/system/$1 $MODPATH/$1 && ln -sf ../$1 $MODPATH/system/$1
    fi
}

# 要求已设置 OUTFD、ZIPFILE
install_module() {
  rm -rf $TMPDIR
  mkdir -p $TMPDIR
  chcon u:object_r:system_file:s0 $TMPDIR
  cd $TMPDIR

  mount_partitions
  api_level_arch_detect

  # 准备 busybox 和二进制文件
  if $BOOTMODE; then
    boot_actions
  else
    recovery_actions
  fi

  # 解压 prop 文件
  unzip -o "$ZIPFILE" module.prop -d $TMPDIR >&2
  [ ! -f $TMPDIR/module.prop ] && abort "! Unable to extract zip file!"

  local MODDIRNAME=modules
  $BOOTMODE && MODDIRNAME=modules_update
  local MODULEROOT=$NVBASE/$MODDIRNAME
  MODID=`grep_prop id $TMPDIR/module.prop`
  MODNAME=`grep_prop name $TMPDIR/module.prop`
  MODAUTH=`grep_prop author $TMPDIR/module.prop`
  MODPATH=$MODULEROOT/$MODID

  # 检查受管理的特性
  check_managed_features $TMPDIR/module.prop

  # 创建模块路径
  rm -rf $MODPATH
  mkdir -p $MODPATH

  if is_legacy_script; then
    unzip -oj "$ZIPFILE" module.prop install.sh uninstall.sh 'common/*' -d $TMPDIR >&2

    # 加载安装脚本
    . $TMPDIR/install.sh

    # 回调
    print_modname
    on_install

    [ -f $TMPDIR/uninstall.sh ] && cp -af $TMPDIR/uninstall.sh $MODPATH/uninstall.sh
    $SKIPMOUNT && touch $MODPATH/skip_mount
    $PROPFILE && cp -af $TMPDIR/system.prop $MODPATH/system.prop
    cp -af $TMPDIR/module.prop $MODPATH/module.prop
    $POSTFSDATA && cp -af $TMPDIR/post-fs-data.sh $MODPATH/post-fs-data.sh
    $LATESTARTSERVICE && cp -af $TMPDIR/service.sh $MODPATH/service.sh

    ui_print "- Setting permissions"
    set_permissions
  else
    print_title "$MODNAME" "by $MODAUTH"
    print_title "Powered by KernelSU"

    unzip -o "$ZIPFILE" customize.sh -d $MODPATH >&2

    if ! grep -q '^SKIPUNZIP=1$' $MODPATH/customize.sh 2>/dev/null; then
      ui_print "- Extracting module files"
      unzip -o "$ZIPFILE" -x 'META-INF/*' -d $MODPATH >&2

      # 默认权限
      set_perm_recursive $MODPATH 0 0 0755 0644
      set_perm_recursive $MODPATH/system/bin 0 2000 0755 0755
      set_perm_recursive $MODPATH/system/xbin 0 2000 0755 0755
      set_perm_recursive $MODPATH/system/system_ext/bin 0 2000 0755 0755
      set_perm_recursive $MODPATH/system/vendor 0 2000 0755 0755 u:object_r:vendor_file:s0
    fi

    # 加载定制脚本
    [ -f $MODPATH/customize.sh ] && . $MODPATH/customize.sh
  fi

  # 处理替换目录
  for TARGET in $REPLACE; do
    ui_print "- Replace target: $TARGET"
    mark_replace $MODPATH$TARGET
  done

  # 处理删除文件
  for TARGET in $REMOVE; do
    ui_print "- Remove target: $TARGET"
    mark_remove $MODPATH$TARGET
  done

  handle_partition vendor
  handle_partition system_ext
  handle_partition product
  handle_partition odm

  if $BOOTMODE; then
    mktouch $NVBASE/modules/$MODID/update
    rm -rf $NVBASE/modules/$MODID/remove 2>/dev/null
    rm -rf $NVBASE/modules/$MODID/disable 2>/dev/null
    cp -af $MODPATH/module.prop $NVBASE/modules/$MODID/module.prop

    # 通知 ksud 立即应用该模块
    if [ "$MODULE_HOT_INSTALL_REQUEST" = "true" ]; then
      echo "$MODULE_HOT_RUN_SCRIPT" > $MODPATH/.ksu_hot_install
    else
      rm -f $MODPATH/.ksu_hot_install
    fi
  fi

  # 删除不属于模块的内容，并清理空目录
  rm -rf \
  $MODPATH/system/placeholder $MODPATH/customize.sh \
  $MODPATH/README.md $MODPATH/.git*
  rmdir -p $MODPATH 2>/dev/null

  cd /
  $BOOTMODE || recovery_cleanup
  rm -rf $TMPDIR

  ui_print "- Done"
}

##########
# 预设
##########

# 检测是否处于 boot 模式
[ -z $BOOTMODE ] && ps | grep zygote | grep -qv grep && BOOTMODE=true
[ -z $BOOTMODE ] && ps -A 2>/dev/null | grep zygote | grep -qv grep && BOOTMODE=true
[ -z $BOOTMODE ] && BOOTMODE=false

NVBASE=/data/adb
TMPDIR=/dev/tmp
POSTFSDATAD=$NVBASE/post-fs-data.d
SERVICED=$NVBASE/service.d

# 部分模块依赖于此
export MAGISK_VER=25.2
export MAGISK_VER_CODE=25200
