package net.nuggz.lotrmc.entity.client;

import net.nuggz.lotrmc.entity.OrcEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * GeckoLib renderer for the orc entity.
 *
 * Registered on the client event bus in ModClientEvents.
 * No model layers or mesh definitions needed — GeckoLib handles that.
 */
public class OrcRenderer extends GeoEntityRenderer<OrcEntity> {

    public OrcRenderer(EntityRendererProvider.Context context) {
        super(context, new OrcModel());
    }
}