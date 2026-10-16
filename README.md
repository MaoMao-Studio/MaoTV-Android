# 猫TV（Android 版）

iOS 版「猫TV」的 Android 移植：Kotlin + Jetpack Compose，双播放内核（libVLC / Media3），手机和电视盒子通用（遥控器 + 触摸）。
包名 `com.cncat.maotv`，版本 4.0，minSdk 23（Android 6.0）。

## 一、编译（GitHub Actions，不需要本地环境）

1. 把整个工程推送到你的私有仓库（`main` 分支）。**不会自动编译**：到仓库的 Actions 页 → 左侧选 “Build APK” → Run workflow，手动触发。
2. 当前只编译 **arm64-v8a**（测试阶段）。编译完成后在该次运行页面底部 Artifacts 下载 `MaoTV-apk`。
   问题都解决后再把 v7a 加回来：`app/build.gradle.kts` 里 `include("arm64-v8a")` 改成 `include("arm64-v8a", "armeabi-v7a")`。
3. 编译失败时把 Actions 日志里红色的报错行发给我。

## 二、在 GitHub 上签名（V1 + V2 + V3 同时开启）

**方式 A（推荐，一次点击）**：Actions 页 → 左侧选 “生成签名密钥（只需运行一次）” → Run workflow。
它会在仓库里创建 `signing/maotv.jks` 和 `signing/signing.properties`（随机密码）。之后每次 “Build APK” 都自动用它签名。
> 密钥和密码都放在你的**私有仓库**里，请不要把仓库改成公开；请自己备份 `signing/` 目录——换了密钥后，已安装的旧版必须卸载才能装新版。

**方式 B（更安全）**：自己用 keytool / MT 管理器生成 .jks，把文件做 base64 后放进仓库 Secrets：
`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。Secrets 优先于 `signing/` 目录。

构建日志里的 “Verify signature” 一步会用 apksigner 打印 V1 / V2 / V3 是否都已启用。
没有密钥时输出的是 unsigned APK，可以像以前一样用 MT 管理器签（勾选 V2、V3 和 zipalign）。

## 三、服务器端

- `connect_test.php`：放在原来 tvios.php 同目录，里面填你自己的私钥（私钥不要给任何人）。
- 客户端里的公钥写在 `app/src/main/java/com/cncat/maotv/net/ServerProbe.kt`（`PUBLIC_KEY_B64`）。
- 接口地址：`tvandroid.php`（前半段 URL 不变），见 `net/Api.kt`。

## 四、操作方式

遥控器：
| 按键 | 作用 |
|---|---|
| 上 / 下 | 画面状态：换台（“换台反转”设置可对调）；选单状态：移动光标 |
| 左 / 右 | 画面状态：音量 ∓5%；选单里在 分类 → 频道 → ☆ → 节目单把手 → 节目单 之间移动 |
| 确定（短按） | 画面状态：呼出选单；选单里：选台 / 收藏 / 展开节目单 / 按下按钮 |
| 确定（长按） | 直接把光标放到右下角按钮行 |
| 菜单键 | 呼出选单（光标在按钮行）/ 在选单里切换 列表⇄按钮行 / 关闭弹出菜单 |
| 返回 | 逐级收回；画面状态下 3 秒内按两次退出（提示“再按一次退出猫TV”） |
| 数字键 | 输入台号换台（见下） |

数字选台：按数字键时若左侧选单已呼出，先收回；右上角显示已输入的数字，最多 3 位（频道总数更多时按位数放宽）。
每按一个数字重新计时 3 秒，3 秒无操作后换台；按“确定”立即换台；按“返回”取消。前导 0 忽略（012 → 12、005 → 5）。
台号不存在时弹出“您输入的频道不存在”，不换台。

触摸：点画面呼出 / 收起选单；上下滑换台；左右滑调音量；竖屏下半屏是分类 → 频道列表，我的收藏里按住 1 秒可拖动排序。

界面：
- 横屏：沉浸式全屏；选单呼出时右上角显示当前时间（HH:mm:ss，圆角半透明底框），与右下角按钮同时出现 / 消失，右边缘与按钮行最右侧按钮对齐。
- 竖屏：顶部显示系统状态栏，画面在其下方（不重叠）；不显示“台号 + 频道名 + 节目”信息条，音量条 / 提示条仍然显示。

## 五、重要说明（请读）

- **这套代码是在没有安卓编译环境的沙箱里写成的，没有真正编译过、更没有在设备上运行过。** 我只做了：逐个核对 libVLC 3.6.0 / Media3 1.5.0 / Compose 的 API 源码、用 Kotlin 编译器检查语法。所以第一次在 Actions 上编译很可能有几处类型 / 引用错误，这是正常的，请把日志发给我，一般几轮就能修干净。
- 依赖 `org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1` 如果解析不到，删掉 `app/build.gradle.kts` 里那一行即可（代码用反射加载），只是 Media3 内核就解不了 MP2 / AC3；VLC 内核不受影响。
- 帧率匹配、画中画、后台播放、HTTPS 证书放行的真实效果依赖具体设备和系统，需要实机验证。
- 字幕样式（字号 / 描边 / 字距 / 位置）是按你给的 1080p 截图估算的参数，可能需要实机微调（见 `player/Engine.kt` 里的 `SubtitleStyle`）。
