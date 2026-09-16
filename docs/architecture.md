# MiDuo 1.0.2 client architecture
Kotlin/Compose launcher derived from Duo Launcher. Android owns secure lock screen, Recents and system app-window transitions; this source snapshot does not implement lock-screen projection.

All Kotlin paths below are under app/src/main/java/com/jake/duolauncher/.
- MainActivity, LauncherScreen, LauncherPager: activity lifecycle, inner/cover layout and paging.
- LauncherModel, LayoutModel, HomeEditing, HomeDrag, FolderPanel: saved layout and editing.
- WidgetController, WidgetPicker, ZeroPaddingWidgetHost: native widget binding and input.
- LeadingDashboard, ReferenceWidgets: leading page and built-in Duo-style widgets; weather is sample content.
- FoldTransition, GlassProjectionRenderer, GlassProjectionMath, GlassFoldPose: application-owned fold rendering, not a SystemUI mirror.
- PanelOrientation, StandbyScreen: panel-specific orientation and opt-in cover StandBy.
- SimpleLauncherSettings, DesktopEditor, CompactActionBar, CompactWidgetActions: setup and editing UI.
- DeviceStatus, StatusSignalMapping, StatusRail: observed status and display mapping.
- OfflineLicense, OfflineLicenseStore, ActivationClient: public-key verification, local receipt and online activation. Issuer secrets are not client code.
- SystemShadeController, NavigationGesture, NavigationOverlay: optional accessibility navigation.
- WirelessAdbSetup, NavigationActivation, moe/shizuku/manager/adb: user-initiated local ADB pairing/navigation setup.
- LauncherBackground, LayoutBackup, BackupController: local wallpaper/layout persistence.

Preserve native widget vertical scrolling, horizontal folder pages, panel identity, existing layout and license data when changing gestures. Tests with duoProjectionTestsOnly=true use the focused foldProjectionTest instrumentation source set. Runtime source is separate from private deployment and issuer infrastructure.
