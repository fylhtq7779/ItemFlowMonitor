package com.itemflowmonitor.config;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Экран настроек мода — скроллящийся список CycleButton для включения/выключения контейнеров.
 */
public class IFMConfigScreen extends Screen {
	private final Screen parent;
	private final IFMConfig config;

	/** Локальное состояние — применяется к конфигу только при нажатии Done */
	private final Map<String, Boolean> localState = new LinkedHashMap<>();

	/** Ключи перевода для названий контейнеров */
	private static final Map<String, String> CONTAINER_TRANSLATION_KEYS = Map.of(
		IFMConfig.CHEST, "itemflowmonitor.container.chest",
		IFMConfig.BARREL, "itemflowmonitor.container.barrel",
		IFMConfig.SHULKER_BOX, "itemflowmonitor.container.shulker_box",
		IFMConfig.HOPPER, "itemflowmonitor.container.hopper",
		IFMConfig.DISPENSER, "itemflowmonitor.container.dispenser",
		IFMConfig.ENDER_CHEST, "itemflowmonitor.container.ender_chest",
		IFMConfig.FURNACE, "itemflowmonitor.container.furnace",
		IFMConfig.SMOKER, "itemflowmonitor.container.smoker",
		IFMConfig.BLAST_FURNACE, "itemflowmonitor.container.blast_furnace"
	);

	/** Порядок отображения контейнеров */
	private static final List<String> CONTAINER_ORDER = List.of(
		IFMConfig.CHEST,
		IFMConfig.BARREL,
		IFMConfig.SHULKER_BOX,
		IFMConfig.HOPPER,
		IFMConfig.DISPENSER,
		IFMConfig.ENDER_CHEST,
		IFMConfig.FURNACE,
		IFMConfig.SMOKER,
		IFMConfig.BLAST_FURNACE
	);

	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;
	private static final int ENTRY_HEIGHT = 25;
	private static final int HEADER_HEIGHT = 40;
	private static final int FOOTER_HEIGHT = 33;

	public IFMConfigScreen(Screen parent) {
		super(Component.translatable("itemflowmonitor.config.title"));
		this.parent = parent;
		this.config = IFMConfig.getInstance();
	}

	@Override
	protected void init() {
		localState.clear();

		// Цветные лейблы: зелёный для ВКЛ, тёмно-серый для ВЫКЛ
		Component onText = Component.translatable("options.on").withStyle(ChatFormatting.GREEN);
		Component offText = Component.translatable("options.off").withStyle(ChatFormatting.DARK_GRAY);

		// Скроллящийся список переключателей
		ToggleList list = new ToggleList(this.minecraft);

		for (String key : CONTAINER_ORDER) {
			String translationKey = CONTAINER_TRANSLATION_KEYS.getOrDefault(key, key);
			boolean enabled = config.isContainerEnabled(key);
			localState.put(key, enabled);

			CycleButton<Boolean> cycleButton = CycleButton.booleanBuilder(onText, offText, enabled)
				.create(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT,
					Component.translatable(translationKey),
					(button, value) -> {
						localState.put(key, value);
						// Убираем фокус (белую обводку) после клика
						Minecraft.getInstance().execute(() -> button.setFocused(false));
					});

			list.addToggleEntry(new ToggleList.ToggleEntry(cycleButton));
		}

		// Позиционируем список между заголовком и кнопкой Done
		list.updateSizeAndPosition(this.width, this.height - HEADER_HEIGHT - FOOTER_HEIGHT, HEADER_HEIGHT);
		this.addRenderableWidget(list);

		// Кнопка Done внизу экрана
		this.addRenderableWidget(Button.builder(
			Component.translatable("itemflowmonitor.config.done"),
			button -> saveAndClose()
		)
		.pos(this.width / 2 - 50, this.height - FOOTER_HEIGHT + 5)
		.size(100, BUTTON_HEIGHT)
		.build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);

		// Заголовок
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);

		// Подпись под заголовком
		graphics.drawCenteredString(this.font,
			Component.translatable("itemflowmonitor.config.subtitle"),
			this.width / 2, 24, 0xFFAAAAAA);
	}

	/** Сохранить настройки и вернуться к предыдущему экрану */
	private void saveAndClose() {
		for (var entry : localState.entrySet()) {
			config.setContainerEnabled(entry.getKey(), entry.getValue());
		}
		config.save();
		this.minecraft.setScreen(parent);
	}

	@Override
	public void onClose() {
		// Esc — отмена без сохранения
		this.minecraft.setScreen(parent);
	}

	/** Скроллящийся список переключателей контейнеров */
	private static class ToggleList extends ContainerObjectSelectionList<ToggleList.ToggleEntry> {

		public ToggleList(Minecraft mc) {
			super(mc, 0, 0, 0, ENTRY_HEIGHT);
		}

		public void addToggleEntry(ToggleEntry entry) {
			this.addEntry(entry);
		}

		@Override
		public int getRowWidth() {
			return BUTTON_WIDTH + 4;
		}

		/** Запись списка, содержащая CycleButton */
		private static class ToggleEntry extends ContainerObjectSelectionList.Entry<ToggleEntry> {
			private final CycleButton<Boolean> button;

			public ToggleEntry(CycleButton<Boolean> button) {
				this.button = button;
			}

			@Override
			public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float delta) {
				// Центрируем кнопку по ширине записи
				button.setX(getContentX() + (getContentWidth() - BUTTON_WIDTH) / 2);
				button.setY(getContentY());
				button.render(graphics, mouseX, mouseY, delta);
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return List.of(button);
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return List.of(button);
			}

			/** Блокируем прокрутку колёсиком для предотвращения переключения значений */
			@Override
			public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
				return false;
			}
		}
	}
}
