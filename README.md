# OneBlockChunk

A Paper **26.3** plugin that adds the **OneBlock Chunk**: a single block that holds a whole
real Minecraft chunk you can shrink into and play inside.

## Crafting

| | | |
|---|---|---|
| Dirt | Grass Block | Stone |
| Any Log | *(empty)* | Water Bucket |
| Deepslate | Deepslate | Deepslate |

## How it works

- **Place it**: it looks like a grass block with a tiny 1/16-scale copy of its own chunk
  (terrain, trees, water, caves at the surface) sitting on top.
- **Jump on it**: you shrink down to 1/16 size and drop into that chunk at full size, where
  you can play normal Minecraft: mine, build, farm.
- **Walk off the edge** of the chunk (or `/obc leave`): you pop back out beside the block on
  that side and grow back to normal size.
- **Look at it**: the mini model updates within about a second of any change made inside,
  so all your work shows up on the block.
- **Watch from outside**: every mob and player on the surface inside the chunk shows as a
  tiny 1/16 copy moving around on the mini model, live.
- **Look out from inside**: the area around the block is rebuilt around your chunk at 16x
  scale (one outside block = one chunk), and players and mobs near the block show as 16x
  giants. Someone standing next to the block looks like a giant standing next to your chunk.
- **Break it**: it drops as an item tagged with its chunk number. Place it anywhere and your
  chunk, with everything you built, comes with it.

Each OneBlock Chunk's chunk lives in a separate world, `oneblockchunk_pockets`. Only
those chunks get vanilla terrain; everything around them is void.

## Commands

- `/oneblockchunk give [player]` (alias `/obc`), permission `oneblockchunk.admin` (op)
- `/oneblockchunk leave`

Permission `oneblockchunk.use` (default: everyone) lets players jump in.

## Building

Requires Java 25.

```
mvn package
```

The jar is written to `target/OneBlockChunk-1.0.0.jar`; drop it in your server's `plugins/` folder.
