# 高德地图去广告 LSPosed 模块

针对高德地图 Android 版（适配 17.00.0.2009）的去广告 / 界面净化 LSPosed 模块。

## 功能

**首页 Tab**
- 移除搜索栏下方整个快捷图标区（驾车/公交地铁/租车/打车/订酒店/火车票机票/顺风车/扫街榜等），下方内容自动上移补位
- 保留 回家/去单位/常去地点 一行
- 移除回家行以下所有推荐内容
- 移除地图上的“扫街榜”浮标
- 移除地图底部“来领十一出行补贴”弹窗

**底栏**
- 只保留 首页 / 我的，隐藏 探索 / AI助手 / 打车

**我的 Tab**
- 整栏移除 订单 / 收藏 / 待评价
- 订单收藏窗体中移除 钱包卡券 / 借钱
- 移除 达人任务 及以下所有功能
- 移除 dock 栏上方“开启通知权限”弹窗

**开屏广告**
- 检测到“跳过”按钮自动点击跳过

## 已知限制

- 首页/我的页被清除的空白区域，AJX 框架仍可能按内部布局数据把点击路由给已隐藏的条目
  （如误入火车票/扫街榜/达人页面）。模块已加入空白区域点按拦截，可缓解但受框架
  自绘事件分发限制，无法保证 100% 消除。

## 原理

- 挂钩 `ViewGroup.addView` / `View.setVisibility` / `TextView.setText` / AJX 自绘 Label 的文本设置方法，
  在视图挂载与动态文本更新时按规则集匹配并隐藏目标视图。
- `slice` 规则类型：以锚点文本定位列表项块，整段隐藏连续的兄弟项并把后续内容上移补位，
  解决 AJX 绝对布局隐藏后留空洞的问题；同时把回家行动态钉扎在面板顶部 + 固定间隔处，
  面板收起/展开时间隔跟随，不会错位。
- `card` 规则类型：隐藏卡片内指定行，并同步收缩卡片祖先高度、下方内容上移补位。
- 空白区域点按拦截：记录被清理容器，点按命中其无可见内容覆盖区域时吞掉该手势，
  拖动/滚动不受影响。
- 支持通过 JSON 文件外置规则（`/sdcard/Download/amap_adblock.json`），修改后广播 `reload` 热生效。
- 内置调试广播 `am broadcast -a com.amap.adblocker.CMD --es cmd dump`，
  可导出当前窗口视图树用于适配新版本。

## 安装

1. 手机需要 root 并安装 LSPosed（Zygisk 版）
2. 安装 `out/AmapAdBlock.apk`
3. 在 LSPosed 管理器中启用模块，作用域勾选「高德地图」
4. 强制停止高德地图后重新打开

## 构建

无 Gradle，纯命令行构建，需要 JDK 17 + Android SDK build-tools 34：

```bash
bash build.sh
```

## 适配其他版本

不同版本高德的视图结构可能变化。打开高德后执行：

```bash
adb shell "am broadcast -a com.amap.adblocker.CMD --es cmd deep"
adb pull /data/data/com.autonavi.minimap/files/amap_adblock_dump.txt
```

根据视图树修改 `RuleSet.java` 中的内置规则，或外置 JSON 规则热调。

## 声明

仅供个人学习与个性化定制使用，请勿用于商业用途。
