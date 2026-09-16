package com.jake.duolauncher

/** Small, Android-free projection so the system-inspired layout can be unit tested. */
data class Phase22AppCandidate(
    val id: String,
    val packageName: String,
    val label: String,
    val isWork: Boolean = false,
)

private data class AppChoice(
    val packages: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
)

private data class FolderChoice(
    val uuid: String,
    val title: String,
    val apps: List<AppChoice>,
)

private fun choice(vararg packages: String, labels: List<String> = emptyList()) =
    AppChoice(packages.toList(), labels)

private val PHASE_22_EXCLUDED_PACKAGES = setOf(
    "org.telegram.messenger", "com.whatsapp", "com.facebook.katana", "com.instagram.android",
    "com.twitter.android", "com.grabtaxi.passenger", "com.ubercab", "com.booking",
    "com.airbnb.android", "ctrip.english", "com.didiglobal.passenger", "com.nhn.android.nmap",
    "com.paypal.android.p2pmobile", "com.globe.gcash.android", "com.gojek.gopay", "com.paymaya",
    "id.dana", "com.telkomsel.telkomselcm", "ph.com.globe.globeonesuperapp",
    "com.binance.dev", "com.bitget.exchange", "com.bybit.app", "com.okinc.okex.gp", "pro.huobi",
    "com.wallet.crypto.trustapp", "com.bochk.app.aos", "hk.com.hsbc.hsbchkmobilebanking",
)

private val PHASE_22_SINGLES = listOf(
    choice("org.localsend.localsend_app"),
    choice(labels = listOf("Talk Station")),
    choice("com.microsoft.rdc.androidx"),
    choice("com.miui.gallery"),
    choice("com.miui.voiceassistProxy"),
    choice("com.xiaomi.market"),
    choice("com.eg.android.AlipayGphone"),
    choice("com.android.settings"),
    choice("com.xingin.xhs"),
    choice("com.xiaomi.smarthome"),
    choice("com.android.chrome", "com.android.browser"),
    choice("cn.wps.moffice_hd", "cn.wps.moffice_eng"),
    choice("com.android.email"),
    choice("com.ss.android.lark"),
)

private val PHASE_22_FOLDERS = listOf(
    FolderChoice("22000000-0000-0000-0000-000000000001", "AI", listOf(
        choice("com.larus.nova"), choice("com.openai.chatgpt"),
        choice(labels = listOf("Team Codex Console")), choice("com.miui.voiceassistProxy"),
    )),
    FolderChoice("22000000-0000-0000-0000-000000000002", "影音视听", listOf(
        choice("tv.danmaku.bili"), choice("com.youku.phone"), choice("com.tencent.qqlive"),
        choice("com.trim.media"), choice("com.ss.android.ugc.aweme"), choice("com.qiyi.video"),
        choice("com.cmcc.cmvideo"), choice("com.trim.app"), choice("com.hunantv.imgo.activity"),
        choice("com.miui.video"), choice("com.miui.player"),
    )),
    FolderChoice("22000000-0000-0000-0000-000000000003", "体育运动", listOf(
        choice("com.hupu.games"), choice("com.dongqiudi.news"), choice("com.hupu.shihuo"),
        choice("com.mi.health"), choice("com.huawei.health"),
    )),
    FolderChoice("22000000-0000-0000-0000-000000000004", "时尚购物", listOf(
        choice("com.xiaomi.shop"), choice("com.taobao.taobao"), choice("com.xunmeng.pinduoduo"),
        choice("com.smzdm.client.android"), choice("com.wudaokou.hippo"), choice("com.jingdong.app.mall"),
        choice("com.mcdonalds.gma.cn"), choice("com.dianping.v1"), choice("com.taobao.idlefish"),
        choice("com.alibaba.wireless"),
    )),
    FolderChoice("22000000-0000-0000-0000-000000000005", "效率办公", listOf(
        choice("com.android.email"), choice("com.alibaba.android.rimet"), choice("com.netease.mail"),
        choice("com.baidu.netdisk"), choice("com.qq.qcloud"), choice("md.obsidian"),
        choice("cn.wps.moffice_eng"), choice("com.alibaba.aliyun"),
    )),
    FolderChoice("22000000-0000-0000-0000-000000000006", "聊天社交", listOf(
        choice("com.tencent.mm"), choice("com.tencent.mobileqq"), choice("com.tencent.wework"),
        choice("com.ss.android.lark"),
    )),
    FolderChoice("22000000-0000-0000-0000-000000000007", "新闻资讯", listOf(
        choice("com.sina.weibo"), choice("com.coolapk.market"), choice("com.tencent.weread"),
        choice("com.douban.frodo"), choice("cn.com.sina.finance"), choice("cn.damai"),
        choice("com.duokan.reader"), choice("com.zhihu.android"), choice("com.quark.browser"),
    )),
    FolderChoice("22000000-0000-0000-0000-000000000008", "实用工具", listOf(
        choice("com.miui.calculator"), choice("com.miui.notes"), choice("com.android.soundrecorder"),
        choice("com.xiaomi.scanner"), choice("com.miui.compass"), choice("com.miui.securitycenter", "com.miui.securitymanager"),
        choice("com.duokan.phone.remotecontroller"), choice("com.android.providers.downloads.ui"),
        choice("com.android.virtualization.terminal"), choice("bin.mt.plus"),
    )),
    FolderChoice("22000000-0000-0000-0000-000000000009", "旅行交通", listOf(
        choice("com.sdu.didi.psnger"), choice("ctrip.android.view"), choice("com.umetrip.android.msky.app"),
        choice("com.china3s.android"), choice("com.autonavi.minimap"), choice("com.baidu.BaiduMap"),
        choice("com.MobileTicket"), choice("com.plateno.botaoota"),
        choice("com.disney.shanghaidisneyland_goo"), choice("com.taobao.trip"), choice("com.zuzuChe"),
    )),
    FolderChoice("22000000-0000-0000-0000-000000000010", "金融理财", listOf(
        choice("com.antfortune.wealth"), choice("com.unionpay"), choice("cmb.pb"),
        choice("cn.com.cmbc.newmbank"), choice("com.pingan.paces.ccms"), choice("com.chinamworld.main"),
        choice("com.android.bankabc"), choice("com.ecitic.bank.mobile"), choice("com.zxscnew"),
        choice("com.mipay.wallet"),
    )),
)

private fun AppChoice.resolve(
    apps: List<Phase22AppCandidate>,
    used: Set<String>,
): Phase22AppCandidate? {
    packages.forEach { packageName ->
        apps.firstOrNull { it.id !in used && it.packageName == packageName }?.let { return it }
    }
    labels.forEach { label ->
        apps.firstOrNull { it.id !in used && it.label.equals(label, ignoreCase = true) }?.let { return it }
    }
    return null
}

/**
 * Applies the restrained two-page Phase 2.2 desktop:
 * - page 1 mirrors MIUI's high-frequency standalone apps;
 * - page 2 contains domestic/system categories;
 * - Dock, leading page and every widget placement remain untouched.
 * Everything else remains available in All Apps.
 */
fun phase22AppLayout(current: HomeLayout, candidates: List<Phase22AppCandidate>, freshInstall: Boolean = false, english: Boolean = false): HomeLayout {
    val apps = candidates.filter { !it.isWork && it.packageName !in PHASE_22_EXCLUDED_PACKAGES }
        .distinctBy(Phase22AppCandidate::id)
    val retainedFolderIds = current.leadingSlots.filterNotNull().filter(::isFolderId).toSet()
    val retainedFolders = current.folders.filter { it.id in retainedFolderIds }
    val used = (current.dock.filterNotNull() + current.leadingSlots.filterNotNull() +
        retainedFolders.flatMap(FolderEntry::appIds)).toMutableSet()
    val blocked = current.widgetPlacements.flatMapTo(mutableSetOf(), WidgetPlacement::coveredIndices)
    val slots = MutableList(HOME_CELLS * 2) { null as String? }

    val pageOneCells = (0 until HOME_CELLS).filterNot(blocked::contains).iterator()
    (if (freshInstall) FRESH_HOME_SINGLES else PHASE_22_SINGLES).forEach { appChoice ->
        val app = appChoice.resolve(apps, used) ?: return@forEach
        if (!pageOneCells.hasNext()) return@forEach
        slots[pageOneCells.next()] = app.id
        used += app.id
    }

    val folders = retainedFolders.toMutableList()
    val pageTwoCells = (HOME_CELLS until HOME_CELLS * 2).filterNot(blocked::contains).iterator()
    PHASE_22_FOLDERS.forEach { folderChoice ->
        val members = buildList {
            folderChoice.apps.forEach { appChoice ->
                appChoice.resolve(apps, used + this.map(Phase22AppCandidate::id))?.let(::add)
            }
        }.distinctBy(Phase22AppCandidate::id)
        if (members.size < 2 || !pageTwoCells.hasNext()) return@forEach
        val id = "folder:${folderChoice.uuid}"
        slots[pageTwoCells.next()] = id
        folders += FolderEntry(id, if (english) FOLDER_ENGLISH.getValue(folderChoice.title) else folderChoice.title, members.map(Phase22AppCandidate::id))
        used += members.map(Phase22AppCandidate::id)
    }

    return current.copy(slots = slots.dropLastWhile { it == null }, folders = folders)
}

private val FRESH_HOME_SINGLES = listOf(
    choice("com.miui.gallery", "com.google.android.apps.photos", "com.sec.android.gallery3d"),
    choice("com.android.settings"),
    choice("com.android.deskclock", "com.google.android.deskclock", "com.sec.android.app.clockpackage"),
    choice("com.android.fileexplorer", "com.mi.android.globalFileexplorer", "com.sec.android.app.myfiles"),
    choice("com.tencent.mm"), choice("com.eg.android.AlipayGphone"),
    choice("com.xingin.xhs"), choice("com.autonavi.minimap", "com.baidu.BaiduMap"),
    choice("com.xiaomi.smarthome"), choice("com.xiaomi.market"),
    choice("com.android.calendar", "com.google.android.calendar"),
    choice("com.miui.weather2"),
)

private val FOLDER_ENGLISH = mapOf("AI" to "AI", "影音视听" to "Entertainment", "体育运动" to "Health & Sports",
    "时尚购物" to "Shopping", "效率办公" to "Productivity", "聊天社交" to "Social",
    "新闻资讯" to "Reading & News", "实用工具" to "Tools", "旅行交通" to "Travel", "金融理财" to "Finance")

/** Only real, personal-profile launcher entries; missing roles stay empty, never unrelated filler. */
fun freshInstallDock(candidates: List<Phase22AppCandidate>): List<String?> {
    val apps = candidates.filterNot { it.isWork }
    val used = mutableSetOf<String>()
    return listOf(
        choice("com.android.contacts", "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer"),
        choice("com.android.mms", "com.google.android.apps.messaging", "com.samsung.android.messaging"),
        choice("com.android.browser", "com.mi.globalbrowser", "com.android.chrome", "com.sec.android.app.sbrowser"),
        choice("com.android.camera", "com.android.camera2", "com.sec.android.app.camera", "com.google.android.GoogleCamera"),
    ).map { it.resolve(apps, used)?.id?.also(used::add) }
}
