# MiDuo 1.0.2 隐私与权限 / Privacy
维护者：D.JT。适用于此客户端版本；第三方应用、小组件和系统各有自己的政策。

## 本地数据
桌面布局、应用目录、壁纸、组件绑定、偏好和已签名授权保存在设备上。应用名称/图标用于展示已安装应用，不作为激活请求内容发送。组件提供者自行处理其内容与网络。
壁纸使用系统图片选择器；布局导出由用户主动发起，可能包含应用列表、文件夹名称及工作资料信息，分享前请检查。

## 联网激活
首次激活向 https://mymiduo.xyz/api/v1/activate 发送授权兑换码及设备绑定标识。标识由 Android ID 与包名派生的摘要生成，不是 IMEI，也不能描述为匿名数据。服务端用来校验授权及绑定设备，返回客户端可验证的签名凭证。本地保存有效凭证。
服务端及网络基础设施可接触请求 IP 和访问日志；请勿公开授权码、签名凭证、设备标识或私密领取/订单链接。清除应用数据不会自动删除服务端绑定；如需协助请通过官网反馈入口联系作者。

## 可选权限与无线调试
- 无障碍服务用于用户操作触发的通知/快捷设置、返回/Home/最近任务等导航，以及本应用设置辅助。1.0.2 配置不允许读取窗口内容或注入任意手势。
- 无线调试由用户主动开启并输入配对码，用于本机连接、授予导航所需权限及可恢复的导航设置。配对身份与密钥保存在设备本地；不发送给授权服务器。完成后可关闭无线调试，并在系统中撤销已配对身份。
- 近似定位仅在用户请求相关位置功能时使用，不申请后台定位。1.0.2 天气为示例，不是实时天气接口。
- 电量、Wi-Fi、SIM 等读数用于桌面状态展示；可用性受系统与权限限制。小组件绑定和默认桌面选择由系统确认。

不包含广告/分析 SDK 或自动崩溃上传服务；这不代表应用完全离线或不存在授权后台。

## 官网订单
官网可能保存订单、支付交易号、付款凭证、授权领取与绑定记录。限免使用受保护的 IP 摘要按北京时间限制同 IP 当日领取。付款凭证仅供后台人工核对，不应公开。官网订单与客户端本地布局是不同的数据范围。
不要把银行卡、身份证或无关支付明细上传为凭证；保留核验所需金额、交易号和时间即可。

## 删除与反馈
卸载/清除存储会删除客户端本地设置和组件绑定，不会自动删除用户导出的文件、网站订单或服务端授权绑定。系统与组件供应方可能另行处理数据。手动提交截图/日志前遮挡个人信息；通过官网小红书群联系作者咨询数据处理，不在公共 Issue 上传凭证。

## English summary
Layouts, wallpapers, widget bindings and settings stay on-device unless you export them. Activation sends a code and a device-derived identifier to mymiduo.xyz; server binding and order records are not removed by uninstalling. Infrastructure can see IP addresses. Optional accessibility and local wireless-debugging setup assist navigation; pairing secrets stay on-device. There is no advertising/analytics SDK or automatic crash upload. Review screenshots and exports before sharing; third-party widgets follow their providers' policies.
