package com.itemflowmonitor.tracker;

import com.itemflowmonitor.ItemFlowMonitor;
import com.itemflowmonitor.util.ChestUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;

/**
 * Observer-подход: сравнивает содержимое контейнеров каждый тик,
 * детектирует добавленные предметы без зависимости от Mixin.
 * Работает с любыми модами, оптимизирующими хопперы (Lithium и др.).
 *
 * Безопасность: все исключения перехватываются per-трекер —
 * проблема с одним контейнером не влияет на остальные и не крашит серверный тик.
 */
public class ContainerObserver {

	/** Максимальный размер контейнера (защита от мод-блоков с гигантским inventory) */
	private static final int MAX_CONTAINER_SIZE = 256;

	/** Rate-limit для логирования ошибок: не чаще 1 раза в 60 секунд */
	private static volatile long lastErrorLogTime = 0;
	private static final long ERROR_LOG_INTERVAL_MS = 60_000;

	/** Снимок одного слота — Item + количество */
	private record SlotSnapshot(Item item, int count) {}

	/** Предыдущие снимки содержимого контейнеров */
	private final Map<BlockPos, SlotSnapshot[]> snapshots = new HashMap<>();

	/**
	 * Вызывается каждый серверный тик.
	 * Итерируется по всем не-paused трекерам, сравнивает содержимое с предыдущим снимком,
	 * записывает положительные дельты как события поступления.
	 */
	public void tick(MinecraftServer server, TrackerManager manager, long currentTick) {
		for (var entry : manager.getAllTrackers().entrySet()) {
			BlockPos pos = entry.getKey();
			ContainerTracker tracker = entry.getValue();

			if (tracker.isPaused()) continue;

			try {
				observeContainer(server, tracker, pos, currentTick);
			} catch (Exception e) {
				logErrorRateLimited("IFM: ошибка observer для " + pos, e);
			}
		}
	}

	/** Наблюдение за одним контейнером — вынесено для изоляции исключений */
	private void observeContainer(MinecraftServer server, ContainerTracker tracker,
								  BlockPos pos, long currentTick) {
		// Получаем ServerLevel по dimension трекера
		ServerLevel level = getTrackerLevel(server, tracker);
		if (level == null || !level.isLoaded(pos)) return;

		// Получаем полный контейнер (двойной сундук → CompoundContainer)
		Container container = ChestUtil.getFullContainer(level, pos);
		if (container == null) return;

		int size = container.getContainerSize();

		// Защита от мод-блоков с аномально большим inventory
		if (size <= 0 || size > MAX_CONTAINER_SIZE) return;

		SlotSnapshot[] previous = snapshots.get(pos);

		// Создаём текущий снимок
		SlotSnapshot[] current = new SlotSnapshot[size];
		for (int i = 0; i < size; i++) {
			ItemStack stack = container.getItem(i);
			if (stack.isEmpty()) {
				current[i] = new SlotSnapshot(Items.AIR, 0);
			} else {
				current[i] = new SlotSnapshot(stack.getItem(), stack.getCount());
			}
		}

		// Первый снимок — просто сохраняем без записи событий
		if (previous == null) {
			snapshots.put(pos, current);
			return;
		}

		// Сравниваем слоты и записываем положительные дельты
		compareAndRecord(tracker, previous, current, currentTick);

		// Обновляем снимок
		snapshots.put(pos, current);
	}

	/**
	 * Сравнивает предыдущий и текущий снимки, записывает добавленные предметы.
	 */
	private void compareAndRecord(ContainerTracker tracker, SlotSnapshot[] previous,
								  SlotSnapshot[] current, long currentTick) {
		// Размер контейнера мог измениться (двойной сундук → одинарный)
		int minSize = Math.min(previous.length, current.length);
		boolean hasEvents = false;

		for (int i = 0; i < minSize; i++) {
			SlotSnapshot prev = previous[i];
			SlotSnapshot cur = current[i];

			if (cur.item == Items.AIR || cur.count <= 0) continue;

			if (cur.item == prev.item) {
				// Тот же предмет — считаем дельту
				int delta = cur.count - prev.count;
				if (delta > 0) {
					tracker.recordEvent(currentTick, cur.item, delta);
					hasEvents = true;
				}
			} else if (prev.item == Items.AIR || prev.count <= 0) {
				// Слот был пуст, теперь занят — весь стек новый
				tracker.recordEvent(currentTick, cur.item, cur.count);
				hasEvents = true;
			} else {
				// Предмет сменился — новый предмет появился (старый ушёл)
				tracker.recordEvent(currentTick, cur.item, cur.count);
				hasEvents = true;
			}
		}

		// Новые слоты (контейнер стал больше — двойной сундук)
		for (int i = minSize; i < current.length; i++) {
			SlotSnapshot cur = current[i];
			if (cur.item != Items.AIR && cur.count > 0) {
				tracker.recordEvent(currentTick, cur.item, cur.count);
				hasEvents = true;
			}
		}

		if (hasEvents) {
			TrackerSavedData.markDirty();
		}
	}

	/** Удалить снимок для позиции (при удалении трекера) */
	public void removeSnapshot(BlockPos pos) {
		snapshots.remove(pos);
	}

	/** Очистить все снимки (при смене мира) */
	public void clear() {
		snapshots.clear();
	}

	/** Логирование ошибок с ограничением частоты (не чаще 1 раза в 60 секунд) */
	private static void logErrorRateLimited(String message, Exception e) {
		long now = System.currentTimeMillis();
		if (now - lastErrorLogTime >= ERROR_LOG_INTERVAL_MS) {
			lastErrorLogTime = now;
			ItemFlowMonitor.LOGGER.warn("{}: {}", message, e.getMessage());
		}
	}

	/** Получить ServerLevel по dimension ID трекера */
	private static ServerLevel getTrackerLevel(MinecraftServer server, ContainerTracker tracker) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().identifier().toString().equals(tracker.getDimension())) {
				return level;
			}
		}
		return null;
	}
}
