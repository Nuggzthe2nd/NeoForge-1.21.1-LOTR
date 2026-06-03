package net.nuggz.lotrmc.entity.client;

import net.nuggz.lotrmc.entity.OrcEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.BlockAndItemGeoLayer;
import software.bernie.geckolib.renderer.layer.ItemArmorGeoLayer;

public class OrcRenderer extends GeoEntityRenderer<OrcEntity> {

    public OrcRenderer(EntityRendererProvider.Context context) {
        super(context, new OrcModel());

        // Armor rendering — GeckoLib does not auto-assign bones; each bone must be
        // mapped to the right equipment slot item and the matching HumanoidModel part.
        // Boots fall back to the leg bones because right_boot/left_boot have no cubes.
        addRenderLayer(new ItemArmorGeoLayer<OrcEntity>(this) {
            @Nullable
            @Override
            protected ItemStack getArmorItemForBone(GeoBone bone, OrcEntity orc) {
                return switch (bone.getName()) {
                    case "head"                          -> nonEmpty(helmetStack);
                    case "body", "right_arm", "left_arm" -> nonEmpty(chestplateStack);
                    case "right_leg", "left_leg"         -> nonEmpty(leggingsStack);
                    case "right_boot", "left_boot"       -> nonEmpty(bootsStack);
                    default -> null;
                };
            }

            @Override
            protected ModelPart getModelPartForBone(GeoBone bone, EquipmentSlot slot,
                                                    ItemStack stack, OrcEntity orc,
                                                    HumanoidModel<?> baseModel) {
                return switch (bone.getName()) {
                    case "head"                    -> baseModel.head;
                    case "right_arm"               -> baseModel.rightArm;
                    case "left_arm"                -> baseModel.leftArm;
                    case "right_leg", "right_boot" -> baseModel.rightLeg;
                    case "left_leg",  "left_boot"  -> baseModel.leftLeg;
                    default                        -> baseModel.body;
                };
            }

            @Nullable
            private ItemStack nonEmpty(@Nullable ItemStack stack) {
                return (stack != null && !stack.isEmpty()) ? stack : null;
            }
        });

        // Held-item rendering — GeoEntityRenderer skips vanilla's ItemInHandLayer,
        // so we attach the main-hand item directly to the right_arm bone.
        addRenderLayer(new BlockAndItemGeoLayer<OrcEntity>(this,
            (bone, orc) -> {
                if (!"right_hand".equals(bone.getName())) return null;
                ItemStack held = orc.getMainHandItem();
                return held.isEmpty() ? null : held;
            },
            (bone, orc) -> null) {
            @Override
            protected ItemDisplayContext getTransformTypeForStack(GeoBone bone, ItemStack stack, OrcEntity animatable) {
                return ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
            }
        });
    }
}