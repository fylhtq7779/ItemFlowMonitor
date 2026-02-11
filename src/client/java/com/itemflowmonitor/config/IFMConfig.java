package com.itemflowmonitor.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.itemflowmonitor.ItemFlowMonitor;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Конфигурация мода — какие контейнеры поддерживаются.
 * Хранится в config/itemflowmonitor.json, загружается при старте клиента.
 */
public class IFMConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH = FabricLoader.getInstance()
			.getConfigDir().resolve("itemflowmonitor.json");

	// Ключи контейнеров
	public static final String CHEST = "chest";
	public static final String BARREL = "barrel";
	public static final String SHULKER_BOX = "shulker_box";
	public static final String HOPPER = "hopper";
	public static final String DISPENSER = "dispenser";
	public static final String ENDER_CHEST = "ender_chest";
	public static final String FURNACE = "furnace";
	public static final String SMOKER = "smoker";
	public static final String BLAST_FURNACE = "blast_furnace";

	/** Единственный экземпляр конфига */
	private static IFMConfig instance;

	/** Карта: ключ контейнера → включён ли */
	private Map<String, Boolean> enabledContainers;

	private IFMConfig() {
		enabledContainers = createDefaults();
	}

	/** Дефолтные значения — все включены кроме эндер-сундука */
	private static Map<String, Boolean> createDefaults() {
		Map<String, Boolean> map = new LinkedHashMap<>();
		map.put(CHEST, true);
		map.put(BARREL, true);
		map.put(SHULKER_BOX, true);
		map.put(HOPPER, true);
		map.put(DISPENSER, true);
		map.put(ENDER_CHEST, false);
		map.put(FURNACE, true);
		map.put(SMOKER, true);
		map.put(BLAST_FURNACE, true);
		return map;
	}

	/** Получить экземпляр конфига (lazy-load) */
	public static IFMConfig getInstance() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	/** Включён ли контейнер по ключу */
	public boolean isContainerEnabled(String key) {
		return enabledContainers.getOrDefault(key, true);
	}

	/** Установить значение для контейнера */
	public void setContainerEnabled(String key, boolean enabled) {
		enabledContainers.put(key, enabled);
	}

	/** Получить копию карты (для UI) */
	public Map<String, Boolean> getEnabledContainers() {
		return new LinkedHashMap<>(enabledContainers);
	}

	/** Загрузка конфига из файла или создание дефолтного */
	private static IFMConfig load() {
		if (Files.exists(CONFIG_PATH)) {
			try {
				String json = Files.readString(CONFIG_PATH);
				ConfigData data = GSON.fromJson(json, ConfigData.class);
				if (data != null && data.enabledContainers != null) {
					IFMConfig config = new IFMConfig();
					// Мержим: дефолты + значения из файла (новые ключи подхватятся автоматически)
					for (Map.Entry<String, Boolean> entry : data.enabledContainers.entrySet()) {
						config.enabledContainers.put(entry.getKey(), entry.getValue());
					}
					ItemFlowMonitor.LOGGER.debug("IFM конфиг загружен из {}", CONFIG_PATH);
					return config;
				}
			} catch (Exception e) {
				ItemFlowMonitor.LOGGER.warn("Не удалось прочитать IFM конфиг, используем дефолты", e);
			}
		}

		// Файла нет или ошибка — создаём дефолтный и сохраняем
		IFMConfig config = new IFMConfig();
		config.save();
		return config;
	}

	/** Сохранение конфига в файл */
	public void save() {
		try {
			ConfigData data = new ConfigData();
			data.enabledContainers = new LinkedHashMap<>(enabledContainers);
			String json = GSON.toJson(data);
			Files.createDirectories(CONFIG_PATH.getParent());
			Files.writeString(CONFIG_PATH, json);
			ItemFlowMonitor.LOGGER.debug("IFM конфиг сохранён в {}", CONFIG_PATH);
		} catch (IOException e) {
			ItemFlowMonitor.LOGGER.error("Не удалось сохранить IFM конфиг", e);
		}
	}

	/** Перезагрузка конфига (сброс кеша) */
	public static void reload() {
		instance = null;
	}

	/** DTO для сериализации через Gson */
	private static class ConfigData {
		Map<String, Boolean> enabledContainers;
	}
}
