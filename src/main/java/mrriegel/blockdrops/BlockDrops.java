package mrriegel.blockdrops;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.tuple.Pair;
import org.lwjgl.input.Keyboard;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import mezz.jei.gui.recipes.RecipeLayout;
import mezz.jei.gui.recipes.RecipesGui;
import mrriegel.blockdrops.util.Drop;
import mrriegel.blockdrops.util.FakeClientPlayer;
import mrriegel.blockdrops.util.FakeClientWorld;
import mrriegel.blockdrops.util.StackWrapper;
import mrriegel.blockdrops.util.WrapperJson;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraftforge.client.event.GuiScreenEvent.KeyboardInputEvent;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.Mod.Instance;
import net.minecraftforge.fml.common.ProgressManager;
import net.minecraftforge.fml.common.ProgressManager.ProgressBar;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

@Mod(modid = BlockDrops.MODID, name = BlockDrops.MODNAME, version = BlockDrops.VERSION, acceptedMinecraftVersions = "[1.12,1.13)", dependencies = "required-after:jei@[4.8.0,);", clientSideOnly = true)
@EventBusSubscriber
public class BlockDrops {
	public static final String MODID = "blockdrops";
	public static final String VERSION = "1.4.0";
	public static final String MODNAME = "Block Drops";

	@Instance(BlockDrops.MODID)
	public static BlockDrops instance;

	public static boolean all, showChance, showMinMax, multithreaded;
	public static int iteration;
	public static Set<String> blacklist;

	public static List<Wrapper> recipeWrappers;
	public static Gson gson;

	private File recipeWrapFile, modHashFile;

	@EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		File configDir = new File(event.getModConfigurationDirectory(), "BlockDrops");
		recipeWrapFile = new File(configDir, "blockdrops.txt");
		modHashFile = new File(configDir, "modVersions.txt");
		Configuration config = new Configuration(new File(configDir, "config.cfg"));
		config.load();
		all = config.getBoolean("allDrops", Configuration.CATEGORY_CLIENT, false, "Show block drops of any block.");
		showChance = config.getBoolean("showChance", Configuration.CATEGORY_CLIENT, true, "Show chance of drops.");
		showMinMax = config.getBoolean("showMinMax", Configuration.CATEGORY_CLIENT, true, "Show minimum and maximum of drops.");
		multithreaded = config.getBoolean("multithreaded", Configuration.CATEGORY_CLIENT, true, "Multithreaded calculation of drops");
		iteration = config.getInt("iteration", Configuration.CATEGORY_CLIENT, 4000, 1, 99999, "Number of calculation. The higher the more precise the chance.");
		blacklist = Sets.newHashSet(config.getStringList("blacklist", Configuration.CATEGORY_CLIENT, new String[] { "flatcoloredblocks", "chisel", "xtones", "wallpapercraft", "sonarcore", "microblockcbe" }, "Mod IDs of mods that won't be scanned."));
		if (config.hasChanged())
			config.save();
		gson = new GsonBuilder().setPrettyPrinting().registerTypeAdapter(Wrapper.class, new WrapperJson()).create();
	}

	@EventHandler
	public void postInit(FMLPostInitializationEvent event) throws IOException {
		if (recipeWrapFile.exists()) {
			recipeWrappers = gson.fromJson(new BufferedReader(new FileReader(recipeWrapFile)), new TypeToken<List<Wrapper>>() {
			}.getType());
			if (recipeWrappers == null)
				recipeWrappers = new ArrayList<>();
		} else {
			recipeWrappers = Lists.newArrayList();
			recipeWrapFile.createNewFile();
			modHashFile.createNewFile();
		}
		Map<String, String> mods = Maps.newHashMap();
		Loader.instance().getActiveModList().forEach(m -> mods.put(m.getModId(), m.getVersion()));
		for (String black : blacklist)
			mods.remove(black);
		Map<String, String> fileMods = null;
		if (modHashFile.exists()) {
			fileMods = gson.fromJson(new BufferedReader(new FileReader(modHashFile)), new TypeToken<Map<String, String>>() {
			}.getType());
			if (fileMods == null)
				fileMods = Maps.newHashMap();
		} else {
			modHashFile.createNewFile();
			fileMods = Maps.newHashMap();
		}
		Set<String> check = Sets.newHashSet();
		for (Map.Entry<String, String> entry : mods.entrySet()) {
			if (!fileMods.containsKey(entry.getKey()) || !fileMods.get(entry.getKey()).equals(entry.getValue()))
				check.add(entry.getKey());
			//			else {
			//				if (!fileMods.get(entry.getKey()).equals(entry.getValue()))
			//					check.add(entry.getKey());
			//			}
		}
		if (!check.isEmpty()) {
			recipeWrappers.removeIf(w -> check.contains(w.getIn().getItem().getRegistryName().getResourceDomain()));
			recipeWrappers.addAll(Lists.newArrayList(getRecipes(check)));
		}
		recipeWrappers.removeIf(rw -> rw.getIn().isEmpty());
		recipeWrappers.forEach(rw -> rw.getOut().removeIf(d -> d.out.isEmpty()));

		Writer fw = new BufferedWriter(new FileWriter(modHashFile));
		fw.write(gson.toJson(mods));
		fw.close();
		fw = new BufferedWriter(new FileWriter(recipeWrapFile));
		fw.write(gson.toJson(recipeWrappers));
		fw.close();
	}

	public static List<Wrapper> getRecipes(Collection<String> ids) {
		List<Wrapper> res = Lists.newArrayList();
		Set<IBlockState> stateSet = Sets.newHashSet();
		for (Block b : ForgeRegistries.BLOCKS) {
			if (!ids.contains(b.getRegistryName().getResourceDomain()) || Item.getItemFromBlock(b) == null)
				continue;
			NonNullList<ItemStack> lis = NonNullList.create();
			b.getSubBlocks(b.getCreativeTabToDisplayOn(), lis);
			try {
				List<ItemStack> stacks = Lists.newArrayList();
				for (int i = 0; i < 16; i++) {
					IBlockState st = b.getStateFromMeta(i);
					for (ItemStack s : lis)
						if (!s.isEmpty() && s.isItemEqual(getStack(st)) && !stacks.stream().anyMatch(ss -> ss.isItemEqual(s))) {
							stateSet.add(st);
							stacks.add(s);
						}
				}
			} catch (Exception e) {
			}
		}
		List<IBlockState> states = Lists.newArrayList(stateSet);
		states.sort((IBlockState o1, IBlockState o2) -> {
			int id = Integer.compare(Block.getIdFromBlock(o1.getBlock()), Block.getIdFromBlock(o2.getBlock()));
			int meta = Integer.compare(o1.getBlock().getMetaFromState(o1), o2.getBlock().getMetaFromState(o2));
			return id != 0 ? id : meta;
		});

		ProgressManager.ProgressBar bar = ProgressManager.push("Analysing Drops", states.size());
		ExecutorService threadPool = Executors.newCachedThreadPool(); // Pool to store all threads
		Consumer<IBlockState> task = (st) -> {
			List<Drop> drops;
			bar.step(st.getBlock().getRegistryName().toString());
			try {
				drops = getList(st);
			} catch (Throwable e) {
				//				BlockDrops.logger.error("An error occured while calculating drops for " + st.getBlock().getLocalizedName() + " (" + e.getClass() + ")");
				drops = Collections.emptyList();
			}
			if (drops.isEmpty())
				return;
			res.add(new Wrapper(getStack(st), drops));
		};
		for (IBlockState st : states) {
			if (multithreaded) {
				threadPool.execute(() -> task.accept(st));
			} else
				task.accept(st);
		}
		threadPool.shutdown();
		try {
			threadPool.awaitTermination(1, TimeUnit.HOURS);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		if (bar.getSteps() != bar.getStep())
			ReflectionHelper.setPrivateValue(ProgressBar.class, bar, bar.getSteps(), "step");
		ProgressManager.pop(bar);
		return res;

	}

	private static List<Drop> getList(IBlockState state) {
		List<Drop> drops = Lists.newArrayList();
		if (getStack(state).isEmpty())
			return drops;
		List<StackWrapper> stacks0 = Lists.newArrayList(), stacks1 = Lists.newArrayList(), stacks2 = Lists.newArrayList(), stacks3 = Lists.newArrayList();
		Map<StackWrapper, Pair<Integer, Integer>> pairs0 = Maps.newHashMap(), pairs1 = Maps.newHashMap(), pairs2 = Maps.newHashMap(), pairs3 = Maps.newHashMap();
		boolean crashed = false;
		for (int i = 0; i < BlockDrops.iteration; i++) {
			for (int j = 0; j < 4; j++) {
				List<ItemStack> lis = Lists.newArrayList(state.getBlock().getDrops(FakeClientWorld.getInstance(), BlockPos.ORIGIN, state, j));
				if (!crashed)
					try {
						net.minecraftforge.event.ForgeEventFactory.fireBlockHarvesting(lis, FakeClientWorld.getInstance(), BlockPos.ORIGIN, state, j, 1f, false, FakeClientPlayer.getInstance());
					} catch (Throwable t) {
						crashed = true;
					}
				lis.removeIf(ItemStack::isEmpty);
				switch (j) {
				case 0:
					add(pairs0, lis);
					break;
				case 1:
					add(pairs1, lis);
					break;
				case 2:
					add(pairs2, lis);
					break;
				case 3:
					add(pairs3, lis);
					break;
				}
				for (ItemStack s : lis) {
					switch (j) {
					case 0:
						add(stacks0, s);
						break;
					case 1:
						add(stacks1, s);
						break;
					case 2:
						add(stacks2, s);
						break;
					case 3:
						add(stacks3, s);
						break;
					}
				}
			}
		}

		List<StackWrapper> stacks = Lists.newArrayList();
		for (StackWrapper w : stacks0)
			add(stacks, w.stack);
		for (StackWrapper w : stacks1)
			add(stacks, w.stack);
		for (StackWrapper w : stacks2)
			add(stacks, w.stack);
		for (StackWrapper w : stacks3)
			add(stacks, w.stack);

		if (!BlockDrops.all) {
			stacks.removeIf(tmp -> tmp.stack.isItemEqual(getStack(state)));
		}
		stacks.sort((o1, o2) -> {
			int id = Integer.compare(Item.getIdFromItem(o1.stack.getItem()), Item.getIdFromItem(o2.stack.getItem()));
			int meta = Integer.compare(o1.stack.getItemDamage(), o2.stack.getItemDamage());
			return id != 0 ? id : meta;
		});

		for (int i = 0; i < stacks.size(); i++) {
			StackWrapper stack = stacks.get(i);
			float s0 = getChance(stacks0, stack.stack);
			float s1 = getChance(stacks1, stack.stack);
			float s2 = getChance(stacks2, stack.stack);
			float s3 = getChance(stacks3, stack.stack);
			if (!stack.stack.isEmpty())
				drops.add(new Drop(stack.stack, s0, s1, s2, s3, pairs0.get(stack), pairs1.get(stack), pairs2.get(stack), pairs3.get(stack)));
		}
		return drops;

	}

	private static float getChance(List<StackWrapper> stacks, ItemStack stack) {
		if (!BlockDrops.showChance)
			return 0f;
		int con = contains(stacks, stack);
		if (con == -1)
			return 0F;
		return 100F * ((float) stacks.get(con).size / (float) BlockDrops.iteration);
	}

	private static int contains(List<StackWrapper> lis, ItemStack stack) {
		for (int i = 0; i < lis.size(); i++)
			if (lis.get(i).stack.isItemEqual(stack))
				return i;
		return -1;
	}

	private static void add(List<StackWrapper> lis, ItemStack stack) {
		int con = contains(lis, stack);
		if (con == -1)
			lis.add(new StackWrapper(stack, stack.getCount()));
		else {
			StackWrapper tmp = lis.get(con);
			tmp.size += stack.getCount();
			lis.set(con, tmp);
		}
	}

	private static void add(Map<StackWrapper, Pair<Integer, Integer>> map, List<ItemStack> lis) {
		List<StackWrapper> list = Lists.newArrayList();
		for (ItemStack s : lis)
			add(list, s);
		for (StackWrapper w : list) {
			if (map.get(w) == null)
				map.put(w, Pair.of(10000, 0));
			int min = map.get(w).getLeft();
			int max = map.get(w).getRight();
			Pair<Integer, Integer> pair = Pair.of(Math.min(min, w.size), Math.max(max, w.size));
			map.put(w, pair);
		}
	}

	private static ItemStack getStack(IBlockState state) {
		return state.getBlock().getPickBlock(state, new RayTraceResult(FakeClientPlayer.getInstance()), FakeClientWorld.getInstance(), BlockPos.ORIGIN, FakeClientPlayer.getInstance());
	}

	private static Field recipeLayouts, recipeWrapper;

	@SubscribeEvent
	public static void key(KeyboardInputEvent.Post event) throws IllegalAccessException {
		if (Minecraft.getMinecraft().currentScreen instanceof RecipesGui && Keyboard.getEventKeyState()) {
			if (recipeLayouts == null) {
				recipeLayouts = ReflectionHelper.findField(RecipesGui.class, "recipeLayouts");
				recipeWrapper = ReflectionHelper.findField(RecipeLayout.class, "recipeWrapper");
			}
			MutableBoolean change = new MutableBoolean(false);
			((List<RecipeLayout>) recipeLayouts.get(Minecraft.getMinecraft().currentScreen)).stream().map(rl -> {
				try {
					return recipeWrapper.get(rl);
				} catch (IllegalArgumentException | IllegalAccessException e) {
					return null;
				}
			}).filter(o -> o instanceof Wrapper).map(o -> (Wrapper) o)/*.collect(Collectors.toList())*/.forEach(w -> {
				if (Keyboard.isKeyDown(Keyboard.KEY_LEFT)) {
					w.decreaseIndex();
					change.setTrue();
				} else if (Keyboard.isKeyDown(Keyboard.KEY_RIGHT)) {
					w.increaseIndex();
					change.setTrue();
				}
			});
			if (change.isTrue())
				((RecipesGui) Minecraft.getMinecraft().currentScreen).onStateChange();
		}
	}

}
