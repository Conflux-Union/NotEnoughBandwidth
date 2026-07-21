package cn.ussshenzhou.notenoughbandwidth.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * When `-Dneb.disableMod=true` is passed on the JVM command line, no NEB mixin
 * is applied. This yields a truly vanilla Minecraft runtime for the `raw` /
 * `zlib` baseline benchmarks, rather than a half-disabled hybrid where some
 * @Overwrite / @Redirect effects linger after the mod's runtime init is skipped.
 * <p>
 * The flag is read exactly once at mixin-config load time; it is not reloadable.
 */
public final class NebMixinPlugin implements IMixinConfigPlugin {
    private boolean disabled;

    @Override
    public void onLoad(String mixinPackage) {
        this.disabled = Boolean.getBoolean("neb.disableMod");
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (disabled) {
            return false;
        }
        //#if MC<12005
        //$$ // Varint21FrameDecoder/Prepender have a different shape on 1.20.1 (no
        //$$ // copyVarint, no standalone VarInt class) and the aggregation path
        //$$ // already keeps blobs under the vanilla limit via sendBatched, so these
        //$$ // two mixins are compiled (to keep this file buildable) but never applied.
        //$$ if (mixinClassName.endsWith(".Varint21FrameDecoderMixin")
        //$$         || mixinClassName.endsWith(".Varint21LengthFieldPrependerMixin")) {
        //$$     return false;
        //$$ }
        //#endif
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
