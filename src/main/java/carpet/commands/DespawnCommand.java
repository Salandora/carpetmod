package carpet.commands;

import carpet.CarpetSettings;
import carpet.utils.Messenger;
import carpet.utils.SpawnReporter;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandSource;
import net.minecraft.command.ISuggestionProvider;
import net.minecraft.command.arguments.DimensionArgument;
import net.minecraft.command.arguments.EntitySummonArgument;
import net.minecraft.command.arguments.SuggestionProviders;
import net.minecraft.entity.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.IRegistry;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.WorldServer;
import net.minecraft.world.dimension.DimensionType;

import java.util.ArrayList;
import java.util.List;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.command.Commands.argument;
import static net.minecraft.command.Commands.literal;

public class DespawnCommand
{
    public static void register(CommandDispatcher<CommandSource> dispatcher)
    {
        LiteralArgumentBuilder<CommandSource> literalargumentbuilder = literal("despawn").
                requires((player) -> CarpetSettings.getBool("commandDespawn") && player.hasPermissionLevel(2));

        literalargumentbuilder.
                then(literal("entity").
                        then(argument("entity", EntitySummonArgument.entitySummon()).suggests(SuggestionProviders.SUMMONABLE_ENTITIES).
                            executes( (c)-> despawn(c, EntitySummonArgument.getEntityId(c, "entity"), c.getSource().getWorld().dimension.getType())).
                            then(argument("dimension", DimensionArgument.func_212595_a()).
                                executes( (c) -> despawn(c, EntitySummonArgument.getEntityId(c, "entity"), DimensionArgument.func_212592_a(c, "dimension")))
                            )
                        )
                ).
                then(literal("type").
                    then(argument("type", StringArgumentType.word()).
                            suggests( (c, b) -> ISuggestionProvider.suggest(SpawnReporter.mob_groups,b)).
                            executes((c) -> despawnType(c, getString(c, "type"), c.getSource().getWorld().dimension.getType())).
                            then(argument("dimension", DimensionArgument.func_212595_a()).
                                    executes((c) -> despawnType(c, getString(c, "type"), DimensionArgument.func_212592_a(c, "dimension")))
                            )
                    )
                );

        dispatcher.register(literalargumentbuilder);
    }

    private static int despawn(CommandContext<CommandSource> c, ResourceLocation name, DimensionType dim)
    {
        if (dim == null)
            dim = c.getSource().getWorld().dimension.getType();

        EntityType<? extends Entity> type;
        try {
            type = IRegistry.field_212629_r.func_212608_b(name);
        } catch (Exception e) {
            Messenger.m(c.getSource(), "r Entity type not found.");
            return 0;
        }

        if (type == null)
        {
            Messenger.m(c.getSource(), "r Entity type not found.");
            return 0;
        }

        WorldServer w = c.getSource().getServer().getWorld(dim);
        if (w == null)
        {
            Messenger.m(c.getSource(), "r Dimension not found.");
            return 0;
        }

        int i = 0;
        Class<?> cls = type.getEntityClass();// getCreatureClass();
        for (Entity entity : w.loadedEntityList)
        {
            if (entity instanceof EntityLiving && ((EntityLiving)entity).canDespawn() && cls.isAssignableFrom(entity.getClass()))
            {
                ++i;
                entity.remove();
            }
        }

        Messenger.m(c.getSource(), String.format("g Despawned %d entites.", i));
        return 1;
    }

    private static int despawnType(CommandContext<CommandSource> c, String mobType, DimensionType dim)
    {
        if (dim == null)
            dim = c.getSource().getWorld().dimension.getType();

        EnumCreatureType type = SpawnReporter.get_creature_type_from_code(mobType);
        if (type == null)
        {
            Messenger.m(c.getSource(), String.format("r Incorrect creature type: %s", mobType));
            return 0;
        }

        WorldServer w = c.getSource().getServer().getWorld(dim);
        if (w == null)
        {
            Messenger.m(c.getSource(), "r Dimension not found.");
            return 0;
        }

        int i = 0;
        Class<?> cls = type.getBaseClass();// getCreatureClass();
        for (Entity entity : w.loadedEntityList)
        {
            if (entity instanceof EntityLiving && ((EntityLiving)entity).canDespawn() && cls.isAssignableFrom(entity.getClass()))
            {
                ++i;
                entity.remove();
            }
        }

        Messenger.m(c.getSource(), String.format("g Despawned %d entites.", i));
        return 1;
    }
}
