# Contributing

Keep changes focused. Describe the user-visible problem, resulting behavior, and validation. Start with an issue before substantial features or changes to dock geometry, fold layout, or Google integration.

Read the [code map](docs/architecture.md) for ownership, persistence and gesture constraints. The [user guide](docs/user-guide.md) describes the behavior changes should preserve.

Run `./scripts/gradle.sh -PduoProjectionTestsOnly=true :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. Use disposable emulators for instrumentation. Fixtures that alter Home selection, profiles, widgets, or settings must restore them; never use a personal phone as an instrumentation fixture.

Preserve one-page-per-swipe behavior, native widget scrolling and long-press pickup, placements, widget bindings, and Home-page retention. Keep access optional and explain it at the point of use. Tests should reproduce failures or protect meaningful behavior.

Do not commit signing keys, passwords, SDK paths, user layouts, device captures, account information, research media, or copied application code. Use sample data in screenshots and keep private implementation notebooks outside the public source set.

`PUBLIC-FILES` explicitly defines what ships in source archives and what CI builds. When adding a new public document or root file, update that allowlist and `.gitignore` as needed, then export to a new temporary directory with `scripts/export-public-source.sh`. Existing public application source trees are already included. The exporter validates a pristine tree, excludes private material and strips macOS metadata. See [release preparation](docs/public-release.md).

Bug reports should include app version, phone/Android version, folded/unfolded state, navigation mode, relevant widget/provider, and reproduction steps. Redact personal/work attachments. Do not post credentials or sensitive exploit details publicly; use the repository's private vulnerability reporting channel if available.

Contributions use the project's MIT license. Retain notices for third-party material and identify its source.
