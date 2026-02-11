package com.itemflowmonitor.tracker;

import com.itemflowmonitor.ItemFlowMonitor;
import com.itemflowmonitor.RateMode;
import com.itemflowmonitor.TrackingMode;
import com.itemflowmonitor.TrackingPeriod;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Сохраняемые данные трекеров — настройки + буфер событий + состояние rate.
 * Сериализуется через Codec, автосохраняется при сохранении мира.
 */
public class TrackerSavedData extends SavedData {

	/** Прямая ссылка на текущий экземпляр (надёжнее, чем DataStorage.get) */
	private static TrackerSavedData instance;

	/** Запись одного события для сериализации (короткие имена полей для компактности) */
	public record EventEntry(long tick, String itemId, int count) {
		public static final Codec<EventEntry> CODEC = RecordCodecBuilder.create(inst ->
			inst.group(
				Codec.LONG.fieldOf("t").forGetter(EventEntry::tick),
				Codec.STRING.fieldOf("i").forGetter(EventEntry::itemId),
				Codec.INT.fieldOf("c").forGetter(EventEntry::count)
			).apply(inst, EventEntry::new)
		);
	}

	/** Одна запись трекера — настройки + полное состояние */
	public record TrackerEntry(
			BlockPos pos, int mode, int period, int rateMode, String itemId,
			long startTick, double smoothedRate, List<EventEntry> events,
			String ownerUuid, String dimension
	) {
		public static final Codec<TrackerEntry> CODEC = RecordCodecBuilder.create(inst ->
			inst.group(
				BlockPos.CODEC.fieldOf("pos").forGetter(TrackerEntry::pos),
				Codec.INT.fieldOf("mode").forGetter(TrackerEntry::mode),
				Codec.INT.fieldOf("period").forGetter(TrackerEntry::period),
				Codec.INT.optionalFieldOf("rateMode", 0).forGetter(TrackerEntry::rateMode),
				Codec.STRING.fieldOf("item").forGetter(TrackerEntry::itemId),
				Codec.LONG.optionalFieldOf("startTick", -1L).forGetter(TrackerEntry::startTick),
				Codec.DOUBLE.optionalFieldOf("smoothedRate", -1.0).forGetter(TrackerEntry::smoothedRate),
				EventEntry.CODEC.listOf().optionalFieldOf("events", List.of()).forGetter(TrackerEntry::events),
				Codec.STRING.optionalFieldOf("owner", "").forGetter(TrackerEntry::ownerUuid),
				Codec.STRING.optionalFieldOf("dim", "minecraft:overworld").forGetter(TrackerEntry::dimension)
			).apply(inst, TrackerEntry::new)
		);
	}

	/** Записи, загруженные с диска (используются только при первой загрузке) */
	private List<TrackerEntry> loadedEntries;

	public static final Codec<TrackerSavedData> CODEC = RecordCodecBuilder.create(inst ->
		inst.group(
			TrackerEntry.CODEC.listOf().fieldOf("trackers").forGetter(TrackerSavedData::getEntries)
		).apply(inst, TrackerSavedData::new)
	);

	public static final SavedDataType<TrackerSavedData> TYPE =
		new SavedDataType<>("itemflowmonitor_trackers", TrackerSavedData::new, CODEC, null);

	public TrackerSavedData() {
		this.loadedEntries = new ArrayList<>();
	}

	public TrackerSavedData(List<TrackerEntry> entries) {
		this.loadedEntries = new ArrayList<>(entries);
	}

	/**
	 * Вызывается Codec при сериализации — строит полный снимок из TrackerManager.
	 */
	public List<TrackerEntry> getEntries() {
		TrackerManager manager = TrackerManager.getInstance();
		List<TrackerEntry> result = new ArrayList<>();
		for (Map.Entry<BlockPos, ContainerTracker> entry : manager.getAllTrackers().entrySet()) {
			ContainerTracker tracker = entry.getValue();
			String itemId = "";
			if (tracker.getTrackedItem() != null) {
				itemId = BuiltInRegistries.ITEM.getKey(tracker.getTrackedItem()).toString();
			}

			// Сериализация буфера событий
			List<EventEntry> eventEntries = new ArrayList<>();
			for (ContainerTracker.ItemEvent event : tracker.getEvents()) {
				String eventItemId = BuiltInRegistries.ITEM.getKey(event.item()).toString();
				eventEntries.add(new EventEntry(event.tick(), eventItemId, event.count()));
			}

			String ownerUuid = tracker.getOwnerUuid() != null ? tracker.getOwnerUuid().toString() : "";

			result.add(new TrackerEntry(
				entry.getKey(),
				tracker.getMode().ordinal(),
				tracker.getPeriod().ordinal(),
				tracker.getRateMode().ordinal(),
				itemId,
				tracker.getStartTick(),
				tracker.getSmoothedRate(),
				eventEntries,
				ownerUuid,
				tracker.getDimension()
			));
		}
		ItemFlowMonitor.LOGGER.debug("IFM: сериализация {} трекеров для сохранения", result.size());
		return result;
	}

	/** Инициализировать из DataStorage при старте сервера */
	public static void init(MinecraftServer server) {
		TrackerManager.getInstance().clear();
		try {
			instance = server.overworld().getDataStorage().computeIfAbsent(TYPE);
			instance.loadIntoManager(server.overworld().getGameTime());
		} catch (Exception e) {
			// Повреждённые данные — создаём пустой экземпляр, мир загружается
			ItemFlowMonitor.LOGGER.warn("IFM: ошибка загрузки данных трекеров, настройки сброшены: {}", e.getMessage());
			instance = new TrackerSavedData();
		}
	}

	/** Загрузить сохранённые данные в TrackerManager */
	public void loadIntoManager(long currentGameTime) {
		TrackerManager manager = TrackerManager.getInstance();
		int loaded = 0;

		for (TrackerEntry entry : loadedEntries) {
			try {
				ContainerTracker tracker = manager.getOrCreate(entry.pos());
				// Ghost-check: отсчёт с момента загрузки мира
				tracker.setLastViewerTick(currentGameTime);

				TrackingMode[] modes = TrackingMode.values();
				if (entry.mode() >= 0 && entry.mode() < modes.length) {
					tracker.setMode(modes[entry.mode()]);
				}

				TrackingPeriod[] periods = TrackingPeriod.values();
				if (entry.period() >= 0 && entry.period() < periods.length) {
					tracker.setPeriod(periods[entry.period()]);
				}

				RateMode[] rateModes = RateMode.values();
				if (entry.rateMode() >= 0 && entry.rateMode() < rateModes.length) {
					tracker.setRateMode(rateModes[entry.rateMode()]);
				}

				if (!entry.itemId().isEmpty()) {
					try {
						Item item = BuiltInRegistries.ITEM.get(Identifier.parse(entry.itemId()))
							.map(ref -> ref.value()).orElse(null);
						tracker.setTrackedItem(item);
					} catch (Exception e) {
						// Невалидный itemId — пропускаем предмет
					}
				}

				// Восстановление буфера событий и состояния rate
				List<ContainerTracker.ItemEvent> loadedEvents = new ArrayList<>();
				for (EventEntry eventEntry : entry.events()) {
					try {
						Item eventItem = BuiltInRegistries.ITEM.get(Identifier.parse(eventEntry.itemId()))
							.map(ref -> ref.value()).orElse(null);
						if (eventItem != null) {
							loadedEvents.add(new ContainerTracker.ItemEvent(
								eventEntry.tick(), eventItem, eventEntry.count()));
						}
					} catch (Exception e) {
						// Невалидное событие — пропускаем
					}
				}
				tracker.restoreState(entry.startTick(), entry.smoothedRate(), loadedEvents);

				// Восстановление владельца трекера
				if (!entry.ownerUuid().isEmpty()) {
					try {
						tracker.setOwnerUuid(java.util.UUID.fromString(entry.ownerUuid()));
					} catch (IllegalArgumentException e) {
						// Невалидный UUID — игнорируем
					}
				}

				// Восстановление dimension
				tracker.setDimension(entry.dimension());
				loaded++;
			} catch (Exception e) {
				// Повреждённая запись — пропускаем, продолжаем с остальными
				ItemFlowMonitor.LOGGER.warn("IFM: пропущен повреждённый трекер при загрузке: {}", e.getMessage());
			}
		}

		ItemFlowMonitor.LOGGER.debug("IFM: загружено {}/{} трекеров из сохранения", loaded, loadedEntries.size());
	}

	/** Пометить dirty (вызывать при изменении настроек или записи событий) */
	public static void markDirty() {
		if (instance != null) {
			instance.setDirty();
		}
	}

	/** Очистить ссылку при остановке сервера */
	public static void cleanup() {
		instance = null;
	}
}
