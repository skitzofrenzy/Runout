# Runout: Third-party notices

Runout's own source code is published under the MIT License (the `LICENSE` file at the top of the repository). Runout is built with the open-source components below, which stay under their own licences. This list matches the project's build configuration (`gradle/libs.versions.toml` and the `build.gradle.kts` files) and is checked again for each release.

## Components

| Component | Used for | Licence | Copyright holder / source |
|---|---|---|---|
| Kotlin standard library and Kotlin Multiplatform runtime | The language runtime for the app and the engine | Apache License 2.0 | JetBrains s.r.o. and Kotlin contributors, <https://github.com/JetBrains/kotlin> |
| kotlinx.coroutines | Background work | Apache License 2.0 | JetBrains s.r.o. and contributors, <https://github.com/Kotlin/kotlinx.coroutines> |
| Compose Multiplatform (runtime, foundation, Material 3, UI) | The user interface on Android and iOS | Apache License 2.0 | JetBrains s.r.o. and contributors, <https://github.com/JetBrains/compose-multiplatform>; parts derived from Jetpack Compose, Copyright The Android Open Source Project |
| AndroidX Activity Compose and the AndroidX libraries it depends on | Android activity, photo picker and camera hand-off | Apache License 2.0 | The Android Open Source Project, <https://github.com/androidx/androidx> |
| Skiko (iOS builds only) | Drawing on iOS | Apache License 2.0 | JetBrains s.r.o. and contributors, <https://github.com/JetBrains/skiko>; contains code adapted from the Android Open Source Project |
| Skia (inside Skiko, iOS builds only) | 2D graphics | BSD 3-Clause License | Copyright (c) 2011 Google Inc., <https://skia.org> |

Where the licence texts are:

- Apache License 2.0: [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt) in this folder. It applies to every component marked "Apache License 2.0" above. Where a project publishes a "NOTICE" file, it is available at that project's link in the table.
- BSD 3-Clause License (Skia): [licenses/BSD-3-Clause-Skia.txt](licenses/BSD-3-Clause-Skia.txt) in this folder.

These components are provided under their licences "as is", without warranty. Nothing here changes the terms they were published under. The release builds may include further native components that come with the libraries above; their notices are included in each release.

## Rules, names and logos of others

- The rules summaries in Runout are original, plain-language summaries. They are not copies of any rulebook. They point to the official sources, for example the World Pool-Billiard Association (WPA) and the World Professional Billiards and Snooker Association (WPBSA), and the official rulebooks decide any question of rules.
- Runout is an independent project. It is not affiliated with, endorsed by or sponsored by the WPA, the WPBSA, any league, tour, promoter, broadcaster, cloth maker or ball maker, or by Apple or Google.
- Product, organisation, league and event names that appear in Runout or its documents belong to their owners. They are used only to describe rules, ball colour layouts and the colour of table cloth, and no ownership of them is claimed.

## Questions

Email nexovadigital.info@gmail.com or use the issue tracker at <https://github.com/skitzofrenzy/Runout/issues>.
