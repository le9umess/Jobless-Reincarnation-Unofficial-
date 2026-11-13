# Jobless Reincarnation · 无职转生

An unofficial, free fan mod that brings the childhood chapter of *Mushoku Tensei* (無職転生) to Minecraft:
growing up in Buena Village, learning magic from Roxy and the sword from Paul, and meeting Sylphiette.

非官方的免费同人模组：在 Minecraft 里经历《无职转生》的幼年篇，在布耶纳村长大，跟洛琪希学魔术、跟保罗学剑，
认识希露菲。

Version 0.1.1, a fix release after 0.1.0 (the first public release). 版本 0.1.1，0.1.0（首个公开版）之后的修复版。

## What is in 0.1.1 / 0.1.1 有什么

- **Buena Village.** When a new world has a suitable site, one village is built into the new terrain: the Greyrat home
  with Paul's sparring ground beside its yard, the square, a watermill on the stream, fields, twelve houses, the
  hunter's cabin by the forest and a watchtower. You then start about 240–320 blocks outside the village, facing it. If a new world has no suitable site, the first player to join is told once; joining again does not repeat it.
- **Five characters.** Paul, Zenith, Lilia and Roxy live in the Greyrat home; Sylphie waits under the big tree on the
  hill. Each has their own model and Epic Fight animations. All five blink; Roxy and Sylphie smile when they greet you,
  and Roxy waves when you come near.
- **What should they call you?** The first time you join a world, choose how the family addresses you: Rudy, your game
  name, or a name of your own (up to 16 characters). You can change it later in the Magic Journal.
- **Magic with Roxy.** Eight spells learned from tomes: Water Ball, Water Shield, Earth Wall, Air Burst, Healing, Fire
  Ball, Mire and Stone Cannon. Practice makes them stronger, and Water Ball, Fire Ball and Stone Cannon take three
  different forms as you master them. Mana is a crystal in the corner of the screen rather than a number; pick spells
  from a recall bar, and when you run short, overdraw one more spell at a price.
- **The sword with Paul.** Train with a practice wooden sword and awaken battle aura. Learn the first technique of each
  of three schools — Arm Strike (Sword God style), Flow (Water God style) and Tear Pouch (North God style) — and work
  out the Longsword of Light yourself: a charged cut that dashes you forward.
- **Sparring with Paul.** The first mini-boss: a two-phase match inside a ring marked on the ground, sword only in the
  first phase and magic allowed in the second. Nobody dies: if you lose, he helps you back up.
- **Also:** the Mana Reader, a practice dummy, the books on the Greyrat shelves with short guides, and first-person arm
  animations through Punchy!.

- **布耶纳村**：新世界里找到合适的村址时生成一座，长在新生成的地形里：格雷拉特家宅和院子旁边保罗的练剑空地、广场、
  小河边的水车、田地、12 户民居、罗尔兹家的小屋和瞭望塔。这时出生点在村外约 240–320 格，面朝村子。新建世界没找到村址时，第一个进来的玩家会收到一次提示，重新进入不会再提示。
- **五位人物**：保罗、塞妮丝、莉莉雅、洛琪希住在格雷拉特家，希露菲在山丘的大树下。每人都有自己的模型和史诗战斗动作。
  五个人都会眨眼；洛琪希和希露菲打招呼时会笑，你走近时洛琪希会招手。
- **怎么称呼你**：第一次进入世界时，选家人怎么叫你：鲁迪、游戏名，或者自己起一个（最多 16 个字）。以后可以在魔术手札里改。
- **跟洛琪希学魔术**：从魔法书学八种术式：水弹术、水盾术、土壁、冲击波、治疗术、火球术、泥沼、岩炮弹。越练越强，
  水弹术、火球术、岩炮弹练到后面会变成三种不同的样子。魔力显示成屏幕角上的一颗晶体，不是数字；用回想栏选术，魔力见底时还能付出代价透支一发。
- **跟保罗学剑**：用练习木剑练习，觉醒斗气。学三个流派各自的第一招：剑神流·击臂、水神流·流、北神流·落泪弹；再自己悟出
  光之太刀：蓄力后向前突进的一刀。
- **保罗对练**：第一个小 Boss。地上画出场地圈，分两管血，第一管只能用剑，第二管可以用魔术。不会死人：输了他会扶你起来。
- **另外**：测魔晶、练剑木人桩、格雷拉特家书架上的藏书和导读，装 Punchy! 后的第一人称手部动作。

## Requirements / 需要

- Minecraft 1.21.1, NeoForge 21.1.251 or later 21.1.x
- Epic Fight, exactly 21.17.3.1 — client and server
- GeckoLib 4.9.3 or later 4.x — client and server
- Punchy! 3.0 or later — client only; dedicated servers do not need it. 0.1.0 was tested with 3.0 and 3.2a on our own
  machine (single player, and a local dedicated server with two clients); the real-client check of 0.1.1 (screens and the one-time message) used 3.0. Newer versions have not been tested.

The game stops with an error that names the missing mod if any of these is absent.

- Minecraft 1.21.1，NeoForge 21.1.251 或更新的 21.1.x
- 史诗战斗（Epic Fight）必须正好是 21.17.3.1：客户端和服务端都要
- GeckoLib 4.9.3 或更新的 4.x：客户端和服务端都要
- Punchy! 3.0 或更新：只装客户端，专用服不用装。0.1.0 在本机测过 3.0 和 3.2a（单人，以及本机专用服加两个客户端），
  0.1.1 的真客户端检查（画面和提示）用的是 3.0；更新的版本没测过

缺少其中任何一个，游戏加载时会报错并写明缺哪个。

## Known limitations / 已知问题和限制

- **Start a new world.** The village only generates in newly created worlds. Worlds made without the mod will not get one.
- **The first world creation takes a while.** The mod surveys for a village site and then prepares your spawn; the
  loading screen shows how many of the candidate sites it has checked. On our test machine the 15 seeds that got a village took about 10–74 seconds, and a world where no site passes took about 93–95 seconds of searching; these are samples, not limits, and it may take longer on other hardware or under load. Default, Large Biomes and Amplified worlds have no time limit for the search; Single Biome worlds and modpacks that replace the world generator have a fixed amount of work. Opening the same world again is quick.
- **A few worlds may have no village.** If no suitable site is found, no village is generated, the spawn is vanilla's and the first player to join is told once. In our tests 1 of 16 seeds found no site. Worlds created with 0.1.0 that have no village stay that way; create a new world to play the full start.
- **Lightly tested.** The village search was tested with 16 seeds, and no other world-generation mods. Multiplayer was tested on a local
  dedicated server, with up to 180 ms of simulated latency, but not over the internet.
- **Saves.** Worlds saved with 0.1.0 are meant to keep working in later versions.

- **要新建世界**：布耶纳村只在新建的世界里生成，没装模组时建的世界不会补出村子。
- **第一次创建世界要等一会儿**：模组要先找村址、再安排出生点，加载画面上会显示已看多少处、共多少处。在我们的测试机上，找到村子的 15 个种子约 10–74 秒；
  整张表都没有合格位置的世界，光找村址就要约 93–95 秒。这些是样本，不是上限，其他硬件或机器负载下可能更久。默认、巨型生物群系、放大化世界找村址没有时限；“单一生物群系”世界和换了世界生成器的模组环境有固定的工作量上限。同一个世界再打开很快。
- **少数世界可能没有村子**：找不到合适的地方时就不生成村子，出生点照原版，第一个进世界的玩家会收到一次提示。我们测的 16 个种子里有 1 个没找到。用 0.1.0 建的、已经没有村子的世界升级后仍然没有，想体验完整开局请新建世界。
- **测得不多**：选址测了 16 个种子，没测别的地形模组；联机只在本机的专用服上测过，最多模拟了 180 毫秒的延迟，没有实际异地联机。
- **存档**：以后的版本都要能打开 0.1.0 存下的世界。

## Building from source / 从源码构建

Java 21 is required.

```text
Windows:          .\gradlew.bat build
Linux and macOS:  bash ./gradlew build
```

The JAR is written to `build/libs/`. Epic Fight is fetched from Modrinth's Maven and GeckoLib from its own Maven
during the build; nothing has to be downloaded by hand.

需要 Java 21。Windows 上运行 `.\gradlew.bat build`，Linux 和 macOS 上运行 `bash ./gradlew build`。JAR 在 `build/libs/` 里。
构建时会自动从 Modrinth 的 Maven 取 Epic Fight、从 GeckoLib 自己的 Maven 取 GeckoLib，不用手动下载。

## License / 许可

- Code, data and build scripts: GNU GPL v3.0 or later — see `LICENSE`. The build scripts started from the NeoForged
  MDK template, whose MIT notice is kept in `TEMPLATE_LICENSE.txt`; the Gradle wrapper files (`gradlew`,
  `gradlew.bat`, `gradle/wrapper/`) stay under the Apache License 2.0.
- The mod's own art and writing: © 2026 Le9umess, all rights reserved — see `LICENSE-ASSETS`. Playing, videos,
  screenshots and modpacks that download the mod from its official pages are welcome.
- 代码、数据和构建脚本用 GPL 第 3 版或更新版本。构建脚本改自 NeoForged MDK 模板，它的 MIT 声明保留在
  `TEMPLATE_LICENSE.txt`；Gradle wrapper 的文件（`gradlew`、`gradlew.bat`、`gradle/wrapper/`）仍用 Apache License 2.0。
- 本模组自己的美术和文字版权归 Le9umess，保留所有权利。欢迎游玩、做视频、截图，也欢迎放进从官方页面下载本模组的整合包。
- Third-party files and dependencies: see `THIRD-PARTY.md`.

*Mushoku Tensei* and its characters belong to their respective rights holders. This project is not
affiliated with or endorsed by them, and it is not sold or monetised in any way.
《无职转生》及其人物归各自的权利人所有；本项目与权利人无关，也不以任何方式收费或盈利。

## How this mod is made / 制作方式

Made by Le9umess with AI assistance in development.
本模组由 Le9umess 制作，AI 辅助进行开发制作。

Source provenance / 源码来源：Le9umess · JR-0.1.0 — [source statement / 来源说明](SOURCE-PROVENANCE.md).

## Feedback / 反馈

Bug reports and suggestions / 问题反馈和建议: <https://github.com/le9umess/Jobless-Reincarnation-Unofficial-/issues>
