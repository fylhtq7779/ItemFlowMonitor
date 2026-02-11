package com.itemflowmonitor;

import com.itemflowmonitor.network.TrackerNetworking;
import com.itemflowmonitor.tracker.TrackerManager;
import com.itemflowmonitor.tracker.TrackerSavedData;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ItemFlowMonitor implements ModInitializer {
	public static final String MOD_ID = "itemflowmonitor";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		TrackerNetworking.init();
		ServerTickEvents.END_SERVER_TICK.register(TrackerNetworking::tick);

		// Загрузка трекеров при старте сервера
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			TrackerSavedData.init(server);
		});

		// Сохранение трекеров при остановке сервера (подстраховка)
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			TrackerSavedData.markDirty();
		});

		// Очистка ссылки после полной остановки
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			TrackerSavedData.cleanup();
			TrackerNetworking.clearAllCachedStates();
		});

		// Удаление/перенос трекера при разрушении контейнера
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			TrackerManager manager = TrackerManager.getInstance();
			boolean changed = false;

			if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)) {
				ChestType type = state.getValue(ChestBlock.TYPE);
				if (type != ChestType.SINGLE) {
					// Двойной сундук: определяем позицию второй половины
					Direction facing = state.getValue(ChestBlock.FACING);
					Direction connectedDir = (type == ChestType.LEFT)
							? facing.getClockWise() : facing.getCounterClockWise();
					BlockPos otherPos = pos.relative(connectedDir);

					if (manager.hasTracker(pos)) {
						// Трекер на сломанной половине → перенести на оставшуюся
						manager.moveTracker(pos, otherPos);
						TrackerNetworking.clearCachedState(pos);
						changed = true;
						LOGGER.debug("IFM: трекер перенесён {} → {} при разрушении половины сундука", pos, otherPos);
					}
					// Если трекер на otherPos — ничего не делаем, он остаётся на месте
				} else {
					// Одинарный сундук — просто удаляем
					if (manager.hasTracker(pos)) {
						manager.remove(pos);
						TrackerNetworking.clearCachedState(pos);
						changed = true;
					}
				}
			} else {
				// Не сундук — просто удаляем трекер
				if (manager.hasTracker(pos)) {
					manager.remove(pos);
					TrackerNetworking.clearCachedState(pos);
					changed = true;
				}
			}

			if (changed) {
				TrackerSavedData.markDirty();
			}
		});

		LOGGER.info("Item Flow Monitor загружен!");
	}
}
