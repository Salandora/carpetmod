package carpet.script;

import carpet.CarpetSettings;
import carpet.helpers.FeatureGenerator;
import carpet.script.Expression.ExpressionException;
import carpet.script.Expression.InternalExpressionException;
import carpet.utils.BlockInfo;
import carpet.utils.Messenger;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import javafx.util.Pair;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.CommandSource;
import net.minecraft.command.arguments.ParticleArgument;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketCustomSound;
import net.minecraft.particles.IParticleData;
import net.minecraft.pathfinding.PathType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.state.IProperty;
import net.minecraft.state.StateContainer;
import net.minecraft.util.EntitySelectors;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.IRegistry;
import net.minecraft.world.EnumLightType;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.storage.SessionLockException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.sqrt;

/**
 * <h1>Minecraft specific API and <code>scarpet</code> language add-ons and commands</h1>
 * <p>Here is the gist of the Minecraft related functions. Otherwise the CarpetScript could live without Minecraft.</p>
 * <h2>Dimension issues</h2>
 * <p>One note, which is important is that most of the calls for entities and blocks
 * would refer to the current dimension of the caller, meaning, that if we for example
 * list all the players using <code>player('all')</code> function, if a player is in the
 * other dimension, calls to entities and blocks around that player would point incorrectly.
 * Moreover, running commandblocks in the spawn chunks would mean that commands will always
 * refer to the overworld blocks and entities.
 * In case you would want to run commands across all dimensions, just run three of them, using
 * <code>/execute in overworld/the_nether/the_end run script run ...</code> and query
 * players using <code>player('*')</code>, which only returns players in current dimension.</p>
 */
public class CarpetExpression
{
    private CommandSource source;
    private BlockPos origin;
    private Expression expr;
    private static long tickStart = 0L;

    private static boolean stopAll = false;

    /**
     * <h1><code>script stop/script resume</code> command</h1>
     * <div style="padding-left: 20px; border-radius: 5px 45px; border:1px solid grey;">
     * <p>
     * <code>/script stop</code> allows to stop execution of any script currently running that calls the
     * <code>gametick()</code> function which
     * allows the game loop to regain control of the game and process other commands. This will also make sure
     * that all current and future programs will stop their execution. Execution of all programs will be
     * prevented until <code>/script resume</code> command is called.
     * </p>
     * <p>Lets look at the following example. This is a program computes Fibonacci number in a recursive manner:</p>
     * <pre>
     * fib(n) -&gt; if(n&lt;3, 1, fib(n-1)+fib(n-2) ); fib(8)
     * </pre>
     * <p> That's really bad way of doing it, because the higher number we need to compute the compute requirements will rise
     * exponentially with <code>n</code>. It takes a little over 50 milliseconds to do fib(24), so above one tick,
     * but about a minute to do fib(40). Calling fib(40) will not only freeze the game, but also you woudn't be able to interrupt
     * its execution. We can modify the script as follows</p>
     * <pre>fib(n) -&gt; ( gametick(50); if(n&lt;3, 1, fib(n-1)+fib(n-2) ) ); fib(40)</pre>
     * <p>But this would never finish as such call would finish after <code>~ 2^40</code> ticks. To make our computations
     * responsive, yet able to respond to user interactions, other commands, as well as interrupt execution,
     * we could do the following:</p>
     * <pre>fib(n) -&gt; ( if(n==23, gametick(50) ); if(n&lt;3, 1, fib(n-1)+fib(n-2) ) ); fib(40)</pre>
     * <p>This would slow down the computation of fib(40) from a minute to two, but allows the game to keep continue running
     * and be responsive to commands, using about half of each tick to advance the computation.
     * Obviously depending on the problem, and available hardware, certain things can take
     * more or less time to execute, so portioning of work with calling <code>gametick</code> should be balanced in each
     * case separately</p>
     * </div>
     * @param doStop .
     */
    public static void BreakExecutionOfAllScriptsWithCommands(boolean doStop)
    {
        stopAll = doStop;
    }

    static class CarpetContext extends Context
    {
        public CommandSource s;
        public BlockPos origin;
        CarpetContext(Expression expr, CommandSource source, BlockPos origin)
        {
            super(expr);
            s = source;
            this.origin = origin;
        }

        @Override
        public Context recreateFor(Expression e)
        {
            return new CarpetContext(e, this.s, this.origin);
        }

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
            return (c_, t_) -> test.apply(((BlockValue) v0).getBlockState(), ((BlockValue) v0).getPos()) ? Value.TRUE : Value.FALSE;
        BlockValue block = BlockValue.fromParams(cc, params, 0).block;
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
            return (c_, t_) -> new StringValue(test.apply( ((BlockValue) v0).getBlockState(), ((BlockValue) v0).getPos()));
        BlockValue block = BlockValue.fromParams(cc, params, 0).block;
        return (c_, t_) -> new StringValue(test.apply(block.getBlockState(), block.getPos()));
    }

    private LazyValue genericStateTest(
            Context c,
            String name,
            List<LazyValue> params,
            Expression.TriFunction<IBlockState, BlockPos, World, Value> test
    )
    {
        CarpetContext cc = (CarpetContext) c;
        if (params.size() == 0)
        {
            throw new InternalExpressionException(name + " requires at least one parameter");
        }
        Value v0 = params.get(0).evalValue(c);
        if (v0 instanceof BlockValue)
            return (c_, t_) ->
            {
                try
                {
                    return test.apply(((BlockValue) v0).getBlockState(), ((BlockValue) v0).getPos(), cc.s.getWorld());
                }
                catch (NullPointerException ignored)
                {
                    throw new InternalExpressionException(name+" function requires a block that is positioned in the world");
                }
            };
        BlockValue block = BlockValue.fromParams(cc, params, 0).block;
        return (c_, t_) -> test.apply(block.getBlockState(), block.getPos(), cc.s.getWorld());
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

    private int drawParticleLine(WorldServer world, IParticleData particle, Vec3d from, Vec3d to, double density)
    {
        double lineLengthSq = from.squareDistanceTo(to);
        if (lineLengthSq == 0) return 0;
        Vec3d incvec = to.subtract(from).scale(2*density/sqrt(lineLengthSq));
        int pcount = 0;
        for (Vec3d delta = new Vec3d(0.0,0.0,0.0);
             delta.lengthSquared()<lineLengthSq;
             delta = delta.add(incvec.scale(Expression.randomizer.nextFloat())))
        {
            for (EntityPlayer player : world.playerEntities)
            {
                world.spawnParticle((EntityPlayerMP)player, particle, true,
                        delta.x+from.x, delta.y+from.y, delta.z+from.z, 1,
                        0.0, 0.0, 0.0, 0.0);
                pcount ++;
            }
        }
        return pcount;
    }

    /**
     * <h1>Blocks / World API</h1>
     * <div style="padding-left: 20px; border-radius: 5px 45px; border:1px solid grey;">
     * <h2>Specifying blocks</h2>
     * <h3><code>block(x, y, z), block(state)</code></h3>
     * <p>Returns either a block from specified location, or block with a specific state
     * (as used by <code>/setblock</code> command), so allowing for block properties etc.
     * Blocks can be referenced everywhere by its simple name, but its only used in its default state</p>
     * <pre>
     * block('air')  =&gt; air
     * block('iron_trapdoor[half=top]')  =&gt; iron_trapdoor
     * block(0,0,0) == block('bedrock')  =&gt; 1
     * </pre>
     * <h2>World Manipulation</h2>
     * <p>All the functions below can be used with block value, queried with coord triple, or 3-long list.
     * All <code>pos</code> in the functions referenced below refer to either method of passing block position</p>
     * <h3><code>set(pos, block, property?, value?, ...)</code></h3>
     * <p>First part of the <code>set</code> function is either a coord triple, list of tree numbers, or other block
     * with coordinates. Second part, <code>block</code> is either block value as a result of <code>block()</code> function
     * string value indicating the block name, and optional <code>property - value</code> pairs for extra block properties.
     * If <code>block</code> is specified only by name, then if a destination block is the same the <code>set</code> operation
     * is skipped, otherwise is executed, for other potential extra properties</p>
     * <p>The returned value is either the block state that has been set, or <code>false</code> if block setting was skipped</p>
     * <pre>
     * set(0,5,0,'bedrock')  =&gt; bedrock
     * set(l(0,5,0), 'bedrock')  =&gt; bedrock
     * set(block(0,5,0), 'bedrock')  =&gt; bedrock
     * scan(0,5,0,0,0,0,set(_,'bedrock'))  =&gt; 1
     * set(pos(players()), 'bedrock')  =&gt; bedrock
     * set(0,0,0,'bedrock')  =&gt; 0   // or 1 in overworlds generated in 1.8 and before
     * scan(0,100,0,20,20,20,set(_,'glass'))
     *     // filling the area with glass
     * scan(0,100,0,20,20,20,set(_,block('glass')))
     *     // little bit faster due to internal caching of block state selectors
     * b = block('glass'); scan(0,100,0,20,20,20,set(_,b))
     *     // yet another option, skips all parsing
     * set(x,y,z,'iron_trapdoor')  // sets bottom iron trapdoor
     * set(x,y,z,'iron_trapdoor[half=top]')  // Incorrect. sets bottom iron trapdoor - no parsing of properties
     * set(x,y,z,'iron_trapdoor','half','top') // correct - top trapdoor
     * set(x,y,z,block('iron_trapdoor[half=top]')) // also correct, block() provides extra parsing
     * </pre>
     * <h3><code>update(pos)</code></h3>
     * <p>Causes a block update at position.</p>
     * <h3><code>block_tick(pos)</code></h3>
     * <p>Causes a block to tick at position.</p>
     * <h3><code>random_tick(pos)</code></h3>
     * <p>Causes a random tick at position.</p>
     *
     * <h2>Block and World querying</h2>
     *
     * <h3><code>pos(block), pos(entity)</code></h3>
     * <p>Returns a triple of coordinates of a specified block or entity. Technically entities are queried with
     * <code>query</code> function and the same can be achieved with <code>query(entity,'pos')</code>, but for simplicity
     * <code>pos</code> allows to pass all positional objects.</p>
     * <pre>
     *     pos(block(0,5,0))  =&gt; l(0,5,0)
     *     pos(players()) =&gt; l(12.3, 45.6, 32.05)
     *     pos(block('stone'))  =&gt; Error: Cannot fetch position of an unrealized block
     * </pre>
     * <h3><code>property(pos, name)</code></h3>
     * <p>Returns property of block at <code>pos</code>, or specified by <code>block</code> argument. If a block doesn't
     * have that property, <code>null</code> value is returned. Returned values are always strings. It is expected from
     * the user to know what to expect and convert values to numbers using <code>number()</code> function or booleans
     * using <code>bool()</code> function.</p>
     * <pre>
     *     set(x,y,z,'iron_trapdoor','half','top'); property(x,y,z,'half')  =&gt; top
     *     set(x,y,z,'air'); property(x,y,z,'half')  =&gt; null
     *     property(block('iron_trapdoor[half=top]'),'half')  =&gt; top
     *     property(block('iron_trapdoor[half=top]'),'powered')  =&gt; false
     *     bool(property(block('iron_trapdoor[half=top]'),'powered'))  =&gt; 0
     * </pre>
     *
     * <h3><code>solid(pos)</code></h3>
     * <p>Boolean function, true of the block is solid</p>
     * <h3> <code>air(pos)</code></h3>
     * <p>Boolean function, true if a block is air.... or cave air...
     * or void air.... or any other air they come up with.</p>
     * <h3><code>liquid(pos)</code></h3>
     * <p>Boolean function, true of the block is liquid.</p>
     * <h3><code>liquid(pos)</code></h3>
     * <p>Boolean function, true of the block is liquid, or liquidlogged</p>
     * <h3><code>flammable(pos)</code></h3>
     * <p>Boolean function, true of the block is flammable</p>
     * <h3><code>transparent(pos)</code></h3>
     * <p>Boolean function, true of the block is transparent</p>
     * <h3><code>opacity(pos)</code></h3>
     * <p>Numeric, returning opacity level of a block</p>
     * <h3><code>blocks_daylight(pos)</code></h3>
     * <p>Boolean function, true of the block blocks daylight</p>
     * <h3><code>emitted_light(pos)</code></h3>
     * <p>Numeric, returning light level emitted from block</p>
     * <h3><code>light(pos)</code></h3>
     * <p>Integer function, returning total light level at position</p>
     * <h3><code>block_light(pos)</code></h3>
     * <p>Integer function, returning block light at position. From torches and other light sources.</p>
     * <h3><code>sky_light(pos)</code></h3>
     * <p>Numeric function, returning sky light at position. From the sky access.</p>
     * <h3><code>see_sky(pos)</code></h3>
     * <p>Boolean function, returning true if the block can see sky.</p>
     * <h3><code>hardness(pos)</code></h3>
     * <p>Numeric function, indicating hardness of a block.</p>
     * <h3><code>blast_resistance(pos)</code></h3>
     * <p>Numeric function, indicating blast_resistance of a block.</p>

     * <h3><code>top(type, x, z)</code></h3>
     * <p>Returns the Y value of the topmost block at given x, z coords, according to the
     * heightmap specified by <code>type</code>. Valid options are:</p>
     * <ul>
     *     <li><code>light</code>: topmost light blocking block</li>
     *     <li><code>motion</code>: topmost motion blocking block</li>
     *     <li><code>terrain</code>: topmost motion blocking block except leaves</li>
     *     <li><code>ocean_floor</code>: topmost non-water block</li>
     *     <li><code>surface</code>: topmost surface block</li>
     * </ul>
     * <pre>
     * top('motion', x, z)  =&gt; 63
     * top('ocean_floor', x, z)  =&gt; 41
     * </pre>
     * <h3><code>loaded(pos)</code></h3>
     * <p>Boolean function, true of the block is loaded. Normally <code>scarpet</code> doesn't check if operates on
     * loaded area - the game will automatically load missing blocks. We see this as advantage.
     * Vanilla <code>fill/clone</code> commands only check the specified corners for loadness.</p>
     * <pre>
     * loaded(pos(players()))  =&gt; 1
     * loaded(100000,100,1000000)  =&gt; 0
     * </pre>
     * <h3><code>loaded_ep(pos)</code></h3>
     * <p>Boolean function, true of the block is loaded and entity processing, as per 1.13.2</p>
     * <h3><code>suffocates(pos)</code></h3>
     * <p>Boolean function, true of the block causes suffocation.</p>
     * <h3><code>power(pos)</code></h3>
     * <p>Numeric function, returning redstone power level at position.</p>
     * <h3><code>ticks_randomly(pos)</code></h3>
     * <p>Boolean function, true if the block ticks randomly.</p>
     * <h3><code>blocks_movement(pos)</code></h3>
     * <p>Boolean function, true if block at position blocks movement.</p>
     * <h3><code>block_sound(pos)</code></h3>
     * <p>Returns the name of sound type made by the block at position. One of:</p>
     * <ul>
     *     <li><code>wood     </code>  </li>
     *     <li><code>gravel   </code>  </li>
     *     <li><code>grass    </code>  </li>
     *     <li><code>stone    </code>  </li>
     *     <li><code>metal    </code>  </li>
     *     <li><code>glass    </code>  </li>
     *     <li><code>wool     </code>  </li>
     *     <li><code>sand     </code>  </li>
     *     <li><code>snow     </code>  </li>
     *     <li><code>ladder   </code>  </li>
     *     <li><code>anvil    </code>  </li>
     *     <li><code>slime    </code>  </li>
     *     <li><code>sea_grass</code>  </li>
     *     <li><code>coral    </code>  </li>
     * </ul>
     * <h3><code>material(pos)</code></h3>
     * <p>Returns the name of material of the block at position. very useful to target a group of blocks. One of:</p>
     * <ul>
     *     <li><code> air                </code>  </li>
     *     <li><code> void               </code>  </li>
     *     <li><code> portal             </code>  </li>
     *     <li><code> carpet             </code>  </li>
     *     <li><code> plant              </code>  </li>
     *     <li><code> water_plant        </code>  </li>
     *     <li><code> vine               </code>  </li>
     *     <li><code> sea_grass          </code>  </li>
     *     <li><code> water              </code>  </li>
     *     <li><code> bubble_column      </code>  </li>
     *     <li><code> lava               </code>  </li>
     *     <li><code> snow_layer         </code>  </li>
     *     <li><code> fire               </code>  </li>
     *     <li><code> redstone_bits      </code>  </li>
     *     <li><code> cobweb             </code>  </li>
     *     <li><code> redstone_lamp      </code>  </li>
     *     <li><code> clay               </code>  </li>
     *     <li><code> dirt               </code>  </li>
     *     <li><code> grass              </code>  </li>
     *     <li><code> packed_ice         </code>  </li>
     *     <li><code> sand               </code>  </li>
     *     <li><code> sponge             </code>  </li>
     *     <li><code> wood               </code>  </li>
     *     <li><code> wool               </code>  </li>
     *     <li><code> tnt                </code>  </li>
     *     <li><code> leaves             </code>  </li>
     *     <li><code> glass              </code>  </li>
     *     <li><code> ice                </code>  </li>
     *     <li><code> cactus             </code>  </li>
     *     <li><code> stone              </code>  </li>
     *     <li><code> iron               </code>  </li>
     *     <li><code> snow               </code>  </li>
     *     <li><code> anvil              </code>  </li>
     *     <li><code> barrier            </code>  </li>
     *     <li><code> piston             </code>  </li>
     *     <li><code> coral              </code>  </li>
     *     <li><code> gourd              </code>  </li>
     *     <li><code> gragon_egg         </code>  </li>
     *     <li><code> cake               </code>  </li>
     * </ul>
     * <h3><code>map_colour(pos)</code></h3>
     * <p>Returns the map colour of a block at position. One of:</p>
     * <ul>
     *     <li><code> air            </code>  </li>
     *     <li><code> grass          </code>  </li>
     *     <li><code> sand           </code>  </li>
     *     <li><code> wool           </code>  </li>
     *     <li><code> tnt            </code>  </li>
     *     <li><code> ice            </code>  </li>
     *     <li><code> iron           </code>  </li>
     *     <li><code> foliage        </code>  </li>
     *     <li><code> snow           </code>  </li>
     *     <li><code> clay           </code>  </li>
     *     <li><code> dirt           </code>  </li>
     *     <li><code> stone          </code>  </li>
     *     <li><code> water          </code>  </li>
     *     <li><code> wood           </code>  </li>
     *     <li><code> quartz         </code>  </li>
     *     <li><code> adobe          </code>  </li>
     *     <li><code> magenta        </code>  </li>
     *     <li><code> light_blue     </code>  </li>
     *     <li><code> yellow         </code>  </li>
     *     <li><code> lime           </code>  </li>
     *     <li><code> pink           </code>  </li>
     *     <li><code> gray           </code>  </li>
     *     <li><code> light_gray     </code>  </li>
     *     <li><code> cyan           </code>  </li>
     *     <li><code> purple         </code>  </li>
     *     <li><code> blue           </code>  </li>
     *     <li><code> brown          </code>  </li>
     *     <li><code> green          </code>  </li>
     *     <li><code> red            </code>  </li>
     *     <li><code> black          </code>  </li>
     *     <li><code> gold           </code>  </li>
     *     <li><code> diamond        </code>  </li>
     *     <li><code> lapis          </code>  </li>
     *     <li><code> emerald        </code>  </li>
     *     <li><code> obsidian       </code>  </li>
     *     <li><code> netherrack     </code>  </li>
     *     <li><code> white_terracotta          </code>  </li>
     *     <li><code> orange_terracotta         </code>  </li>
     *     <li><code> magenta_terracotta        </code>  </li>
     *     <li><code> light_blue_terracotta     </code>  </li>
     *     <li><code> yellow_terracotta         </code>  </li>
     *     <li><code> lime_terracotta           </code>  </li>
     *     <li><code> pink_terracotta           </code>  </li>
     *     <li><code> gray_terracotta           </code>  </li>
     *     <li><code> light_gray_terracotta     </code>  </li>
     *     <li><code> cyan_terracotta           </code>  </li>
     *     <li><code> purple_terracotta         </code>  </li>
     *     <li><code> blue_terracotta           </code>  </li>
     *     <li><code> brown_terracotta          </code>  </li>
     *     <li><code> green_terracotta          </code>  </li>
     *     <li><code> red_terracotta            </code>  </li>
     *     <li><code> black_terracotta          </code>  </li>
     * </ul>
     * </div>
     */

    public void API_BlockManipulation()
    {
        this.expr.addLazyFunction("block", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
            if (lv.size() == 0)
            {
                throw new InternalExpressionException("Block requires at least one parameter");
            }
            if (lv.size() == 1)
            {
                return (c_, t_) -> BlockValue.fromCommandExpression(lv.get(0).evalValue(cc).getString());
                //return new BlockValue(IRegistry.field_212618_g.get(new ResourceLocation(lv.get(0).getString())).getDefaultState(), origin);
            }
            return (c_, t_) -> BlockValue.fromParams(cc, lv, 0).block;
        });

        this.expr.addLazyFunction("pos", 1, (c, t, lv) ->
        {
            Value arg = lv.get(0).evalValue(c);
            if (arg instanceof BlockValue)
            {
                BlockPos pos = ((BlockValue) arg).getPos();
                if (pos == null)
                    throw new InternalExpressionException("Cannot fetch position of an unrealized block");
                return (c_, t_) -> ListValue.of(new NumericValue(pos.getX()), new NumericValue(pos.getY()), new NumericValue(pos.getZ()));
            }
            else if (arg instanceof EntityValue)
            {
                Entity e = ((EntityValue) arg).getEntity();
                if (e == null)
                    throw new InternalExpressionException("Null entity");
                return(c_, t_) -> ListValue.of(new NumericValue(e.posX), new NumericValue(e.posY), new NumericValue(e.posZ));
            }
            else
            {
                throw new InternalExpressionException("pos works only with a block or an entity type");
            }
        });

        this.expr.addLazyFunction("solid", -1, (c, t, lv) ->
                booleanStateTest(c, "solid", lv, (s, p) -> s.isSolid()));

        this.expr.addLazyFunction("air", -1, (c, t, lv) ->
                booleanStateTest(c, "air", lv, (s, p) -> s.isAir()));

        this.expr.addLazyFunction("liquid", -1, (c, t, lv) ->
                booleanStateTest(c, "liquid", lv, (s, p) -> !s.getFluidState().isEmpty()));

        this.expr.addLazyFunction("flammable", -1, (c, t, lv) ->
                booleanStateTest(c, "flammable", lv, (s, p) -> !s.getMaterial().isFlammable()));

        this.expr.addLazyFunction("transparent", -1, (c, t, lv) ->
                booleanStateTest(c, "transparent", lv, (s, p) -> !s.getMaterial().isOpaque()));

        this.expr.addLazyFunction("opacity", -1, (c, t, lv) ->
                genericStateTest(c, "opacity", lv, (s, p, w) -> new NumericValue(s.getOpacity(w, p))));

        this.expr.addLazyFunction("blocks_daylight", -1, (c, t, lv) ->
                genericStateTest(c, "blocks_daylight", lv, (s, p, w) -> new NumericValue(s.propagatesSkylightDown(w, p))));

        this.expr.addLazyFunction("emitted_light", -1, (c, t, lv) ->
                genericStateTest(c, "emitted_light", lv, (s, p, w) -> new NumericValue(s.getLightValue())));

        this.expr.addLazyFunction("light", -1, (c, t, lv) ->
                genericStateTest(c, "light", lv, (s, p, w) -> new NumericValue(w.getLight(p))));

        this.expr.addLazyFunction("block_light", -1, (c, t, lv) ->
                genericStateTest(c, "block_light", lv, (s, p, w) -> new NumericValue(w.getLightFor(EnumLightType.BLOCK, p))));

        this.expr.addLazyFunction("sky_light", -1, (c, t, lv) ->
                genericStateTest(c, "sky_light", lv, (s, p, w) -> new NumericValue(w.getLightFor(EnumLightType.SKY, p))));

        this.expr.addLazyFunction("see_sky", -1, (c, t, lv) ->
                genericStateTest(c, "see_sky", lv, (s, p, w) -> new NumericValue(w.canSeeSky(p))));

        this.expr.addLazyFunction("hardness", -1, (c, t, lv) ->
                genericStateTest(c, "hardness", lv, (s, p, w) -> new NumericValue(s.getBlockHardness(w, p))));

        this.expr.addLazyFunction("blast_resistance", -1, (c, t, lv) ->
                genericStateTest(c, "blast_resistance", lv, (s, p, w) -> new NumericValue(s.getBlock().getExplosionResistance())));


        this.expr.addLazyFunction("top", -1, (c, t, lv) -> {
            String type = lv.get(0).evalValue(c).getString().toLowerCase(Locale.ROOT);
            Heightmap.Type htype;
            switch (type)
            {
                case "light": htype = Heightmap.Type.LIGHT_BLOCKING; break;
                case "motion": htype = Heightmap.Type.MOTION_BLOCKING; break;
                case "terrain": htype = Heightmap.Type.MOTION_BLOCKING_NO_LEAVES; break;
                case "ocean_floor": htype = Heightmap.Type.OCEAN_FLOOR; break;
                case "surface": htype = Heightmap.Type.WORLD_SURFACE; break;
                default: throw new InternalExpressionException("Unknown heightmap type: "+type);
            }
            int x;
            int z;
            Value v1 = lv.get(1).evalValue(c);
            if (v1 instanceof BlockValue)
            {
                BlockPos inpos = ((BlockValue)v1).getPos();
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
                genericStateTest(c, "loaded", lv, (s, p, w) -> w.isBlockLoaded(p)?Value.TRUE:Value.FALSE));

        this.expr.addLazyFunction("loaded_ep", -1, (c, t, lv) ->
                genericStateTest(c, "loaded_ep", lv, (s, p, w) ->
                        w.isAreaLoaded(p.getX()-32, 0, p.getZ() - 32,
                                p.getX()+32, 0, p.getZ()+32, true)?Value.TRUE:Value.FALSE));

        this.expr.addLazyFunction("suffocates", -1, (c, t, lv) ->
                booleanStateTest(c, "suffocates", lv, (s, p) -> s.causesSuffocation()));

        this.expr.addLazyFunction("power", -1, (c, t, lv) ->
                genericStateTest(c, "power", lv, (s, p, w) -> new NumericValue(w.getRedstonePowerFromNeighbors(p))));

        this.expr.addLazyFunction("ticks_randomly", -1, (c, t, lv) ->
                booleanStateTest(c, "ticks_randomly", lv, (s, p) -> s.needsRandomTick()));

        this.expr.addLazyFunction("update", -1, (c, t, lv) ->
                booleanStateTest(c, "update", lv, (s, p) ->
                {
                    ((CarpetContext) c).s.getWorld().neighborChanged(p, s.getBlock(), p);
                    return true;
                }));

        this.expr.addLazyFunction("block_tick", -1, (c, t, lv) ->
                booleanStateTest(c, "block_tick", lv, (s, p) ->
                {
                    World w = ((CarpetContext)c).s.getWorld();
                    s.randomTick(w, p, w.rand);
                    return true;
                }));

        this.expr.addLazyFunction("random_tick", -1, (c, t, lv) ->
                booleanStateTest(c, "random_tick", lv, (s, p) ->
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
            BlockValue.LocatorResult locator = BlockValue.fromParams(cc, lv, 0);
            Value blockArg = lv.get(locator.offset).evalValue(cc);
            BlockValue bv = ((blockArg instanceof BlockValue)) ? (BlockValue) blockArg : BlockValue.fromString(blockArg.getString());
            if (bv == BlockValue.NULL)
                throw new InternalExpressionException("block to set to should be a valid block");
            IBlockState bs = bv.getBlockState();

            IBlockState targetBlockState = world.getBlockState(locator.block.getPos());
            if (lv.size()==1+locator.offset && !(blockArg instanceof BlockValue)) // no reqs for properties
                if (targetBlockState.getBlock() == bs.getBlock())
                    return (c_, t_) -> Value.FALSE;

            StateContainer<Block, IBlockState> states = bs.getBlock().getStateContainer();

            for (int i = 1+locator.offset; i < lv.size(); i += 2)
            {
                String paramString = lv.get(i).evalValue(c).getString();
                IProperty<?> property = states.getProperty(paramString);
                if (property == null)
                    throw new InternalExpressionException("property " + paramString + " doesn't apply to " + blockArg.getString());

                String paramValue = lv.get(i + 1).evalValue(c).getString();

                bs = setProperty(property, paramString, paramValue, bs);
            }
            cc.s.getWorld().setBlockState(locator.block.getPos(), bs, 2 | (CarpetSettings.fillUpdates ? 0 : 1024));
            final IBlockState finalBS = bs;
            return (c_, t_) -> new BlockValue(finalBS, world, locator.block.getPos());
        });

        this.expr.addLazyFunction("blocks_movement", -1, (c, t, lv) ->
                booleanStateTest(c, "blocks_movement", lv, (s, p) ->
                        !s.allowsMovement(((CarpetContext) c).s.getWorld(), p, PathType.LAND)));

        this.expr.addLazyFunction("block_sound", -1, (c, t, lv) ->
                stateStringQuery(c, "block_sound", lv, (s, p) ->
                        BlockInfo.soundName.get(s.getBlock().getSoundType())));

        this.expr.addLazyFunction("material",-1, (c, t, lv) ->
                stateStringQuery(c, "material", lv, (s, p) ->
                        BlockInfo.materialName.get(s.getMaterial())));

        this.expr.addLazyFunction("map_colour", -1,  (c, t, lv) ->
                stateStringQuery(c, "map_colour", lv, (s, p) ->
                        BlockInfo.mapColourName.get(s.getMapColor(((CarpetContext)c).s.getWorld(), p))));

        this.expr.addLazyFunction("property", -1, (c, t, lv) ->
        {
            BlockValue.LocatorResult locator = BlockValue.fromParams((CarpetContext) c, lv, 0);
            IBlockState state = locator.block.getBlockState();
            String tag = lv.get(locator.offset).evalValue(c).getString();
            StateContainer<Block, IBlockState> states = state.getBlock().getStateContainer();
            IProperty<?> property = states.getProperty(tag);
            if (property == null)
                return LazyValue.NULL;
            return (_c, _t ) -> new StringValue(state.get(property).toString());
        });
    }

    /**
     * <h1>Entity API</h1>
     * <div style="padding-left: 20px; border-radius: 5px 45px; border:1px solid grey;">
     * <h2>Entity Selection</h2>
     * <p>Entities have to be fetched before using them. Entities can also change their state between calls to the script
     * if game happens either in between separate calls to the programs, or if the program calls <code>game_tick</code>
     * on its own. In this case - entities would need to be re-fetched, or the code should account for entities getting dead</p>
     * <h3><code>player(), player(type), player(name)</code></h3>
     * <p>
     * With no arguments, it returns the calling player or the player closest to the caller. Note that the main context
     * will receive <code>p</code> variable pointing to this player. With <code>type</code> or <code>name</code> specified
     * it will try first to match a type, returning a list of players matching a type, and if this fails, will assume its
     * player name query retuning player with that name, or <code>null</code> if no player was found.
     * With <code>'all'</code>, list of all players in the game, in all dimensions, so end user needs to be cautious, that
     * you might be referring to wrong blocks and entities around the player in question.
     * WIth <code>type = '*'</code> it returns all players in caller dimension, <code>'survival'</code> returns all survival
     * and adventure players, <code>'creative'</code> returns all creative players, <code>'spectating'</code> returns all spectating
     * players, and <code>'!spectating'</code>, all not-spectating players. If all fails,
     * with <code>name</code>, the player in question, if is logged in.</p>
     * <h3><code>entity_id(uuid), entity_id(id)</code></h3>
     * <p>Fetching entities wither by their ID obtained via <code>entity ~ 'id'</code>, which is unique
     * for a dimension and current world run, or by UUID, obtained via <code>entity ~ 'uuid'</code>.
     * It returns null if no such entity
     * is found. Safer way to 'store' entities between calls, as missing entities will be returning <code>null</code>.
     * Both calls using UUID or numerical ID are <code>O(1)</code>, but obviously using UUIDs takes more memory and compute.</p>
     * <h3><code>entity_list(type)</code></h3>
     * <p>Returns global lists of entities in the current dimension of a specified type. Currently the following selectors are available:</p>
     * <ul>
     *     <li><code>*</code>: all</li>
     *     <li><code>living</code></li>
     *     <li><code>items</code></li>
     *     <li><code>players</code></li>
     *     <li><code>!players</code></li>
     * </ul>
     *
     * <h3><code>entity_area(type, cx, cy, cz, dx, dy, dz)</code></h3>
     * <p>Returns entities of a specified type in an area centered on <code>cx, cy, cz</code> and
     * at most <code>dx, dy, dz</code> blocks away from the center point. Uses same selectors as <code>entities_list</code></p>
     *
     * <h3><code>entity_selector(selector)</code></h3>
     * <p>Returns entities satisfying given vanilla entity selector. Most complex among all the methods of selecting
     * entities, but the most capable. Selectors are cached so should be as fast as other methods of selecting entities.</p>
     *
     * <h2>Entity Manipulation</h2>
     *
     * <p>Unlike with blocks, that use plethora of vastly different querying functions, entities are queried with
     * <code>query</code> function and altered via <code>modify</code> function. Type of information needed or
     * values to be modified are different for each call</p>
     * <h3><code>query(e,'removed')</code></h3>
     * <p>Boolean. True if the entity is removed</p>
     * <h3><code>query(e,'id')</code></h3>
     * <p>Returns numerical id of the entity. Most efficient way to keep track of entites in a script. Ids are only unique
     * within current game session (ids are not preserved between restarts), and dimension (each dimension has its own ids
     * which can overlap. </p>
     * <h3><code>query(e,'uuid')</code></h3>
     * <p>Returns UUID (unique id) of the entity. Can be used to access entities with the other vanilla commands and remains unique
     * regardless of the dimension, and is preserved between game restarts.
     * Apparently players cannot be accessed via UUID, but name instead.</p>
     * <pre>
     * map(entities_area('*',x,y,z,30,30,30),run('kill '+query(_,'id'))) // doesn't kill the player
     * </pre>
     * <h3><code>query(e,'pos')</code></h3>
     * <p>Triple of entity position</p>
     * <h3><code>query(e,'x'), query(e,'y'), query(e,'z')</code></h3>
     * <p>Respective entity coordinate</p>
     * <h3><code>query(e,'pitch'), query(e,'yaw')</code></h3>
     * <p>Pitch and Yaw or where entity is looking.</p>
     * <h3><code>query(e,'motion')</code></h3>
     * <p>Triple of entity motion vector, <code>l(motion_x, motion_y, motion_z)</code></p>
     * <h3><code>query(e,'motion_x'), query(e,'motion_y'), query(e,'motion_z')</code></h3>
     * <p>Respective component of the motion vector</p>
     * <h3><code>query(e,'name')</code></h3>
     * <p>String of entity name</p>
     * <h3><code>query(e,'custom_name')</code></h3>
     * <p>String of entity name</p>
     * <h3><code>query(e,'type')</code></h3>
     * <p>String of entity name</p>
     * <pre>
     * query(e,'name')  =&gt; Leatherworker
     * query(e,'custom_name')  =&gt; null
     * query(e,'type')  =&gt; villager
     * </pre>
     * <h3><code>query(e,'is_riding')</code></h3>
     * <p>Boolean. True if riding another entity.</p>
     * <h3><code>query(e,'is_ridden')</code></h3>
     * <p>Boolean. True if another entity is riding it.</p>
     * <h3><code>query(e,'passengers')</code></h3>
     * <p>List of entities riding the entity.</p>
     * <h3><code>query(e,'mount')</code></h3>
     * <p>Entity that <code>e</code> rides.</p>
     * <h3><code>query(e,'tags')</code></h3>
     * <p>List of entity tags.</p>
     * <h3><code>query(e,'has_tags',tag)</code></h3>
     * <p>Boolean, True if the entity is marked with <code>tag</code>.</p>
     * <h3><code>query(e,'is_burning')</code></h3>
     * <p>Boolean, True if the entity is burning.</p>
     * <h3><code>query(e,'fire')</code></h3>
     * <p>Number of remaining ticks of being on fire.</p>
     * <h3><code>query(e,'silent')</code></h3>
     * <p>Boolean, True if the entity is silent.</p>
     * <h3><code>query(e,'gravity')</code></h3>
     * <p>Boolean, True if the entity is affected y gravity, like most entities do.</p>
     * <h3><code>query(e,'immune_to_fire')</code></h3>
     * <p>Boolean, True if the entity is immune to fire.</p>
     * <h3><code>query(e,'dimension')</code></h3>
     * <p>Name of the dimension entity is in.</p>
     * <h3><code>query(e,'height')</code></h3>
     * <p>Height of the entity.</p>
     * <h3><code>query(e,'width')</code></h3>
     * <p>Width of the entity.</p>
     * <h3><code>query(e,'eye_height')</code></h3>
     * <p>Eye height of the entity.</p>
     * <h3><code>query(e,'age')</code></h3>
     * <p>Age, in ticks, of the entity, i.e. how long it existed.</p>
     * <h3><code>query(e,'item')</code></h3>
     * <p>Name of the item if its an item entity, <code>null</code> otherwise</p>
     * <h3><code>query(e,'count')</code></h3>
     * <p>Number of items in a stack from item entity.<code>null</code> otherwise</p>
     * <h3><code>query(e,'is_baby')</code></h3>
     * <p>Boolean, true if its a baby.</p>
     * <h3><code>query(e,'target')</code></h3>
     * <p>Returns mob's attack target or null if none or not applicable.</p>
     * <h3><code>query(e,'home')</code></h3>
     * <p>Returns creature's home position or null if none or not applicable.</p>
     * <h3><code>query(e,'sneaking')</code></h3>
     * <p>Boolean, true if entity is sneaking.</p>
     * <h3><code>query(e,'sprinting')</code></h3>
     * <p>Boolean, true if entity is sprinting.</p>
     * <h3><code>query(e,'swimming')</code></h3>
     * <p>Boolean, true if entity is swimming.</p>
     * <h3><code>query(e,'gamemode')</code></h3>
     * <p>String with gamemode, or <code>null</code> if not a player.</p>
     * <h3><code>query(e,'gamemode_id')</code></h3>
     * <p>Good'ol gamemode id, or null if not a player.</p>
     * <h3><code>query(e,'effect',name?)</code></h3>
     * <p>Without extra arguments, it returns list of effect active on a living entity.
     * Each entry is a triple of short effect name, amplifier, and remaining duration.
     * With an argument, if the living entity has not that potion active, returns <code>null</code>, otherwise
     * return a tuple of amplifier and remaining duration</p>
     * <pre>
     * query(p,'effect')  =&gt; [[haste, 0, 177], [speed, 0, 177]]
     * query(p,'effect','haste')  =&gt; [0, 177]
     * query(p,'effect','resistance')  =&gt; null
     * </pre>
     * <h3><code>query(e,'health')</code></h3>
     * <p>Number indicating remaining entity health, or <code>null</code> if not applicable.</p>
     * <h3><code>query(e,'holds',slot?)</code></h3>
     * <p>Returns triple of short name, stack count, and NBT of item held in <code>slot</code>.
     * Available options for <code>slot</code> are:</p>
     * <ul>
     *     <li><code>main</code></li>
     *     <li><code>offhand</code></li>
     *     <li><code>head</code></li>
     *     <li><code>chest</code></li>
     *     <li><code>legs</code></li>
     *     <li><code>feet</code></li>
     * </ul>
     * <p>If <code>slot</code> is not specified, it defaults to the main hand.</p>
     * <h3><code>query(e,'nbt',path?)</code></h3>
     * <p>Returns full NBT of the entity. If path is specified, it fetches only that portion of the NBT,
     * that corresponds to the path. For specification of <code>path</code> attribute, consult
     * vanilla <code>/data get entity</code> command.</p>
     * <p>Note that calls to <code>nbt</code> are considerably more expensive comparing to other
     * calls in Minecraft API, and should only be used when there is no other option. Also returned
     * NBT is just a string to any retrieval of information post can currently only be done with matching
     * operator <code>~</code>. With time we are hoping not to support the <code>'nbt'</code> call better,
     * but rather to fill the API, so that <code>'nbt'</code> calls are not needed</p>
     * <h2>Entity Modification</h2>
     * <p>Like with entity querying, entity modifications happen through one function. Most position and movements
     * modifications don't work currently on players as their position is controlled by clients.</p>
     * <p>Currently there is no ability to modify NBT directly, but you could always use <code>run('data modify entity</code></p>
     * <h3><code>modify(e,'remove')</code></h3>
     * <p>Removes (not kills) entity from the game.</p>
     * <h3><code>modify(e,'kill')</code></h3>
     * <p>Kills the entity.</p>
     * <h3><code>modify(e, 'pos', x, y, z), modify(e, 'pos', l(x,y,z) )</code></h3>
     * <p>Moves the entity to a specified coords.</p>
     * <h3><code>modify(e, 'x', x), modify(e, 'y', y), modify(e, 'z', z)</code></h3>
     * <p>Moves the entity in.... one direction.</p>
     * <h3><code>modify(e, 'pitch', pitch), modify(e, 'yaw', yaw)</code></h3>
     * <p>Changes entity's pitch or yaw.</p>
     * <h3><code>modify(e, 'move', x, y, z), modify(e, 'move', l(x,y,z) )</code></h3>
     * <p>Moves th entity by a vector from its current location.</p>
     * <h3><code>modify(e, 'motion', x, y, z), modify(e, 'motion', l(x,y,z) )</code></h3>
     * <p>Sets the motion vector (where an how much entity is moving).</p>
     * <h3><code>modify(e, 'motion_z', x), modify(e, 'motion_y', y), modify(e, 'motion_z', z)</code></h3>
     * <p>Sets the corresponding component of the motion vector.</p>
     * <h3><code>modify(e, 'accelerate', x, y, z), modify(e, 'accelerate', l(x, y, z) )</code></h3>
     * <p>Sets adds a vector to the motion vector. Most realistic way to apply a force to an entity.</p>
     * <h3><code>modify(e, 'custom_name'), modify(e, 'custom_name', name )</code></h3>
     * <p>Sets a custom name for an entity. No argument sets it empty, not removes it. Vanilla doesn't allow removing
     * of attributes.</p>
     * <h3><code>modify(e, 'dismount')</code></h3>
     * <p>Dismounts riding entity.</p>
     * <h3><code>modify(e, 'mount', other)</code></h3>
     * <p>Mounts the entity to the <code>other</code>.</p>
     * <h3><code>modify(e, 'drop_passengers')</code></h3>
     * <p>Shakes off all passengers.</p>
     * <h3><code>modify(e, 'mount_passengers', passenger, ? ...), modify(e, 'mount_passengers', l(passengers) )</code></h3>
     * <p>Mounts on all listed entities on <code>e</code>.</p>
     * <h3><code>modify(e, 'tag', tag, ? ...), modify(e, 'tag', l(tags) )</code></h3>
     * <p>Adds tag / tags to the entity.</p>
     * <h3><code>modify(e, 'clear_tag', tag, ? ...), modify(e, 'clear_tag', l(tags) )</code></h3>
     * <p>Removes tag from entity.</p>
     * <h3><code>modify(e, 'talk')</code></h3>
     * <p>Make noises.</p>
     * <h3><code>modify(e, 'home', null), modify(e, 'home', block, distance?), modify(e, 'home', x, y, z, distance?)</code></h3>
     * <p>Sets AI to stay around the home position, within <code>distance</code> blocks from it. <code>distance</code>
     * defaults to 16 blocks. <code>null</code> removes it. <i>May</i> not work fully with mobs that have this AI built in, like
     * Villagers.</p>
     * </div>
     */

    public void API_EntityManipulation()
    {
        this.expr.addLazyFunction("player", -1, (c, t, lv) -> {
            if (lv.size() ==0)
            {
                return (_c, _t) ->
                {
                    Entity callingEntity = ((CarpetContext)_c).s.getEntity();
                    if (callingEntity instanceof EntityPlayer)
                    {
                        return new EntityValue(callingEntity);
                    }
                    Vec3d pos = ((CarpetContext)_c).s.getPos();
                    EntityPlayer closestPlayer = ((CarpetContext)_c).s.getWorld().getClosestPlayer(pos.x, pos.y, pos.z, -1.0, EntitySelectors.IS_ALIVE);
                    if (closestPlayer != null)
                    {
                        return new EntityValue(closestPlayer);
                    }
                    return Value.NULL;
                };
            }
            String playerName = lv.get(0).evalValue(c).getString();
            if ("all".equalsIgnoreCase(playerName))
            {
                return (_c, _t) -> ListValue.wrap(
                        ((CarpetContext)_c).s.getServer().getPlayerList().getPlayers().
                                stream().map(EntityValue::new).collect(Collectors.toList()));
            }
            if ("*".equalsIgnoreCase(playerName))
            {
                return (_c, _t) -> ListValue.wrap(
                        ((CarpetContext)_c).s.getWorld().getPlayers(EntityPlayer.class, (p) -> true).
                                stream().map(EntityValue::new).collect(Collectors.toList()));
            }
            if ("survival".equalsIgnoreCase(playerName))
            {
                return (_c, _t) -> ListValue.wrap(
                        ((CarpetContext)_c).s.getWorld().getPlayers(EntityPlayerMP.class, (p) -> p.interactionManager.survivalOrAdventure()).
                                stream().map(EntityValue::new).collect(Collectors.toList()));
            }
            if ("creative".equalsIgnoreCase(playerName))
            {
                return (_c, _t) -> ListValue.wrap(
                        ((CarpetContext)_c).s.getWorld().getPlayers(EntityPlayer.class, EntityPlayer::isCreative).
                                stream().map(EntityValue::new).collect(Collectors.toList()));
            }
            if ("spectating".equalsIgnoreCase(playerName))
            {
                return (_c, _t) -> ListValue.wrap(
                        ((CarpetContext)_c).s.getWorld().getPlayers(EntityPlayer.class, EntityPlayer::isSpectator).
                                stream().map(EntityValue::new).collect(Collectors.toList()));
            }
            if ("!spectating".equalsIgnoreCase(playerName))
            {
                return (_c, _t) -> ListValue.wrap(
                        ((CarpetContext)_c).s.getWorld().getPlayers(EntityPlayer.class, (p) -> !p.isSpectator()).
                                stream().map(EntityValue::new).collect(Collectors.toList()));
            }
            EntityPlayerMP player = ((CarpetContext)c).s.getServer().getPlayerList().getPlayerByUsername(playerName);
            if (player != null)
                return (_c, _t) -> new EntityValue(player);
            return LazyValue.NULL;
        });

        this.expr.addLazyFunction("entity_id", 1, (c, t, lv) ->
        {
            Value who = lv.get(0).evalValue(c);
            Entity e;
            if (who instanceof NumericValue)
            {
                e = ((CarpetContext)c).s.getWorld().getEntityByID((int)((NumericValue) who).getLong());
            }
            else
            {
                e = ((CarpetContext)c).s.getWorld().getEntityFromUuid(UUID.fromString(who.getString()));
            }
            if (e==null)
            {
                return LazyValue.NULL;
            }
            return (cc, tt) -> new EntityValue(e);
        });

        this.expr.addLazyFunction("entity_list", 1, (c, t, lv) -> {
            String who = lv.get(0).evalValue(c).getString();
            Pair<Class<? extends Entity>, Predicate<? super Entity>> pair = EntityValue.getPredicate(who);
            if (pair == null)
            {
                throw new InternalExpressionException("Unknown entity selection criterion: "+who);
            }
            List<Entity> entityList = ((CarpetContext)c).s.getWorld().getEntities(pair.getKey(), pair.getValue());
            return (_c, _t ) -> ListValue.wrap(entityList.stream().map(EntityValue::new).collect(Collectors.toList()));
        });

        this.expr.addLazyFunction("entity_area", 7, (c, t, lv) -> {
            BlockPos center = new BlockPos(
                    Expression.getNumericValue(lv.get(1).evalValue(c)).getDouble(),
                    Expression.getNumericValue(lv.get(2).evalValue(c)).getDouble(),
                    Expression.getNumericValue(lv.get(3).evalValue(c)).getDouble()
            );
            AxisAlignedBB area = new AxisAlignedBB(center).grow(
                    Expression.getNumericValue(lv.get(4).evalValue(c)).getDouble(),
                    Expression.getNumericValue(lv.get(5).evalValue(c)).getDouble(),
                    Expression.getNumericValue(lv.get(6).evalValue(c)).getDouble()
            );
            String who = lv.get(0).evalValue(c).getString();
            Pair<Class<? extends Entity>, Predicate<? super Entity>> pair = EntityValue.getPredicate(who);
            if (pair == null)
            {
                throw new InternalExpressionException("Unknown entity selection criterion: "+who);
            }
            List<Entity> entityList = ((CarpetContext)c).s.getWorld().getEntitiesWithinAABB(pair.getKey(), area, pair.getValue());
            return (_c, _t ) -> ListValue.wrap(entityList.stream().map(EntityValue::new).collect(Collectors.toList()));
        });

        this.expr.addLazyFunction("entity_selector", -1, (c, t, lv) ->
        {
            String selector = lv.get(0).evalValue(c).getString();
            List<Value> retlist = new ArrayList<>();
            for (Entity e: EntityValue.getEntitiesFromSelector(((CarpetContext)c).s, selector))
            {
                retlist.add(new EntityValue(e));
            }
            return (c_, t_) -> ListValue.wrap(retlist);
        });


        this.expr.addLazyFunction("query", -1, (c, t, lv) -> {
            if (lv.size()<2)
            {
                throw new InternalExpressionException("query_entity takes entity as a first argument, and queried feature as a second");
            }
            Value v = lv.get(0).evalValue(c);
            if (!(v instanceof EntityValue))
                throw new InternalExpressionException("First argument to query_entity should be an entity");
            String what = lv.get(1).evalValue(c).getString();
            if (lv.size()==2)
                return (_c, _t) -> ((EntityValue) v).get(what, null);
            if (lv.size()==3)
                return (_c, _t) -> ((EntityValue) v).get(what, lv.get(2).evalValue(c));
            return (_c, _t) -> ((EntityValue) v).get(what, ListValue.wrap(lv.subList(2, lv.size()).stream().map((vv) -> vv.evalValue(c)).collect(Collectors.toList())));

        });

        // or update
        this.expr.addLazyFunction("modify", -1, (c, t, lv) -> {
            if (lv.size()<2)
            {
                throw new InternalExpressionException("modify_entity takes entity as a first argument, and queried feature as a second");
            }
            Value v = lv.get(0).evalValue(c);
            if (!(v instanceof EntityValue))
                throw new InternalExpressionException("First argument to get should be an entity");
            String what = lv.get(1).evalValue(c).getString();
            if (lv.size()==2)
                ((EntityValue) v).set(what, null);
            else if (lv.size()==3)
                ((EntityValue) v).set(what, lv.get(2).evalValue(c));
            else
                ((EntityValue) v).set(what, ListValue.wrap(lv.subList(2, lv.size()).stream().map((vv) -> vv.evalValue(c)).collect(Collectors.toList())));
            return lv.get(0);
        });
    }

    /**
     * <h1>Iterating over larger areas of blocks</h1>
     * <div style="padding-left: 20px; border-radius: 5px 45px; border:1px solid grey;">
     * <p>These functions help scan larger areas of blocks without using generic loop functions,
     * like nested <code>loop</code>.</p>
     * <h2> </h2>
     * <h3><code>scan(cx, cy, cz, dx, dy, dz, px?, py?, pz?, expr)</code></h3>
     * <p>Evaluates expression over area of blocks defined by its center (<code>cx, cy, cz</code>),
     * expanded in all directions by <code>dx, dy, dz</code> blocks, or optionally in negative with <code>d</code> coords,
     * and <code>p</code> coords in positive values. <code>expr</code> receives <code>_x, _y, _z</code>
     * as coords of current analyzed block and <code>_</code> which represents the block itself.</p>
     * <h3><code>volume(x1, y1, z1, x2, y2, z2, expr)</code></h3>
     * <p>Evaluates expression for each block in the area, the same as the <code>scan</code>function, but using two opposite
     * corners of the rectangular cuboid. Any corners can be specified, its like you would do with <code>/fill</code> command</p>
     * <h3><code>neighbours(x, y, z), neighbours(block), neighbours(l(x,y,z))</code></h3>
     * <p>Returns the list of 6 neighbouring blocks to the argument. Commonly used with other loop functions like <code>for</code></p>
     * <pre>
     * for(neighbours(x,y,z),air(_)) =&gt; 4 // number of air blocks around a block
     * </pre>
     * <h3><code>rect(cx, cy, cz, dx?, dy?, dz?, px?, py?, pz?)</code></h3>
     * <p>returns an iterator, just like <code>range</code> function that iterates over rectangular cubarea of blocks. If
     * only center point is specified, it iterates over 27 blocks. If <code>d</code> arguments are specified, expands selection
     * of respective number of blocks in each direction. If <code>p</code> arguments are specified, it uses <code>d</code> for
     * negative offset, and <code>p</code> for positive.</p>
     * <h3><code>diamond(cx, cy, cz, radius?, height?)</code></h3>
     * <p>Iterates over a diamond like area of blocks. With no radius and height, its 7 blocks centered around the middle
     * (block + neighbours). With radius it expands shape on x and z coords, and wit custom height, on z. Any of these can be
     * zero as well. radius of 0 makes a stick, height of 0 makes a diamond shape pad.</p>
     * </div>
     */

    public void API_IteratingOverAreasOfBlocks()
    {
        this.expr.addLazyFunction("scan", -1, (c, t, lv) ->
        {
            int lvsise = lv.size();
            if (lvsise != 7 && lvsise != 10)
                throw new InternalExpressionException("scan takes 2, or 3 triples of coords, and the expression");
            int cx = (int)Expression.getNumericValue(lv.get(0).evalValue(c)).getLong();
            int cy = (int)Expression.getNumericValue(lv.get(1).evalValue(c)).getLong();
            int cz = (int)Expression.getNumericValue(lv.get(2).evalValue(c)).getLong();
            int xrange = (int)Expression.getNumericValue(lv.get(3).evalValue(c)).getLong();
            int yrange = (int)Expression.getNumericValue(lv.get(4).evalValue(c)).getLong();
            int zrange = (int)Expression.getNumericValue(lv.get(5).evalValue(c)).getLong();
            int xprange = xrange;
            int yprange = yrange;
            int zprange = zrange;
            LazyValue expr;
            if (lvsise == 7)
            {
                expr = lv.get(6);
            }
            else
            {
                xprange = (int)Expression.getNumericValue(lv.get(6).evalValue(c)).getLong();
                yprange = (int)Expression.getNumericValue(lv.get(7).evalValue(c)).getLong();
                zprange = (int)Expression.getNumericValue(lv.get(8).evalValue(c)).getLong();
                expr = lv.get(9);
            }

            //saving outer scope
            LazyValue _x = c.getVariable("_x");
            LazyValue _y = c.getVariable("_y");
            LazyValue _z = c.getVariable("_z");
            LazyValue __ = c.getVariable("_");
            int sCount = 0;
            for (int y=cy-yrange; y <= cy+yprange; y++)
            {
                int yFinal = y;
                c.setVariable("_y", (c_, t_) -> new NumericValue(yFinal).bindTo("_y"));
                for (int x=cx-xrange; x <= cx+xprange; x++)
                {
                    int xFinal = x;
                    c.setVariable("_x", (c_, t_) -> new NumericValue(xFinal).bindTo("_x"));
                    for (int z=cz-zrange; z <= cz+zprange; z++)
                    {
                        int zFinal = z;
                        c.setVariable( "_", (cc_, t_c) -> BlockValue.fromCoords(((CarpetContext)c), xFinal,yFinal,zFinal).bindTo("_"));
                        c.setVariable("_z", (c_, t_) -> new NumericValue(zFinal).bindTo("_z"));
                        if (expr.evalValue(c, Context.BOOLEAN).getBoolean())
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

        this.expr.addLazyFunction("volume", 7, (c, t, lv) ->
        {
            int xi = (int)Expression.getNumericValue(lv.get(0).evalValue(c)).getLong();
            int yi = (int)Expression.getNumericValue(lv.get(1).evalValue(c)).getLong();
            int zi = (int)Expression.getNumericValue(lv.get(2).evalValue(c)).getLong();
            int xj = (int)Expression.getNumericValue(lv.get(3).evalValue(c)).getLong();
            int yj = (int)Expression.getNumericValue(lv.get(4).evalValue(c)).getLong();
            int zj = (int)Expression.getNumericValue(lv.get(5).evalValue(c)).getLong();
            int minx = min(xi, xj);
            int miny = min(yi, yj);
            int minz = min(zi, zj);
            int maxx = max(xi, xj);
            int maxy = max(yi, yj);
            int maxz = max(zi, zj);
            LazyValue expr = lv.get(6);

            //saving outer scope
            LazyValue _x = c.getVariable("_x");
            LazyValue _y = c.getVariable("_y");
            LazyValue _z = c.getVariable("_z");
            LazyValue __ = c.getVariable("_");
            int sCount = 0;
            for (int y=miny; y <= maxy; y++)
            {
                int yFinal = y;
                c.setVariable("_y", (c_, t_) -> new NumericValue(yFinal).bindTo("_y"));
                for (int x=minx; x <= maxx; x++)
                {
                    int xFinal = x;
                    c.setVariable("_x", (c_, t_) -> new NumericValue(xFinal).bindTo("_x"));
                    for (int z=minz; z <= maxz; z++)
                    {
                        int zFinal = z;
                        c.setVariable( "_", (cc_, t_c) -> BlockValue.fromCoords(((CarpetContext)c), xFinal,yFinal,zFinal).bindTo("_"));
                        c.setVariable("_z", (c_, t_) -> new NumericValue(zFinal).bindTo("_z"));
                        if (expr.evalValue(c, Context.BOOLEAN).getBoolean())
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

        this.expr.addLazyFunction("neighbours", -1, (c, t, lv)->
        {

            BlockPos center = BlockValue.fromParams((CarpetContext) c, lv,0).block.getPos();
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
                    Value r = BlockValue.fromCoords(cc, x,y,z);
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
                            BlockValue.fromCoords(cc, cx, cy-1, cz),
                            BlockValue.fromCoords(cc, cx, cy, cz),
                            BlockValue.fromCoords(cc, cx-1, cy, cz),
                            BlockValue.fromCoords(cc, cx, cy, cz-1),
                            BlockValue.fromCoords(cc, cx+1, cy, cz),
                            BlockValue.fromCoords(cc, cx, cy, cz+1),
                            BlockValue.fromCoords(cc, cx, cy+1, cz)
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
                            return BlockValue.fromCoords(cc, cx, cy, cz);
                        }
                        // x = 3-|i-6|
                        // z = |( (i-3)%12-6|-3
                        Value block = BlockValue.fromCoords(cc, cx+(curradius-abs(curpos-2*curradius)), cy, cz-curradius+abs( abs(curpos-curradius)%(4*curradius) -2*curradius ));
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
                            return BlockValue.fromCoords(cc, cx, cy+curheight++, cz);
                        }
                        if (curradius == 0)
                        {
                            curradius++;
                            return BlockValue.fromCoords(cc, cx, cy+curheight, cz);
                        }
                        // x = 3-|i-6|
                        // z = |( (i-3)%12-6|-3

                        Value block = BlockValue.fromCoords(cc, cx+(curradius-abs(curpos-2*curradius)), cy+curheight, cz-curradius+abs( abs(curpos-curradius)%(4*curradius) -2*curradius ));
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
    }

    //TODO sounds
    /**
     * <h1>Auxiliary aspects</h1>
     * <div style="padding-left: 20px; border-radius: 5px 45px; border:1px solid grey;">
     * <p>Collection of other methods that control smaller, yet still important aspects of the game</p>
     * <h2>Sounds</h2>
     * <h3><code>sound(name, pos, volume?, pitch?)</code></h3>
     * <p>Plays a specific sound <code>name</code>, at block or position <code>pos</code>, with optional
     * <code>volume</code> and modified <code>pitch</code>. <code>pos</code> can be either a block, triple of coords,
     * or a list of thee numbers. Uses the same options as a corresponding <code>playsound</code> command.</p>
     * <h2>Particles</h2>
     * <h3><code>particle(name, pos, count?. spread?, speed?, playername?)</code></h3>
     * <p>Renders a cloud of particles <code>name</code> centered around <code>pos</code> position, by default
     * <code>count</code> 10 of them, default <code>speed</code> of 0, and to all players nearby, but these
     * options can be changed via optional arguments. Follow vanilla <code>/particle</code> command on details on those
     * options. Valid particle names are for example
     * <code>'angry_villager', 'item diamond', 'block stone', 'dust 0.8 0.1 0.1 4'</code></p>
     * <h3><code>particle_line(name, pos, pos2, density?)</code></h3>
     * <p>Renders a line of particles from point <code>pos</code> to <code>pos2</code> with supplied density (defaults 1),
     * which indicates how far part you would want particles to appear, so <code>0.1</code> means one every 10cm.</p>
     * <h3><code>particle_rect(name, pos, pos2, density?)</code></h3>
     * <p>Renders a cuboid of particles between point <code>pos</code> to <code>pos2</code> with supplied density.</p>
     * <h2>System function</h2>
     * <h3><code>print(expr)</code></h3>
     * <p>Displays the result of the expression to the chat. Overrides default <code>scarpet</code> behaviour of
     * sending everyting to stderr.</p>
     * <h3><code>run(expr)</code></h3>
     * <p>Runs a command from the string result of the <code>expr</code> expression, and returns its success count</p>
     * <h3><code>save()</code></h3>
     * <p>Performs autosave, saves all chunks, player data, etc. Useful for programs where autosave is disabled
     * due to performance reasons and saves the world only on demand.</p>
     * <h3><code>tick_time()</code></h3>
     * <p>Returns game tick counter. Can be used to run certain operations every n-th ticks, or to count in-game time</p>
     * <h3><code>game_tick(mstime?)</code></h3>
     * <p>Causes game to run for one tick. By default runs it and returns control to the program, but can optionally
     * accept expected tick length, in milliseconds. You can't use it to permanently change the game speed, but
     * setting longer commands with custom tick speeds can be interrupted via <code>/script stop</code> command</p>
     * <pre>
     * loop(1000,tick())  // runs the game as fast as it can for 1000 ticks
     * loop(1000,tick(100)) // runs the game twice as slow for 1000 ticks
     * </pre>
     * <h3><code>current_dimension()</code></h3>
     * <p>Returns current dimension that scripts run in.</p>
     * <h3><code>plop(pos, what)</code></h3>
     * <p>Plops a structure or a feature at a given <code>pos</code>, so block, triple position coordinates
     * or a list of coordinates. To <code>what</code> gets plopped and exactly where it often depends on the
     * feature or structure itself. For example, all structures are chunk aligned, and often span multiple chunks.
     * Repeated calls to plop a structure in the same chunk would result either in the same strucuture generated on
     * top of each other, or with different state, but same position. Most
     * structures generate at specific altitudes, which are hardcoded, or with certain blocks around them. API will cancel
     * all extra position / biome / random requirements for structure / feature placement, but some hardcoded limitations
     * may still cause some of strucutures/features not to place. Some features require special blocks to be present, like
     * coral -&gt; water or ice spikes -&gt; snow block, and for some features, like fossils, placement is all sorts of
     * messed up.</p>
     * <p>
     * All generated structures will retain their properties, like mob spawning, however in many cases the world / dimension
     * itself has certain rules to spawn mobs, like plopping a nether fortress in the overworld will not spawn nether mobs
     * because nether mobs can spawn only in the nether, but plopped in the nether - will behave like a valid nether
     * fortress.</p>
     * <p><code>plop</code>  will not use world random number generator to generate structures and features, but its own.
     * This has a benefit that they will generate properly randomly, not the same time every time</p>
     * <p>Structure list:</p>
     * <ul>
     *
     *     <li><code>monument</code>: Ocean Monument. Generates at fixed Y coordinate, surrounds itself with water.</li>
     *     <li><code>fortress</code>: Nether Fortress. Altitude varies, but its bounded by the code.</li>
     *     <li><code>mansion</code>: Woodland Mansion</li>
     *     <li><code>jungle_temple</code>: Jungle Temple</li>
     *     <li><code>desert_temple</code>: Desert Temple. Generates at fixed Y altitude.</li>
     *     <li><code>end_city</code>: End City with Shulkers</li>
     *     <li><code>igloo</code>: Igloo</li>
     *     <li><code>shipwreck</code>: Shipwreck, version1?</li>
     *     <li><code>shipwreck2</code>: Shipwreck, version2?</li>
     *     <li><code>witchhut</code></li>
     *     <li><code>oceanruin, oceanruin_small, oceanruin_tall</code>: Stone variants of ocean ruins.</li>
     *     <li><code>oceanruin_warm, oceanruin_warm_small, oceanruin_warm_tall</code>: Sandstone variants of ocean ruins.</li>
     *     <li><code>treasure</code>: A treasure chest. Yes, its a whole structure.</li>
     *     <li><code>mineshaft</code>: A mineshaft.</li>
     *     <li><code>mineshaft_mesa</code>: A Mesa (Badlands) version of a mineshaft.</li>
     *     <li><code>village</code>: Plains, oak village.</li>
     *     <li><code>village_desert</code>: Desert, sandstone village.</li>
     *     <li><code>village_savanna</code>: Savanna, acacia village.</li>
     *     <li><code>village_taiga</code>: Taiga, spruce village.</li>
     * </ul>
     * <p>Feature list:</p>
     * <ul>
     *     <li><code>oak</code></li>
     *     <li><code>oak_large</code>: oak with branches.</li>
     *     <li><code>birch</code></li>
     *     <li><code>birch_large</code>: tall variant of birch tree.</li>
     *     <li><code>shrub</code>: low bushes that grow in jungles.</li>
     *     <li><code>shrub_acacia</code>: low bush but configured with acacia</li>
     *     <li><code>shrub_snowy</code>: low bush with white blocks</li>
     *     <li><code>jungle</code>: a tree</li>
     *     <li><code>spruce_matchstick</code>: tall spruce with minimal leafage.</li>
     *     <li><code>dark_oak</code></li>
     *     <li><code>acacia</code></li>
     *     <li><code>spruce</code></li>
     *     <li><code>oak_swamp</code>: oak with more leaves and vines.</li>
     *     <li><code>jungle_large</code>: 2x2 jungle tree</li>
     *     <li><code>spruce_matchstick_large</code>: 2x2 spruce tree with minimal leafage</li>
     *     <li><code>spruce_large</code>: 2x2 spruce tree</li>
     *     <li><code>well</code>: desert well</li>
     *     <li><code>grass</code>: a few spots of tall grass</li>
     *     <li><code>grass_jungle</code>: little bushier grass feature</li>
     *     <li><code>fern</code>: a few random ferns</li>
     *     <li><code>cactus</code>: random cacti</li>
     *     <li><code>dead_bush</code>: a few random dead bushi</li>
     *     <li><code>fossils</code>: underground fossils, placement little wonky</li>
     *     <li><code>mushroom_brown</code>: large brown mushroom.</li>
     *     <li><code>mushroom_red</code>: large red mushroom.</li>
     *     <li><code>ice_spike</code>: ice spike. Require snow block below to place.</li>
     *     <li><code>glowstone</code>: glowstone cluster. Required netherrack above it.</li>
     *     <li><code>melon</code>: a patch of melons</li>
     *     <li><code>pumpkin</code>: a patch of pumpkins</li>
     *     <li><code>sugarcane</code></li>
     *     <li><code>lilypad</code></li>
     *     <li><code>dungeon</code>: Dungeon. These are hard to place, and fail often.</li>
     *     <li><code>iceberg</code>: Iceberg. Generate at sea level.</li>
     *     <li><code>iceberg_blue</code>: Blue ice iceberg.</li>
     *     <li><code>lake</code></li>
     *     <li><code>lava_lake</code></li>
     *     <li><code>end_island</code></li>
     *     <li><code>chorus</code>: Chorus plant. Require endstone to place.</li>
     *     <li><code>sea_grass</code>: a patch of sea grass. Require water.</li>
     *     <li><code>sea_grass_river</code>: a variant.</li>
     *     <li><code>kelp</code></li>
     *     <li><code>coral_tree, coral_mushroom, coral_claw</code>: various coral types, random color.</li>
     *     <li><code>coral</code>: random coral structure. Require water to spawn.</li>
     *     <li><code>sea_pickle</code></li>
     *     <li><code>boulder</code>: A rocky, mossy formation from a giant taiga biome. Doesn't update client properly,
     *     needs relogging.</li>
     * </ul>
     * </div>
     */

    public void API_AuxiliaryAspects()
    {
        this.expr.addLazyFunction("sound", -1, (c, t, lv) -> {
            CarpetContext cc = (CarpetContext)c;
            ResourceLocation soundName = new ResourceLocation(lv.get(0).evalValue(c).getString());
            BlockValue.VectorLocator locator = BlockValue.locateVec(cc, lv, 1);
            if (!(IRegistry.field_212633_v.func_212607_c(soundName)))
                throw new InternalExpressionException("No such sound: "+soundName.getPath());
            float volume = 1.0F;
            float pitch = 1.0F;
            if (lv.size() > 0+locator.offset)
            {
                volume = (float)Expression.getNumericValue(lv.get(0+locator.offset).evalValue(c)).getDouble();
                if (lv.size() > 1+locator.offset)
                {
                    pitch = (float)Expression.getNumericValue(lv.get(1+locator.offset).evalValue(c)).getDouble();
                }
            }
            Vec3d vec = locator.vec;
            double d0 = Math.pow(volume > 1.0F ? (double)(volume * 16.0F) : 16.0D, 2.0D);
            int count = 0;
            for (EntityPlayerMP player : cc.s.getWorld().getPlayers(EntityPlayerMP.class, (p) -> p.getDistanceSq(vec) < d0))
            {
                count++;
                player.connection.sendPacket(new SPacketCustomSound(soundName, SoundCategory.PLAYERS, vec, volume, pitch));
            }
            int totalPlayed = count;
            return (_c, _t) -> new NumericValue(totalPlayed);
        });

        this.expr.addLazyFunction("particle", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext)c;
            MinecraftServer ms = cc.s.getServer();
            WorldServer world = cc.s.getWorld();
            BlockValue.VectorLocator locator = BlockValue.locateVec(cc, lv, 1);
            String particleName = lv.get(0).evalValue(c).getString();
            int count = 10;
            double speed = 0;
            float spread = 0.5f;
            EntityPlayerMP player = null;
            if (lv.size() > locator.offset)
            {
                count = (int)Expression.getNumericValue(lv.get(locator.offset).evalValue(c)).getLong();
                if (lv.size() > 1+locator.offset)
                {
                    spread = (float)Expression.getNumericValue(lv.get(1+locator.offset).evalValue(c)).getDouble();
                    if (lv.size() > 2+locator.offset)
                    {
                        speed = Expression.getNumericValue(lv.get(2 + locator.offset).evalValue(c)).getDouble();
                        if (lv.size() > 3 + locator.offset) // should accept entity as well as long as it is player
                        {
                            player = ms.getPlayerList().getPlayerByUsername(lv.get(3 + locator.offset).evalValue(c).getString());
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
                throw new InternalExpressionException("No such particle: "+particleName);
            }
            Vec3d vec = locator.vec;
            if (player == null)
            {
                for (EntityPlayer p : (world.playerEntities))
                {
                    world.spawnParticle((EntityPlayerMP)p, particle, true, vec.x, vec.y, vec.z, count,
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

        this.expr.addLazyFunction("particle_line", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext)c;
            WorldServer world = cc.s.getWorld();
            String particleName = lv.get(0).evalValue(c).getString();
            IParticleData particle;
            try
            {
                particle = ParticleArgument.func_197189_a(new StringReader(particleName));
            }
            catch (CommandSyntaxException e)
            {
                throw new InternalExpressionException("No such particle: "+particleName);
            }
            BlockValue.VectorLocator pos1 = BlockValue.locateVec(cc, lv, 1);
            BlockValue.VectorLocator pos2 = BlockValue.locateVec(cc, lv, pos1.offset);
            int offset = pos2.offset;
            double density = (lv.size() > offset)?Expression.getNumericValue(lv.get(offset).evalValue(c)).getDouble():1.0;
            if (density <= 0)
            {
                throw new InternalExpressionException("Particle density should be positive");
            }
            return (c_, t_) -> new NumericValue(drawParticleLine(world, particle, pos1.vec, pos2.vec, density));
        });

        this.expr.addLazyFunction("particle_rect", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext)c;
            WorldServer world = cc.s.getWorld();
            String particleName = lv.get(0).evalValue(c).getString();
            IParticleData particle;
            try
            {
                particle = ParticleArgument.func_197189_a(new StringReader(particleName));
            }
            catch (CommandSyntaxException e)
            {
                throw new InternalExpressionException("No such particle: "+particleName);
            }
            BlockValue.VectorLocator pos1 = BlockValue.locateVec(cc, lv, 1);
            BlockValue.VectorLocator pos2 = BlockValue.locateVec(cc, lv, pos1.offset);
            int offset = pos2.offset;
            double density = 1.0;
            if (lv.size() > offset)
            {
                density = Expression.getNumericValue(lv.get(offset).evalValue(c)).getDouble();
            }
            if (density <= 0)
            {
                throw new InternalExpressionException("Particle density should be positive");
            }
            Vec3d a = pos1.vec;
            Vec3d b = pos2.vec;
            double ax = min(a.x, b.x);
            double ay = min(a.y, b.y);
            double az = min(a.z, b.z);
            double bx = max(a.x, b.x);
            double by = max(a.y, b.y);
            double bz = max(a.z, b.z);
            int pc = 0;
            pc += drawParticleLine(world, particle, new Vec3d(ax, ay, az), new Vec3d(ax, by, az), density);
            pc += drawParticleLine(world, particle, new Vec3d(ax, by, az), new Vec3d(bx, by, az), density);
            pc += drawParticleLine(world, particle, new Vec3d(bx, by, az), new Vec3d(bx, ay, az), density);
            pc += drawParticleLine(world, particle, new Vec3d(bx, ay, az), new Vec3d(ax, ay, az), density);

            pc += drawParticleLine(world, particle, new Vec3d(ax, ay, bz), new Vec3d(ax, by, bz), density);
            pc += drawParticleLine(world, particle, new Vec3d(ax, by, bz), new Vec3d(bx, by, bz), density);
            pc += drawParticleLine(world, particle, new Vec3d(bx, by, bz), new Vec3d(bx, ay, bz), density);
            pc += drawParticleLine(world, particle, new Vec3d(bx, ay, bz), new Vec3d(ax, ay, bz), density);

            pc += drawParticleLine(world, particle, new Vec3d(ax, ay, az), new Vec3d(ax, ay, bz), density);
            pc += drawParticleLine(world, particle, new Vec3d(ax, by, az), new Vec3d(ax, by, bz), density);
            pc += drawParticleLine(world, particle, new Vec3d(bx, by, az), new Vec3d(bx, by, bz), density);
            pc += drawParticleLine(world, particle, new Vec3d(bx, ay, az), new Vec3d(bx, ay, bz), density);
            int particleCount = pc;
            return (c_, t_) -> new NumericValue(particleCount);
        });

        //"overridden" native call that prints to stderr
        this.expr.addLazyFunction("print", 1, (c, t, lv) ->
        {
            Messenger.m(((CarpetContext)c).s, "w " + lv.get(0).evalValue(c).getString());
            return lv.get(0); // pass through for variables
        });


        this.expr.addLazyFunction("run", 1, (c, t, lv) -> {
            BlockPos target = BlockValue.locateBlockPos((CarpetContext) c,
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

        this.expr.addLazyFunction("tick_time", 0, (c, t, lv) ->
                (cc, tt) -> new NumericValue(((CarpetContext)cc).s.getServer().getTickCounter()));

        this.expr.addLazyFunction("game_tick", -1, (c, t, lv) -> {
            CommandSource s = ((CarpetContext)c).s;
            s.getServer().tick( () -> System.nanoTime()-tickStart<50000000L);
            s.getServer().dontPanic(); // optional not to freak out the watchdog
            if (lv.size()>0)
            {
                long ms_total = Expression.getNumericValue(lv.get(0).evalValue(c)).getLong();
                long end_expected = tickStart+ms_total*1000000L;
                long wait = end_expected-System.nanoTime();
                if (wait > 0L)
                {
                    try
                    {
                        Thread.sleep(wait/1000000L);
                    }
                    catch (InterruptedException ignored)
                    {
                    }
                }
            }
            tickStart = System.nanoTime(); // for the next tick
            Thread.yield();
            if(stopAll)
                throw new Expression.ExitStatement(Value.NULL);
            return (cc, tt) -> Value.TRUE;
        });

        this.expr.addLazyFunction("current_dimension", 0, (c, t, lv) -> {
            CommandSource s = ((CarpetContext)c).s;
            return (cc, tt) -> new StringValue(s.getWorld().dimension.getType().toString().replaceFirst("minecraft:",""));
        });

        this.expr.addLazyFunction("plop", 4, (c, t, lv) ->{
            BlockValue.LocatorResult locator = BlockValue.fromParams((CarpetContext)c, lv, 0);
            Boolean res = FeatureGenerator.spawn(lv.get(locator.offset).evalValue(c).getString(), ((CarpetContext)c).s.getWorld(), locator.block.getPos());
            if (res == null)
                return (c_, t_) -> Value.NULL;
            return (c_, t_) -> new NumericValue(res);
        });
    }

    static
    {
        Expression.globalVariables.put("_x", (c, t) -> Value.ZERO);
        Expression.globalVariables.put("_y", (c, t) -> Value.ZERO);
        Expression.globalVariables.put("_z", (c, t) -> Value.ZERO);
    }
    /**
     * <h1>.</h1>
     * @param expression expression
     * @param source source
     * @param origin origin
     */
    public CarpetExpression(String expression, CommandSource source, BlockPos origin)
    {
        this.origin = origin;
        this.source = source;
        this.expr = new Expression(expression);

        API_BlockManipulation();
        API_EntityManipulation();
        API_IteratingOverAreasOfBlocks();
        API_AuxiliaryAspects();
    }

    /**
     * <h1><code>/script scan</code>, <code>/script scan</code> and <code>/script outline</code> commands</h1>
     * <div style="padding-left: 20px; border-radius: 5px 45px; border:1px solid grey;">
     * <p>These commands can be used to evaluate an expression over an area of blocks. They all need to have specified
     * the origin of the analyzed area (which is used as referenced (0,0,0), and two corners of an area to analyzed. If
     * you would want that the script block coordinates refer to the actual world coordinates, use origin of <code>0 0 0</code>,
     * or if it doesn't matter, duplicating coordinates of one of the corners is the easiest way.</p>
     * <p>These commands, unlike raw <code>/script run </code> are limited by vanilla fill / clone command
     * limit of 32k blocks, which can be altered with carpet mod's own <code>/carpet fillLimit</code> command.</p>
     * <h2></h2>
     * <h3><code>/script scan origin&lt;x y z&gt;  corner&lt;x y z&gt; corner&lt;x y z&gt; expr</code></h3>
     * <p>Evaluates expression for each point in the area and returns number of successes (result was positive). Since
     * the command by itself doesn't affect the area, the effects would be in side effects.</p>
     * <h3><code>/script fill origin&lt;x y z&gt;  corner&lt;x y z&gt; corner&lt;x y z&gt; "expr" &lt;block&gt; (? replace &lt;replacement&gt;) </code></h3>
     * <p>Think of it as a regular fill command, that sets blocks based on whether a result of the command was successful.
     * Note that the expression is in quotes. Thankfully string constants in <code>scarpet</code> use single quotes. Can be used
     * to fill complex geometric shapes.</p>
     * <h3><code>/script outline origin&lt;x y z&gt;  corner&lt;x y z&gt; corner&lt;x y z&gt; "expr" &lt;block&gt; (? replace &lt;replacement&gt;) </code></h3>
     * <p>Similar to <code>fill</code> command it evaluates an expression for each block in the area, but in this case setting blocks
     * where condition was true and any of the neighbouring blocks were evaluated negatively. This allows to create surface areas,
     * like sphere for example, without resorting to various rounding modes and tricks.</p>
     * <p>Here is an example of seven ways to draw a sphere of radius of 32 blocks around 0 100 0: </p>
     * <pre>
     * /script outline 0 100 0 -40 60 -40 40 140 40 "x*x+y*y+z*z &lt;  32*32" white_stained_glass replace air
     * /script outline 0 100 0 -40 60 -40 40 140 40 "x*x+y*y+z*z &lt;= 32*32" white_stained_glass replace air
     * /script outline 0 100 0 -40 60 -40 40 140 40 "x*x+y*y+z*z &lt;  32.5*32.5" white_stained_glass replace air
     * /script fill    0 100 0 -40 60 -40 40 140 40 "floor(sqrt(x*x+y*y+z*z)) == 32" white_stained_glass replace air
     * /script fill    0 100 0 -40 60 -40 40 140 40 "round(sqrt(x*x+y*y+z*z)) == 32" white_stained_glass replace air
     * /script fill    0 100 0 -40 60 -40 40 140 40 "ceil(sqrt(x*x+y*y+z*z)) == 32" white_stained_glass replace air
     * /draw sphere 0 100 0 32 white_stained_glass replace air
     *
     * fluffy ball round(sqrt(x*x+y*y+z*z)-rand(abs(y)))==32
     *
     * </pre>
     * <p>The last method is the one that world edit is using (part of carpet mod). It turns out that the outline method with <code>32.5</code> radius,
     * fill method with <code>round</code> function and draw command are equivalent</p>
     * </div>
     * @param x .
     * @param y .
     * @param z .
     * @return .
     */
    public boolean fillAndScanCommand(int x, int y, int z)
    {
        if (stopAll)
            return false;
        try
        {
            Context context = new CarpetContext(this.expr, source, origin).
                    with("x", (c, t) -> new NumericValue(x - origin.getX()).bindTo("x")).
                    with("y", (c, t) -> new NumericValue(y - origin.getY()).bindTo("y")).
                    with("z", (c, t) -> new NumericValue(z - origin.getZ()).bindTo("z")).
                    with("_", (c, t) -> new BlockValue(null, source.getWorld(), new BlockPos(x, y, z)).bindTo("_"));
            Entity e = source.getEntity();
            if (e==null)
            {
                context.with("p", (cc, tt) -> Value.NULL.reboundedTo("p") );
            }
            else
            {
                context.with("p", (cc, tt) -> new EntityValue(e).bindTo("p"));
            }
            return this.expr.eval(context).getBoolean();
        }
        catch (ExpressionException e)
        {
            throw new ExpressionInspector.CarpetExpressionException(e.getMessage());
        }
        catch (ArithmeticException ae)
        {
            throw new ExpressionInspector.CarpetExpressionException("math doesn't compute... "+ae.getMessage());
        }
    }

    /**
     * <h1><code>/script run</code> command</h1>
     * <div style="padding-left: 20px; border-radius: 5px 45px; border:1px solid grey;">
     * <p>primary way to input commands. The command executes in the context, position, and dimension of the executing
     * player, commandblock, etc... The command receives 4 variables, <code>x</code>, <code>y</code>, <code>z</code>
     * and <code>p</code> indicating position and the executing entity of the command.
     * It is advisable to use <code>/execute in ... at ... as ... run script run ...</code> or similar, to simulate running
     * commands in a different scope</p>
     * </div>
     * @param pos .
     * @return .
     */
    public String scriptRunCommand(BlockPos pos)
    {
        if (stopAll)
            return "SCRIPTING PAUSED";
        try
        {
            Context context = new CarpetContext(this.expr, source, origin).
                    with("x", (c, t) -> new NumericValue(pos.getX() - origin.getX()).bindTo("x")).
                    with("y", (c, t) -> new NumericValue(pos.getY() - origin.getY()).bindTo("y")).
                    with("z", (c, t) -> new NumericValue(pos.getZ() - origin.getZ()).bindTo("z"));
            Entity e = source.getEntity();
            if (e==null)
            {
                context.with("p", (cc, tt) -> Value.NULL.reboundedTo("p") );
            }
            else
            {
                context.with("p", (cc, tt) -> new EntityValue(e).bindTo("p"));
            }
            return this.expr.eval(context).getString();
        }
        catch (ExpressionException e)
        {
            throw new ExpressionInspector.CarpetExpressionException(e.getMessage());
        }
        catch (ArithmeticException ae)
        {
            throw new ExpressionInspector.CarpetExpressionException("math doesn't compute... "+ae.getMessage());
        }
    }

    /**
     * <h1><code>/script invoke / invokepoint / invokearea</code>, <code>/script globals</code> commands</h1>
     * <div style="padding-left: 20px; border-radius: 5px 45px; border:1px solid grey;">
     * <p><code>invoke</code> family of commands provide convenient way to invoke stored procedures (i.e. functions
     * that has been defined previously by any running script. To view current stored procedure set,
     * run <code>/script globals</code>, to define a new stored procedure, just run a <code>/script run function(a,b) -&gt; ( ... )</code>
     * command with your procedure once, and to forget a procedure, use <code>undef</code> function:
     * <code>/script run undef('function')</code></p>
     * <h2></h2>
     * <h3><code>/script invoke &lt;fun&gt; &lt;args?&gt; ... </code></h3>
     * <p>Equivalent of running <code>/script run fun(args, ...)</code>, but you get the benefit of getting the tab completion of the
     * command name, and lower permission level required to run these (since player is not capable of running any custom code
     * in this case, only this that has been executed before by an operator). Arguments will be checked for validity, and
     * you can only pass simple values as arguments (strings, numbers, or <code>null</code> value). Use quotes to include
     * whitespaces in argument strings.</p>
     * <p>Command will check provided arguments with required arguments (count) and fail if not enough or too much arguments
     * are provided. Operators defining functions are advised to use descriptive arguments names, as these will be visible
     * for invokers and form the base of understanding what each argument does.</p>
     * <p><code>invoke</code> family of commands will tab complete any stored function that does not start with <code>'_'</code>,
     * it will still allow to run procedures starting with <code>'_'</code> but not suggest them, and ban execution of any
     * hidden stored procedures, so ones that start with <code>'__'</code>. In case operator needs to use subroutines
     * for convenience and don't want to expose them to the <code>invoke</code> callers, they can use this mechanic.</p>
     * <pre>
     * /script run example_function(const, phrase, price) -&gt; print(const+' '+phrase+' '+price)
     * /script invoke example_function pi costs 5
     * </pre>
     * <h3><code>/script invokepoint &lt;fun&gt; &lt;coords x y z&gt; &lt;args?&gt; ... </code></h3>
     * <p>It is equivalent to <code>invoke</code> except it assumes that the first three arguments are coordinates, and provides
     * coordinates tab completion, with <code>looking at... </code> mechanics for convenience. All other arguments are expected
     * at the end</p>
     * <h3><code>/script invokearea &lt;fun&gt; &lt;coords x y z&gt; &lt;coords x y z&gt; &lt;args?&gt; ... </code></h3>
     * <p>It is equivalent to <code>invoke</code> except it assumes that the first three arguments are one set of ccordinates,
     * followed by the second set of coordinates, providing tab completion, with <code>looking at... </code> mechanics for convenience,
     * followed by any other required arguments</p>
     * </div>
     * @param source .
     * @param call .
     * @param coords .
     * @param arg .
     * @return .
     */

    public static String invokeGlobalFunctionCommand(CommandSource source, String call, List<Integer> coords, String arg)
    {
        if (stopAll)
            return "SCRIPTING PAUSED";
        Expression.UserDefinedFunction acf = Expression.globalFunctions.get(call);
        if (acf == null)
            return "UNDEFINED";
        List<LazyValue> argv = new ArrayList<>();
        for (Integer i: coords)
            argv.add( (c, t) -> new NumericValue(i));
        String sign = "";
        for (Tokenizer.Token tok : Tokenizer.simplepass(arg))
        {
            switch (tok.type)
            {
                case VARIABLE:
                    if (Expression.globalVariables.containsKey(tok.surface.toLowerCase(Locale.ROOT)))
                    {
                        argv.add(Expression.globalVariables.get(tok.surface.toLowerCase(Locale.ROOT)));
                        break;
                    }
                case STRINGPARAM:
                    argv.add((c, t) -> new StringValue(tok.surface));
                    sign = "";
                    break;

                case LITERAL:
                    try
                    {
                        String finalSign = sign;
                        argv.add((c, t) ->new NumericValue(finalSign+tok.surface));
                        sign = "";
                    }
                    catch (NumberFormatException exception)
                    {
                        return "Fail: "+sign+tok.surface+" seems like a number but it is not a number. Use quotes to ensure its a string";
                    }
                    break;
                case HEX_LITERAL:
                    try
                    {
                        String finalSign = sign;
                        argv.add((c, t) -> new NumericValue(new BigInteger(finalSign+tok.surface.substring(2), 16).doubleValue()));
                        sign = "";
                    }
                    catch (NumberFormatException exception)
                    {
                        return "Fail: "+sign+tok.surface+" seems like a number but it is not a number. Use quotes to ensure its a string";
                    }
                    break;
                case OPERATOR:
                case UNARY_OPERATOR:
                    if ((tok.surface.equals("-") || tok.surface.equals("-u")) && sign.isEmpty())
                    {
                        sign = "-";
                    }
                    else
                    {
                        return "Fail: operators, like " + tok.surface + " are not allowed in invoke";
                    }
                    break;
                case FUNCTION:
                    return "Fail: passing functions like "+tok.surface+"() to invoke is not allowed";
                case OPEN_PAREN:
                case COMMA:
                case CLOSE_PAREN:
                    return "Fail: "+tok.surface+" is not allowed in invoke";
            }
        }
        List<String> args = acf.getArguments();
        if (argv.size() != args.size())
        {
            String error = "Fail: stored function "+call+" takes "+args.size()+" arguments, not "+argv.size()+ ":\n";
            for (int i = 0; i < max(argv.size(), args.size()); i++)
            {
                error += (i<args.size()?args.get(i):"??")+" => "+(i<argv.size()?argv.get(i).evalValue(null).getString():"??")+"\n";
            }
            return error;
        }
        try
        {
            Expression.none.setLogOutput((s) -> Messenger.m(source, "gi " + s));
            Context context = new CarpetContext(Expression.none, source, BlockPos.ORIGIN);
            return Expression.evalValue(
                    () -> acf.lazyEval(context, Context.VOID, acf.expression, acf.token, argv),
                    context,
                    Context.VOID
            ).getString();
        }
        catch (ExpressionException e)
        {
            return e.getMessage();
        }
        finally
        {
            Expression.none.setLogOutput(null);
        }
    }

    void setLogOutput(boolean to)
    {
        this.expr.setLogOutput(to ? (s) -> Messenger.m(source, "gi " + s) : null);
    }
    static void setChatErrorSnooper(CommandSource source)
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
    static void resetErrorSnooper()
    {
        ExpressionException.errorSnooper=null;
    }
}
