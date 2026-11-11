# Third-party software / 第三方软件

## In this repository / 本仓库里的第三方文件

| Files | From | License |
| --- | --- | --- |
| `build.gradle`, `settings.gradle`, `gradle.properties` | Started from the NeoForged MDK template, then changed for this mod | MIT — see `TEMPLATE_LICENSE.txt` |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/` | Gradle wrapper, unchanged; it downloads Gradle 9.2.1 for the build | Apache License 2.0 — the notice is at the top of `gradlew` and `gradlew.bat`; full text at <https://www.apache.org/licenses/LICENSE-2.0> |

构建文件改自 NeoForged MDK 模板（MIT，原许可见 `TEMPLATE_LICENSE.txt`）；Gradle wrapper（`gradlew`、`gradlew.bat`、
`gradle/wrapper/`）用 Apache-2.0，声明在两个脚本的开头。

## Required by the mod, not included / 需要另装、不包含在内的

None of the mods below is included in this repository or in the mod's JAR. Players install each one
from its author's page. 下面这些模组都不包含在本仓库和本模组的 JAR 里，玩家要从各自作者的页面安装。

| Mod | Role | License (as stated by the author) | Official pages |
| --- | --- | --- | --- |
| Minecraft 1.21.1 (Mojang) | game | Minecraft EULA | |
| NeoForge 21.1.x | mod loader | LGPL-2.1 | <https://neoforged.net/> |
| Epic Fight 21.17.3.1 | required, client and server; this mod compiles against its code | code: GNU GPLv3; assets: © Epic Fight Team, all rights reserved | [CurseForge](https://www.curseforge.com/minecraft/mc-mods/epic-fight-mod) · [Modrinth](https://modrinth.com/mod/epic-fight) |
| GeckoLib 4.9.3+ | required, client and server | MIT | [CurseForge](https://www.curseforge.com/minecraft/mc-mods/geckolib) · [Modrinth](https://modrinth.com/mod/geckolib) |
| Punchy! 3.0+ | required on the client (first-person animations) | all rights reserved; its JAR may not be redistributed | [CurseForge](https://www.curseforge.com/minecraft/mc-mods/punchy) · [Modrinth](https://modrinth.com/mod/punchy-fpa) |

This mod's own code is GPL-3.0-or-later (LICENSE); its own art and writing are © 2026 Le9umess, all rights
reserved (LICENSE-ASSETS). It contains no assets from Epic Fight or Punchy!.
本模组自己的代码用 GPL-3.0-or-later（LICENSE），自己的美术和文字版权归 Le9umess、保留所有权利（LICENSE-ASSETS）。
本模组不包含 Epic Fight 或 Punchy! 的任何素材。
