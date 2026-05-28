package net.nuggz.lotrmc.registry;

import net.nuggz.lotrmc.LotrMC;
import net.nuggz.lotrmc.entity.OrcEntity;
import net.nuggz.lotrmc.entity.client.OrcRenderer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, LotrMC.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<OrcEntity>> ORC =
            ENTITY_TYPES.register("orc", () ->
                    EntityType.Builder.<OrcEntity>of(OrcEntity::new, MobCategory.MONSTER)
                            .sized(0.6f, 1.95f)   // same footprint as a player
                            .clientTrackingRange(8)
                            .build("orc")
            );

    // -------------------------------------------------------------------------
    // Attribute registration (runs on MOD event bus, both sides)
    // -------------------------------------------------------------------------

    @EventBusSubscriber(modid = LotrMC.MODID)
    public static class ModEvents {

        @SubscribeEvent
        public static void onAttributeCreate(EntityAttributeCreationEvent event) {
            event.put(ORC.get(), OrcEntity.createAttributes().build());
        }
    }

    // -------------------------------------------------------------------------
    // Renderer registration (CLIENT only, MOD event bus)
    // -------------------------------------------------------------------------

    @EventBusSubscriber(modid = LotrMC.MODID, value = Dist.CLIENT)
    public static class ClientEvents {

        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(ORC.get(), OrcRenderer::new);
        }
    }
}
