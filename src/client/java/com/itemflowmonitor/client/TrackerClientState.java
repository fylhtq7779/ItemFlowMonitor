package com.itemflowmonitor.client;

import com.itemflowmonitor.RateMode;
import com.itemflowmonitor.TrackingMode;
import com.itemflowmonitor.TrackingPeriod;
import com.itemflowmonitor.network.TrackerUpdateS2CPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.Map;

/**
 * Клиентский кеш данных трекера, полученных от сервера.
 * Хранит кеш по BlockPos для мгновенного отображения при открытии сундука.
 */
public class TrackerClientState {
	private static boolean active = false;
	private static double rate = 0;
	private static TrackingMode mode = TrackingMode.ALL;
	private static TrackingPeriod period = TrackingPeriod.MINUTE;
	private static RateMode rateMode = RateMode.PREDICTED;
	private static Item trackedItem = null;
	private static int currentCount = 0;
	private static int maxCapacity = 0;

	/** Кеш данных трекеров по позициям блоков */
	private static final Map<BlockPos, CachedData> cache = new HashMap<>();

	/** Кешированные данные одного трекера */
	public record CachedData(double rate, TrackingMode mode, TrackingPeriod period, RateMode rateMode, Item trackedItem,
							 int currentCount, int maxCapacity) {}

	/** Обновить текущее отображение + кеш из S2C пакета */
	public static void update(TrackerUpdateS2CPacket packet) {
		active = packet.active();

		// Сервер сообщает что трекер не существует — очищаем кеш и сбрасываем состояние
		if (!active) {
			cache.remove(packet.pos());
			rate = 0;
			trackedItem = null;
			currentCount = 0;
			maxCapacity = 0;
			return;
		}

		rate = packet.rate();

		TrackingMode[] modes = TrackingMode.values();
		if (packet.modeOrdinal() >= 0 && packet.modeOrdinal() < modes.length) {
			mode = modes[packet.modeOrdinal()];
		}

		TrackingPeriod[] periods = TrackingPeriod.values();
		if (packet.periodOrdinal() >= 0 && packet.periodOrdinal() < periods.length) {
			period = periods[packet.periodOrdinal()];
		}

		RateMode[] rateModes = RateMode.values();
		if (packet.rateModeOrdinal() >= 0 && packet.rateModeOrdinal() < rateModes.length) {
			rateMode = rateModes[packet.rateModeOrdinal()];
		}

		if (!packet.trackedItemId().isEmpty()) {
			trackedItem = BuiltInRegistries.ITEM.get(Identifier.parse(packet.trackedItemId()))
					.map(ref -> ref.value()).orElse(null);
		} else {
			trackedItem = null;
		}

		currentCount = packet.currentCount();
		maxCapacity = packet.maxCapacity();

		// Сохраняем в кеш по позиции
		cache.put(packet.pos(), new CachedData(rate, mode, period, rateMode, trackedItem, currentCount, maxCapacity));
	}

	/** Восстановить состояние из кеша. Возвращает true если данные найдены.
	 * Восстанавливает настройки, но НЕ rate — он придёт актуальный с сервера. */
	public static boolean restoreFromCache(BlockPos pos) {
		CachedData cached = cache.get(pos);
		if (cached == null) return false;
		active = true;
		rate = 0; // Rate покажем когда придёт актуальный S2C, иначе подскакивает
		mode = cached.mode;
		period = cached.period;
		rateMode = cached.rateMode;
		trackedItem = cached.trackedItem;
		currentCount = 0;
		maxCapacity = 0;
		return true;
	}

	public static void reset() {
		active = false;
		rate = 0;
		trackedItem = null;
		currentCount = 0;
		maxCapacity = 0;
	}

	/** Удалить данные конкретного трекера из кеша */
	public static void removeFromCache(BlockPos pos) {
		cache.remove(pos);
	}

	public static void clearCache() {
		cache.clear();
	}

	public static boolean isActive() { return active; }
	public static double getRate() { return rate; }
	public static TrackingMode getMode() { return mode; }
	public static TrackingPeriod getPeriod() { return period; }
	public static RateMode getRateMode() { return rateMode; }
	public static Item getTrackedItem() { return trackedItem; }
	public static int getCurrentCount() { return currentCount; }
	public static int getMaxCapacity() { return maxCapacity; }
}
