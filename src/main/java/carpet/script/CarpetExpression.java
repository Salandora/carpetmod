package carpet.script;

import carpet.CarpetSettings;
import carpet.helpers.FeatureGenerator;
import carpet.script.Expression.ExpressionException;
import carpet.script.Expression.InternalExpressionException;
import carpet.utils.BlockInfo;
import carpet.utils.Messenger;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.CommandSource;
import net.minecraft.command.arguments.EntitySelector;
import net.minecraft.command.arguments.EntitySelectorParser;
import net.minecraft.command.arguments.ParticleArgument;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.particles.IParticleData;
import net.minecraft.pathfinding.PathType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.state.IProperty;
import net.minecraft.state.StateContainer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.IRegistry;
import net.minecraft.world.EnumLightType;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.storage.SessionLockException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiFunction;

import static java.lang.Math.abs;

public class CarpetExpression
{
    public static class CarpetExpressionException extends ExpressionException
    {
        CarpetExpressionException(String message)
        {
            super(message);
        }
    }
    private static class CarpetContext extends Context
    {
        public CommandSource s;
        public BlockPos origin;
        CarpetContext(Expression expr, CommandSource source, BlockPos origin)
        {
            super(expr);
            s = source;
            this.origin = origin;
        }
    }

    private CommandSource source;
    private BlockPos origin;
    private Expression expr;

    public static class BlockValue extends Value
    {
        public static final BlockValue AIR = new BlockValue(Blocks.AIR.getDefaultState(), null, BlockPos.ORIGIN);
        public static final BlockValue NULL = new BlockValue(null, null, null);
        private IBlockState blockState;
        private BlockPos pos;
        private World world;

        public IBlockState getBlockState()
        {
            if (blockState != null)
            {
                return blockState;
            }
            if (world != null && pos != null)
            {
                blockState = world.getBlockState(pos);
                return blockState;
            }
            throw new InternalExpressionException("Attemted to fetch blockstate without world or stored blockstate");
        }

        BlockValue(IBlockState state, World world, BlockPos position)
        {
            this.world = world;
            blockState = state;
            pos = position;
        }


        @Override
        public String getString()
        {
            return IRegistry.field_212618_g.getKey(getBlockState().getBlock()).getPath();
        }

        @Override
        public boolean getBoolean()
        {
            return this != NULL && !getBlockState().isAir();
        }

        @Override
        public Value clone()
        {
            return new BlockValue(blockState, world, pos);
        }

        public BlockPos getPos()
        {
            return pos;
        }


    }
    private BlockValue blockValueFromCoords(CarpetContext c, int x, int y, int z)
    {
        BlockPos pos = locateBlockPos(c, x,y,z);
        return new BlockValue(null, c.s.getWorld(), pos);
    }

    private BlockValue blockFromString(String str)
    {
        try
        {
            ResourceLocation blockId = ResourceLocation.read(new StringReader(str));
            if (IRegistry.field_212618_g.func_212607_c(blockId))
            {

                Block block = IRegistry.field_212618_g.get(blockId);
                return new BlockValue(block.getDefaultState(), null, null);
            }
        }
        catch (CommandSyntaxException ignored)
        {
        }
        return BlockValue.NULL;
    }

    private static class LocatorResult
    {
        BlockValue block;
        int offset;
        LocatorResult(BlockValue b, int o)
        {
            block = b;
            offset = o;
        }
    }
    private LocatorResult locateBlockValue(CarpetContext c, List<LazyValue> params, int offset)
    {
        try
        {
            Value v1 = params.get(0 + offset).evalValue(c);
            if (v1 instanceof BlockValue)
            {
                return new LocatorResult(((BlockValue) v1), 1+offset);
            }
            int xpos = (int) ((NumericValue) v1).getLong();
            int ypos = (int) ((NumericValue) params.get(1 + offset).evalValue(c)).getLong();
            int zpos = (int) ((NumericValue) params.get(2 + offset).evalValue(c)).getLong();
            return new LocatorResult(
                    new BlockValue(
                            null,
                            c.s.getWorld(),
                            new BlockPos(c.origin.getX() + xpos, c.origin.getY() + ypos, c.origin.getZ() + zpos)
                    ),
                    3+offset
            );
        }
        catch (IndexOutOfBoundsException e)
        {
            throw new InternalExpressionException("position should be defined either by three coordinates, or a block value");
        }
    }

    private static class VectorLocator
    {
        Vec3d vec;
        int offset;
        VectorLocator(Vec3d v, int o)
        {
            vec = v;
            offset = o;
        }
    }
    private VectorLocator locateVec(CarpetContext c, List<LazyValue> params, int offset)
    {
        try
        {
            Value v1 = params.get(0 + offset).evalValue(c);
            if (v1 instanceof BlockValue)
            {
                return new VectorLocator(new Vec3d(((BlockValue) v1).getPos()).add(0.5,0.5,0.5), 1+offset);
            }
            return new VectorLocator( new Vec3d(
                    Expression.getNumericValue(v1).getDouble(),
                    Expression.getNumericValue(params.get(1 + offset).evalValue(c)).getDouble(),
                    Expression.getNumericValue(params.get(2 + offset).evalValue(c)).getDouble()),
                    offset+3
            );
        }
        catch (IndexOutOfBoundsException e)
        {
            throw new InternalExpressionException("position should be defined either by three coordinates, or a block value");
        }
    }

    private BlockPos locateBlockPos(CarpetContext c, int xpos, int ypos, int zpos)
    {
        return new BlockPos(c.origin.getX() + xpos, c.origin.getY() + ypos, c.origin.getZ() + zpos);
    }


    private LazyValue booleanStateTest(
            Context c,
            String name,
            List<LazyValue> params,
            BiFunction<IBlockState, BlockPos, Boolean> test
    )
    {
        CarpetContext cc = (CarpetContext) c;
        if (params.size() == 0)
        {
            throw new InternalExpressionException(name + " requires at least one parameter");
        }
        Value v0 = params.get(0).evalValue(c);
        if (v0 instanceof BlockValue)
            return (c_, t_) -> test.apply(((BlockValue) v0).getBlockState(), ((BlockValue) v0).pos) ? Value.TRUE : Value.FALSE;
        BlockValue block = locateBlockValue(cc, params, 0).block;
        return (c_, t_) -> test.apply(block.getBlockState(), block.getPos()) ? Value.TRUE : Value.FALSE;
    }

    private LazyValue stateStringQuery(
            Context c,
            String name,
            List<LazyValue> params,
            BiFunction<IBlockState, BlockPos, String> test
    )
    {
        CarpetContext cc = (CarpetContext) c;
        if (params.size() == 0)
        {
            throw new InternalExpressionException(name + " requires at least one parameter");
        }

        Value v0 = params.get(0).evalValue(c);
        if (v0 instanceof BlockValue)
            return (c_, t_) -> new StringValue(test.apply( ((BlockValue) v0).getBlockState(), ((BlockValue) v0).pos));
        BlockValue block = locateBlockValue(cc, params, 0).block;
        return (c_, t_) -> new StringValue(test.apply(block.getBlockState(), block.getPos()));
    }


    private <T extends Comparable<T>> IBlockState setProperty(IProperty<T> property, String name, String value,
                                                              IBlockState bs)
    {
        Optional<T> optional = property.parseValue(value);

        if (optional.isPresent())
        {
            bs = bs.with(property, optional.get());
        }
        else
        {
            throw new InternalExpressionException(value + " is not a valid value for property " + name);
        }
        return bs;
    }

    public CarpetExpression(String expression, CommandSource source, BlockPos origin)
    {
        this.origin = origin;
        this.source = source;
        this.expr = new Expression(expression);

        this.expr.defaultVariables.put("_x", (c, t) -> new NumericValue(origin.getX()).boundTo("_x"));
        this.expr.defaultVariables.put("_y", (c, t) -> new NumericValue(origin.getY()).boundTo("_y"));
        this.expr.defaultVariables.put("_z", (c, t) -> new NumericValue(origin.getZ()).boundTo("_z"));

        this.expr.addLazyFunction("block", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
                if (lv.size() == 0)
                {
                    throw new InternalExpressionException("block requires at least one parameter");
                }
                if (lv.size() == 1)
                {
                    return (c_, t_) -> blockFromString(lv.get(0).evalValue(cc).getString());
                    //return new BlockValue(IRegistry.field_212618_g.get(new ResourceLocation(lv.get(0).getString())).getDefaultState(), origin);
                }
                return (c_, t_) -> locateBlockValue(cc, lv, 0).block;
        });

        this.expr.addLazyFunction("pos", 1, (c, t, lv) ->
        {
            Value arg = lv.get(0).evalValue(c);
            if (!(arg instanceof BlockValue ))
                throw new InternalExpressionException("you can only get position of a block type");
            BlockPos pos = ((BlockValue)arg).pos;
            if (pos == null)
                throw new InternalExpressionException("cannot fetch position of unlocalized block");
            return (c_, t_) -> new ListValue(Arrays.asList(new NumericValue(pos.getX()),new NumericValue(pos.getY()),new NumericValue(pos.getZ())));
        });

        this.expr.addLazyFunction("player", -1, (c, t, lv) -> {
            return LazyValue.NULL;
        });

        this.expr.addLazyFunction("entity", -1, (c, t, lv) -> {
            String selector = lv.get(0).evalValue(c).getString();
            EntitySelector entityselector = new EntitySelectorParser(new StringReader(selector), true).build();
            try
            {
                Collection<? extends Entity > entities = entityselector.select(((CarpetContext)c).s);
                List<Value> retlist = new ArrayList<>();
                for (Entity e: entities)
                {
                    retlist.add(new StringValue(e.toString()));
                }
                return (c_, t_) -> ListValue.wrap(retlist);
            }
            catch (CommandSyntaxException e)
            {
                throw new InternalExpressionException("Cannot select entities from "+selector);
            }
        });


        this.expr.addLazyFunction("solid", -1, (c, t, lv) ->
                booleanStateTest(c, "solid", lv, (s, p) -> s.isSolid()));

        this.expr.addLazyFunction("air", -1, (c, t, lv) ->
                booleanStateTest(c, "air", lv, (s, p) -> s.isAir()));

        this.expr.addLazyFunction("liquid", -1, (c, t, lv) ->
                booleanStateTest(c, "liquid", lv, (s, p) -> !s.getFluidState().isEmpty()));

        this.expr.addLazyFunction("light", -1, (c, t, lv) ->
                (c_, t_) -> new NumericValue(((CarpetContext)c).s.getWorld().getLight(locateBlockValue((CarpetContext) c, lv, 0).block.getPos())));

        this.expr.addLazyFunction("blockLight", -1, (c, t, lv) ->
                (c_, t_) -> new NumericValue(((CarpetContext)c).s.getWorld().getLightFor(EnumLightType.BLOCK, locateBlockValue((CarpetContext) c, lv, 0).block.getPos())));

        this.expr.addLazyFunction("skyLight", -1, (c, t, lv) ->
                (c_, t_) -> new NumericValue(((CarpetContext)c).s.getWorld().getLightFor(EnumLightType.SKY, locateBlockValue((CarpetContext) c, lv, 0).block.getPos())));

        this.expr.addLazyFunction("seeSky", -1, (c, t, lv) ->
                (c_, t_) -> new NumericValue(((CarpetContext)c).s.getWorld().canSeeSky(locateBlockValue((CarpetContext) c, lv, 0).block.getPos())));

        this.expr.addLazyFunction("top", -1, (c, t, lv) -> {
            String type = lv.get(0).evalValue(c).getString().toLowerCase(Locale.ROOT);
            Heightmap.Type htype;
            switch (type)
            {
                case "light": htype = Heightmap.Type.LIGHT_BLOCKING; break;
                case "motion": htype = Heightmap.Type.MOTION_BLOCKING; break;
                case "surface": htype = Heightmap.Type.WORLD_SURFACE; break;
                case "ocean floor": htype = Heightmap.Type.OCEAN_FLOOR; break;
                case "terrain": htype = Heightmap.Type.MOTION_BLOCKING_NO_LEAVES; break;
                default: htype = Heightmap.Type.LIGHT_BLOCKING;
            }
            int x;
            int z;
            Value v1 = lv.get(1).evalValue(c);
            if (v1 instanceof BlockValue)
            {
                BlockPos inpos = ((BlockValue)v1).pos;
                x = inpos.getX();
                z = inpos.getZ();
            }
            else
            {
                x = (int)Expression.getNumericValue(lv.get(1).evalValue(c)).getLong();
                z = (int)Expression.getNumericValue(lv.get(2).evalValue(c)).getLong();
            }
            int y = ((CarpetContext)c).s.getWorld().getChunk(x >> 4, z >> 4).getTopBlockY(htype, x & 15, z & 15) + 1;
            return (c_, t_) -> new NumericValue(y);
            //BlockPos pos = new BlockPos(x,y,z);
            //return new BlockValue(source.getWorld().getBlockState(pos), pos);
        });

        this.expr.addLazyFunction("loaded", -1, (c, t, lv) ->
                (c_, t_) -> ((CarpetContext)c).s.getWorld().isBlockLoaded(locateBlockValue((CarpetContext) c, lv, 0).block.getPos()) ? Value.TRUE : Value.FALSE);

        this.expr.addLazyFunction("loadedEP", -1, (c, t, lv) ->
        {
            BlockPos pos = locateBlockValue((CarpetContext)c, lv, 0).block.getPos();
            return (c_, t_) -> ((CarpetContext)c).s.getWorld().isAreaLoaded(pos.getX() - 32, 0, pos.getZ() - 32,
                    pos.getX() + 32, 0, pos.getZ() + 32, true) ? Value.TRUE : Value.FALSE;
        });

        this.expr.addLazyFunction("suffocates", -1, (c, t, lv) ->
                booleanStateTest(c, "suffocates", lv, (s, p) -> s.causesSuffocation()));

        this.expr.addLazyFunction("power", -1, (c, t, lv) ->
                (c_, t_) -> new NumericValue(((CarpetContext)c).s.getWorld().getRedstonePowerFromNeighbors(locateBlockValue((CarpetContext) c, lv, 0).block.getPos())));

        this.expr.addLazyFunction("ticksRandomly", -1, (c, t, lv) ->
                booleanStateTest(c, "ticksRandomly", lv, (s, p) -> s.needsRandomTick()));

        this.expr.addLazyFunction("update", -1, (c, t, lv) ->
                booleanStateTest(c, "update", lv, (s, p) ->
                {
                    ((CarpetContext) c).s.getWorld().neighborChanged(p, s.getBlock(), p);
                    return true;
                }));

        this.expr.addLazyFunction("forcetick", -1, (c, t, lv) ->
                booleanStateTest(c, "forcetick", lv, (s, p) ->
                {
                    World w = ((CarpetContext)c).s.getWorld();
                    s.randomTick(w, p, w.rand);
                    return true;
                }));

        this.expr.addLazyFunction("randomtick", -1, (c, t, lv) ->
                booleanStateTest(c, "randomtick", lv, (s, p) ->
                {
                    World w = ((CarpetContext)c).s.getWorld();
                    if (s.needsRandomTick() || s.getFluidState().getTickRandomly())
                        s.randomTick(w, p, w.rand);
                    return true;
                }));


        this.expr.addLazyFunction("set", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext)c;
            World world = cc.s.getWorld();
            if (lv.size() < 2 || lv.size() % 2 == 1)
                throw new InternalExpressionException("set block should have at least 2 params and odd attributes");
            LocatorResult locator = locateBlockValue(cc, lv, 0);
            Value v3 = lv.get(locator.offset).evalValue(cc);
            BlockValue bv = ((v3 instanceof BlockValue)) ? (BlockValue) v3 : blockFromString(v3.getString());
            if (bv == BlockValue.NULL)
                throw new InternalExpressionException("block to set to should be a valid block");
            IBlockState bs = bv.getBlockState();

            IBlockState targetBlockState = world.getBlockState(locator.block.getPos());
            if (lv.size()==1+locator.offset) // no reqs for properties
                if (targetBlockState.getBlock() == bs.getBlock())
                    return (c_, t_) -> Value.FALSE;

            StateContainer<Block, IBlockState> states = bs.getBlock().getStateContainer();

            for (int i = 1+locator.offset; i < lv.size(); i += 2)
            {
                String paramString = lv.get(i).evalValue(c).getString();
                IProperty<?> property = states.getProperty(paramString);
                if (property == null)
                    throw new InternalExpressionException("property " + paramString + " doesn't apply to " + v3.getString());

                String paramValue = lv.get(i + 1).evalValue(c).getString();

                bs = setProperty(property, paramString, paramValue, bs);
            }
            cc.s.getWorld().setBlockState(locator.block.getPos(), bs, 2 | (CarpetSettings.getBool("fillUpdates") ? 0 : 1024));
            final IBlockState finalBS = bs;
            return (c_, t_) -> new BlockValue(finalBS, world, locator.block.getPos());
        });

        this.expr.addLazyFunction("blocksMovement", -1, (c, t, lv) ->
                booleanStateTest(c, "blocksMovement", lv, (s, p) ->
                        !s.allowsMovement(((CarpetContext) c).s.getWorld(), p, PathType.LAND)));

        this.expr.addLazyFunction("sound", -1, (c, t, lv) ->
                stateStringQuery(c, "sound", lv, (s, p) ->
                        BlockInfo.soundName.get(s.getBlock().getSoundType())));

        this.expr.addLazyFunction("material",-1, (c, t, lv) ->
                stateStringQuery(c, "material", lv, (s, p) ->
                        BlockInfo.materialName.get(s.getMaterial())));

        this.expr.addLazyFunction("mapColour", -1,  (c, t, lv) ->
                stateStringQuery(c, "mapColour", lv, (s, p) ->
                        BlockInfo.mapColourName.get(s.getMapColor(((CarpetContext)c).s.getWorld(), p))));

        this.expr.addLazyFunction("property", -1, (c, t, lv) ->
        {
            LocatorResult locator = locateBlockValue((CarpetContext) c, lv, 0);
            IBlockState state = locator.block.getBlockState();
            String tag = lv.get(locator.offset).evalValue(c).getString();
            StateContainer<Block, IBlockState> states = state.getBlock().getStateContainer();
            IProperty<?> property = states.getProperty(tag);
            if (property == null)
                return LazyValue.NULL;
            return (_c, _t ) -> new StringValue(state.get(property).toString());
        });

        //particle(x,y,z,"particle",count?10, duration,bool all)
        this.expr.addLazyFunction("particle", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext)c;
            MinecraftServer ms = cc.s.getServer();
            WorldServer world = cc.s.getWorld();
            VectorLocator locator = locateVec(cc, lv, 0);
            String particleName = lv.get(locator.offset).evalValue(c).getString();
            int count = 10;
            double speed = 0;
            float spread = 0.5f;
            EntityPlayerMP player = null;
            if (lv.size() > 1+locator.offset)
            {
                count = (int)Expression.getNumericValue(lv.get(1+locator.offset).evalValue(c)).getLong();
                if (lv.size() > 2+locator.offset)
                {
                    spread = (float)Expression.getNumericValue(lv.get(2+locator.offset).evalValue(c)).getDouble();
                    if (lv.size() > 3+locator.offset)
                    {
                        speed = Expression.getNumericValue(lv.get(3 + locator.offset).evalValue(c)).getDouble();
                        if (lv.size() > 4 + locator.offset) // should accept entity as well as long as it is player
                        {
                            player = ms.getPlayerList().getPlayerByUsername(lv.get(4 + locator.offset).evalValue(c).getString());
                        }
                    }
                }
            }
            IParticleData particle;
            try
            {
                particle = ParticleArgument.func_197189_a(new StringReader(particleName));
            }
            catch (CommandSyntaxException e)
            {
                return (c_, t_) -> Value.NULL;
            }
            Vec3d vec = locator.vec;
            if (player == null)
            {
                for (EntityPlayerMP p : (ms.getPlayerList().getPlayers()))
                {
                    world.spawnParticle(p, particle, true, vec.x, vec.y, vec.z, count,
                            spread, spread, spread, speed);
                }
            }
            else
            {
                world.spawnParticle(player,
                    particle, true, vec.x, vec.y, vec.z, count,
                    spread, spread, spread, speed);
            }

            return (c_, t_) -> Value.TRUE;
        });

        // consider changing to scan
        this.expr.addLazyFunction("scan", 7, (c, t, lv) ->
        {
            int cx = (int)Expression.getNumericValue(lv.get(0).evalValue(c)).getLong();
            int cy = (int)Expression.getNumericValue(lv.get(1).evalValue(c)).getLong();
            int cz = (int)Expression.getNumericValue(lv.get(2).evalValue(c)).getLong();
            int xrange = (int)Expression.getNumericValue(lv.get(3).evalValue(c)).getLong();
            int yrange = (int)Expression.getNumericValue(lv.get(4).evalValue(c)).getLong();
            int zrange = (int)Expression.getNumericValue(lv.get(5).evalValue(c)).getLong();
            LazyValue expr = lv.get(6);

            //saving outer scope
            LazyValue _x = c.getVariable("_x");
            LazyValue _y = c.getVariable("_y");
            LazyValue _z = c.getVariable("_z");
            LazyValue __ = c.getVariable("_");
            int sCount = 0;
            for (int y=cy-yrange; y <= cy+yrange; y++)
            {
                int yFinal = y;
                c.setVariable("_y", (c_, t_) -> new NumericValue(yFinal).boundTo("_y"));
                for (int x=cx-xrange; x <= cx+xrange; x++)
                {
                    int xFinal = x;
                    c.setVariable("_x", (c_, t_) -> new NumericValue(xFinal).boundTo("_x"));
                    for (int z=cz-zrange; z <= cz+zrange; z++)
                    {
                        int zFinal = z;
                        c.setVariable( "_", (cc_, t_c) -> blockValueFromCoords(((CarpetContext)c), xFinal,yFinal,zFinal).boundTo("_"));
                        c.setVariable("_z", (c_, t_) -> new NumericValue(zFinal).boundTo("_z"));
                        if (expr.evalValue(c).getBoolean())
                        {
                            sCount += 1;
                        }
                    }
                }
            }
            //restoring outer scope
            c.setVariable("_x", _x);
            c.setVariable("_y", _y);
            c.setVariable("_z", _z);
            c.setVariable("_", __);
            int finalSCount = sCount;
            return (c_, t_) -> new NumericValue(finalSCount);
        });

        this.expr.addLazyFunction("print", 1, (c, t, lv) ->
        {
            Messenger.m(((CarpetContext)c).s, "w " + lv.get(0).evalValue(c).getString());
            return lv.get(0); // pass through for variables
        });


        this.expr.addLazyFunction("run", 1, (c, t, lv) -> {
            BlockPos target = locateBlockPos((CarpetContext) c,
                    (int)Expression.getNumericValue(c.getVariable("x").evalValue(c)).getLong(),
                    (int)Expression.getNumericValue(c.getVariable("y").evalValue(c)).getLong(),
                    (int)Expression.getNumericValue(c.getVariable("z").evalValue(c)).getLong()
            );
            Vec3d posf = new Vec3d((double)target.getX()+0.5D,(double)target.getY(),(double)target.getZ()+0.5D);
            CommandSource s = ((CarpetContext)c).s;
            return (c_, t_) -> new NumericValue(s.getServer().getCommandManager().handleCommand(
                    s.withPos(posf).withFeedbackDisabled(), lv.get(0).evalValue(c).getString()));
        });

        this.expr.addLazyFunction("save", 0, (c, t, lv) -> {
            CommandSource s = ((CarpetContext)c).s;

            s.getServer().getPlayerList().saveAllPlayerData();
            boolean saving = s.getWorld().disableLevelSaving;
            s.getWorld().disableLevelSaving = false;
            try
            {
                s.getWorld().saveAllChunks(true,null);
            }
            catch (SessionLockException ignored) { }
            s.getWorld().getChunkProvider().tick(() -> true);
            s.getWorld().getChunkProvider().flushToDisk();
            s.getWorld().disableLevelSaving = saving;
            CarpetSettings.LOG.warn("Saved chunks");
            return (cc, tt) -> Value.TRUE;
        });

        this.expr.addLazyFunction("tick", -1, (c, t, lv) -> {
            CommandSource s = ((CarpetContext)c).s;
            long start = System.nanoTime();
            s.getServer().tick( () -> System.nanoTime()-start<50000000);
            s.getServer().dontPanic(); // optional not to freak out the watchdog
            if (lv.size()>0)
            {
                long ms_total = Expression.getNumericValue(lv.get(0).evalValue(c)).getLong();
                long end_expected = start+ms_total*1000000;
                long wait = end_expected-System.nanoTime();
                if (wait > 0)
                {
                    try
                    {
                        Thread.sleep(wait/1000000);
                    }
                    catch (InterruptedException ignored)
                    {
                    }
                }
            }
            Thread.yield();
            return (cc, tt) -> Value.TRUE;
        });



        this.expr.addLazyFunction("neighbours", -1, (c, t, lv)->
        {

            BlockPos center = locateBlockValue((CarpetContext) c, lv,0).block.getPos();
            World world = ((CarpetContext) c).s.getWorld();

            List<Value> neighbours = new ArrayList<>();
            neighbours.add(new BlockValue(null, world, center.up()));
            neighbours.add(new BlockValue(null, world, center.down()));
            neighbours.add(new BlockValue(null, world, center.north()));
            neighbours.add(new BlockValue(null, world, center.south()));
            neighbours.add(new BlockValue(null, world, center.east()));
            neighbours.add(new BlockValue(null, world, center.west()));
            return (c_, t_) -> ListValue.wrap(neighbours);
        });

        this.expr.addLazyFunction("rect", -1, (c, t, lv)->
        {
            if (lv.size() != 3 && lv.size() != 6 && lv.size() != 9)
            {
                throw new InternalExpressionException("rectangular region should be specified with 3, 6, or 9 coordinates");
            }
            int cx;
            int cy;
            int cz;
            int sminx;
            int sminy;
            int sminz;
            int smaxx;
            int smaxy;
            int smaxz;
            try
            {
                cx = (int)((NumericValue) lv.get(0).evalValue(c)).getLong();
                cy = (int)((NumericValue) lv.get(1).evalValue(c)).getLong();
                cz = (int)((NumericValue) lv.get(2).evalValue(c)).getLong();
                if (lv.size()==3) // only done this way because of stupid Java lambda final reqs
                {
                    sminx = 1;
                    sminy = 1;
                    sminz = 1;
                    smaxx = 1;
                    smaxy = 1;
                    smaxz = 1;
                }
                else if (lv.size()==6)
                {
                    sminx = (int) ((NumericValue) lv.get(3).evalValue(c)).getLong();
                    sminy = (int) ((NumericValue) lv.get(4).evalValue(c)).getLong();
                    sminz = (int) ((NumericValue) lv.get(5).evalValue(c)).getLong();
                    smaxx = sminx;
                    smaxy = sminy;
                    smaxz = sminz;
                }
                else // size == 9
                {
                    sminx = (int) ((NumericValue) lv.get(3).evalValue(c)).getLong();
                    sminy = (int) ((NumericValue) lv.get(4).evalValue(c)).getLong();
                    sminz = (int) ((NumericValue) lv.get(5).evalValue(c)).getLong();
                    smaxx = (int)((NumericValue) lv.get(6).evalValue(c)).getLong();
                    smaxy = (int)((NumericValue) lv.get(7).evalValue(c)).getLong();
                    smaxz = (int)((NumericValue) lv.get(8).evalValue(c)).getLong();
                }
            }
            catch (ClassCastException exc)
            {
                throw new InternalExpressionException("Attempted to pass a non-number to rect");
            }
            CarpetContext cc = (CarpetContext)c;
            return (c_, t_) -> new LazyListValue()
            {
                int minx = cx-sminx;
                int miny = cy-sminy;
                int minz = cz-sminz;
                int maxx = cx+smaxx;
                int maxy = cy+smaxy;
                int maxz = cz+smaxz;
                int x;
                int y;
                int z;
                {
                    x = minx;
                    y = miny;
                    z = minz;
                }
                @Override
                public boolean hasNext()
                {
                    return y <= maxy;
                }

                @Override
                public Value next()
                {
                    Value r = blockValueFromCoords(cc, x,y,z);
                    //possibly reroll context
                    x++;
                    if (x > maxx)
                    {
                        x = minx;
                        z++;
                        if (z > maxz)
                        {
                            z = minz;
                            y++;
                            // hasNext should fail if we went over
                        }
                    }

                    return r;
                }

                @Override
                public void fatality()
                {
                    // possibly return original x, y, z
                }
            };
        });

        this.expr.addLazyFunction("diamond", -1, (c, t, lv)->
        {
            CarpetContext cc = (CarpetContext)c;
            if (lv.size() != 3 && lv.size() != 4 && lv.size() != 5)
            {
                throw new InternalExpressionException("diamond region should be specified with 3 to 5 coordinates");
            }

            int cx;
            int cy;
            int cz;
            int width;
            int height;
            try
            {
                cx = (int)((NumericValue) lv.get(0).evalValue(c)).getLong();
                cy = (int)((NumericValue) lv.get(1).evalValue(c)).getLong();
                cz = (int)((NumericValue) lv.get(2).evalValue(c)).getLong();
                if (lv.size()==3)
                {
                    return (_c, _t ) -> new ListValue(Arrays.asList(
                            blockValueFromCoords(cc, cx, cy-1, cz),
                            blockValueFromCoords(cc, cx, cy, cz),
                            blockValueFromCoords(cc, cx-1, cy, cz),
                            blockValueFromCoords(cc, cx, cy, cz-1),
                            blockValueFromCoords(cc, cx+1, cy, cz),
                            blockValueFromCoords(cc, cx, cy, cz+1),
                            blockValueFromCoords(cc, cx, cy+1, cz)
                    ));
                }
                else if (lv.size()==4)
                {
                    width = (int) ((NumericValue) lv.get(3).evalValue(c)).getLong();
                    height = 0;
                }
                else // size == 5
                {
                    width = (int) ((NumericValue) lv.get(3).evalValue(c)).getLong();
                    height = (int) ((NumericValue) lv.get(4).evalValue(c)).getLong();
                }
            }
            catch (ClassCastException exc)
            {
                throw new InternalExpressionException("Attempted to pass a non-number to diamond");
            }
            if (height == 0)
            {
                return (c_, t_) -> new LazyListValue()
                {
                    int curradius = 0;
                    int curpos = 0;
                    {

                    }
                    @Override
                    public boolean hasNext()
                    {
                        return curradius <= width;
                    }

                    @Override
                    public Value next()
                    {
                        if (curradius == 0)
                        {
                            curradius = 1;
                            return blockValueFromCoords(cc, cx, cy, cz);
                        }
                        // x = 3-|i-6|
                        // z = |( (i-3)%12-6|-3
                        Value block = blockValueFromCoords(cc, cx+(curradius-abs(curpos-2*curradius)), cy, cz-curradius+abs( abs(curpos-curradius)%(4*curradius) -2*curradius ));
                        curpos++;
                        if (curpos>=curradius*4)
                        {
                            curradius++;
                            curpos = 0;
                        }
                        return block;

                    }
                };
            }
            else
            {
                return (c_, t_) -> new LazyListValue()
                {
                    int curradius = 0;
                    int curpos = 0;
                    int curheight = -height;
                    @Override
                    public boolean hasNext()
                    {
                       return curheight <= height;
                    }

                    @Override
                    public Value next()
                    {
                        if (curheight == -height || curheight == height)
                        {
                            return blockValueFromCoords(cc, cx, cy+curheight++, cz);
                        }
                        if (curradius == 0)
                        {
                            curradius++;
                            return blockValueFromCoords(cc, cx, cy+curheight, cz);
                        }
                        // x = 3-|i-6|
                        // z = |( (i-3)%12-6|-3

                        Value block = blockValueFromCoords(cc, cx+(curradius-abs(curpos-2*curradius)), cy+curheight, cz-curradius+abs( abs(curpos-curradius)%(4*curradius) -2*curradius ));
                        curpos++;
                        if (curpos>=curradius*4)
                        {
                            curradius++;
                            curpos = 0;
                            if (curradius>width -abs(width*curheight/height))
                            {
                                curheight++;
                                curradius = 0;
                                curpos = 0;
                            }
                        }
                        return block;
                    }
                };

            }
        });

        //not ready yet
        this.expr.addLazyFunction("plop", 4, (c, t, lv) ->{
            LocatorResult locator = locateBlockValue((CarpetContext)c, lv, 0);
            Boolean res = FeatureGenerator.spawn(lv.get(locator.offset).evalValue(c).getString(), ((CarpetContext)c).s.getWorld(), locator.block.getPos());
            if (res == null)
                return (c_, t_) -> Value.NULL;
            return (c_, t_) -> new NumericValue(res);
        });
    }

    public boolean test(BlockPos pos)
    {
        return test(pos.getX(), pos.getY(), pos.getZ());
    }

    public boolean test(int x, int y, int z)
    {
        try
        {
            Context context = new CarpetContext(this.expr, source, origin).
                    with("x", (c, t) -> new NumericValue(x - origin.getX()).boundTo("x")).
                    with("y", (c, t) -> new NumericValue(y - origin.getY()).boundTo("y")).
                    with("z", (c, t) -> new NumericValue(z - origin.getZ()).boundTo("z"));
            return this.expr.eval(context).getBoolean();
        }
        catch (ExpressionException e)
        {
            throw new CarpetExpressionException(e.getMessage());
        }
    }

    public String eval(BlockPos pos)
    {
        return eval(pos.getX(), pos.getY(), pos.getZ());
    }


    public String eval(int x, int y, int z)
    {
        try
        {
            Context context = new CarpetContext(this.expr, source, origin).
                    with("x", (c, t) -> new NumericValue(x - origin.getX()).boundTo("x")).
                    with("y", (c, t) -> new NumericValue(y - origin.getY()).boundTo("y")).
                    with("z", (c, t) -> new NumericValue(z - origin.getZ()).boundTo("z"));
            return this.expr.eval(context).getString();
        }
        catch (ExpressionException e)
        {
            throw new CarpetExpressionException(e.getMessage());
        }
    }

    public static String invokeGlobal(CommandSource source, String call, List<String> argv)
    {
        Expression.UserDefinedFunction acf = Expression.global_functions.get(call);
        List<String> args = acf.getArguments();
        if (argv.size() != args.size())
        {
            return "Fail: stored function "+call+" takes "+args.size()+" arguments, not "+argv.size()+
                  ": "+String.join(", ", args);
        }
        try
        {
            Vec3d pos = source.getPos();
            Expression expr = new Expression(call+"("+String.join(" , ",argv)+" ) ");
            Context context = new CarpetContext(expr, source, BlockPos.ORIGIN).
                    with("x", (c, t) -> new NumericValue(Math.round(pos.x)).boundTo("x")).
                    with("y", (c, t) -> new NumericValue(Math.round(pos.y)).boundTo("y")).
                    with("z", (c, t) -> new NumericValue(Math.round(pos.z)).boundTo("z"));
            return expr.eval(context).getString();
        }
        catch (ExpressionException e)
        {
             return e.getMessage();
        }

    }


    public void execute()
    {
        this.expr.eval(new CarpetContext(this.expr, source, origin)).getString();
    }


    public void setLogOutput(boolean to)
    {
        this.expr.setLogOutput(to ? (s) -> Messenger.m(source, "gi " + s) : null);
    }
    public static void setChatErrorSnooper(CommandSource source)
    {
        ExpressionException.errorSnooper = (expr, token, message) ->
        {
            try
            {
                source.asPlayer();
            }
            catch (CommandSyntaxException e)
            {
                return null;
            }
            String[] lines = expr.getCodeString().split("\n");

            String shebang = message;

            if (lines.length > 1)
            {
                shebang += " at line "+(token.lineno+1)+", pos "+(token.linepos+1);
            }
            else
            {
                shebang += " at pos "+(token.pos+1);
            }
            if (expr.getName() != null)
            {
                shebang += " in "+expr.getName()+"";
            }
            Messenger.m(source, "r "+shebang);

            if (lines.length > 1 && token.lineno > 0)
            {
                Messenger.m(source, "l "+lines[token.lineno-1]);
            }
            Messenger.m(source, "l "+lines[token.lineno].substring(0, token.linepos), "r  HERE>> ", "l "+
                    lines[token.lineno].substring(token.linepos));

            if (lines.length > 1 && token.lineno < lines.length-1)
            {
                Messenger.m(source, "l "+lines[token.lineno+1]);
            }
            return new ArrayList<>();
        };
    }
    public static void resetErrorSnooper()
    {
        ExpressionException.errorSnooper=null;
    }
}
