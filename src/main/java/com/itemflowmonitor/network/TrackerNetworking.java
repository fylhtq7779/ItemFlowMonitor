package com.itemflowmonitor.network;

import com.itemflowmonitor.ItemFlowMonitor;
import com.itemflowmonitor.RateMode;
import com.itemflowmonitor.TrackingMode;
import com.itemflowmonitor.TrackingPeriod;
import com.itemflowmonitor.tracker.ContainerTracker;
import com.itemflowmonitor.tracker.TrackerManager;
import com.itemflowmonitor.util.ChestUtil;
import com.itemflowmonitor.tracker.TrackerSavedData;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Регистрация сетевых пакетов и серверная обработка.
 */
public class TrackerNetworking {

	/** Порог изменения rate для отправки пакета */
	private static final double RATE_DELTA_THRESHOLD = 0.01;
	/** Максимальный интервал между отправками (fallback), в тиках */
	private static final int FALLBACK_INTERVAL_TICKS = 20;
	/** Таймаут ghost-трекера: 5 минут без viewer'ов → пауза */
	private static final long GHOST_TIMEOUT_TICKS = 6000;
	/** Интервал проверки ghost-трекеров (10 секунд) */
	private static final int GHOST_CHECK_INTERVAL = 200;
	/** Максимальная дистанция взаимодействия с контейнером (блоки, squared) */
	private static final double MAX_INTERACTION_DISTANCE_SQ = 10.0 * 10.0;

	/** Rate-limit для WARN логирования невалидных пакетов (60 сек) */
	private static volatile long lastInvalidPacketLogTime = 0;
	private static final long INVALID_PACKET_LOG_INTERVAL_MS = 60_000;

	/** Кеш последнего отправленного состояния per-BlockPos */
	private static final Map<BlockPos, CachedState> sentCache = new HashMap<>();

	private record CachedState(double rate, int currentCount, int maxCapacity, long tick) {}

	/** Регистрация типов пакетов и серверных обработчиков */
	public static void init() {
		// Регистрация типов пакетов
		PayloadTypeRegistry.playC2S().register(TrackerConfigC2SPacket.TYPE, TrackerConfigC2SPacket.CODEC);
		PayloadTypeRegistry.playS2C().register(TrackerUpdateS2CPacket.TYPE, TrackerUpdateS2CPacket.CODEC);

		// Обработка C2S: клиент настраивает трекер
		ServerPlayNetworking.registerGlobalReceiver(TrackerConfigC2SPacket.TYPE, (payload, context) -> {
			MinecraftServer server = context.server();
			server.execute(() -> handleConfig(server, context.player(), payload));
		});

		// При входе игрока — отправляем данные всех трекеров для клиентского кеша
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.player;
			TrackerManager manager = TrackerManager.getInstance();
			for (var entry : manager.getAllTrackers().entrySet()) {
				sendTrackerUpdate(player, entry.getKey(), entry.getValue());
			}
		});

		// При дисконнекте — немедленно удаляем viewer'а
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			TrackerManager.getInstance().removeViewer(handler.player.getUUID());
		});
	}

	/** Обработка C2S пакета на сервере */
	private static void handleConfig(MinecraftServer server, ServerPlayer player, TrackerConfigC2SPacket packet) {
		BlockPos pos = packet.pos();

		// --- Валидация C2S пакета (никогда не доверяем клиенту) ---
		if (!validatePacket(player, packet)) return;

		TrackerManager manager = TrackerManager.getInstance();

		if (packet.active()) {
			boolean subscribeOnly = (packet.modeOrdinal() == -1);
			boolean resetOnly = (packet.modeOrdinal() == -2);

			if (resetOnly) {
				// Сброс счётчика — очищаем события трекера
				ContainerTracker tracker = manager.getTracker(pos);
				if (tracker != null) {
					tracker.clearEvents(player.level().getGameTime());
					sendTrackerUpdate(player, pos, tracker);
					ItemFlowMonitor.LOGGER.debug("IFM: счётчик сброшен для {} игроком {}",
							pos, player.getName().getString());
				}
			} else if (subscribeOnly) {
				// Подписка — только регистрируем зрителя, если трекер существует
				if (manager.hasTracker(pos)) {
					ContainerTracker tracker = manager.getTracker(pos);
					tracker.markViewerActive(player.level().getGameTime());
					manager.setViewer(player.getUUID(), pos);
					// Немедленно отправляем текущее состояние
					sendTrackerUpdate(player, pos, tracker);
					ItemFlowMonitor.LOGGER.debug("IFM: подписка на {} игроком {}",
							pos, player.getName().getString());
				} else {
					// Трекер не существует — сообщаем клиенту чтобы очистил кеш
					ServerPlayNetworking.send(player, new TrackerUpdateS2CPacket(
							pos, false, 0, 0, 0, 0, "", 0, 0));
				}
			} else {
				// Полное обновление настроек
				boolean isNew = !manager.hasTracker(pos);

				// Лимит трекеров на игрока — только при создании нового
				if (isNew) {
					int playerTrackerCount = manager.countTrackersByOwner(player.getUUID());
					if (playerTrackerCount >= TrackerManager.MAX_TRACKERS_PER_PLAYER) {
						player.sendSystemMessage(Component.translatable("itemflowmonitor.limit_reached",
								TrackerManager.MAX_TRACKERS_PER_PLAYER));
						// Сообщаем клиенту что трекер не создан — UI должен вернуться в OFF
						ServerPlayNetworking.send(player, new TrackerUpdateS2CPacket(
								pos, false, 0, 0, 0, 0, "", 0, 0));
						return;
					}
				}

				ContainerTracker tracker = manager.getOrCreate(pos);
				if (isNew) {
					tracker.setOwnerUuid(player.getUUID());
					tracker.setDimension(player.level().dimension().identifier().toString());
				}
				tracker.markViewerActive(player.level().getGameTime());
				tracker.initStartTick(player.level().getGameTime());

				TrackingMode[] modes = TrackingMode.values();
				if (packet.modeOrdinal() >= 0 && packet.modeOrdinal() < modes.length) {
					tracker.setMode(modes[packet.modeOrdinal()]);
				}

				TrackingPeriod[] periods = TrackingPeriod.values();
				if (packet.periodOrdinal() >= 0 && packet.periodOrdinal() < periods.length) {
					tracker.setPeriod(periods[packet.periodOrdinal()]);
				}

				RateMode[] rateModes = RateMode.values();
				if (packet.rateModeOrdinal() >= 0 && packet.rateModeOrdinal() < rateModes.length) {
					tracker.setRateMode(rateModes[packet.rateModeOrdinal()]);
				}

				// Устанавливаем предмет только для MANUAL — для ALL/AUTO он сброшен в setMode()
				if (tracker.getMode() == TrackingMode.MANUAL) {
					if (!packet.itemId().isEmpty() && packet.itemId().length() <= 256) {
						try {
							Item item = BuiltInRegistries.ITEM.get(Identifier.parse(packet.itemId()))
								.map(ref -> ref.value()).orElse(null);
							tracker.setTrackedItem(item);
						} catch (Exception e) {
							// Невалидный itemId от клиента — игнорируем
							tracker.setTrackedItem(null);
						}
					} else {
						tracker.setTrackedItem(null);
					}
				}

				manager.setViewer(player.getUUID(), pos);
				TrackerSavedData.markDirty();

				// Немедленно отправляем текущее состояние клиенту
				sendTrackerUpdate(player, pos, tracker);

				ItemFlowMonitor.LOGGER.debug("IFM: трекер обновлён для {} игроком {}",
						pos, player.getName().getString());
			}
		} else {
			// Игрок отключил трекинг — удаляем трекер и зрителя
			manager.removeViewer(player.getUUID());
			if (manager.hasTracker(pos)) {
				manager.remove(pos);
				sentCache.remove(pos);
				TrackerSavedData.markDirty();
				ItemFlowMonitor.LOGGER.debug("IFM: трекер удалён для {} игроком {}",
						pos, player.getName().getString());
			}
		}
	}

	/** Вызывается каждый серверный тик — отправляет S2C обновления зрителям */
	public static void tick(MinecraftServer server) {
		TrackerManager manager = TrackerManager.getInstance();
		long currentTick = server.overworld().getGameTime();

		// Периодическая проверка: ghost-трекеры + валидация блоков (раз в 10 секунд)
		if (currentTick % GHOST_CHECK_INTERVAL == 0) {
			checkGhostTrackers(server, manager, currentTick);
		}

		Map<UUID, BlockPos> viewers = manager.getActiveViewers();
		if (viewers.isEmpty()) return;

		List<UUID> toRemove = new ArrayList<>();
		// Группировка viewer'ов по позиции — один расчёт на контейнер
		Map<BlockPos, List<ServerPlayer>> viewersByPos = new HashMap<>();

		for (var entry : viewers.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				toRemove.add(entry.getKey());
				continue;
			}

			// Проверяем, что игрок ещё смотрит в контейнер
			if (player.containerMenu == player.inventoryMenu) {
				toRemove.add(entry.getKey());
				continue;
			}

			BlockPos pos = entry.getValue();
			viewersByPos.computeIfAbsent(pos, k -> new ArrayList<>()).add(player);
		}

		// Удаляем неактивных зрителей
		for (UUID id : toRemove) {
			manager.removeViewer(id);
		}

		// Для каждой уникальной позиции — один расчёт, delta-check
		for (var posEntry : viewersByPos.entrySet()) {
			BlockPos pos = posEntry.getKey();
			List<ServerPlayer> players = posEntry.getValue();

			ContainerTracker tracker = manager.getTracker(pos);
			if (tracker == null) continue;

			// Отмечаем что viewer активен — сбрасывает ghost-таймер
			tracker.markViewerActive(currentTick);

			double rate = tracker.getRate(currentTick);
			CachedState cached = sentCache.get(pos);

			// Отправляем только при изменении rate или по таймауту
			boolean shouldSend = (cached == null)
					|| (Math.abs(rate - cached.rate) > RATE_DELTA_THRESHOLD)
					|| (currentTick - cached.tick >= FALLBACK_INTERVAL_TICKS);

			if (!shouldSend) continue;

			// Вычисляем пакет один раз для всех viewer'ов позиции
			TrackerUpdateS2CPacket packet = buildUpdatePacket(players.get(0), pos, tracker, rate);

			for (ServerPlayer player : players) {
				ServerPlayNetworking.send(player, packet);
			}

			sentCache.put(pos, new CachedState(rate, packet.currentCount(), packet.maxCapacity(), currentTick));
		}
	}

	/** Проверка ghost-трекеров и валидация блоков (вызывается раз в 10 сек) */
	private static void checkGhostTrackers(MinecraftServer server, TrackerManager manager, long currentTick) {
		Map<UUID, BlockPos> viewers = manager.getActiveViewers();
		List<BlockPos> toRemove = new ArrayList<>();

		for (var entry : manager.getAllTrackers().entrySet()) {
			BlockPos pos = entry.getKey();
			ContainerTracker tracker = entry.getValue();

			// Валидация блока: проверяем что контейнер ещё существует
			ServerLevel level = getTrackerLevel(server, tracker);
			if (level != null && level.isLoaded(pos)) {
				BlockEntity be = level.getBlockEntity(pos);
				if (!(be instanceof Container)) {
					// Блок больше не контейнер (взрыв, поршень и т.д.) → удалить трекер
					toRemove.add(pos);
					continue;
				}
			}

			// Ghost-check: пауза трекеров без viewer'ов
			if (tracker.isPaused()) continue;

			long lastViewer = tracker.getLastViewerTick();
			if (lastViewer < 0) continue;

			if (currentTick - lastViewer > GHOST_TIMEOUT_TICKS) {
				boolean hasViewer = viewers.values().stream().anyMatch(p -> p.equals(pos));
				if (!hasViewer) {
					tracker.setPaused(true);
					sentCache.remove(pos);
					ItemFlowMonitor.LOGGER.debug("IFM: трекер {} приостановлен (нет viewer'ов {}с)",
							pos, GHOST_TIMEOUT_TICKS / 20);
				}
			}
		}

		// Удаляем невалидные трекеры (вне итерации по Map)
		for (BlockPos pos : toRemove) {
			manager.remove(pos);
			sentCache.remove(pos);
			ItemFlowMonitor.LOGGER.debug("IFM: трекер {} удалён (блок больше не контейнер)", pos);
		}
		if (!toRemove.isEmpty()) {
			TrackerSavedData.markDirty();
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

	/**
	 * Валидация C2S пакета. Все проверки — на серверной стороне.
	 * @return true если пакет валиден, false если нужно игнорировать
	 */
	private static boolean validatePacket(ServerPlayer player, TrackerConfigC2SPacket packet) {
		BlockPos pos = packet.pos();
		ServerLevel level = (ServerLevel) player.level();

		// 1. Блок в загруженном чанке
		if (!level.isLoaded(pos)) {
			logInvalidPacket(player, "позиция в невыгруженном чанке: " + pos);
			return false;
		}

		// 2. Блок в радиусе взаимодействия игрока
		double distSq = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		if (distSq > MAX_INTERACTION_DISTANCE_SQ) {
			logInvalidPacket(player, "позиция слишком далеко: " + pos + " (dist²=" + (int) distSq + ")");
			return false;
		}

		// 3. Блок — контейнер (BlockEntity + Container)
		BlockEntity be = level.getBlockEntity(pos);
		if (!(be instanceof Container)) {
			logInvalidPacket(player, "блок не является контейнером: " + pos);
			return false;
		}

		// 4. Валидация ordinal'ов (кроме спец-сигналов -1, -2)
		if (packet.active() && packet.modeOrdinal() >= 0) {
			if (packet.modeOrdinal() >= TrackingMode.values().length) {
				logInvalidPacket(player, "невалидный modeOrdinal: " + packet.modeOrdinal());
				return false;
			}
			if (packet.periodOrdinal() < 0 || packet.periodOrdinal() >= TrackingPeriod.values().length) {
				logInvalidPacket(player, "невалидный periodOrdinal: " + packet.periodOrdinal());
				return false;
			}
			if (packet.rateModeOrdinal() < 0 || packet.rateModeOrdinal() >= RateMode.values().length) {
				logInvalidPacket(player, "невалидный rateModeOrdinal: " + packet.rateModeOrdinal());
				return false;
			}
		}

		// 5. Валидация itemId
		if (!packet.itemId().isEmpty() && packet.itemId().length() > 256) {
			logInvalidPacket(player, "itemId слишком длинный: " + packet.itemId().length());
			return false;
		}

		return true;
	}

	/** Логирование невалидных пакетов с ограничением частоты */
	private static void logInvalidPacket(ServerPlayer player, String reason) {
		long now = System.currentTimeMillis();
		if (now - lastInvalidPacketLogTime >= INVALID_PACKET_LOG_INTERVAL_MS) {
			lastInvalidPacketLogTime = now;
			ItemFlowMonitor.LOGGER.warn("IFM: невалидный C2S пакет от {}: {}",
					player.getName().getString(), reason);
		}
	}

	/** Отправить S2C пакет с текущим состоянием трекера (используется из handleConfig для немедленной отправки) */
	private static void sendTrackerUpdate(ServerPlayer player, BlockPos pos, ContainerTracker tracker) {
		double rate = tracker.getRate(player.level().getGameTime());
		TrackerUpdateS2CPacket packet = buildUpdatePacket(player, pos, tracker, rate);
		ServerPlayNetworking.send(player, packet);
	}

	/** Построить S2C пакет — один расчёт, переиспользуется для нескольких viewer'ов */
	private static TrackerUpdateS2CPacket buildUpdatePacket(ServerPlayer player, BlockPos pos, ContainerTracker tracker, double rate) {
		String itemId = "";
		if (tracker.getTrackedItem() != null) {
			itemId = BuiltInRegistries.ITEM.getKey(tracker.getTrackedItem()).toString();
		}

		// Подсчёт заполненности контейнера для ETA
		int currentCount = 0;
		int maxCapacity = 0;
		Container container = ChestUtil.getFullContainer(player.level(), pos);
		if (container != null) {
			Item trackedItem = tracker.getTrackedItem();
			boolean trackAll = (tracker.getMode() == TrackingMode.ALL);

			if (trackAll) {
				for (int i = 0; i < container.getContainerSize(); i++) {
					ItemStack stack = container.getItem(i);
					currentCount += stack.getCount();
					maxCapacity += stack.isEmpty() ? container.getMaxStackSize() : stack.getMaxStackSize();
				}
			} else if (trackedItem != null) {
				int itemMaxStack = trackedItem.getDefaultInstance().getMaxStackSize();
				for (int i = 0; i < container.getContainerSize(); i++) {
					ItemStack stack = container.getItem(i);
					if (stack.isEmpty()) {
						maxCapacity += itemMaxStack;
					} else if (stack.is(trackedItem)) {
						currentCount += stack.getCount();
						maxCapacity += stack.getMaxStackSize();
					}
					// Слоты с другими предметами — ёмкость для отслеживаемого = 0
				}
			}
		}

		return new TrackerUpdateS2CPacket(
				pos, true, rate,
				tracker.getMode().ordinal(),
				tracker.getPeriod().ordinal(),
				tracker.getRateMode().ordinal(),
				itemId,
				currentCount,
				maxCapacity
		);
	}

	/** Очистить кеш состояния для позиции (при удалении трекера) */
	public static void clearCachedState(BlockPos pos) {
		sentCache.remove(pos);
	}

	/** Очистить весь кеш (при смене мира) */
	public static void clearAllCachedStates() {
		sentCache.clear();
	}
}
