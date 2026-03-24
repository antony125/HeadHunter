package net.evmodder.DropHeads;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.block.data.Rotatable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;
import net.evmodder.DropHeads.commands.CommandDropRate;
import net.evmodder.DropHeads.datatypes.DropMode;
import net.evmodder.DropHeads.datatypes.EntitySetting;
import net.evmodder.DropHeads.events.EntityBeheadEvent;
import net.evmodder.DropHeads.listeners.EntityDeathListener;
import net.evmodder.EvLib.bukkit.EvUtils;
import net.evmodder.EvLib.util.FileIO;
import net.evmodder.EvLib.TextUtils;
import net.evmodder.EvLib.bukkit.TellrawUtils;
import net.evmodder.EvLib.bukkit.TellrawUtils.Component;
import net.evmodder.EvLib.bukkit.YetAnotherProfile;

/** Public API for head drop chance logic loaded from DropHeads configs.
 * Warning: Functions may change or disappear in future releases
 */
public final class DropChanceAPI{
	private final boolean VANILLA_WSKELE_HANDLING;
	private final boolean DEBUG_MODE, LOG_PLAYER_BEHEAD, LOG_MOB_BEHEAD;
	private final String LOG_MOB_FORMAT, LOG_PLAYER_FORMAT;
	private final int JSON_LIMIT;

	private final DropHeads pl;
	private final Random rand;
	private final HashSet<Material> headOverwriteBlocks;

	private final EntitySetting<Set<Material>> requiredWeapons;
	private final EntitySetting<Double> dropChances;
	private final EntitySetting<List<DropMode>> dropModes;
	private final EntitySetting<Map<Material, Double>> weaponMults;
	private final EntitySetting<TreeMap</*timeAlive=*/Integer, Double>> timeAliveMults;// Note: Bukkit's e.getTicksLived() returns an int.

	DropChanceAPI(){
		pl = DropHeads.getPlugin();
		rand = new Random();
		VANILLA_WSKELE_HANDLING = pl.getConfig().getBoolean("vanilla-wither-skeleton-skulls", false);
		DEBUG_MODE = pl.getConfig().getBoolean("debug-messages", true);
		final boolean ENABLE_LOG = pl.getConfig().getBoolean("log.enable", false);
		LOG_MOB_BEHEAD = ENABLE_LOG && pl.getConfig().getBoolean("log.log-mob-behead", false);
		LOG_PLAYER_BEHEAD = ENABLE_LOG && pl.getConfig().getBoolean("log.log-player-behead", false);
		LOG_MOB_FORMAT = LOG_MOB_BEHEAD ? pl.getConfig().getString("log.log-mob-behead-format",
				"${TIMESTAMP},mob decapitated,${VICTIM},${KILLER},${ITEM}") : null;
		LOG_PLAYER_FORMAT = LOG_PLAYER_BEHEAD ? pl.getConfig().getString("log.log-player-behead-format",
				"${TIMESTAMP},player decapitated,${VICTIM},${KILLER},${ITEM}") : null;
		JSON_LIMIT = pl.getConfig().getInt("message-json-limit", 15000);

		dropModes = EntitySetting.fromConfig(pl, "head-item-drop-mode", List.of(DropMode.EVENT, DropMode.SPAWN_RANDOM), (k,v)->{
			final boolean isList = v instanceof List;
			final List<?> vs = isList ? (List<?>)v : List.of(v);
			final List<DropMode> modes = new ArrayList<>(/*capacity=*/vs.size());
			for(Object o : isList ? (List<?>)v : List.of(v)){
				if(o instanceof String s){
					try{modes.add(DropMode.valueOf(s.toUpperCase()));}
					catch(IllegalArgumentException ex){pl.getLogger().severe("Unknown head DropMode: "+s);}
				}
				else{
					pl.getLogger().warning("Invalid entity-setting (expected DropMode) for "+k+": "+v);
					return null;
				}
			}
			if(modes.isEmpty()){
				pl.getLogger().severe("No DropMode(s) specified for "+k+"! Heads will not be dropped!");
				return List.of(DropMode.EVENT, DropMode.SPAWN_RANDOM);
			}
			return modes;
		});
		boolean anyPlacementMode = Stream.concat(
				dropModes.globalDefault().stream(),
				Stream.concat(
					dropModes.typeSettings() == null ? Stream.of() : dropModes.typeSettings().values().stream().flatMap(List::stream),
					dropModes.subtypeSettings() == null ? Stream.of() : dropModes.subtypeSettings().values().stream().flatMap(List::stream)
				)
			).anyMatch(mode -> mode.name().startsWith("PLACE"));

		if(!anyPlacementMode) headOverwriteBlocks = null;
		else{
			List<String> matNames = pl.getConfig().getStringList("head-place-overwrite-blocks");
			headOverwriteBlocks = new HashSet<>(matNames.size());
			for(String matName : matNames){
				try{headOverwriteBlocks.add(Material.valueOf(matName.toUpperCase()));}
				catch(IllegalArgumentException ex){pl.getLogger().severe("Unknown material in 'head-place-overwrite-blocks': "+matName);}
			}
		}

		requiredWeapons = EntitySetting.fromConfig(pl, "require-weapon", Set.of(), (k,v)->{
			if(v instanceof List){
				return Collections.unmodifiableSet(((List<?>)v).stream().map(n -> n.toString()).map(matName -> {
					if(matName.isEmpty()){
						pl.getLogger().warning("Empty weapon name! (should be unreachable, please report this to the developer)");
						return (Material)null;
					}
					final Material mat = Material.getMaterial(matName.toUpperCase());
					if(mat == null) pl.getLogger().warning("Unknown weapon: \""+matName+"\"!");
					return mat;
				})
				.filter(mat -> mat != null)
				.collect(Collectors.toSet()));
			}
			pl.getLogger().warning("Invalid entity-setting (expected weapon list) for "+k+": "+v);
			return null;
		});

		weaponMults = EntitySetting.fromConfig(pl, "weapon-multipliers", Map.of(), (k,v)->{
			if(v instanceof ConfigurationSection cs){
				HashMap<Material, Double> specificWeaponMults = new HashMap<>();
				for(String matName : cs.getKeys(/*deep=*/false)){
					final Material mat = Material.getMaterial(matName.toUpperCase());
					if(mat != null) specificWeaponMults.put(mat, cs.getDouble(matName));
					else pl.getLogger().warning("Invalid weapon: \""+matName+"\"!");
				}
				return specificWeaponMults;
			}
			pl.getLogger().warning("Invalid entity-setting section (expected weapon:number list) for "+k+": "+v);
			return null;
		});

		final TreeMap<Integer, Double> defaultTimeAliveMults = new TreeMap<>();
		defaultTimeAliveMults.put(Integer.MIN_VALUE, 1d);
		timeAliveMults = EntitySetting.fromConfig(pl, "time-alive-multipliers", defaultTimeAliveMults, (k,v) -> {
			if(v instanceof ConfigurationSection cs){
				@SuppressWarnings("unchecked")
				final TreeMap<Integer, Double> timeAliveMults = (TreeMap<Integer, Double>)defaultTimeAliveMults.clone();
				for(String formattedTime : cs.getKeys(/*deep=*/false)){
					try{
						final int timeInTicks = (int)(TextUtils.parseTimeInMillis(formattedTime, /*default unit=millis-per-tick=*/50)/50L);
						timeAliveMults.put(timeInTicks, cs.getDouble(formattedTime));
					}
					catch(NumberFormatException ex){
						pl.getLogger().severe("Error parsing time string for 'time-alive-multipliers' of "+k+": \""+formattedTime+'"');
					}
				}
				if(timeAliveMults.size() > 1) return timeAliveMults;
			}
			pl.getLogger().severe("time-alive-multipliers for "+k+" is incorrectly defined");
			return null;
		});

		final BiFunction<String, String, Double> parseDropChance = (k,v) -> {
			try{
				final double dropChance = Double.parseDouble(v);
				if(dropChance < 0d || dropChance > 1d){
					pl.getLogger().severe("Invalid value for "+k+" in 'head-drop-rates.txt': "+v);
					pl.getLogger().warning("Drop chance should be a decimal between 0 and 1");
					return (dropChance > 1d && dropChance <= 100d) ? dropChance/100d : null;
				}
				return dropChance;
			}
			catch(NumberFormatException e){
				pl.getLogger().severe("Invalid value for "+k+" in 'head-drop-rates.txt': "+v);
				return null;
			}
		};
		//Load individual mobs' drop chances
		dropChances = EntitySetting.fromTextFile(pl, "head-drop-rates.txt", "configs/head-drop-rates.txt", 0d, parseDropChance);
		if(VANILLA_WSKELE_HANDLING && dropChances.get(EntityType.WITHER_SKELETON, 0.025d) != 0.025d){
			pl.getLogger().warning("Wither Skeleton Skull drop chance has been modified in 'head-drop-rates.txt'"
					+ " (0.025->"+dropChances.get(EntityType.WITHER_SKELETON)+"), "
					+ "but this value will be ignored because 'vanilla-wither-skeleton-skulls' is set to true.");
		}
	}

	/** Get the default head drop chance for an entity when a drop chance chance is specified in the config.
	 * @return The default drop chance [0, 1]
	 */
	public double getDefaultDropChance(){return dropChances.globalDefault();}

	/** Check whether the given weapon is allowed to cause a head drop; will always be <code>true</code> if no specific weapon(s) are required.
	 * @param entity The entity that was killed
	 * @param weapon The weapon <code>Material</code> that was used
	 * @return Boolean value
	 */
	public boolean isWeaponAbleToBehead(Entity entity, Material weapon){
		final Set<Material> weapons = requiredWeapons.get(entity);
		return weapons.isEmpty() || weapons.contains(weapon);
	}
	/** Get the raw drop chance (ignore all multipliers) of a head for a specific texture key. Only called by CommandDropRate currently.
	 * @param textureKey The target texture key
	 * @param useDefault Whether to return the default-chance if key is not found, otherwise will return null
	 * @return The drop chance [0, 1] or null
	 */
	public Double getRawDropChanceOrDefaultFromTextureKey(String textureKey, boolean useDefault){
		return dropChances.get(textureKey, useDefault ? dropChances.globalDefault() : null);
	}
	/** Get the raw drop chance (ignore all multipliers) of a head for a specific entity.
	 * @param entity The target entity
	 * @return The drop chance [0, 1]
	 */
	public double getRawDropChance(Entity entity){return dropChances.get(entity);}
	/** Get the drop chance multiplier applied based on Material of the weapon used.
	 * @param weapon The type of weapon used
	 * @return The weapon-type multiplier
	 */
	public double getWeaponMult(Entity entity, Material weapon){return weaponMults.get(entity).getOrDefault(weapon, 1D);}
	/** Get the drop chance multiplier applied based on how many ticks an entity has been alive.
	 * @param entity The entity to check the lifetime of
	 * @return The time-alive multiplier
	 */
	public double getTimeAliveMult(Entity entity){
		return timeAliveMults.get(entity).floorEntry(
				entity.getType() == EntityType.PLAYER
					? ((Player)entity).getStatistic(Statistic.TIME_SINCE_DEATH)
					: entity.getTicksLived()
		).getValue();
	}

	/** Set the raw drop chance of a head for a specific texture key.
	 * @param textureKey The target texture key
	 * @param newChance The new drop chance to use for the entity
	 * @param updateFile Whether to also update <code>plugins/DropHeads/head-drop-rates.txt</code> file (permanently change value)
	 * @return Whether the change took place
	 */
	public boolean setRawDropChance(String textureKey, double newChance, boolean updateFile){
		if(dropChances.get(textureKey, null) == newChance) return false;
		final int firstSep = textureKey.indexOf('|');
		final String eName = firstSep == -1 ? textureKey : textureKey.substring(0, firstSep);
		final EntityType eType;
		try{eType = EntityType.valueOf(eName);}
		catch(IllegalArgumentException ex){return false;}
		if(firstSep != -1){
			if(!pl.getAPI().textureExists(textureKey)) return false;
			dropChances.subtypeSettings().put(textureKey, newChance);
		}
		else dropChances.typeSettings().put(eType, newChance);
		if(!updateFile) return true;

		String spaces = StringUtils.repeat(' ', 19-textureKey.length());
		String insertLine = textureKey + ':' + spaces + newChance;
		String content;
		try{content = new String(Files.readAllBytes(Paths.get(FileIO.DIR+"head-drop-rates.txt")));}
		catch(IOException e){e.printStackTrace(); return false;}

		String updated = content.replaceAll(
				"(?m)^"+textureKey.replace("|", "\\|")+":\\s*\\d*\\.?\\d+(\n?)",
				(newChance == dropChances.globalDefault()) ? "" : insertLine+"$1");
		if(updated.equals(content)) updated += "\n"+insertLine;
		return FileIO.saveFile("head-drop-rates.txt", updated);
	}

	/** Drop a head item for an entity using the appropriate <code>DropMode</code> setting.
	 * @param headItem The head item which will be dropped
	 * @param entity The entity from which the head item came
	 * @param killer The entity responsible for causing the head item to drop
	 * @param evt The <code>Entity*Event</code> from which this function is being called
	 */
	@SuppressWarnings("deprecation")
	public void dropHeadItem(ItemStack headItem, Entity entity, Entity killer, Event evt){
		for(DropMode mode : dropModes.get(entity)){
			if(headItem == null) break;
			switch(mode){
				case EVENT:
					if(evt instanceof EntityDeathEvent ede){ede.getDrops().add(headItem); headItem = null;}
					break;
				case PLACE_BY_KILLER:
				case PLACE_BY_VICTIM:
				case PLACE: {
					Block headBlock = EvUtils.getClosestBlock(entity.getLocation(), 5, b -> headOverwriteBlocks.contains(b.getType())).getBlock();
					BlockState state = headBlock.getState();
					state.setType(headItem.getType());
					Vector facingVector = entity.getLocation().getDirection(); facingVector.setY(0);
					BlockFace blockRotation = MiscUtils.getHeadPlacementDirection(facingVector);
					try{
						Rotatable data = (Rotatable)headBlock.getBlockData();
						data.setRotation(blockRotation);
						state.setBlockData(data);
					}
					catch(ClassCastException ex){
						((Skull)state).setRotation(blockRotation);
					}
					if(headItem.getType() == Material.PLAYER_HEAD){
						YetAnotherProfile.fromSkullMeta((SkullMeta)headItem.getItemMeta()).set((Skull)state);
					}
					if(mode != DropMode.PLACE){
						Entity entityToCheck = (killer == null ||
								(mode == DropMode.PLACE_BY_VICTIM && (entity instanceof Player || killer instanceof Player == false)))
								? entity : killer;
						Event testPermsEvent;
						if(entityToCheck instanceof Player){
							testPermsEvent = new BlockPlaceEvent(headBlock, state,
								headBlock.getRelative(BlockFace.DOWN), headItem, (Player)entityToCheck, /*canBuild=*/true, EquipmentSlot.HAND);
						}
						else{
							testPermsEvent = new EntityBlockFormEvent(entityToCheck, headBlock, state);
						}
						pl.getServer().getPluginManager().callEvent(testPermsEvent);
						if(((Cancellable)testPermsEvent).isCancelled()){
							pl.getLogger().fine("Head placement failed, permission-lacking player: "+entityToCheck.getName());
							break;
						}
					}
					state.update(/*force=*/true);
					headItem = null;
					break;
				}
				case GIVE:
					headItem = MiscUtils.giveItemToEntity(killer, headItem);
					break;
				case SPAWN_RANDOM:
					EvUtils.dropItemNaturally(entity.getLocation(), headItem, rand);
					headItem = null;
					break;
				case SPAWN:
					entity.getWorld().dropItem(entity.getLocation(), headItem);
					headItem = null;
					break;
			}//switch(mode)
		}//for(DROP_MODES)
	}

	private Component getVictimComponent(Entity entity){
		return MiscUtils.getDisplayNameSelectorComponent(entity, true);
	}
	private Component getKillerComponent(Entity killer){
		if(killer == null) return null;
		if(killer instanceof Projectile){
			ProjectileSource shooter = ((Projectile)killer).getShooter();
			if(shooter instanceof Entity) return MiscUtils.getDisplayNameSelectorComponent((Entity)shooter, true);
			else if(shooter instanceof BlockProjectileSource){
				return TellrawUtils.getLocalizedDisplayName(((BlockProjectileSource)shooter).getBlock().getState());
			}
			else pl.getLogger().warning("Unrecognized projectile source: "+shooter.getClass().getName());
		}
		return MiscUtils.getDisplayNameSelectorComponent(killer, true);
	}
	private Component getWeaponComponent(Entity killer, ItemStack weapon){
		if(weapon != null && weapon.getType() != Material.AIR){
			return MiscUtils.getMurderItemComponent(weapon, JSON_LIMIT);
		}
		if(killer != null && killer instanceof Projectile) return MiscUtils.getDisplayNameSelectorComponent(killer, true);
		return null;
	}

	/** Logs a behead event to the DropHeads log file.
	 * @param entity The entity that was beheaded
	 * @param killer The entity that did the beheading
	 * @param weapon The weapon item used to do the beheading
	 */
	public void logHeadDrop(Entity entity, Entity killer, ItemStack weapon){
		pl.writeToLogFile(
				(entity instanceof Player ? LOG_PLAYER_FORMAT : LOG_MOB_FORMAT)
				.replaceAll("(?i)\\$\\{(VICTIM|BEHEADED)_UUID\\}", ""+entity.getUniqueId())
				.replaceAll("(?i)\\$\\{(KILLER|BEHEADER)_UUID\\}", killer == null ? "" : ""+killer.getUniqueId())
				.replaceAll("(?i)\\$\\{(VICTIM|BEHEADED)(_NAME)?\\}", Matcher.quoteReplacement(getVictimComponent(entity).toPlainText()))
				.replaceAll("(?i)\\$\\{(KILLER|BEHEADER)(_NAME)?\\}", killer == null ? "" : Matcher.quoteReplacement(getKillerComponent(killer).toPlainText()))
				.replaceAll("(?i)\\$\\{(ITEM|WEAPON)\\}", weapon == null ? "" : Matcher.quoteReplacement(getWeaponComponent(killer, weapon).toPlainText()))
				.replaceAll("(?i)\\$\\{TIMESTAMP\\}", ""+System.currentTimeMillis())
		);
	}

	/** Attempt to drop a head item for an Entity.
	 * @param entity The entity for which to to create a head
	 * @param killer The entity which did the killing, or null
	 * @param evt The <code>Entity*Event</code> from which this function is being called
	 * @param weapon The weapon used to kill, or null
	 * @return Whether the head drop was completed successfully
	 */
	public boolean triggerHeadDropEvent(Entity entity, Entity killer, Event evt, ItemStack weapon){
		ItemStack headItem = pl.getAPI().getHead(entity);
		EntityBeheadEvent beheadEvent = new EntityBeheadEvent(entity, killer, evt, headItem);
		pl.getServer().getPluginManager().callEvent(beheadEvent);
		if(beheadEvent.isCancelled()){
			if(DEBUG_MODE) pl.getLogger().info("EntityBeheadEvent was cancelled");
			return false;
		}
		if(weapon != null && weapon.getType() == Material.AIR) weapon = null;
		dropHeadItem(headItem, entity, killer, evt);
		if(entity instanceof Player ? LOG_PLAYER_BEHEAD : LOG_MOB_BEHEAD) logHeadDrop(entity, killer, weapon);
		return true;
	}

	//========== Friend EntityDeathListener
	/** Get raw drop chances (EntityType -> Double).
	 * @return An unmodifiable map (EntityType => drop chance)
	 */
	public Map<EntityType, Double> getEntityDropChances(EntityDeathListener.Friend f){
		Objects.requireNonNull(f);
		return dropChances.typeSettings();
	}

	//========== Friend CommandDropRate
	public boolean hasTimeAliveMults(CommandDropRate.Friend f){
		Objects.requireNonNull(f);
		return timeAliveMults.hasAnyValue();
	}
	public boolean hasWeaponMults(CommandDropRate.Friend f){
		Objects.requireNonNull(f);
		return weaponMults.hasAnyValue();
	}
	public EntitySetting<Set<Material>> getRequiredWeapons(CommandDropRate.Friend f){
		Objects.requireNonNull(f);
		return requiredWeapons;
	}
}
