package com.itemflowmonitor.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Утилита для нормализации позиций двойных сундуков.
 * Всегда возвращает одну «каноническую» позицию для обеих половин.
 */
public class ChestUtil {

	/**
	 * Нормализует позицию двойного сундука к каноническому блоку.
	 * Для одинарных контейнеров возвращает ту же позицию.
	 */
	public static BlockPos getCanonicalPos(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)) {
			ChestType type = state.getValue(ChestBlock.TYPE);
			if (type != ChestType.SINGLE) {
				Direction facing = state.getValue(ChestBlock.FACING);
				Direction connectedDir = (type == ChestType.LEFT)
						? facing.getClockWise() : facing.getCounterClockWise();
				BlockPos otherPos = pos.relative(connectedDir);
				return smallerPos(pos, otherPos);
			}
		}
		return pos;
	}

	/**
	 * Находит каноническую позицию для двойного сундука из CompoundContainer.
	 * Используется в мixin, где Level недоступен напрямую.
	 */
	public static BlockPos getCanonicalPosFromCompound(CompoundContainer compound) {
		BlockPos pos1 = null;
		BlockPos pos2 = null;
		for (Container half : new Container[]{compound.container1, compound.container2}) {
			if (half instanceof BlockEntity be) {
				if (pos1 == null) {
					pos1 = be.getBlockPos();
				} else {
					pos2 = be.getBlockPos();
				}
			}
		}
		if (pos1 != null && pos2 != null) {
			return smallerPos(pos1, pos2);
		}
		return pos1; // fallback: одна половина
	}

	/**
	 * Возвращает полный контейнер по позиции блока.
	 * Для двойных сундуков — объединённый CompoundContainer из обеих половин.
	 * Для обычных контейнеров — Container из BlockEntity.
	 * Возвращает null если контейнер не найден (например, эндер-сундук).
	 */
	public static Container getFullContainer(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);

		// Двойные сундуки — объединяем обе половины
		if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)) {
			ChestType type = state.getValue(ChestBlock.TYPE);
			if (type != ChestType.SINGLE) {
				Direction facing = state.getValue(ChestBlock.FACING);
				Direction connectedDir = (type == ChestType.LEFT)
						? facing.getClockWise() : facing.getCounterClockWise();
				BlockPos otherPos = pos.relative(connectedDir);
				BlockEntity be = level.getBlockEntity(pos);
				BlockEntity otherBe = level.getBlockEntity(otherPos);
				if (be instanceof Container c1 && otherBe instanceof Container c2) {
					return new CompoundContainer(c1, c2);
				}
			}
		}

		// Обычные контейнеры
		BlockEntity be = level.getBlockEntity(pos);
		if (be instanceof Container container) {
			return container;
		}
		return null;
	}

	/** Выбирает каноническую (меньшую) позицию из двух */
	private static BlockPos smallerPos(BlockPos a, BlockPos b) {
		if (a.getX() != b.getX()) return a.getX() < b.getX() ? a : b;
		if (a.getZ() != b.getZ()) return a.getZ() < b.getZ() ? a : b;
		return a.getY() <= b.getY() ? a : b;
	}
}
