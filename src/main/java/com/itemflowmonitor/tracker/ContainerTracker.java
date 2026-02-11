package com.itemflowmonitor.tracker;

import com.itemflowmonitor.RateMode;
import com.itemflowmonitor.TrackingMode;
import com.itemflowmonitor.TrackingPeriod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

/**
 * Трекер потока предметов для одного контейнера.
 * Хранит настройки отслеживания и кольцевой буфер событий поступления.
 */
public class ContainerTracker {
	/** Максимальный возраст события в тиках (1 час) */
	private static final long MAX_EVENT_AGE = 72000;

	private BlockPos pos;
	private TrackingMode mode;
	private TrackingPeriod period;
	private RateMode rateMode;
	private Item trackedItem; // null для ALL, авто-определяется для AUTO
	private UUID ownerUuid; // UUID игрока, создавшего трекер (для лимита)
	private String dimension = "minecraft:overworld"; // Dimension ID (для периодической валидации)

	/** Сглаженное значение rate для PREDICTED (EMA). -1 = не инициализировано. */
	private double smoothedRate = -1;

	/** Тик начала отслеживания для режима AVERAGE. -1 = не инициализировано. */
	private long startTick = -1;

	/** Тик последней активности viewer'а. -1 = viewer ещё не подключался. */
	private long lastViewerTick = -1;

	/** Трекер на паузе — не записывает события, не тратит ресурсы */
	private boolean paused = false;

	/** Кольцевой буфер событий поступления предметов */
	private final LinkedList<ItemEvent> events = new LinkedList<>();

	public ContainerTracker(BlockPos pos) {
		this.pos = pos;
		this.mode = TrackingMode.ALL;
		this.period = TrackingPeriod.MINUTE;
		this.rateMode = RateMode.PREDICTED;
		this.trackedItem = null;
	}

	/**
	 * Записать событие поступления предмета в контейнер.
	 * В режиме AUTO — первый предмет фиксируется как отслеживаемый.
	 */
	public void recordEvent(long currentTick, Item item, int count) {
		// AUTO-режим: фиксируем первый предмет
		if (mode == TrackingMode.AUTO && trackedItem == null) {
			trackedItem = item;
		}

		// Фиксируем тик начала для AVERAGE
		if (startTick < 0) {
			startTick = currentTick;
		}

		events.add(new ItemEvent(currentTick, item, count));

		// Удаляем устаревшие события
		trimOldEvents(currentTick);
	}

	/** Подсчёт предметов за текущий выбранный период */
	public double getRate(long currentTick) {
		return getRate(currentTick, this.period);
	}

	/** Доля периода для окна выборки PREDICTED (25%) */
	private static final double PREDICT_WINDOW_RATIO = 0.25;

	/** Коэффициент EMA-сглаживания (0.05 ≈ плавное, ~1 сек до стабилизации) */
	private static final double SMOOTH_ALPHA = 0.05;

	/**
	 * Подсчёт предметов за указанный период.
	 * ACTUAL — абсолютное число за скользящее окно периода.
	 * PREDICTED — экстраполяция из короткого окна (10 сек) + EMA-сглаживание.
	 */
	/** Порог тишины для перехода к затуханию AVERAGE (5 секунд) */
	private static final long AVG_DECAY_THRESHOLD = 100;

	public double getRate(long currentTick, TrackingPeriod period) {
		if (rateMode == RateMode.AVERAGE) {
			if (startTick < 0 || currentTick <= startTick) return 0;
			int total = 0;
			long lastMatchTick = startTick;
			for (ItemEvent event : events) {
				if (shouldCount(event.item())) {
					total += event.count();
					lastMatchTick = event.tick();
				}
			}
			if (total <= 0) return 0;

			// Между событиями: elapsed до последнего события (стабильно).
			// Если поток остановился (>5 сек): elapsed до текущего тика (затухание).
			long elapsed = (currentTick - lastMatchTick > AVG_DECAY_THRESHOLD)
					? currentTick - startTick
					: lastMatchTick - startTick;
			if (elapsed <= 0) return 0;

			double avg = (double) total / elapsed * period.getTicks();
			return Math.round(avg * 10.0) / 10.0;
		}

		if (rateMode == RateMode.PREDICTED) {
			long predictWindow = Math.max(200, (long) (period.getTicks() * PREDICT_WINDOW_RATIO));
			long windowStart = currentTick - predictWindow;
			int total = 0;
			for (ItemEvent event : events) {
				if (event.tick() < windowStart) continue;
				if (event.tick() > currentTick) break;
				if (shouldCount(event.item())) {
					total += event.count();
				}
			}
			double rawRate = (double) total / predictWindow * period.getTicks();

			// EMA-сглаживание: убирает дребезг из-за границ окна
			if (smoothedRate < 0) {
				smoothedRate = rawRate;
			} else {
				smoothedRate = SMOOTH_ALPHA * rawRate + (1 - SMOOTH_ALPHA) * smoothedRate;
			}
			// Округление до 1 знака — убирает микро-колебания
			return Math.round(smoothedRate * 10.0) / 10.0;
		}

		// ACTUAL — считаем за полный период
		long windowStart = currentTick - period.getTicks();
		int total = 0;
		for (ItemEvent event : events) {
			if (event.tick() < windowStart) continue;
			if (event.tick() > currentTick) break;
			if (shouldCount(event.item())) {
				total += event.count();
			}
		}
		return total;
	}

	/** Должен ли этот предмет учитываться в подсчёте */
	private boolean shouldCount(Item item) {
		return switch (mode) {
			case ALL -> true;
			case AUTO, MANUAL -> trackedItem != null && trackedItem == item;
		};
	}

	/** Удалить события старше MAX_EVENT_AGE */
	private void trimOldEvents(long currentTick) {
		long cutoff = currentTick - MAX_EVENT_AGE;
		Iterator<ItemEvent> it = events.iterator();
		while (it.hasNext()) {
			if (it.next().tick() < cutoff) {
				it.remove();
			} else {
				break; // события отсортированы по tick
			}
		}
	}

	/** Очистить буфер событий и сбросить сглаживание/среднее */
	public void clearEvents(long currentTick) {
		events.clear();
		smoothedRate = -1;
		startTick = currentTick;
	}

	/** Инициализировать startTick при активации (если ещё не установлен) */
	public void initStartTick(long currentTick) {
		if (startTick < 0) {
			startTick = currentTick;
		}
	}

	// --- Восстановление состояния из сохранения ---

	/** Восстановить полное внутреннее состояние (буфер событий, startTick, smoothedRate) */
	public void restoreState(long savedStartTick, double savedSmoothedRate, List<ItemEvent> savedEvents) {
		this.startTick = savedStartTick;
		this.smoothedRate = savedSmoothedRate;
		this.events.clear();
		this.events.addAll(savedEvents);
	}

	public long getStartTick() { return startTick; }
	public double getSmoothedRate() { return smoothedRate; }
	public List<ItemEvent> getEvents() { return java.util.Collections.unmodifiableList(events); }

	// --- Getters / Setters ---

	public BlockPos getPos() { return pos; }
	void setPos(BlockPos pos) { this.pos = pos; }

	public TrackingMode getMode() { return mode; }
	public void setMode(TrackingMode mode) {
		this.mode = mode;
		// ALL не отслеживает конкретный предмет, AUTO ждёт первый новый
		if (mode == TrackingMode.ALL || mode == TrackingMode.AUTO) {
			this.trackedItem = null;
		}
	}

	public TrackingPeriod getPeriod() { return period; }
	public void setPeriod(TrackingPeriod period) { this.period = period; this.smoothedRate = -1; }

	public RateMode getRateMode() { return rateMode; }
	public void setRateMode(RateMode rateMode) { this.rateMode = rateMode; this.smoothedRate = -1; }

	public Item getTrackedItem() { return trackedItem; }
	public void setTrackedItem(Item item) { this.trackedItem = item; }

	// --- Владелец трекера ---

	public UUID getOwnerUuid() { return ownerUuid; }
	public void setOwnerUuid(UUID uuid) { this.ownerUuid = uuid; }

	public String getDimension() { return dimension; }
	public void setDimension(String dimension) { this.dimension = dimension; }

	// --- Пауза и viewer tracking ---

	/** Отметить что viewer активен — обновляет таймер и снимает паузу.
	 * Если viewer подключился после перерыва (>1 сек) — сбрасывает EMA,
	 * чтобы rate мгновенно показал актуальное значение без "раскачки". */
	public void markViewerActive(long currentTick) {
		if (lastViewerTick >= 0 && currentTick - lastViewerTick > 20) {
			smoothedRate = -1;
		}
		this.lastViewerTick = currentTick;
		this.paused = false;
	}

	public boolean isPaused() { return paused; }
	public void setPaused(boolean paused) { this.paused = paused; }
	public long getLastViewerTick() { return lastViewerTick; }
	public void setLastViewerTick(long tick) { this.lastViewerTick = tick; }

	/** Запись о событии поступления предмета */
	public record ItemEvent(long tick, Item item, int count) {}
}
