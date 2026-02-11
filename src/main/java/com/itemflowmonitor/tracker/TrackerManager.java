package com.itemflowmonitor.tracker;

import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Глобальный реестр трекеров контейнеров.
 * Синглтон, хранит все активные трекеры по позициям блоков.
 */
public class TrackerManager {
	private static final TrackerManager INSTANCE = new TrackerManager();

	/** Максимальное количество трекеров на одного игрока */
	public static final int MAX_TRACKERS_PER_PLAYER = 100;

	private final Map<BlockPos, ContainerTracker> trackers = new HashMap<>();
	/** Игроки, которые сейчас смотрят в контейнер с трекером */
	private final Map<UUID, BlockPos> activeViewers = new HashMap<>();

	private TrackerManager() {}

	public static TrackerManager getInstance() {
		return INSTANCE;
	}

	/** Получить трекер для позиции или создать новый */
	public ContainerTracker getOrCreate(BlockPos pos) {
		return trackers.computeIfAbsent(pos, ContainerTracker::new);
	}

	/** Получить трекер для позиции (null если нет) */
	public ContainerTracker getTracker(BlockPos pos) {
		return trackers.get(pos);
	}

	/** Удалить трекер для позиции */
	public void remove(BlockPos pos) {
		trackers.remove(pos);
	}

	/** Есть ли трекер для данной позиции */
	public boolean hasTracker(BlockPos pos) {
		return trackers.containsKey(pos);
	}

	/** Перенести трекер с одной позиции на другую (при разрушении половины двойного сундука) */
	public boolean moveTracker(BlockPos from, BlockPos to) {
		ContainerTracker tracker = trackers.remove(from);
		if (tracker == null) return false;
		tracker.setPos(to);
		trackers.put(to, tracker);
		return true;
	}

	/** Все трекеры (для сериализации) */
	public Map<BlockPos, ContainerTracker> getAllTrackers() {
		return Collections.unmodifiableMap(trackers);
	}

	/** Есть ли хотя бы один трекер (для оптимизации hot path в миксине) */
	public boolean hasAnyTrackers() {
		return !trackers.isEmpty();
	}

	/** Количество трекеров, принадлежащих игроку */
	public int countTrackersByOwner(UUID ownerUuid) {
		int count = 0;
		for (ContainerTracker tracker : trackers.values()) {
			if (ownerUuid.equals(tracker.getOwnerUuid())) {
				count++;
			}
		}
		return count;
	}

	/** Очистить все трекеры (при смене мира) */
	public void clear() {
		trackers.clear();
		activeViewers.clear();
	}

	// --- Зрители ---

	public void setViewer(UUID playerId, BlockPos pos) {
		activeViewers.put(playerId, pos);
	}

	public void removeViewer(UUID playerId) {
		activeViewers.remove(playerId);
	}

	public Map<UUID, BlockPos> getActiveViewers() {
		return Collections.unmodifiableMap(activeViewers);
	}
}
