# MiDuo
折叠屏上的 Duo 风格桌面。由 **D.JT** 在 Duo Launcher 基础上持续开发。

**官网与正式下载：[mymiduo.xyz](https://mymiduo.xyz/)**  
**本源码快照：1.0.2（versionCode 50）**。公开源码版本可能滞后官网版本，请分别查看 tag 和官网版本号。

## 体验与授权
- 无需付费即可安装并打开桌面试用。设置修改、设为默认桌面、无障碍/全面屏手势配置及图标/文件夹编辑等需要官方授权；试用不等于全部功能免费。
- 官方授权为单设备永久授权，首次激活联网绑定设备；不支持自助换绑。价格和活动以官网为准，授权不能解除 HyperOS 系统限制。
- 本项目由技术爱好者开发，当前仍有 bug。**先在自己的机型试用，再决定购买**。反馈请使用 Issues 或官网小红书群。

## 1.0.2 主要能力
- 右侧四位 Dock，内外屏桌面与 Duo 风格时间、日历、天气占位组件。
- 跟随折叠的玻璃投影动画，可在设置中关闭；1.0.2 **不覆盖系统锁屏**。
- 外屏横置 StandBy，默认关闭；内屏不启用此模式。
- 横向 3×3 文件夹分页、系统 AppWidget、桌面编辑与壁纸设置。
- 可选本机无线调试配对与导航设置辅助；不是 Root，也不保证所有 HyperOS 版本可用。

天气目前为示例数据；部分组件点击、SIM 信号精度和应用打开/返回动效仍需改进。系统应用窗口动画和安全锁屏仍由系统控制。

## 安装
Android 12（API 31）及以上。重点适配小米折叠屏，不代表所有机型兼容。
从官网或本仓库正式 Release 下载；核对 [1.0.2 说明与 SHA256](docs/releases/1.0.2.md)。
正式包、旧测试包和自行编译包可能签名不同，**不要为了覆盖安装而卸载或清除数据**，先处理布局与授权迁移。

[使用说明](docs/user-guide.md) · [隐私与权限](PRIVACY.md) · [构建与发布](docs/public-release.md) · [代码结构](docs/architecture.md)

## 源码与官方发行版
公开客户端源码采用 [MIT](LICENSE)，保留上游及 [第三方声明](THIRD_PARTY_NOTICES.md)。允许依许可证学习、修改和再分发；商业收费不改变这些权利。
D.JT 为 MiDuo 修改部分作者，不主张对上游代码的原创所有权。
MiDuo 名称和官方签名不表示任何第三方构建受官方认可；重新分发时请清楚标明来源与修改，不冒充官方。

授权签发服务、商城后台、发行签名密钥及用户数据不属于本客户端源码发布。
客户端只包含验签公钥和激活请求逻辑。公钥不是秘密；不公开私钥并不意味着客户端绝对无法被修改绕过。
源码许可不授予使用官方私钥、后台或他人授权数据的权限。

## Build
JDK 17+、Android SDK 36，配置 ANDROID_HOME 或本地 local.properties：
```sh
./gradlew --no-daemon -PduoProjectionTestsOnly=true :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```
Windows 可用 gradlew.bat。未提供私有发行签名时 Release 为 unsigned；自行构建不能作为官方包的同签名升级。
不要在公开 Issues/PR 中提交授权码、配对码、私密订单链接、凭据或含个人信息的日志。

Independent Android project; not affiliated with Apple, Xiaomi, Google, or other device vendors.  
English: MiDuo by D.JT is a foldable launcher derived from Duo Launcher. Public source may lag the official website. You can install and preview Home without paying; settings and editing require official activation. This version has known bugs—try before buying. Client source is MIT licensed; official signing keys, activation infrastructure, store backend and customer data are excluded. Weather is sample data; lock-screen projection is not supported in 1.0.2.
