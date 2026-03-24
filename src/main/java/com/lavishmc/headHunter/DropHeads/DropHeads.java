/*
 * DropHeads - a Bukkit plugin for naturally dropping mob heads
 *
 * Copyright (C) 2017 - 2022 Nathan / EvModder
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.evmodder.DropHeads;

import org.bukkit.configuration.Configuration;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import net.evmodder.DropHeads.commands.*;
import net.evmodder.DropHeads.datatypes.EntitySetting;
import net.evmodder.DropHeads.datatypes.NoteblockMode;
import net.evmodder.DropHeads.listeners.*;
import net.evmodder.EvLib.bukkit.EvPlugin;
import net.evmodder.EvLib.bukkit.ConfigUtils;
import net.evmodder.EvLib.bukkit.Updater;
import net.evmodder.EvLib.util.FileIO;

public final class DropHeads extends EvPlugin{
	private static DropHeads instance; public static DropHeads getPlugin(){return instance;}
	private InternalAPI api; public HeadAPI getAPI(){return api;} public InternalAPI getInternalAPI(){return api;}
	private DropChanceAPI dropChanceAPI; public DropChanceAPI getDropChanceAPI(){return dropChanceAPI;}
	private boolean LOGFILE_ENABLED;
	private String LOGFILE_NAME;

	@Override public void reloadConfig(){
		ConfigUtils.updateConfigDirName(this);
		InputStream defaultConfig = getClass().getResourceAsStream("/configs/config.yml");
		config = ConfigUtils.loadConfig(this, "config-"+getName()+".yml", defaultConfig, /*notifyIfNew=*/true);
	}

	@Override public void onEvEnable(){
		if(config.getBoolean("update-plugin", false)){
			new Updater(/*plugin=*/this, /*id=*/274151, getFile(), Updater.UpdateType.DEFAULT, /*callback=*/null, /*announce=*/true);
		}
		if(config.getBoolean("bstats-enabled", false) && !config.getBoolean("new")){
			new MetricsLite(this, /*bStats id=*/20140);
		}
		instance = this;
		final NoteblockMode m = MiscUtils.parseEnumOrDefault(config.getString("noteblock-mob-sounds", "OFF"), NoteblockMode.OFF);
		final boolean CRACKED_IRON_GOLEMS_ENABLED = config.getBoolean("cracked-iron-golem-heads", false);

		// Load translations
		final InputStream translationsIS = getClass().getResourceAsStream("/configs/translations.yml");
		final Configuration translations = ConfigUtils.loadConfig(this, "translations.yml", translationsIS, /*notifyIfNew=*/false);
		if(!translations.getBoolean("new")) translations.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(translationsIS)));
		for(String key : translations.getKeys(true)) if(!config.isSet(key)) config.set(key, translations.get(key));

		// Load entity-settings
		final Configuration entitySettings = ConfigUtils.loadConfig(this, "entity-settings.yml",
				getClass().getResourceAsStream("/configs/entity-settings.yml"), /*notifyIfNew=*/false);
		for(String key : entitySettings.getKeys(true)) if(!config.isSet(key)) config.set(key, entitySettings.get(key));

		api = new InternalAPI(m, CRACKED_IRON_GOLEMS_ENABLED);
		dropChanceAPI = new DropChanceAPI();

		final EntitySetting<Boolean> allowNonPlayerKills = EntitySetting.fromConfig(this, "drop-for-nonplayer-kills", false, null);
		final EntitySetting<Boolean> allowIndirectPlayerKills = EntitySetting.fromConfig(this, "drop-for-indirect-player-kills", false, null);
		Stream.concat(
			allowIndirectPlayerKills.typeSettings() == null ? Stream.of() :
			allowIndirectPlayerKills.typeSettings().entrySet().stream()
				.filter(e -> e.getValue() && allowNonPlayerKills.get(e.getKey())).map(e -> e.getKey().name()),
			allowIndirectPlayerKills.subtypeSettings() == null ? Stream.of() :
			allowIndirectPlayerKills.subtypeSettings().entrySet().stream()
				.filter(e -> e.getValue() && allowNonPlayerKills.get(e.getKey())).map(e -> e.getKey())
		).forEach(e ->{
			getLogger().warning("drop-for-indirect-player-kills is true for '"+e+"', which is unnecessary because this mob does not require a player to kill");
		});
		final EntitySetting<Boolean> allowProjectileKills = EntitySetting.fromConfig(this, "drop-for-ranged-kills", false, null);
		final boolean trackRangedWeapon = allowProjectileKills.hasAnyValue();
		new EntityDeathListener(allowNonPlayerKills, allowIndirectPlayerKills, allowProjectileKills, trackRangedWeapon);

		if(config.getBoolean("fix-creative-nbt-copy", true)){
			getServer().getPluginManager().registerEvents(new CreativeMiddleClickListener(), this);
		}
		if(m == NoteblockMode.LISTENER){
			getServer().getPluginManager().registerEvents(new NoteblockPlayListener(), this);
		}

		new CommandDropRate(this);

		LOGFILE_ENABLED = config.getBoolean("log.enable", false);
		if(LOGFILE_ENABLED) LOGFILE_NAME = config.getString("log.filename", "log.txt");
	}

	@Override public void onEvDisable(){}

	public boolean writeToLogFile(String line){
		if(!LOGFILE_ENABLED) return false;
		line = line.replace("\n", "")+"\n";
		getLogger().fine("Writing line to logfile: "+line);
		return FileIO.saveFile(LOGFILE_NAME, line, /*append=*/true);
	}
}
