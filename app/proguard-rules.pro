# DiscoverBounds resolves the public Window Extensions API by class and member name so it can
# retain the split-host fallback on devices whose extension implementation differs. R8 cannot
# infer these references from the strings used by Class.forName/getMethod/Proxy.
-keep class androidx.window.extensions.** { *; }
-keep class moe.shizuku.manager.adb.PairingContext { *; }
-keep class org.conscrypt.** { *; }
# Conscrypt includes optional pre-Lollipop socket adapters. Their platform-only
# parameter classes do not exist on this app's supported Android 12+ devices.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl

# Android manifest components and directly constructed widget-host classes are traced by AGP/R8.
# Layout and backup persistence use org.json with explicit keys, so there are no model classes
# that require broad reflection or serialization keep rules.
