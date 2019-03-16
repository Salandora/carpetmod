package carpet.helpers.entities;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.EntityWaterMob;
import net.minecraft.world.World;

public abstract class AbstractWaterCreature extends EntityWaterMob {

    protected AbstractWaterCreature(EntityType<?> type, World p_i48565_2_) {
        super(type, p_i48565_2_);
    }
}
