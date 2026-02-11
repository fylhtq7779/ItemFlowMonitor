package com.itemflowmonitor;

import net.minecraft.network.chat.Component;

/**
 * Режим выбора отслеживаемого предмета.
 */
public enum TrackingMode {
	ALL("All", "itemflowmonitor.mode.all"),
	AUTO("Auto", "itemflowmonitor.mode.auto"),
	MANUAL("Manual", "itemflowmonitor.mode.manual");

	private final String label;
	private final String translationKey;

	TrackingMode(String label, String translationKey) {
		this.label = label;
		this.translationKey = translationKey;
	}

	/** Фоллбэк-лейбл для серверного кода (debug-команды, логи) */
	public String getLabel() {
		return label;
	}

	/** Ключ перевода для UI */
	public String getTranslationKey() {
		return translationKey;
	}

	/** Компонент с переведённым лейблом */
	public Component getComponent() {
		return Component.translatable(translationKey);
	}
}
