package com.itemflowmonitor;

import net.minecraft.network.chat.Component;

/**
 * Единицы измерения потока предметов.
 */
public enum TrackingPeriod {
	MINUTE("/min", "itemflowmonitor.period.minute", 1200),
	HOUR("/hour", "itemflowmonitor.period.hour", 72000);

	private final String label;
	private final String translationKey;
	private final int ticks;

	TrackingPeriod(String label, String translationKey, int ticks) {
		this.label = label;
		this.translationKey = translationKey;
		this.ticks = ticks;
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

	/** Количество тиков в этом периоде */
	public int getTicks() {
		return ticks;
	}
}
