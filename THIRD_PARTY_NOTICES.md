# Third-party notices

MiDuo is derived from Duo Launcher (MIT). Copyright (c) 2026 Duo Launcher contributors; MiDuo modifications copyright (c) 2026 D.JT. Dependencies retain their own licenses. The APK includes notices under `assets/licenses/`; this public document expands attribution without changing the existing 1.0.2 APK.

| Component family | Source | License |
| --- | --- | --- |
| AndroidX, Jetpack Compose, Material components and icons, Window | https://android.googlesource.com/platform/frameworks/support/ | Apache 2.0 |
| Haze background blur for Compose, copyright Chris Banes and contributors | https://github.com/chrisbanes/haze | Apache 2.0 |
| GlassProjection optical model, Gaussian pyramid and shader adaptation, revision 18489f3 | https://github.com/spideytznn/GlassProjection | MIT; full copyright and license in `app/src/main/assets/licenses/GlassProjection-MIT.txt` |
| Kotlin standard library | https://github.com/JetBrains/kotlin | Apache 2.0 |
| Shizuku local ADB transport and native pairing, RikkaApps contributors, commit 2650830c | https://github.com/RikkaApps/Shizuku | Apache 2.0; modified Kotlin transport, native libadb.so from v13.6.0; no Shizuku manager/daemon redistributed |
| Conscrypt 2.5.3 TLS provider | https://github.com/google/conscrypt | Apache 2.0 and bundled notices; app/src/main/assets/licenses/Conscrypt-NOTICE.txt |
| BoringSSL, used by pairing and Conscrypt | https://github.com/google/boringssl | app/src/main/assets/licenses/BoringSSL-LICENSE.txt and Conscrypt-NOTICE.txt |
| Bouncy Castle | https://www.bouncycastle.org | MIT-style license; app/src/main/assets/licenses/BouncyCastle-LICENSE.txt |
| Kotlin coroutines | https://github.com/Kotlin/kotlinx.coroutines | Apache 2.0 |
| Kotlin serialization | https://github.com/Kotlin/kotlinx.serialization | Apache 2.0 |
| JetBrains annotations | https://github.com/JetBrains/java-annotations | Apache 2.0 |
| Guava ListenableFuture | https://github.com/google/guava | Apache 2.0 |
| JSpecify annotations | https://github.com/jspecify/jspecify | Apache 2.0 |
| Gradle wrapper and build tooling | https://github.com/gradle/gradle | Apache 2.0; build-tool distributions include their additional notices |

The Gradle dependency graph records the resolved artifact versions. Test and build tools are not application features; their upstream distributions provide their respective notices.

The default wallpaper and launcher icon are supplied as MiDuo project artwork/resources; no Apple reference imagery is included in this public export. Installed application icons and widget content belong to their respective providers. Search can delegate to installed applications; their application code and content are not redistributed here.

Private design-study images, copied reference files, device captures, and probe research are excluded from the public source package. Apple, Google, Android, Samsung, and other referenced names are trademarks of their respective owners; this project is unaffiliated with those companies.
