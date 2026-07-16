package cn.ussshenzhou.notenoughbandwidth.util;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

/**
 * Preprocessor {@code @Pattern} templates for API shape changes between
 * Minecraft versions (method vs field access, constructors) that plain
 * rename mappings cannot express. The methods are never invoked at runtime;
 * the remapper rewrites matching call sites when deriving other versions.
 */
@SuppressWarnings("unused")
final class VersionPatterns {
    private VersionPatterns() {
    }

    @Pattern
    private static int chunkPosX(ChunkPos pos) {
        //#if MC>=260100
        return pos.x();
        //#else
        //$$ return pos.x;
        //#endif
    }

    @Pattern
    private static int chunkPosZ(ChunkPos pos) {
        //#if MC>=260100
        return pos.z();
        //#else
        //$$ return pos.z;
        //#endif
    }

    @Pattern
    private static ChunkPos chunkPosFromLong(long packed) {
        //#if MC>=260100
        return ChunkPos.unpack(packed);
        //#else
        //$$ return new ChunkPos(packed);
        //#endif
    }

    @Pattern
    private static Identifier identifierOf(String namespace, String path) {
        //#if MC>=12100
        return Identifier.fromNamespaceAndPath(namespace, path);
        //#else
        //$$ return new ResourceLocation(namespace, path);
        //#endif
    }

    @Pattern
    private static Identifier vanillaIdentifierOf(String path) {
        //#if MC>=12100
        return Identifier.withDefaultNamespace(path);
        //#else
        //$$ return new ResourceLocation("minecraft", path);
        //#endif
    }
}
