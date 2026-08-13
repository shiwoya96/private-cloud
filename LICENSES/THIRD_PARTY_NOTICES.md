# 第三方软件声明

本目录记录应用的直接依赖以及构建时会解析到的关键传递依赖。最终发布前，应以
`gradle :app:dependencies` 和生成 APK/AAB 的实际依赖图为准，重新生成完整的软件物料清单
并复核许可证义务。

| 组件 | 坐标 / 版本 | 许可证 | 上游 |
| --- | --- | --- | --- |
| OkHttp | `com.squareup.okhttp3:okhttp:5.3.2` | Apache License 2.0 | <https://github.com/square/okhttp> |
| Okio（传递） | `com.squareup.okio:*` | Apache License 2.0 | <https://github.com/square/okio> |
| Kotlin 标准库（传递） | `org.jetbrains.kotlin:kotlin-stdlib` | Apache License 2.0 | <https://github.com/JetBrains/kotlin> |
| AndroidX WorkManager | `androidx.work:work-runtime:2.11.2` | Apache License 2.0 | <https://android.googlesource.com/platform/frameworks/support/> |
| AndroidX 传递组件 | 构建解析版本 | Apache License 2.0 | <https://android.googlesource.com/platform/frameworks/support/> |
| jcifs-ng | `eu.agno3.jcifs:jcifs-ng:2.1.10` | GNU LGPL 2.1 | <https://github.com/AgNO3/jcifs-ng> |
| SLF4J API / Android backend | `org.slf4j:*:1.7.36` | MIT License | <https://www.slf4j.org/> |
| Bouncy Castle（排除 jcifs-ng 1.76 后显式引入） | `org.bouncycastle:bcprov-jdk18on:1.84` | Bouncy Castle License | <https://www.bouncycastle.org/> |
| JUnit（仅测试） | `junit:junit:4.13.2` | Eclipse Public License 1.0 | <https://junit.org/junit4/> |
| JSON-java（仅测试） | `org.json:json:20260522` | Public Domain | <https://github.com/stleary/JSON-java> |

许可证原文：

- Apache License 2.0：<https://www.apache.org/licenses/LICENSE-2.0>
- GNU Lesser General Public License：<https://www.gnu.org/licenses/lgpl-2.1.html>
- MIT License（SLF4J）：<https://www.slf4j.org/license.html>
- Bouncy Castle License：<https://www.bouncycastle.org/about/license.html>
- Eclipse Public License 1.0：<https://www.eclipse.org/legal/epl-v10.html>

jcifs-ng 是 LGPL 组件。分发 APK 时请保留本声明、相应许可证文本以及获取/替换该库的方式；
若对该库本身做了修改，还应按其许可证提供修改后的源代码。此文件不是法律意见。
