package dev.humanautopilot.schematic;


import net.minecraft.world.level.block.state.BlockState;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minimal Sponge-format schematic (.schem) reader.
 *
 * <p>Supports both v2 (most common, what WorldEdit produces by default) and
 * v3 (newer "Blocks" sub-compound layout). Block entities, biomes, and
 * entities are silently ignored — we only need the block grid for placement.
 *
 * <p>NBT format is read with a hand-rolled parser to avoid pulling in
 * WorldEdit or another NBT dependency. The parser only implements the tag
 * types that schematics actually use (compound, list, string, byte,
 * short, int, long, byte-array, int-array, long-array, float, double).
 *
 * <p>Block strings (e.g. {@code "minecraft:oak_stairs[facing=east,half=top]"})
 * are resolved to {@link BlockState} with the game's own block-state parser (ported from KawaiiCompanion,
 * where they went through Bukkit).
 * Unrecognized blocks (modded, future-version, typo) fall back to air rather
 * than failing the entire load.
 */
public final class SchematicLoader {

    /** Hard cap on schematic volume in cells (≈ a 200×200×200 build).
     *  Dimensions are attacker-controllable 16-bit shorts and every
     *  decoder allocates {@code int[width*height*length]} BEFORE reading
     *  any block data — without this cap a ~100-byte crafted file
     *  declaring 65535×65535×65535 OOMs the server on first preview,
     *  and the small on-disk file-size cap can't catch it. */
    private static final long MAX_CELLS = 32_000_000L;

    /** Cap on the highest palette id. A single sparse palette entry like
     *  {@code {"minecraft:stone": 2000000000}} would otherwise allocate a
     *  multi-GB reference array. */
    private static final int MAX_PALETTE = 65_536;

    private static void checkVolume(String what, int w, int h, int l) throws IOException {
        long cells = (long) w * h * l;
        if (cells > MAX_CELLS) {
            throw new IOException(what + " too large: " + w + "x" + h + "x" + l
                    + " = " + cells + " cells (max " + MAX_CELLS + ")");
        }
    }

    /** Parsed schematic ready for placement. */
    public static final class Schematic {
        /** Dimensions in blocks. width=X, height=Y (vertical), length=Z. */
        public final int width;
        public final int height;
        public final int length;
        /** Palette of unique block states. Indexed by entries in {@link #indices}. */
        public final BlockState[] palette;
        /** Per-cell palette index, length = width*height*length. */
        public final int[] indices;
        /** Schematic file name (for display). */
        public final String name;

        public Schematic(String name, int w, int h, int l, BlockState[] pal, int[] idx) {
            this.name = name;
            this.width = w;
            this.height = h;
            this.length = l;
            this.palette = pal;
            this.indices = idx;
        }

        /** Total cells (incl. air). */
        public int totalCells() { return width * height * length; }

        /** Linear index for (x,y,z) — matches Sponge layout (Y outer, Z middle, X inner). */
        public int linearIndex(int x, int y, int z) {
            return (y * length + z) * width + x;
        }

        /** Block at (x,y,z), or {@code null} if palette entry missing. */
        public BlockState blockAt(int x, int y, int z) {
            int i = linearIndex(x, y, z);
            if (i < 0 || i >= indices.length) return null;
            int p = indices[i];
            if (p < 0 || p >= palette.length) return null;
            return palette[p];
        }
    }

    /** "minecraft:oak_stairs[facing=east]" to a block state; throws IllegalArgumentException if unknown. */
    public static BlockState parseState(String text) {
        try {
            return net.minecraft.commands.arguments.blocks.BlockStateParser
                    .parseForBlock(net.minecraft.core.registries.BuiltInRegistries.BLOCK, text, false).blockState();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            // Blocks renamed since older schematics were saved: try the new name.
            String id = text.contains("[") ? text.substring(0, text.indexOf('[')) : text;
            String rest = text.substring(id.length());
            String bare = id.startsWith("minecraft:") ? id.substring(10) : id;
            String now = RENAMED.get(bare);
            if (now != null) {
                try {
                    return net.minecraft.commands.arguments.blocks.BlockStateParser
                            .parseForBlock(net.minecraft.core.registries.BuiltInRegistries.BLOCK, "minecraft:" + now + rest, false).blockState();
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e2) {
                    // fall through
                }
            }
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    /** Old block ids that have a new name now. */
    private static final Map<String, String> RENAMED = Map.of(
            "chain", "iron_chain",
            "grass", "short_grass",
            "grass_path", "dirt_path");

    /** The file's NBT: usually gzipped, but some tools save it plain (then the first byte is the compound tag, 10). */
    private static java.io.InputStream open(File file) throws IOException {
        java.io.BufferedInputStream in = new java.io.BufferedInputStream(new FileInputStream(file));
        in.mark(2);
        int a = in.read(), b = in.read();
        in.reset();
        return a == 0x1f && b == 0x8b ? new GZIPInputStream(in) : in;
    }

    /** Load a .schem file from disk. Throws on IO / malformed NBT. */
    public static Schematic load(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                open(file))) {
            // NBT root: <type=10 (Compound)> <name (str)> <payload>
            int rootType = in.readUnsignedByte();
            if (rootType != 10) {
                throw new IOException("Not an NBT compound at root (type=" + rootType + ")");
            }
            readUtf(in); // root name — usually "Schematic" or "" — discard
            NbtCompound root = readCompound(in);

            // Sponge v3 spec wraps everything in a "Schematic" compound at
            // the top level. Other exporters dump fields straight at root.
            // If we see a wrapper, descend into it before reading fields.
            NbtCompound wrapper = root.getCompound("Schematic");
            if (wrapper != null && wrapper.getCompound("Blocks") != null) {
                root = wrapper;
            }

            // Dimensions live at root in both v2 and v3.
            int width  = root.getShortAsInt("Width", 0);
            int height = root.getShortAsInt("Height", 0);
            int length = root.getShortAsInt("Length", 0);

            // Try to find Palette + BlockData in either of the known layouts.
            // We do NOT trust the Version tag here — some files have it set
            // incorrectly. Just probe the structure directly.
            NbtCompound paletteCompound = null;
            byte[] blockData = null;

            // v3 layout: Blocks { Palette {...}, Data [byte;] }
            NbtCompound blocks = root.getCompound("Blocks");
            if (blocks != null) {
                paletteCompound = blocks.getCompound("Palette");
                blockData = blocks.getByteArray("Data");
            }

            // v2 layout (fallback): Palette {...} + BlockData [byte;] at root
            if (paletteCompound == null) paletteCompound = root.getCompound("Palette");
            if (blockData == null)       blockData       = root.getByteArray("BlockData");

            // Litematica format detection — uses Regions { <name> { BlockStatePalette,
            // BlockStates (packed longs), Size, ... } }. Totally different from
            // Sponge, so route to its own loader.
            if (paletteCompound == null || blockData == null) {
                NbtCompound regions = root.getCompound("Regions");
                if (regions != null) {
                    NbtCompound region = null;
                    String regionName = null;
                    for (Map.Entry<String, Object> e : regions.entries()) {
                        if (e.getValue() instanceof NbtCompound) {
                            region = (NbtCompound) e.getValue();
                            regionName = e.getKey();
                            break;
                        }
                    }
                    if (region != null) {
                        return loadLitematicaRegion(file, regionName, region);
                    }
                }
            }

            // Legacy MCEdit/Schematica .schematic format detection — uses
            // a "Materials" string tag ("Alpha") and "Blocks"+"Data" byte
            // arrays of pre-1.13 numeric block IDs. These were removed
            // when Minecraft "flattened" block IDs to namespaced strings,
            // so we maintain our own translation table for the common
            // ~200 block IDs.
            if (paletteCompound == null || blockData == null) {
                byte[] legacyBlocks = root.getByteArray("Blocks");
                if (legacyBlocks != null) {
                    return loadLegacyMcEdit(file, root, legacyBlocks);
                }
                throw new IOException("Schematic missing Palette/BlockData (unsupported format)");
            }

            if (width <= 0 || height <= 0 || length <= 0) {
                throw new IOException("Invalid dimensions " + width + "x" + height + "x" + length);
            }
            checkVolume("Schematic", width, height, length);

            // Resolve palette
            BlockState[] palette = resolvePalette(paletteCompound);

            // Decode varint-packed block indices (Sponge stores them as a byte
            // array; each block uses 1-5 bytes depending on palette size).
            int total = width * height * length;
            int[] indices = decodeVarints(blockData, total);

            return new Schematic(file.getName(), width, height, length, palette, indices);
        }
    }

    /** Convert a Sponge palette compound to a dense {@link BlockState} array. */
    private static BlockState[] resolvePalette(NbtCompound paletteCompound) throws IOException {
        // Find the max id so we know the array size (palette may be sparse
        // but in practice is dense 0..N-1).
        int max = -1;
        for (Map.Entry<String, Object> e : paletteCompound.entries()) {
            int id = toIntOrDefault(e.getValue(), -1);
            if (id > max) max = id;
        }
        if (max < 0) return new BlockState[0];
        if (max >= MAX_PALETTE) {
            throw new IOException("Palette id " + max + " out of range (max " + MAX_PALETTE + ")");
        }
        BlockState[] palette = new BlockState[max + 1];

        BlockState fallbackAir = parseState("minecraft:air");
        for (Map.Entry<String, Object> e : paletteCompound.entries()) {
            int id = toIntOrDefault(e.getValue(), -1);
            if (id < 0 || id >= palette.length) continue;
            String key = e.getKey();
            BlockState bd;
            try {
                bd = parseState(key);
            } catch (Throwable t) {
                // Unknown / modded / future-version block — render as air.
                // We log nothing here to avoid spamming server logs for
                // unknown blocks (one warning per block can be thousands
                // of lines on a big schematic). The build itself will
                // appear with holes where unknown blocks would have gone.
                bd = fallbackAir;
            }
            palette[id] = bd;
        }
        // Plug any sparse holes with air too, so blockAt never returns null.
        for (int i = 0; i < palette.length; i++) {
            if (palette[i] == null) palette[i] = fallbackAir;
        }
        return palette;
    }

    /** Decode Sponge's varint-packed block-data array into per-cell ints. */
    private static int[] decodeVarints(byte[] data, int totalCells) throws IOException {
        int[] out = new int[totalCells];
        int byteIdx = 0;
        for (int cell = 0; cell < totalCells; cell++) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (byteIdx >= data.length) {
                    throw new IOException("Truncated BlockData (cell " + cell + "/" + totalCells + ")");
                }
                byte b = data[byteIdx++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 35) throw new IOException("Varint too long at cell " + cell);
            }
            out[cell] = value;
        }
        return out;
    }

    // ====================================================================
    // ====================== Litematica .schematic =======================
    // ====================================================================

    /**
     * Load a single region from a Litematica-format file. Litematica stores
     * Width/Height/Length on the region's Size compound (which can be
     * negative — we take abs and accept potential mirroring on those axes),
     * the palette as a List of compounds {Name, Properties}, and the block
     * indices as a packed long-array with variable bit width.
     */
    private static Schematic loadLitematicaRegion(File file, String regionName, NbtCompound region)
            throws IOException {
        NbtCompound size = region.getCompound("Size");
        if (size == null) throw new IOException("Litematica region '" + regionName + "' missing Size");
        int w = Math.abs(size.getInt("x", 0));
        int h = Math.abs(size.getInt("y", 0));
        int l = Math.abs(size.getInt("z", 0));
        if (w <= 0 || h <= 0 || l <= 0) {
            throw new IOException("Litematica region '" + regionName + "' has invalid size "
                    + w + "x" + h + "x" + l);
        }
        checkVolume("Litematica region '" + regionName + "'", w, h, l);

        List<Object> paletteList = region.getList("BlockStatePalette");
        if (paletteList == null) {
            throw new IOException("Litematica region '" + regionName + "' missing BlockStatePalette");
        }

        long[] blockStates = region.getLongArray("BlockStates");
        if (blockStates == null) {
            throw new IOException("Litematica region '" + regionName + "' missing BlockStates");
        }

        BlockState[] palette = resolveLitematicaPalette(paletteList);

        // Litematica uses max(2, ceil(log2(N))) bits per entry, spanning across
        // long boundaries (i.e. one entry can use bits from two adjacent longs).
        int paletteSize = paletteList.size();
        int bits = Math.max(2, ceilLog2(Math.max(2, paletteSize)));

        int total = w * h * l;
        int[] indices = decodePackedLongsSpanning(blockStates, total, bits);

        // Litematica's storage order is YZX (same as Sponge), so the existing
        // linearIndex math works without reordering.
        return new Schematic(file.getName(), w, h, l, palette, indices);
    }

    /**
     * Convert a Litematica BlockStatePalette list (entries are compounds with
     * "Name" + optional "Properties") into a {@link BlockState} array.
     */
    private static BlockState[] resolveLitematicaPalette(List<Object> paletteList) {
        BlockState fallbackAir = parseState("minecraft:air");
        BlockState[] out = new BlockState[paletteList.size()];
        for (int i = 0; i < paletteList.size(); i++) {
            Object o = paletteList.get(i);
            if (!(o instanceof NbtCompound)) { out[i] = fallbackAir; continue; }
            NbtCompound entry = (NbtCompound) o;
            Object nameObj = entry.getRaw("Name");
            if (!(nameObj instanceof String)) { out[i] = fallbackAir; continue; }
            String name = (String) nameObj;

            NbtCompound props = entry.getCompound("Properties");
            String stateStr;
            if (props == null || props.isEmpty()) {
                stateStr = name;
            } else {
                StringBuilder sb = new StringBuilder(name).append('[');
                boolean first = true;
                for (Map.Entry<String, Object> e : props.entries()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(e.getKey()).append('=').append(e.getValue());
                }
                sb.append(']');
                stateStr = sb.toString();
            }

            BlockState bd;
            try {
                bd = parseState(stateStr);
            } catch (Throwable t) {
                // Unknown / modded / future block → render as air rather than
                // failing the entire load.
                bd = fallbackAir;
            }
            out[i] = bd;
        }
        return out;
    }

    /**
     * Decode a Litematica BlockStates long array. Each entry uses {@code bits}
     * bits and CAN span across long boundaries (the lower part comes from
     * long[startIdx], the upper part — if any — from long[startIdx+1]).
     */
    private static int[] decodePackedLongsSpanning(long[] data, int total, int bits)
            throws IOException {
        int[] out = new int[total];
        long mask = bits >= 64 ? -1L : ((1L << bits) - 1L);
        for (int i = 0; i < total; i++) {
            long startBit = (long) i * bits;
            int startIdx = (int) (startBit >>> 6);       // /64
            int endIdx   = (int) (((long) (i + 1) * bits - 1) >>> 6);
            int startOff = (int) (startBit & 63L);       // %64
            if (startIdx >= data.length) {
                // Breaking here used to silently fill the rest of the
                // region with palette entry 0 — fail loudly like the
                // Sponge and legacy decoders do for truncated data.
                throw new IOException("Truncated BlockDatas (entry " + i + "/" + total + ")");
            }
            long value;
            if (startIdx == endIdx) {
                value = (data[startIdx] >>> startOff) & mask;
            } else if (endIdx < data.length) {
                value = ((data[startIdx] >>> startOff)
                        | (data[endIdx] << (64 - startOff))) & mask;
            } else {
                value = (data[startIdx] >>> startOff) & mask;
            }
            out[i] = (int) value;
        }
        return out;
    }

    private static int ceilLog2(int n) {
        if (n <= 1) return 0;
        return 32 - Integer.numberOfLeadingZeros(n - 1);
    }

    // ====================================================================
    // ===================== Legacy MCEdit / Schematica ===================
    // ====================================================================

    /**
     * Load a pre-1.13 MCEdit / Schematica format schematic. These store
     * blocks as a flat {@code byte[width*height*length]} of numeric block
     * IDs (0-255) plus a parallel {@code Data} byte array of metadata
     * values (variant / orientation nibbles). The "flattening" update in
     * 1.13 retired this system, so we maintain our own translation table
     * for the common ~200 IDs. Unknown IDs fall back to stone so the
     * building's shape is still visible.
     *
     * <p>Limitations:
     * <ul>
     *   <li>Orientation data (stair facing, door hinge, etc.) is dropped —
     *       directional blocks render with their default facing.</li>
     *   <li>Block IDs above 255 (via the "AddBlocks" nibble array used by
     *       some mod blocks) aren't supported.</li>
     *   <li>Tile entity payloads (chest contents, sign text) are not
     *       preserved — only the block type.</li>
     * </ul>
     */
    private static Schematic loadLegacyMcEdit(File file, NbtCompound root, byte[] blocks)
            throws IOException {
        int w = root.getShortAsInt("Width", 0);
        int h = root.getShortAsInt("Height", 0);
        int l = root.getShortAsInt("Length", 0);
        if (w <= 0 || h <= 0 || l <= 0) {
            throw new IOException("Legacy schematic has invalid dimensions "
                    + w + "x" + h + "x" + l);
        }
        checkVolume("Legacy schematic", w, h, l);
        int total = w * h * l;
        if (blocks.length < total) {
            throw new IOException("Legacy schematic Blocks array truncated ("
                    + blocks.length + " < " + total + ")");
        }
        byte[] dataArr = root.getByteArray("Data");

        // Build a palette on the fly — most schematics use only ~50
        // distinct (id, data) pairs even at large sizes, so deduping here
        // keeps the index array compact.
        Map<Integer, Integer> seen = new HashMap<>();
        List<BlockState> paletteList = new ArrayList<>();
        int[] indices = new int[total];

        for (int i = 0; i < total; i++) {
            int blockId = blocks[i] & 0xFF;
            // Use only the variant nibble (lower 4 bits). Upper bits are
            // facing/orientation which we don't preserve.
            int dataVal = (dataArr != null && i < dataArr.length)
                    ? (dataArr[i] & 0x0F) : 0;
            int key = (blockId << 4) | dataVal;
            Integer pidx = seen.get(key);
            if (pidx == null) {
                BlockState bd = legacyToBlockData(blockId, dataVal);
                pidx = paletteList.size();
                paletteList.add(bd);
                seen.put(key, pidx);
            }
            indices[i] = pidx;
        }
        BlockState[] palette = paletteList.toArray(new BlockState[0]);
        return new Schematic(file.getName(), w, h, l, palette, indices);
    }

    private static BlockState legacyToBlockData(int id, int data) {
        String key = legacyToBlockKey(id, data);
        try {
            return parseState(key);
        } catch (Throwable t) {
            // Whatever we tried isn't valid in this MC version — fall
            // back to stone so the build's shape is at least visible.
            return parseState("minecraft:stone");
        }
    }

    private static final String[] COLOR_16 = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };

    @SuppressWarnings("DuplicateBranchesInSwitch") // many IDs share fallthroughs
    private static String legacyToBlockKey(int id, int data) {
        int d = data & 0x0F;
        switch (id) {
            case 0:   return "minecraft:air";
            case 1:
                switch (d) {
                    case 1: return "minecraft:granite";
                    case 2: return "minecraft:polished_granite";
                    case 3: return "minecraft:diorite";
                    case 4: return "minecraft:polished_diorite";
                    case 5: return "minecraft:andesite";
                    case 6: return "minecraft:polished_andesite";
                    default: return "minecraft:stone";
                }
            case 2:   return "minecraft:grass_block";
            case 3:
                switch (d) {
                    case 1: return "minecraft:coarse_dirt";
                    case 2: return "minecraft:podzol";
                    default: return "minecraft:dirt";
                }
            case 4:   return "minecraft:cobblestone";
            case 5:   return woodPlanks(d);
            case 6:   return woodSapling(d);
            case 7:   return "minecraft:bedrock";
            case 8:   case 9:   return "minecraft:water";
            case 10:  case 11:  return "minecraft:lava";
            case 12:  return d == 1 ? "minecraft:red_sand" : "minecraft:sand";
            case 13:  return "minecraft:gravel";
            case 14:  return "minecraft:gold_ore";
            case 15:  return "minecraft:iron_ore";
            case 16:  return "minecraft:coal_ore";
            case 17:  return woodLog(d & 3);
            case 18:  return woodLeaves(d & 3);
            case 19:  return d == 1 ? "minecraft:wet_sponge" : "minecraft:sponge";
            case 20:  return "minecraft:glass";
            case 21:  return "minecraft:lapis_ore";
            case 22:  return "minecraft:lapis_block";
            case 23:  return "minecraft:dispenser";
            case 24:
                switch (d) {
                    case 1: return "minecraft:chiseled_sandstone";
                    case 2: return "minecraft:smooth_sandstone";
                    default: return "minecraft:sandstone";
                }
            case 25:  return "minecraft:note_block";
            case 26:  return "minecraft:red_bed";
            case 27:  return "minecraft:powered_rail";
            case 28:  return "minecraft:detector_rail";
            case 29:  return "minecraft:sticky_piston";
            case 30:  return "minecraft:cobweb";
            case 31:
                switch (d) {
                    case 0: return "minecraft:dead_bush";
                    case 2: return "minecraft:fern";
                    default: return "minecraft:short_grass";
                }
            case 32:  return "minecraft:dead_bush";
            case 33:  return "minecraft:piston";
            case 34:  return "minecraft:piston_head";
            case 35:  return "minecraft:" + COLOR_16[d] + "_wool";
            case 37:  return "minecraft:dandelion";
            case 38:  return "minecraft:poppy";
            case 39:  return "minecraft:brown_mushroom";
            case 40:  return "minecraft:red_mushroom";
            case 41:  return "minecraft:gold_block";
            case 42:  return "minecraft:iron_block";
            case 43:  return stoneSlab(d); // double slab → solid slab
            case 44:  return stoneSlab(d);
            case 45:  return "minecraft:bricks";
            case 46:  return "minecraft:tnt";
            case 47:  return "minecraft:bookshelf";
            case 48:  return "minecraft:mossy_cobblestone";
            case 49:  return "minecraft:obsidian";
            case 50:  return "minecraft:torch";
            case 51:  return "minecraft:fire";
            case 52:  return "minecraft:spawner";
            case 53:  return "minecraft:oak_stairs";
            case 54:  return "minecraft:chest";
            case 55:  return "minecraft:redstone_wire";
            case 56:  return "minecraft:diamond_ore";
            case 57:  return "minecraft:diamond_block";
            case 58:  return "minecraft:crafting_table";
            case 59:  return "minecraft:wheat";
            case 60:  return "minecraft:farmland";
            case 61:  case 62: return "minecraft:furnace";
            case 63:  return "minecraft:oak_sign";
            case 64:  return "minecraft:oak_door";
            case 65:  return "minecraft:ladder";
            case 66:  return "minecraft:rail";
            case 67:  return "minecraft:cobblestone_stairs";
            case 68:  return "minecraft:oak_wall_sign";
            case 69:  return "minecraft:lever";
            case 70:  return "minecraft:stone_pressure_plate";
            case 71:  return "minecraft:iron_door";
            case 72:  return "minecraft:oak_pressure_plate";
            case 73:  case 74: return "minecraft:redstone_ore";
            case 75:  case 76: return "minecraft:redstone_torch";
            case 77:  return "minecraft:stone_button";
            case 78:  return "minecraft:snow";
            case 79:  return "minecraft:ice";
            case 80:  return "minecraft:snow_block";
            case 81:  return "minecraft:cactus";
            case 82:  return "minecraft:clay";
            case 83:  return "minecraft:sugar_cane";
            case 84:  return "minecraft:jukebox";
            case 85:  return "minecraft:oak_fence";
            case 86:  return "minecraft:carved_pumpkin";
            case 87:  return "minecraft:netherrack";
            case 88:  return "minecraft:soul_sand";
            case 89:  return "minecraft:glowstone";
            case 90:  return "minecraft:nether_portal";
            case 91:  return "minecraft:jack_o_lantern";
            case 92:  return "minecraft:cake";
            case 93:  case 94: return "minecraft:repeater";
            case 95:  return "minecraft:" + COLOR_16[d] + "_stained_glass";
            case 96:  return "minecraft:oak_trapdoor";
            case 97:  return "minecraft:infested_stone";
            case 98:
                switch (d) {
                    case 1: return "minecraft:mossy_stone_bricks";
                    case 2: return "minecraft:cracked_stone_bricks";
                    case 3: return "minecraft:chiseled_stone_bricks";
                    default: return "minecraft:stone_bricks";
                }
            case 99:  return "minecraft:brown_mushroom_block";
            case 100: return "minecraft:red_mushroom_block";
            case 101: return "minecraft:iron_bars";
            case 102: return "minecraft:glass_pane";
            case 103: return "minecraft:melon";
            case 104: return "minecraft:pumpkin_stem";
            case 105: return "minecraft:melon_stem";
            case 106: return "minecraft:vine";
            case 107: return "minecraft:oak_fence_gate";
            case 108: return "minecraft:brick_stairs";
            case 109: return "minecraft:stone_brick_stairs";
            case 110: return "minecraft:mycelium";
            case 111: return "minecraft:lily_pad";
            case 112: return "minecraft:nether_bricks";
            case 113: return "minecraft:nether_brick_fence";
            case 114: return "minecraft:nether_brick_stairs";
            case 115: return "minecraft:nether_wart";
            case 116: return "minecraft:enchanting_table";
            case 117: return "minecraft:brewing_stand";
            case 118: return "minecraft:cauldron";
            case 119: return "minecraft:end_portal";
            case 120: return "minecraft:end_portal_frame";
            case 121: return "minecraft:end_stone";
            case 122: return "minecraft:dragon_egg";
            case 123: case 124: return "minecraft:redstone_lamp";
            case 125: return woodSlab(d); // double wood slab → single slab
            case 126: return woodSlab(d);
            case 127: return "minecraft:cocoa";
            case 128: return "minecraft:sandstone_stairs";
            case 129: return "minecraft:emerald_ore";
            case 130: return "minecraft:ender_chest";
            case 131: return "minecraft:tripwire_hook";
            case 132: return "minecraft:tripwire";
            case 133: return "minecraft:emerald_block";
            case 134: return "minecraft:spruce_stairs";
            case 135: return "minecraft:birch_stairs";
            case 136: return "minecraft:jungle_stairs";
            case 137: return "minecraft:command_block";
            case 138: return "minecraft:beacon";
            case 139: return d == 1 ? "minecraft:mossy_cobblestone_wall" : "minecraft:cobblestone_wall";
            case 140: return "minecraft:flower_pot";
            case 141: return "minecraft:carrots";
            case 142: return "minecraft:potatoes";
            case 143: return "minecraft:oak_button";
            case 144: return "minecraft:skeleton_skull";
            case 145: return "minecraft:anvil";
            case 146: return "minecraft:trapped_chest";
            case 147: return "minecraft:light_weighted_pressure_plate";
            case 148: return "minecraft:heavy_weighted_pressure_plate";
            case 149: case 150: return "minecraft:comparator";
            case 151: case 178: return "minecraft:daylight_detector";
            case 152: return "minecraft:redstone_block";
            case 153: return "minecraft:nether_quartz_ore";
            case 154: return "minecraft:hopper";
            case 155:
                switch (d) {
                    case 1: return "minecraft:chiseled_quartz_block";
                    case 2: return "minecraft:quartz_pillar";
                    default: return "minecraft:quartz_block";
                }
            case 156: return "minecraft:quartz_stairs";
            case 157: return "minecraft:activator_rail";
            case 158: return "minecraft:dropper";
            case 159: return "minecraft:" + COLOR_16[d] + "_terracotta";
            case 160: return "minecraft:" + COLOR_16[d] + "_stained_glass_pane";
            case 161: return d == 1 ? "minecraft:dark_oak_leaves" : "minecraft:acacia_leaves";
            case 162: return d == 1 ? "minecraft:dark_oak_log"    : "minecraft:acacia_log";
            case 163: return "minecraft:acacia_stairs";
            case 164: return "minecraft:dark_oak_stairs";
            case 165: return "minecraft:slime_block";
            case 166: return "minecraft:barrier";
            case 167: return "minecraft:iron_trapdoor";
            case 168:
                switch (d) {
                    case 1: return "minecraft:prismarine_bricks";
                    case 2: return "minecraft:dark_prismarine";
                    default: return "minecraft:prismarine";
                }
            case 169: return "minecraft:sea_lantern";
            case 170: return "minecraft:hay_block";
            case 171: return "minecraft:" + COLOR_16[d] + "_carpet";
            case 172: return "minecraft:terracotta";
            case 173: return "minecraft:coal_block";
            case 174: return "minecraft:packed_ice";
            case 175:
                switch (d) {
                    case 0: return "minecraft:sunflower";
                    case 1: return "minecraft:lilac";
                    case 2: return "minecraft:tall_grass";
                    case 3: return "minecraft:large_fern";
                    case 4: return "minecraft:rose_bush";
                    case 5: return "minecraft:peony";
                    default: return "minecraft:sunflower";
                }
            case 176: case 177: return "minecraft:white_banner";
            case 179:
                switch (d) {
                    case 1: return "minecraft:chiseled_red_sandstone";
                    case 2: return "minecraft:smooth_red_sandstone";
                    default: return "minecraft:red_sandstone";
                }
            case 180: return "minecraft:red_sandstone_stairs";
            case 181: case 182: return "minecraft:red_sandstone_slab";
            case 183: return "minecraft:spruce_fence_gate";
            case 184: return "minecraft:birch_fence_gate";
            case 185: return "minecraft:jungle_fence_gate";
            case 186: return "minecraft:dark_oak_fence_gate";
            case 187: return "minecraft:acacia_fence_gate";
            case 188: return "minecraft:spruce_fence";
            case 189: return "minecraft:birch_fence";
            case 190: return "minecraft:jungle_fence";
            case 191: return "minecraft:dark_oak_fence";
            case 192: return "minecraft:acacia_fence";
            case 193: return "minecraft:spruce_door";
            case 194: return "minecraft:birch_door";
            case 195: return "minecraft:jungle_door";
            case 196: return "minecraft:acacia_door";
            case 197: return "minecraft:dark_oak_door";
            default:  return "minecraft:stone"; // unknown → visible placeholder
        }
    }

    private static String woodPlanks(int v) {
        switch (v & 7) {
            case 1: return "minecraft:spruce_planks";
            case 2: return "minecraft:birch_planks";
            case 3: return "minecraft:jungle_planks";
            case 4: return "minecraft:acacia_planks";
            case 5: return "minecraft:dark_oak_planks";
            default: return "minecraft:oak_planks";
        }
    }

    private static String woodSapling(int v) {
        switch (v & 7) {
            case 1: return "minecraft:spruce_sapling";
            case 2: return "minecraft:birch_sapling";
            case 3: return "minecraft:jungle_sapling";
            case 4: return "minecraft:acacia_sapling";
            case 5: return "minecraft:dark_oak_sapling";
            default: return "minecraft:oak_sapling";
        }
    }

    private static String woodLog(int v) {
        switch (v) {
            case 1: return "minecraft:spruce_log";
            case 2: return "minecraft:birch_log";
            case 3: return "minecraft:jungle_log";
            default: return "minecraft:oak_log";
        }
    }

    private static String woodLeaves(int v) {
        switch (v) {
            case 1: return "minecraft:spruce_leaves";
            case 2: return "minecraft:birch_leaves";
            case 3: return "minecraft:jungle_leaves";
            default: return "minecraft:oak_leaves";
        }
    }

    private static String stoneSlab(int v) {
        switch (v & 7) {
            case 1: return "minecraft:sandstone_slab";
            case 3: return "minecraft:cobblestone_slab";
            case 4: return "minecraft:brick_slab";
            case 5: return "minecraft:stone_brick_slab";
            case 6: return "minecraft:nether_brick_slab";
            case 7: return "minecraft:quartz_slab";
            default: return "minecraft:smooth_stone_slab";
        }
    }

    private static String woodSlab(int v) {
        switch (v & 7) {
            case 1: return "minecraft:spruce_slab";
            case 2: return "minecraft:birch_slab";
            case 3: return "minecraft:jungle_slab";
            case 4: return "minecraft:acacia_slab";
            case 5: return "minecraft:dark_oak_slab";
            default: return "minecraft:oak_slab";
        }
    }

    // ====================================================================
    // ============== NBT primitives (compact hand-rolled reader) =========
    // ====================================================================

    private static int toIntOrDefault(Object o, int def) {
        if (o instanceof Integer) return (Integer) o;
        if (o instanceof Short)   return ((Short) o).intValue();
        if (o instanceof Byte)    return ((Byte)  o).intValue();
        if (o instanceof Long)    return ((Long)  o).intValue();
        return def;
    }

    private static String readUtf(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static NbtCompound readCompound(DataInputStream in) throws IOException {
        NbtCompound out = new NbtCompound();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) break; // TAG_End
            String name = readUtf(in);
            Object val = readPayload(in, type);
            out.put(name, val);
        }
        return out;
    }

    /**
     * Hard cap on the byte footprint of any single NBT array/list. Guards
     * against a corrupt or hostile {@code .schem} that declares a huge length
     * field (e.g. {@code 0x7FFFFFFF}): without this, {@code new byte[len]} /
     * {@code new int[len]} would try to grab multiple gigabytes and OOM the
     * server the instant the file is opened — before any data is even read.
     * 64 MB comfortably fits any sane schematic while making a malicious
     * length fail fast as a normal "bad file" load error.
     */
    private static final int MAX_NBT_BYTES = 64 * 1024 * 1024;

    /**
     * Validate a length read from the file before it's used to size an
     * allocation. Rejects negatives and anything whose {@code len ×
     * bytesPerElem} exceeds {@link #MAX_NBT_BYTES}. Computed in {@code long}
     * so the multiply can't overflow back into a small positive int.
     */
    private static int checkedLen(int len, int bytesPerElem) throws IOException {
        if (len < 0 || (long) len * bytesPerElem > MAX_NBT_BYTES) {
            throw new IOException("NBT array length out of bounds: " + len);
        }
        return len;
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1:  return in.readByte();
            case 2:  return in.readShort();
            case 3:  return in.readInt();
            case 4:  return in.readLong();
            case 5:  return in.readFloat();
            case 6:  return in.readDouble();
            case 7: {
                int len = checkedLen(in.readInt(), 1);
                byte[] b = new byte[len];
                in.readFully(b);
                return b;
            }
            case 8:  return readUtf(in);
            case 9: { // List
                int itemType = in.readUnsignedByte();
                int len = checkedLen(in.readInt(), 8); // 8 = worst-case object reference
                List<Object> list = new ArrayList<>(Math.max(0, len));
                for (int i = 0; i < len; i++) {
                    list.add(itemType == 0 ? null : readPayload(in, itemType));
                }
                return list;
            }
            case 10: return readCompound(in);
            case 11: {
                int len = checkedLen(in.readInt(), 4);
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) arr[i] = in.readInt();
                return arr;
            }
            case 12: {
                int len = checkedLen(in.readInt(), 8);
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) arr[i] = in.readLong();
                return arr;
            }
            default:
                throw new IOException("Unsupported NBT tag type: " + type);
        }
    }

    /**
     * Lightweight compound that preserves insertion order (for stable palette
     * iteration) and provides typed accessors. Not exposed publicly because
     * this whole NBT layer is an implementation detail of the loader.
     */
    static final class NbtCompound {
        private final Map<String, Object> values = new LinkedHashMap<>();

        void put(String name, Object value) { values.put(name, value); }

        Iterable<Map.Entry<String, Object>> entries() { return values.entrySet(); }

        int getInt(String name, int def) { return toIntOrDefault(values.get(name), def); }

        int getShortAsInt(String name, int def) {
            Object o = values.get(name);
            if (o instanceof Short)   return ((Short) o).intValue() & 0xFFFF;
            return toIntOrDefault(o, def);
        }

        byte[] getByteArray(String name) {
            Object o = values.get(name);
            return o instanceof byte[] ? (byte[]) o : null;
        }

        NbtCompound getCompound(String name) {
            Object o = values.get(name);
            return o instanceof NbtCompound ? (NbtCompound) o : null;
        }

        @SuppressWarnings("unchecked")
        List<Object> getList(String name) {
            Object o = values.get(name);
            return o instanceof List ? (List<Object>) o : null;
        }

        long[] getLongArray(String name) {
            Object o = values.get(name);
            return o instanceof long[] ? (long[]) o : null;
        }

        Object getRaw(String name) { return values.get(name); }

        boolean isEmpty() { return values.isEmpty(); }
    }

    private SchematicLoader() {} // util
}
