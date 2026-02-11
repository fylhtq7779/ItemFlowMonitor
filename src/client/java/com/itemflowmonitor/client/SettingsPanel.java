package com.itemflowmonitor.client;

import com.itemflowmonitor.RateMode;
import com.itemflowmonitor.TrackingMode;
import com.itemflowmonitor.TrackingPeriod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Оверлейная панель настроек трекера, рисуется поверх GUI контейнера.
 */
public class SettingsPanel extends AbstractWidget {
	private static final int MIN_PANEL_WIDTH = 120;
	private static final int BASE_PANEL_HEIGHT = 100;
	private static final int PADDING = 6;
	private static final int ROW_HEIGHT = 16;
	private static final int ITEM_ROW_HEIGHT = 18;
	private static final int MAX_VISIBLE_ITEMS = 6;

	// Цвета
	private static final int BG_COLOR = 0xCC000000;
	private static final int BORDER_COLOR = 0xFF555555;
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int SELECTED_COLOR = 0xFF44AA44;
	private static final int HOVER_COLOR = 0xFF888888;
	private static final int NORMAL_COLOR = 0xFF666666;
	private static final int ITEM_HOVER_COLOR = 0x44FFFFFF;
	private static final int ITEM_SELECTED_COLOR = 0x4444AA44;

	// Тултипы
	private static final long TOOLTIP_DELAY_MS = 500;
	private String lastHoveredElement = null;
	private long hoverStartTimeMs = 0;

	private boolean trackingEnabled = false;
	private TrackingPeriod selectedPeriod = TrackingPeriod.MINUTE;
	private RateMode selectedRateMode = RateMode.PREDICTED;

	/** Порядок отображения режимов расчёта (дефолтный первым) */
	private static final RateMode[] RATE_MODE_DISPLAY_ORDER = {
		RateMode.PREDICTED, RateMode.ACTUAL, RateMode.AVERAGE
	};
	private TrackingMode selectedMode = TrackingMode.ALL;
	private Item selectedItem = null;

	private final AbstractContainerMenu menu;
	private List<Item> containerItems = new ArrayList<>();

	/** Callback при изменении настроек — для отправки C2S пакета */
	private Runnable onSettingsChanged;

	/** Callback для сброса счётчика */
	private Runnable onReset;

	/** Флаг: идёт синхронизация с сервером, не вызывать callback */
	private boolean syncing = false;

	public SettingsPanel(int x, int y, AbstractContainerMenu menu) {
		super(x, y, MIN_PANEL_WIDTH, BASE_PANEL_HEIGHT, Component.translatable("itemflowmonitor.panel.title"));
		this.menu = menu;
		refreshContainerItems();
	}

	/** Пересчитать размеры панели (для позиционирования до первого рендера) */
	public void recalculateSize() {
		this.width = calculateWidth();
		this.height = calculateHeight();
	}

	public void setOnSettingsChanged(Runnable callback) {
		this.onSettingsChanged = callback;
	}

	public void setOnReset(Runnable callback) {
		this.onReset = callback;
	}

	private void notifySettingsChanged() {
		if (syncing) return;
		if (onSettingsChanged != null) onSettingsChanged.run();
	}

	/** Синхронизировать панель с данными от сервера (без вызова callback) */
	public void syncFromServer(TrackingMode mode, TrackingPeriod period, RateMode rateMode, Item item) {
		syncing = true;
		this.trackingEnabled = true;
		this.selectedMode = mode;
		this.selectedPeriod = period;
		this.selectedRateMode = rateMode;
		this.selectedItem = item;
		syncing = false;
	}

	/** Сервер отклонил трекер (лимит, удалён) — возвращаем UI в OFF без callback */
	public void syncDisabled() {
		syncing = true;
		this.trackingEnabled = false;
		syncing = false;
	}

	public boolean isTrackingEnabled() {
		return trackingEnabled;
	}

	public RateMode getSelectedRateMode() {
		return selectedRateMode;
	}

	public TrackingPeriod getSelectedPeriod() {
		return selectedPeriod;
	}

	public TrackingMode getSelectedMode() {
		return selectedMode;
	}

	public Item getSelectedItem() {
		return selectedItem;
	}

	/** Сканирует уникальные предметы в контейнере (без инвентаря игрока) */
	private void refreshContainerItems() {
		LinkedHashSet<Item> items = new LinkedHashSet<>();
		for (Slot slot : menu.slots) {
			// Пропускаем слоты инвентаря игрока
			if (slot.container instanceof Inventory) continue;
			ItemStack stack = slot.getItem();
			if (!stack.isEmpty()) {
				items.add(stack.getItem());
			}
		}
		containerItems = new ArrayList<>(items);
	}

	/** Рассчитывает высоту панели в зависимости от режима и содержимого */
	private int calculateHeight() {
		// Заголовок + строка toggle
		int h = PADDING + ROW_HEIGHT + ROW_HEIGHT + PADDING;

		if (!trackingEnabled) {
			return h;
		}

		// Период + расчёт + режим
		h += 11 + ROW_HEIGHT + 11 + ROW_HEIGHT + 11 + ROW_HEIGHT;
		if (selectedMode == TrackingMode.MANUAL) {
			h += 11; // "Select item:" надпись
			if (containerItems.isEmpty()) {
				h += ITEM_ROW_HEIGHT;
			} else {
				int visibleCount = Math.min(containerItems.size(), MAX_VISIBLE_ITEMS);
				h += visibleCount * ITEM_ROW_HEIGHT;
				if (containerItems.size() > MAX_VISIBLE_ITEMS) {
					h += 10; // "+N more..."
				}
			}
		}
		return h;
	}

	/** Рассчитывает ширину панели по самой широкой строке контента */
	private int calculateWidth() {
		var font = Minecraft.getInstance().font;
		int maxContentWidth = MIN_PANEL_WIDTH - PADDING * 2;

		// Заголовок + кнопка Reset
		int titleRowWidth = font.width(Component.translatable("itemflowmonitor.panel.title"));
		if (trackingEnabled) {
			int resetBtnWidth = font.width(Component.translatable("itemflowmonitor.panel.reset")) + 6;
			titleRowWidth += 8 + resetBtnWidth;
		}
		maxContentWidth = Math.max(maxContentWidth, titleRowWidth);

		// Toggle: "Трекинг:" + "ВКЛ" + "ВЫКЛ"
		int toggleRowWidth = font.width(Component.translatable("itemflowmonitor.panel.tracking")) + 4
			+ font.width(Component.translatable("itemflowmonitor.panel.on")) + 6
			+ 2
			+ font.width(Component.translatable("itemflowmonitor.panel.off")) + 6;
		maxContentWidth = Math.max(maxContentWidth, toggleRowWidth);

		if (trackingEnabled) {
			// Период
			int periodRowWidth = 0;
			for (TrackingPeriod p : TrackingPeriod.values()) {
				periodRowWidth += font.width(p.getComponent()) + 6 + 2;
			}
			periodRowWidth -= 2;
			maxContentWidth = Math.max(maxContentWidth, periodRowWidth);

			// Rate mode
			int rateRowWidth = 0;
			for (RateMode rm : RATE_MODE_DISPLAY_ORDER) {
				rateRowWidth += font.width(rm.getComponent()) + 6 + 2;
			}
			rateRowWidth -= 2;
			maxContentWidth = Math.max(maxContentWidth, rateRowWidth);

			// Tracking mode
			int modeRowWidth = 0;
			for (TrackingMode tm : TrackingMode.values()) {
				modeRowWidth += font.width(tm.getComponent()) + 6 + 2;
			}
			modeRowWidth -= 2;
			maxContentWidth = Math.max(maxContentWidth, modeRowWidth);

			// "Выберите предмет:"
			if (selectedMode == TrackingMode.MANUAL) {
				maxContentWidth = Math.max(maxContentWidth,
					font.width(Component.translatable("itemflowmonitor.panel.select_item")));
			}
		}

		return maxContentWidth + PADDING * 2;
	}

	@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		// Обновляем список предметов при каждом рендере в режиме MANUAL
		if (trackingEnabled && selectedMode == TrackingMode.MANUAL) {
			refreshContainerItems();
		}

		// Пересчитываем размеры панели
		this.width = calculateWidth();
		this.height = calculateHeight();

		var font = Minecraft.getInstance().font;

		// ID элемента, на который наведён курсор (определяется ниже)
		String hoveredElementId = null;

		// Фон и рамка (рисуем первыми)
		graphics.fill(getX(), getY(), getX() + width, getY() + height, BG_COLOR);
		graphics.renderOutline(getX(), getY(), width, height, BORDER_COLOR);

		int contentX = getX() + PADDING;
		int currentY = getY() + PADDING;

		// Заголовок + кнопка Reset (справа, когда tracking ON)
		Component titleText = Component.translatable("itemflowmonitor.panel.title");
		graphics.drawString(font, titleText, contentX, currentY, TEXT_COLOR);
		if (trackingEnabled) {
			Component resetText = Component.translatable("itemflowmonitor.panel.reset");
			int resetBtnWidth = font.width(resetText) + 6;
			int resetBtnX = getX() + width - PADDING - resetBtnWidth;
			boolean resetHovered = isInside(mouseX, mouseY, resetBtnX, currentY, resetBtnWidth, 10);
			if (resetHovered) hoveredElementId = "reset";
			int resetColor = resetHovered ? 0xFFAA4444 : NORMAL_COLOR;
			graphics.fill(resetBtnX, currentY, resetBtnX + resetBtnWidth, currentY + 10, resetColor);
			graphics.drawString(font, resetText, resetBtnX + 3, currentY + 1, TEXT_COLOR);
		}
		currentY += ROW_HEIGHT;

		// --- Toggle ON/OFF ---
		Component trackingLabel = Component.translatable("itemflowmonitor.panel.tracking");
		graphics.drawString(font, trackingLabel, contentX, currentY, 0xFFAAAAAA);
		int toggleLabelWidth = font.width(trackingLabel) + 4;

		Component onText = Component.translatable("itemflowmonitor.panel.on");
		Component offText = Component.translatable("itemflowmonitor.panel.off");

		int onBtnX = contentX + toggleLabelWidth;
		int onBtnWidth = font.width(onText) + 6;
		boolean onHovered = isInside(mouseX, mouseY, onBtnX, currentY, onBtnWidth, 10);
		if (onHovered) hoveredElementId = "toggle_on";
		int onColor = trackingEnabled ? SELECTED_COLOR :
					onHovered ? HOVER_COLOR : NORMAL_COLOR;
		graphics.fill(onBtnX, currentY, onBtnX + onBtnWidth, currentY + 10, onColor);
		graphics.drawString(font, onText, onBtnX + 3, currentY + 1, TEXT_COLOR);

		int offBtnX = onBtnX + onBtnWidth + 2;
		int offBtnWidth = font.width(offText) + 6;
		boolean offHovered = isInside(mouseX, mouseY, offBtnX, currentY, offBtnWidth, 10);
		if (offHovered) hoveredElementId = "toggle_off";
		int offColor = !trackingEnabled ? 0xFFAA4444 :
					offHovered ? HOVER_COLOR : NORMAL_COLOR;
		graphics.fill(offBtnX, currentY, offBtnX + offBtnWidth, currentY + 10, offColor);
		graphics.drawString(font, offText, offBtnX + 3, currentY + 1, TEXT_COLOR);

		currentY += ROW_HEIGHT;

		// Если трекинг выключен — рендерим тултип и выходим
		if (!trackingEnabled) {
			renderTooltip(graphics, font, hoveredElementId, mouseX, mouseY);
			return;
		}

		// --- Период ---
		graphics.drawString(font, Component.translatable("itemflowmonitor.panel.period"), contentX, currentY, 0xFFAAAAAA);
		currentY += 11;

		int periodBtnX = contentX;
		for (TrackingPeriod period : TrackingPeriod.values()) {
			Component periodLabel = period.getComponent();
			int btnWidth = font.width(periodLabel) + 6;
			boolean btnHovered = isInside(mouseX, mouseY, periodBtnX, currentY, btnWidth, 10);
			if (btnHovered) hoveredElementId = "period_" + period.name();
			int color = (period == selectedPeriod) ? SELECTED_COLOR :
						btnHovered ? HOVER_COLOR : NORMAL_COLOR;

			graphics.fill(periodBtnX, currentY, periodBtnX + btnWidth, currentY + 10, color);
			graphics.drawString(font, periodLabel, periodBtnX + 3, currentY + 1, TEXT_COLOR);
			periodBtnX += btnWidth + 2;
		}
		currentY += ROW_HEIGHT;

		// --- Расчёт rate ---
		graphics.drawString(font, Component.translatable("itemflowmonitor.panel.rate"), contentX, currentY, 0xFFAAAAAA);
		currentY += 11;

		int rateModeBtnX = contentX;
		for (RateMode rm : RATE_MODE_DISPLAY_ORDER) {
			Component rmLabel = rm.getComponent();
			int btnWidth = font.width(rmLabel) + 6;
			boolean btnHovered = isInside(mouseX, mouseY, rateModeBtnX, currentY, btnWidth, 10);
			if (btnHovered) hoveredElementId = "rate_" + rm.name();
			int color = (rm == selectedRateMode) ? SELECTED_COLOR :
						btnHovered ? HOVER_COLOR : NORMAL_COLOR;

			graphics.fill(rateModeBtnX, currentY, rateModeBtnX + btnWidth, currentY + 10, color);
			graphics.drawString(font, rmLabel, rateModeBtnX + 3, currentY + 1, TEXT_COLOR);
			rateModeBtnX += btnWidth + 2;
		}
		currentY += ROW_HEIGHT;

		// --- Режим ---
		graphics.drawString(font, Component.translatable("itemflowmonitor.panel.mode"), contentX, currentY, 0xFFAAAAAA);
		currentY += 11;

		int modeBtnX = contentX;
		for (TrackingMode mode : TrackingMode.values()) {
			Component modeLabel = mode.getComponent();
			int btnWidth = font.width(modeLabel) + 6;
			boolean btnHovered = isInside(mouseX, mouseY, modeBtnX, currentY, btnWidth, 10);
			if (btnHovered) hoveredElementId = "mode_" + mode.name();
			int color = (mode == selectedMode) ? SELECTED_COLOR :
						btnHovered ? HOVER_COLOR : NORMAL_COLOR;

			graphics.fill(modeBtnX, currentY, modeBtnX + btnWidth, currentY + 10, color);
			graphics.drawString(font, modeLabel, modeBtnX + 3, currentY + 1, TEXT_COLOR);
			modeBtnX += btnWidth + 2;
		}
		currentY += ROW_HEIGHT;

		// --- Список предметов (только в режиме MANUAL) ---
		if (selectedMode == TrackingMode.MANUAL) {
			graphics.drawString(font, Component.translatable("itemflowmonitor.panel.select_item"), contentX, currentY, 0xFFAAAAAA);
			currentY += 11;

			if (containerItems.isEmpty()) {
				graphics.drawString(font, Component.translatable("itemflowmonitor.panel.empty"), contentX, currentY, 0xFF888888);
			} else {
				int visibleCount = Math.min(containerItems.size(), MAX_VISIBLE_ITEMS);
				int rowWidth = this.width - PADDING * 2;

				for (int i = 0; i < visibleCount; i++) {
					Item item = containerItems.get(i);
					ItemStack displayStack = new ItemStack(item);

					boolean isHovered = isInside(mouseX, mouseY, contentX, currentY, rowWidth, ITEM_ROW_HEIGHT);
					boolean isSelected = (item == selectedItem);

					if (isSelected) {
						graphics.fill(contentX, currentY, contentX + rowWidth, currentY + ITEM_ROW_HEIGHT, ITEM_SELECTED_COLOR);
					} else if (isHovered) {
						graphics.fill(contentX, currentY, contentX + rowWidth, currentY + ITEM_ROW_HEIGHT, ITEM_HOVER_COLOR);
					}

					// Иконка предмета 16x16
					graphics.renderItem(displayStack, contentX + 1, currentY + 1);

					// Название предмета (обрезаем если не влезает)
					String fullItemName = displayStack.getHoverName().getString();
					int maxNameWidth = rowWidth - 20;
					String itemName = fullItemName;
					boolean nameTruncated = font.width(fullItemName) > maxNameWidth;
					if (nameTruncated) {
						itemName = font.plainSubstrByWidth(fullItemName, maxNameWidth - font.width("...")) + "...";
					}
					graphics.drawString(font, itemName, contentX + 19, currentY + 5, TEXT_COLOR);

					// Тултип только для предметов с обрезанным именем
					if (isHovered && nameTruncated) {
						hoveredElementId = "item_" + fullItemName;
					}

					currentY += ITEM_ROW_HEIGHT;
				}

				// Если предметов больше, чем видимых — показываем счётчик
				if (containerItems.size() > MAX_VISIBLE_ITEMS) {
					int remaining = containerItems.size() - MAX_VISIBLE_ITEMS;
					graphics.drawString(font, Component.translatable("itemflowmonitor.panel.more", remaining),
							contentX, currentY, 0xFF888888);
				}
			}
		}

		renderTooltip(graphics, font, hoveredElementId, mouseX, mouseY);
	}

	/** Возвращает строки тултипа по ID элемента */
	private List<Component> getTooltipForElement(String elementId) {
		return switch (elementId) {
			case "toggle_on" -> List.of(Component.translatable("itemflowmonitor.tooltip.toggle_on"));
			case "toggle_off" -> List.of(Component.translatable("itemflowmonitor.tooltip.toggle_off"));
			case "period_MINUTE" -> List.of(Component.translatable("itemflowmonitor.tooltip.period_minute"));
			case "period_HOUR" -> List.of(Component.translatable("itemflowmonitor.tooltip.period_hour"));
			case "rate_AVERAGE" -> List.of(
				Component.translatable("itemflowmonitor.tooltip.rate_average.1"),
				Component.translatable("itemflowmonitor.tooltip.rate_average.2"),
				Component.translatable("itemflowmonitor.tooltip.rate_average.3")
			);
			case "rate_ACTUAL" -> List.of(
				Component.translatable("itemflowmonitor.tooltip.rate_actual.1"),
				Component.translatable("itemflowmonitor.tooltip.rate_actual.2"),
				Component.translatable("itemflowmonitor.tooltip.rate_actual.3")
			);
			case "rate_PREDICTED" -> List.of(
				Component.translatable("itemflowmonitor.tooltip.rate_predicted.1"),
				Component.translatable("itemflowmonitor.tooltip.rate_predicted.2"),
				Component.translatable("itemflowmonitor.tooltip.rate_predicted.3")
			);
			case "mode_ALL" -> List.of(Component.translatable("itemflowmonitor.tooltip.mode_all"));
			case "mode_AUTO" -> List.of(Component.translatable("itemflowmonitor.tooltip.mode_auto"));
			case "mode_MANUAL" -> List.of(
				Component.translatable("itemflowmonitor.tooltip.mode_manual.1"),
				Component.translatable("itemflowmonitor.tooltip.mode_manual.2")
			);
			case "reset" -> List.of(Component.translatable("itemflowmonitor.tooltip.reset"));
			default -> {
				// Тултип для предметов с обрезанным названием: "item_<fullName>"
				if (elementId.startsWith("item_")) {
					yield List.of(Component.literal(elementId.substring(5)));
				}
				yield null;
			}
		};
	}

	/** Рендерит тултип с задержкой */
	private void renderTooltip(GuiGraphics graphics, net.minecraft.client.gui.Font font,
							   String hoveredElementId, int mouseX, int mouseY) {
		if (hoveredElementId == null) {
			lastHoveredElement = null;
			return;
		}

		if (!hoveredElementId.equals(lastHoveredElement)) {
			// Наведение на новый элемент — сбрасываем таймер
			lastHoveredElement = hoveredElementId;
			hoverStartTimeMs = System.currentTimeMillis();
			return;
		}

		// Элемент тот же — проверяем задержку
		if (System.currentTimeMillis() - hoverStartTimeMs >= TOOLTIP_DELAY_MS) {
			List<Component> tooltip = getTooltipForElement(hoveredElementId);
			if (tooltip != null) {
				graphics.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
			}
		}
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean hovered) {
		double mouseX = event.x();
		double mouseY = event.y();
		var font = Minecraft.getInstance().font;

		int contentX = getX() + PADDING;

		// Вычисляем Y-позиции секций (должны совпадать с renderWidget)
		int currentY = getY() + PADDING;
		int titleY = currentY;

		// Клик по кнопке Reset (на строке заголовка, справа)
		if (trackingEnabled && mouseY >= titleY && mouseY < titleY + 10) {
			Component resetText = Component.translatable("itemflowmonitor.panel.reset");
			int resetBtnWidth = font.width(resetText) + 6;
			int resetBtnX = getX() + width - PADDING - resetBtnWidth;
			if (mouseX >= resetBtnX && mouseX < resetBtnX + resetBtnWidth) {
				if (onReset != null) onReset.run();
				return;
			}
		}

		currentY += ROW_HEIGHT; // заголовок
		int toggleY = currentY;
		currentY += ROW_HEIGHT; // toggle

		// --- Клик по toggle ON/OFF ---
		if (mouseY >= toggleY && mouseY < toggleY + 10) {
			Component trackingLabel = Component.translatable("itemflowmonitor.panel.tracking");
			Component onText = Component.translatable("itemflowmonitor.panel.on");
			Component offText = Component.translatable("itemflowmonitor.panel.off");

			int toggleLabelWidth = font.width(trackingLabel) + 4;
			int onBtnX = contentX + toggleLabelWidth;
			int onBtnWidth = font.width(onText) + 6;
			int offBtnX = onBtnX + onBtnWidth + 2;
			int offBtnWidth = font.width(offText) + 6;

			if (mouseX >= onBtnX && mouseX < onBtnX + onBtnWidth && !trackingEnabled) {
				trackingEnabled = true;
				notifySettingsChanged();
				return;
			}
			if (mouseX >= offBtnX && mouseX < offBtnX + offBtnWidth && trackingEnabled) {
				trackingEnabled = false;
				notifySettingsChanged();
				return;
			}
		}

		// Если трекинг выключен — остальные кнопки не обрабатываем
		if (!trackingEnabled) return;

		currentY += 11; // "Period:"
		int periodY = currentY;
		currentY += ROW_HEIGHT; // кнопки периода
		currentY += 11; // "Rate:"
		int rateModeY = currentY;
		currentY += ROW_HEIGHT; // кнопки rate mode
		currentY += 11; // "Mode:"
		int modeY = currentY;
		currentY += ROW_HEIGHT; // кнопки режима
		currentY += 11; // "Select item:"
		int itemListY = currentY;

		// Клик по кнопкам периода
		if (mouseY >= periodY && mouseY < periodY + 10) {
			int btnX = contentX;
			for (TrackingPeriod period : TrackingPeriod.values()) {
				int btnWidth = font.width(period.getComponent()) + 6;
				if (mouseX >= btnX && mouseX < btnX + btnWidth) {
					selectedPeriod = period;
					notifySettingsChanged();
					return;
				}
				btnX += btnWidth + 2;
			}
		}

		// Клик по кнопкам rate mode
		if (mouseY >= rateModeY && mouseY < rateModeY + 10) {
			int btnX = contentX;
			for (RateMode rm : RATE_MODE_DISPLAY_ORDER) {
				int btnWidth = font.width(rm.getComponent()) + 6;
				if (mouseX >= btnX && mouseX < btnX + btnWidth) {
					selectedRateMode = rm;
					notifySettingsChanged();
					return;
				}
				btnX += btnWidth + 2;
			}
		}

		// Клик по кнопкам режима
		if (mouseY >= modeY && mouseY < modeY + 10) {
			int btnX = contentX;
			for (TrackingMode mode : TrackingMode.values()) {
				int btnWidth = font.width(mode.getComponent()) + 6;
				if (mouseX >= btnX && mouseX < btnX + btnWidth) {
					selectedMode = mode;
					notifySettingsChanged();
					return;
				}
				btnX += btnWidth + 2;
			}
		}

		// Клик по списку предметов (только в режиме MANUAL)
		if (selectedMode == TrackingMode.MANUAL && !containerItems.isEmpty()) {
			int visibleCount = Math.min(containerItems.size(), MAX_VISIBLE_ITEMS);
			if (mouseY >= itemListY && mouseY < itemListY + visibleCount * ITEM_ROW_HEIGHT) {
				int clickedIndex = (int) ((mouseY - itemListY) / ITEM_ROW_HEIGHT);
				if (clickedIndex >= 0 && clickedIndex < visibleCount) {
					Item clicked = containerItems.get(clickedIndex);
					// Повторный клик снимает выбор
					selectedItem = (clicked == selectedItem) ? null : clicked;
					notifySettingsChanged();
					return;
				}
			}
		}
	}

	/** Проверка, находится ли точка внутри прямоугольника */
	private boolean isInside(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
