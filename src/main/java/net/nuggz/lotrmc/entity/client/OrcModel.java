package net.nuggz.lotrmc.entity.client;

import net.nuggz.lotrmc.LotrMC;
import net.nuggz.lotrmc.entity.OrcEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeckoLib model class for the orc.
 *
 * Points at three resource files under assets/yourmodid/:
 *   geo/entity/orc.geo.json         — your Blockbench exported model
 *   animations/entity/orc.animation.json — your Blockbench exported animations
 *   textures/entity/orc.png         — your texture
 *
 * If you make variant orcs with different textures (archer, berserker, etc.),
 * override getTextureResource() in a subclass and return a different path.
 */
public class OrcModel extends GeoModel<OrcEntity> {

    @Override
    public ResourceLocation getModelResource(OrcEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(LotrMC.MODID, "geo/entity/orc.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(OrcEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(LotrMC.MODID, "textures/entity/orc.png");
    }

    @Override
    public ResourceLocation getAnimationResource(OrcEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(LotrMC.MODID, "animations/entity/orc.animation.json");
    }
}