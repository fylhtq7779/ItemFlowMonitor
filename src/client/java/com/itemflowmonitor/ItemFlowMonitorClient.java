package com.itemflowmonitor;

import com.itemflowmonitor.client.SettingsPanel;
import com.itemflowmonitor.client.TrackerClientState;
import com.itemflowmonitor.config.IFMConfig;
import com.itemflowmonitor.network.TrackerConfigC2SPacket;
import com.itemflowmonitor.network.TrackerUpdateS2CPacket;
import com.itemflowmonitor.util.ChestUtil;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class ItemFlowMonitorClient implements ClientModInitializer {

	/** Сторона размещения кнопки и панели IFM */
	private enum Placement { RIGHT, LEFT, TOP }

	/** Текущая открытая панель настроек (null если скрыта) */
	private SettingsPanel currentPanel = null;

	/** BlockPos контейнера, который сейчас открыт */
	private BlockPos currentContainerPos = null;

	/** Текущий экран контейнера (для перепозиционирования панели) */
	private AbstractContainerScreen<?> currentScreen = null;

	/** Текущее размещение кнопки/панели (определяется при открытии экрана) */
	private Placement currentPlacement = Placement.RIGHT;

	@Override
	public void onInitializeClient() {
		// Регистрируем обработчик S2C пакетов
		ClientPlayNetworking.registerGlobalReceiver(TrackerUpdateS2CPacket.TYPE, (payload, context) -> {
			context.client().execute(() -> {
				TrackerClientState.update(payload);
				// Синхронизируем панель с данными от сервера
				if (currentPanel != null) {
					if (payload.active()) {
						currentPanel.syncFromServer(
							TrackerClientState.getMode(),
							TrackerClientState.getPeriod(),
							TrackerClientState.getRateMode(),
							TrackerClientState.getTrackedItem()
						);
						// Панель могла вырасти — перепозиционируем, чтобы не наезжала на контейнер
						repositionPanel();
					} else {
						// Сервер отклонил (лимит, трекер удалён) — возвращаем UI в OFF
						currentPanel.syncDisabled();
					}
				}
			});
		});

		// Очистка клиентского кеша при отключении от мира
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			TrackerClientState.clearCache();
			TrackerClientState.reset();
		});

		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof AbstractContainerScreen<?> containerScreen && isStorageScreen(client, screen)) {
				// Сбрасываем панель
				currentPanel = null;
				currentScreen = containerScreen;

				// Определяем BlockPos контейнера по hitResult
				currentContainerPos = captureContainerPos(client);

				// Пробуем восстановить из кеша — мгновенное отображение rate
				if (currentContainerPos == null || !TrackerClientState.restoreFromCache(currentContainerPos)) {
					TrackerClientState.reset();
				}

				currentPlacement = determinePlacement(containerScreen);
				addIfmButton(containerScreen);

				// Авто-подписка: если для этого контейнера есть трекер — сразу получим rate
				sendSubscribe();

				// Rate overlay как виджет — рендерится до floating item в пайплайне
				addRateOverlayWidget(containerScreen);

				// Сброс при закрытии экрана
				ScreenEvents.remove(screen).register(screen2 -> {
					currentPanel = null;
					TrackerClientState.reset();
					currentContainerPos = null;
					currentScreen = null;
				});
			}
		});

		ItemFlowMonitor.LOGGER.debug("Item Flow Monitor клиент инициализирован");
	}

	/** Поддерживаемые экраны контейнеров-хранилищ (с учётом конфига) */
	private boolean isStorageScreen(Minecraft client, Screen screen) {
		IFMConfig cfg = IFMConfig.getInstance();

		if (screen instanceof ContainerScreen) {
			// ContainerScreen используется для сундука, бочки и эндер-сундука.
			// Определяем тип контейнера по блоку, на который смотрит игрок.
			String configKey = getContainerScreenConfigKey(client);
			return cfg.isContainerEnabled(configKey);
		}
		if (screen instanceof ShulkerBoxScreen) {
			return cfg.isContainerEnabled(IFMConfig.SHULKER_BOX);
		}
		if (screen instanceof HopperScreen) {
			return cfg.isContainerEnabled(IFMConfig.HOPPER);
		}
		if (screen instanceof DispenserScreen) {
			return cfg.isContainerEnabled(IFMConfig.DISPENSER);
		}
		if (screen instanceof net.minecraft.client.gui.screens.inventory.SmokerScreen) {
			return cfg.isContainerEnabled(IFMConfig.SMOKER);
		}
		if (screen instanceof net.minecraft.client.gui.screens.inventory.BlastFurnaceScreen) {
			return cfg.isContainerEnabled(IFMConfig.BLAST_FURNACE);
		}
		if (screen instanceof AbstractFurnaceScreen<?>) {
			// FurnaceScreen (обычная печка) — fallback для AbstractFurnaceScreen
			return cfg.isContainerEnabled(IFMConfig.FURNACE);
		}

		return false;
	}

	/** Определяет ключ конфига для ContainerScreen по блоку, на который смотрит игрок */
	private String getContainerScreenConfigKey(Minecraft client) {
		if (client.hitResult instanceof BlockHitResult blockHit
				&& client.hitResult.getType() == HitResult.Type.BLOCK
				&& client.level != null) {
			var block = client.level.getBlockState(blockHit.getBlockPos()).getBlock();
			if (block instanceof net.minecraft.world.level.block.EnderChestBlock) {
				return IFMConfig.ENDER_CHEST;
			}
			if (block instanceof net.minecraft.world.level.block.BarrelBlock) {
				return IFMConfig.BARREL;
			}
		}
		// По умолчанию — сундук (одинарный/двойной)
		return IFMConfig.CHEST;
	}

	/** Определяем BlockPos контейнера по взгляду игрока (нормализуем для двойных сундуков) */
	private BlockPos captureContainerPos(Minecraft client) {
		HitResult hit = client.hitResult;
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = blockHit.getBlockPos();
			// Нормализуем позицию двойного сундука к каноническому блоку
			if (client.level != null) {
				pos = ChestUtil.getCanonicalPos(client.level, pos);
			}
			return pos;
		}
		return null;
	}

	private void addIfmButton(AbstractContainerScreen<?> screen) {
		int buttonWidth = 30;
		int buttonHeight = 20;
		int buttonX, buttonY;
		switch (currentPlacement) {
			case LEFT -> {
				buttonX = screen.leftPos - buttonWidth - 2;
				buttonY = screen.topPos;
			}
			case TOP -> {
				buttonX = screen.leftPos + screen.imageWidth - buttonWidth;
				buttonY = screen.topPos - buttonHeight - 2;
				if (buttonY < 0) buttonY = 0;
			}
			default -> { // RIGHT
				buttonX = screen.leftPos + screen.imageWidth + 2;
				buttonY = screen.topPos;
			}
		}

		Button button = Button.builder(Component.translatable("itemflowmonitor.button.label"), btn -> {
			togglePanel(screen);
			// Откладываем снятие фокуса — Minecraft ставит фокус после callback
			Minecraft.getInstance().execute(() -> btn.setFocused(false));
		})
		.pos(buttonX, buttonY)
		.size(buttonWidth, buttonHeight)
		.tooltip(Tooltip.create(Component.translatable("itemflowmonitor.button.tooltip")))
		.build();
		button.setTooltipDelay(java.time.Duration.ofMillis(500));

		Screens.getButtons(screen).add(button);
	}

	/** Показать/скрыть панель настроек */
	private void togglePanel(AbstractContainerScreen<?> screen) {
		if (currentPanel != null) {
			Screens.getButtons(screen).remove(currentPanel);
			currentPanel = null;
		} else {
			currentPanel = new SettingsPanel(0, 0, screen.getMenu());
			currentPanel.setOnSettingsChanged(this::sendConfigToServer);
			currentPanel.setOnReset(this::sendReset);

			if (TrackerClientState.isActive()) {
				// Трекер уже существует — инициализируем панель серверными данными
				currentPanel.syncFromServer(
					TrackerClientState.getMode(),
					TrackerClientState.getPeriod(),
					TrackerClientState.getRateMode(),
					TrackerClientState.getTrackedItem()
				);
			}
			// Если трекера нет — панель откроется с toggle OFF, пользователь сам включит

			repositionPanel();
			Screens.getButtons(screen).add(currentPanel);
		}
	}

	/** Перепозиционировать панель с учётом её текущего размера и защитой от наложения */
	private void repositionPanel() {
		if (currentPanel == null || currentScreen == null) return;

		currentPanel.recalculateSize();
		int panelW = currentPanel.getWidth();
		int panelH = currentPanel.getHeight();

		int panelX, panelY;
		switch (currentPlacement) {
			case LEFT -> {
				panelX = currentScreen.leftPos - panelW - 2;
				panelY = currentScreen.topPos + 22;
			}
			case TOP -> {
				panelX = currentScreen.leftPos + currentScreen.imageWidth - panelW;
				panelY = currentScreen.topPos;
			}
			default -> { // RIGHT
				panelX = currentScreen.leftPos + currentScreen.imageWidth + 2;
				panelY = currentScreen.topPos + 22;
			}
		}

		// Защита от наложения панели на контейнер
		int cLeft = currentScreen.leftPos;
		int cRight = cLeft + currentScreen.imageWidth;
		int cTop = currentScreen.topPos;
		int cBottom = cTop + currentScreen.imageHeight;

		boolean overlaps = panelX < cRight && panelX + panelW > cLeft
				&& panelY < cBottom && panelY + panelH > cTop;
		if (overlaps) {
			// Панель не помещается сбоку — размещаем над контейнером
			panelX = cRight - panelW;
			panelY = cTop - panelH - 2;
			if (panelY < 0) {
				// Не помещается сверху — под контейнером
				panelY = cBottom + 2;
			}
		}

		// Финальная защита от выхода за экран
		if (panelY + panelH > currentScreen.height) {
			panelY = Math.max(0, currentScreen.height - panelH);
		}
		if (panelX < 0) panelX = 0;
		if (panelX + panelW > currentScreen.width) {
			panelX = currentScreen.width - panelW;
		}
		currentPanel.setX(panelX);
		currentPanel.setY(panelY);
	}

	/** Подписка на обновления трекера (без перезаписи настроек) */
	private void sendSubscribe() {
		if (currentContainerPos == null) return;
		TrackerConfigC2SPacket packet = new TrackerConfigC2SPacket(
				currentContainerPos, true, -1, -1, -1, "");
		ClientPlayNetworking.send(packet);
	}

	/** Сброс счётчика на сервере */
	private void sendReset() {
		if (currentContainerPos == null) return;
		TrackerConfigC2SPacket packet = new TrackerConfigC2SPacket(
				currentContainerPos, true, -2, -1, -1, "");
		ClientPlayNetworking.send(packet);
	}

	/** Отправляем текущие настройки на сервер */
	private void sendConfigToServer() {
		if (currentContainerPos == null) return;

		// Если трекинг выключен — отправляем active=false для удаления трекера
		if (currentPanel != null && !currentPanel.isTrackingEnabled()) {
			TrackerConfigC2SPacket packet = new TrackerConfigC2SPacket(
					currentContainerPos, false, -1, -1, -1, "");
			ClientPlayNetworking.send(packet);
			TrackerClientState.removeFromCache(currentContainerPos);
			TrackerClientState.reset();
			return;
		}

		String itemId = "";
		if (currentPanel != null && currentPanel.getSelectedItem() != null
				&& currentPanel.getSelectedMode() == TrackingMode.MANUAL) {
			itemId = BuiltInRegistries.ITEM.getKey(currentPanel.getSelectedItem()).toString();
		}

		int modeOrdinal = currentPanel != null ? currentPanel.getSelectedMode().ordinal() : 0;
		int periodOrdinal = currentPanel != null ? currentPanel.getSelectedPeriod().ordinal() : 0; // MINUTE
		int rateModeOrdinal = currentPanel != null ? currentPanel.getSelectedRateMode().ordinal() : 0;

		TrackerConfigC2SPacket packet = new TrackerConfigC2SPacket(
				currentContainerPos,
				true,
				modeOrdinal,
				periodOrdinal,
				rateModeOrdinal,
				itemId
		);

		ClientPlayNetworking.send(packet);
	}

	/** Неинтерактивный виджет rate overlay — рендерится до floating item */
	private void addRateOverlayWidget(AbstractContainerScreen<?> screen) {
		AbstractWidget widget = new AbstractWidget(0, 0, 0, 0, Component.empty()) {
			@Override
			protected void renderWidget(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
				renderRateOverlay(screen, graphics, mouseX, mouseY);
			}

			@Override
			protected void updateWidgetNarration(NarrationElementOutput output) {}
		};
		widget.active = false;
		Screens.getButtons(screen).add(widget);
	}

	/** Определяет сторону размещения UI: справа → слева → сверху.
	 * Учитывает моды-просмотрщики рецептов (REI/EMI/JEI) и чужие виджеты. */
	private Placement determinePlacement(AbstractContainerScreen<?> screen) {
		int containerRight = screen.leftPos + screen.imageWidth;
		int containerLeft = screen.leftPos;

		// Проверяем правую сторону
		boolean rightOccupied = false;

		// Моды-просмотрщики рецептов всегда добавляют панель справа
		FabricLoader loader = FabricLoader.getInstance();
		if (loader.isModLoaded("roughlyenoughitems")
				|| loader.isModLoaded("emi")
				|| loader.isModLoaded("jei")) {
			rightOccupied = true;
		}

		// Также проверяем чужие виджеты справа от контейнера
		if (!rightOccupied) {
			rightOccupied = hasWidgetsInZone(screen, containerRight, screen.width);
		}

		if (!rightOccupied && containerRight + 32 <= screen.width) {
			return Placement.RIGHT;
		}

		// Проверяем левую сторону
		boolean leftOccupied = hasWidgetsInZone(screen, 0, containerLeft);
		if (!leftOccupied && containerLeft >= 32) {
			return Placement.LEFT;
		}

		// Крайний случай — сверху
		return Placement.TOP;
	}

	/** Проверяет, есть ли чужие виджеты (>10x10) в заданной горизонтальной зоне */
	private boolean hasWidgetsInZone(AbstractContainerScreen<?> screen, int zoneLeft, int zoneRight) {
		for (var widget : Screens.getButtons(screen)) {
			int wLeft = widget.getX();
			int wRight = wLeft + widget.getWidth();
			// Виджет пересекается с зоной и достаточно крупный (не мелкая кнопка)
			if (wRight > zoneLeft && wLeft < zoneRight
					&& widget.getWidth() > 10 && widget.getHeight() > 10) {
				return true;
			}
		}
		return false;
	}

	/** Рисует оверлей rate на экране контейнера */
	private void renderRateOverlay(AbstractContainerScreen<?> screen, net.minecraft.client.gui.GuiGraphics graphics,
								   int mouseX, int mouseY) {
		if (!TrackerClientState.isActive()) return;

		var font = Minecraft.getInstance().font;
		int guiLeft = screen.leftPos;
		int guiTop = screen.topPos;
		int guiWidth = screen.imageWidth;

		// Позиция оверлея — правый верхний угол GUI (над контейнером)
		int overlayX = guiLeft + guiWidth - 4;
		int overlayY = guiTop - 18;
		// Если не помещается сверху — размещаем внутри контейнера
		if (overlayY < 1) {
			overlayY = guiTop + 4;
		}

		double rate = TrackerClientState.getRate();
		String periodLabel = TrackerClientState.getPeriod().getComponent().getString();
		Item trackedItem = TrackerClientState.getTrackedItem();

		String rateText = formatRate(rate) + periodLabel;

		int textWidth = font.width(rateText);
		int totalWidth = (trackedItem != null ? 18 : 0) + textWidth;

		// Позиция — выравнивание справа
		int contentX = overlayX - totalWidth;

		// Границы фона
		int bgLeft = contentX - 2;
		int bgTop = overlayY - 1;
		int bgRight = overlayX + 2;
		int bgBottom = overlayY + 17;

		// Фон
		graphics.fill(bgLeft, bgTop, bgRight, bgBottom, 0xCC000000);
		graphics.renderOutline(bgLeft, bgTop, totalWidth + 4, 18, 0xFF555555);

		// Иконка предмета
		int drawX = contentX;
		if (trackedItem != null) {
			graphics.renderItem(new ItemStack(trackedItem), drawX, overlayY);
			drawX += 18;
		}

		// Текст rate: белый — поток есть, тёмно-серый — потока нет
		int rateColor = rate > 0 ? 0xFFFFFFFF : 0xFF555555;
		graphics.drawString(font, rateText, drawX, overlayY + 5, rateColor);

		// ETA тултип при наведении на оверлей
		if (mouseX >= bgLeft && mouseX <= bgRight && mouseY >= bgTop && mouseY <= bgBottom) {
			renderEtaTooltip(graphics, font, rate, mouseX, mouseY);
		}
	}

	/** Рендерит тултип с прогнозом времени до заполнения контейнера */
	private void renderEtaTooltip(net.minecraft.client.gui.GuiGraphics graphics, net.minecraft.client.gui.Font font,
								  double rate, int mouseX, int mouseY) {
		int maxCap = TrackerClientState.getMaxCapacity();
		int current = TrackerClientState.getCurrentCount();
		int remaining = maxCap - current;

		// Не показываем если rate=0, контейнер полон, или данные недоступны
		if (rate <= 0 || remaining <= 0 || maxCap <= 0) return;

		// rate — предметы за период. Переводим в предметы/секунду.
		double ratePerSecond = rate / TrackerClientState.getPeriod().getTicks() * 20.0;
		long etaSeconds = (long) (remaining / ratePerSecond);

		// Больше 7 дней — не показываем (бессмысленный прогноз)
		if (etaSeconds > 604800) return;

		String timeStr = etaSeconds < 1 ? "<1s" : formatEta(etaSeconds);
		Component tooltip = Component.translatable("itemflowmonitor.tooltip.eta.full", timeStr);
		graphics.setComponentTooltipForNextFrame(font, java.util.List.of(tooltip), mouseX, mouseY);
	}

	/** Компактный формат числа: 15K, 1.5M. До 10K — полное число. Ниже 10 — одна десятая */
	private static String formatRate(double rate) {
		if (rate >= 1_000_000) {
			double v = rate / 1_000_000.0;
			return v >= 10 ? String.format("%dM", Math.round(v)) : String.format("%.1fM", v);
		}
		if (rate >= 10_000) {
			double v = rate / 1_000.0;
			return v >= 10 ? String.format("%dK", Math.round(v)) : String.format("%.1fK", v);
		}
		if (rate >= 10) {
			return String.format("%d", Math.round(rate));
		}
		return String.format("%.1f", rate);
	}

	/** Форматирует время в адаптивный формат: 45s, 12m, 2h 15m, 1d 4h */
	private static String formatEta(long seconds) {
		if (seconds < 60) {
			return seconds + "s";
		}
		long m = seconds / 60;
		long s = seconds % 60;
		if (m < 60) {
			return m < 5 && s > 0 ? m + "m " + s + "s" : m + "m";
		}
		long h = seconds / 3600;
		long rm = (seconds % 3600) / 60;
		if (h < 24) {
			return rm > 0 ? h + "h " + rm + "m" : h + "h";
		}
		long d = seconds / 86400;
		long rh = (seconds % 86400) / 3600;
		return rh > 0 ? d + "d " + rh + "h" : d + "d";
	}
}
