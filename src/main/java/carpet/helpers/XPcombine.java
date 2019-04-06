package carpet.helpers;

import net.minecraft.entity.item.EntityXPOrb;

public class XPcombine
{
    public static void searchForOtherXPNearbyCarpet(EntityXPOrb first)
    {
        for (EntityXPOrb entityxp : first.world.getEntitiesWithinAABB(EntityXPOrb.class, first.getBoundingBox().grow(0.5D, 0.0D, 0.5D)))
        {
            combineItems(first, entityxp);
        }
    }

    private static boolean combineItems(EntityXPOrb first, EntityXPOrb other)
    {
        if (
                first == other
                || !first.isAlive() || !other.isAlive()
                || first.delayBeforeCanPickup == 32767 || other.delayBeforeCanPickup == 32767
                || first.xpOrbAge == -32768 || other.xpOrbAge == -32768
                || first.delayBeforeCombine != 0 || other.delayBeforeCombine != 0
        )
        {
            return false;
        }

        int size = getTextureByXP(first.getXpValue());
        first.setXpValue(first.getXpValue() + other.getXpValue());
        first.delayBeforeCanPickup = Math.max(first.delayBeforeCanPickup, other.delayBeforeCanPickup);
        first.xpOrbAge = Math.min(first.xpOrbAge, other.xpOrbAge);
        other.remove();

        if (getTextureByXP(first.getXpValue()) != size)
        {
            first.world.spawnEntity(new EntityXPOrb(first.world, first.getXpValue(), first));
            first.remove();
        }
        else
        {
            first.delayBeforeCombine = 50;
        }
        return true;
    }

    // COPY FROM CLIENT CODE
    private static int getTextureByXP(int xpValue)
    {
        if (xpValue >= 2477)
        {
            return 10;
        }
        else if (xpValue >= 1237)
        {
            return 9;
        }
        else if (xpValue >= 617)
        {
            return 8;
        }
        else if (xpValue >= 307)
        {
            return 7;
        }
        else if (xpValue >= 149)
        {
            return 6;
        }
        else if (xpValue >= 73)
        {
            return 5;
        }
        else if (xpValue >= 37)
        {
            return 4;
        }
        else if (xpValue >= 17)
        {
            return 3;
        }
        else if (xpValue >= 7)
        {
            return 2;
        }
        else
        {
            return xpValue >= 3 ? 1 : 0;
        }
    }
}
