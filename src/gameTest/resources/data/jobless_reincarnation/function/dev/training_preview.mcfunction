# Development-only scene. Manually invoke ONLY in the disposable JR Magic test world.
# Loaded by -PgameTests runClient, excluded from the distributable mod JAR.
# 'keep' preserves pre-existing blocks at these fixture positions.
setblock 200 -59 203 minecraft:spruce_planks keep
setblock 200 -59 202 jobless_reincarnation:training_board[facing=north] keep
setblock 197 -59 207 minecraft:target keep
setblock 197 -60 207 minecraft:spruce_planks keep
give @s jobless_reincarnation:training_board
give @s jobless_reincarnation:water_tome
give @s jobless_reincarnation:training_staff
tp @s 200.5 -60 200.5 0 0
