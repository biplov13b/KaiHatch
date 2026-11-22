# Third-Party Notices & License Acknowledgements

KaiHatch is licensed under the [Apache License, Version 2.0](LICENSE).

This document lists the conceptual inspiration and third-party libraries used by KaiHatch, along with their respective copyright notices and licenses.

---

## Conceptual Inspiration

### HatchZip
- **Project**: [HatchZip by xevrion](https://github.com/xevrion/hatchzip)
- **Relationship**: Android port of HatchZip's progressive in-place archive extraction concept, with additional features.
- **Implementation**: KaiHatch uses a separate Kotlin and Jetpack Compose implementation and contains no HatchZip source code.

---

## Runtime Dependencies

| Component | Maven Coordinates | Version | License | Copyright / Maintainer |
| :--- | :--- | :--- | :--- | :--- |
| **Apache Commons Compress** | `org.apache.commons:commons-compress` | `1.26.2` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | Copyright 2002–2024 The Apache Software Foundation |
| **XZ for Java** | `org.tukaani:xz` | `1.10` | [0BSD](https://opensource.org/license/0bsd) | Lasse Collin (`<lasse.collin@tukaani.org>`) |
| **AndroidX Core KTX** | `androidx.core:core-ktx` | `1.18.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | The Android Open Source Project |
| **AndroidX Lifecycle** | `androidx.lifecycle:lifecycle-runtime-ktx`, `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose` | `2.10.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | The Android Open Source Project |
| **AndroidX Activity Compose** | `androidx.activity:activity-compose` | `1.13.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | The Android Open Source Project |
| **AndroidX Jetpack Compose** | `androidx.compose:compose-bom` (`ui`, `material3`, `material-icons-extended`) | `2026.03.00` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | The Android Open Source Project |
| **AndroidX WorkManager KTX** | `androidx.work:work-runtime-ktx` | `2.10.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | The Android Open Source Project |
| **KotlinX Coroutines** | `org.jetbrains.kotlinx:kotlinx-coroutines-core`, `kotlinx-coroutines-android` | `1.10.2` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | JetBrains s.r.o. and Kotlin Programming Language contributors |

---

## Test-Only Dependencies (Not Distributed in APK)

| Component | Maven Coordinates | Version | License | Copyright / Maintainer |
| :--- | :--- | :--- | :--- | :--- |
| **JUnit 4** | `junit:junit` | `4.13.2` | [EPL-1.0](https://www.eclipse.org/legal/epl-v10.html) | JUnit Contributors |
| **Robolectric** | `org.robolectric:robolectric` | `4.16` | [MIT](https://opensource.org/licenses/MIT) / [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | Robolectric Contributors / Google Inc. |
| **AndroidX Test Core** | `androidx.test:core` | `1.6.1` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | The Android Open Source Project |
| **AndroidX Test Ext JUnit** | `androidx.test.ext:junit` | `1.3.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | The Android Open Source Project |
| **KotlinX Coroutines Test** | `org.jetbrains.kotlinx:kotlinx-coroutines-test` | `1.10.2` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | JetBrains s.r.o. and Kotlin Programming Language contributors |

---

## License Texts Summary

### Apache License 2.0
Applies to Apache Commons Compress, AndroidX/Jetpack libraries, and Kotlin/KotlinX libraries. Full text is provided in the root [`LICENSE`](LICENSE) file.

### Zero-Clause BSD (0BSD) — XZ for Java (`org.tukaani:xz`)
```text
Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```
