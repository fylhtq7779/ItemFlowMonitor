package com.itemflowmonitor.mixin;

import com.itemflowmonitor.ItemFlowMonitor;
import com.itemflowmonitor.tracker.ContainerTracker;
import com.itemflowmonitor.tracker.TrackerManager;
import com.itemflowmonitor.tracker.TrackerSavedData;
import com.itemflowmonitor.util.ChestUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Перехват передачи предметов через хоппер для записи событий в трекер.
 * Только наблюдает — не модифицирует ItemStack, не меняет return value, не отменяет addItem().
 * Все исключения перехватываются — миксин никогда не ломает vanilla-логику.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {

	/** Сохраняем оригинальный стек перед передачей */
	@Unique
	private static final ThreadLocal<ItemStack> ifm$originalStack = new ThreadLocal<>();

	/** Rate-limit для логирования ошибок: не чаще 1 раза в 60 секунд */
	@Unique
	private static volatile long ifm$lastErrorLogTime = 0;
	@Unique
	private static final long IFM$ERROR_LOG_INTERVAL_MS = 60_000;

	@Inject(
		method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",
		at = @At("HEAD")
	)
	private static void ifm$captureOriginal(Container source, Container dest, ItemStack stack, Direction dir, CallbackInfoReturnable<ItemStack> cir) {
		try {
			// Быстрый выход: если нет ни одного трекера — не аллоцируем копию стека
			if (!TrackerManager.getInstance().hasAnyTrackers()) return;
			ifm$originalStack.set(stack.copy());
		} catch (Exception e) {
			// Не удалось скопировать стек — не критично, просто не запишем событие
			ifm$originalStack.remove();
			ifm$logErrorRateLimited("IFM: ошибка при копировании стека в captureOriginal", e);
		}
	}

	@Inject(
		method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",
		at = @At("RETURN")
	)
	private static void ifm$onItemTransferred(Container source, Container dest, ItemStack stack, Direction dir, CallbackInfoReturnable<ItemStack> cir) {
		ItemStack original = ifm$originalStack.get();
		// Всегда очищаем ThreadLocal — предотвращаем утечку
		ifm$originalStack.remove();

		if (original == null) return;

		try {
			ItemStack remaining = cir.getReturnValue();
			int transferred = original.getCount() - remaining.getCount();
			if (transferred <= 0) return;

			// Получаем BlockPos из контейнера-получателя
			if (dest instanceof BlockEntity be) {
				ifm$tryRecord(be, original, transferred);
			} else if (dest instanceof CompoundContainer compound) {
				ifm$tryRecordCompound(compound, original, transferred);
			}
		} catch (Exception e) {
			ifm$logErrorRateLimited("IFM: ошибка при записи события передачи предмета", e);
		}
	}

	/** Записать событие для одинарного контейнера */
	@Unique
	private static void ifm$tryRecord(BlockEntity be, ItemStack original, int transferred) {
		BlockPos pos = be.getBlockPos();
		ContainerTracker tracker = TrackerManager.getInstance().getTracker(pos);
		if (tracker == null || tracker.isPaused()) return;

		long currentTick = be.getLevel().getGameTime();
		tracker.recordEvent(currentTick, original.getItem(), transferred);
		TrackerSavedData.markDirty();
	}

	/** Записать событие для двойного сундука — нормализуем к каноническому блоку */
	@Unique
	private static void ifm$tryRecordCompound(CompoundContainer compound, ItemStack original, int transferred) {
		BlockPos canonicalPos = ChestUtil.getCanonicalPosFromCompound(compound);
		if (canonicalPos == null) return;

		ContainerTracker tracker = TrackerManager.getInstance().getTracker(canonicalPos);
		if (tracker == null || tracker.isPaused()) return;

		// Берём currentTick из любой половины
		long currentTick = 0;
		for (Container half : new Container[]{compound.container1, compound.container2}) {
			if (half instanceof BlockEntity be && be.getLevel() != null) {
				currentTick = be.getLevel().getGameTime();
				break;
			}
		}
		tracker.recordEvent(currentTick, original.getItem(), transferred);
		TrackerSavedData.markDirty();
	}

	/** Логирование ошибок с ограничением частоты (не чаще 1 раза в 60 секунд) */
	@Unique
	private static void ifm$logErrorRateLimited(String message, Exception e) {
		long now = System.currentTimeMillis();
		if (now - ifm$lastErrorLogTime >= IFM$ERROR_LOG_INTERVAL_MS) {
			ifm$lastErrorLogTime = now;
			ItemFlowMonitor.LOGGER.warn("{}: {}", message, e.getMessage());
		}
	}
}
