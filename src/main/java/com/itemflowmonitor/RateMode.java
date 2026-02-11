package com.itemflowmonitor;

import net.minecraft.network.chat.Component;

/**
 * Режим расчёта rate: фактический или прогнозный.
 */
public enum RateMode {
	AVERAGE("Average", "itemflowmonitor.rate_mode.average"),
	ACTUAL("Actual", "itemflowmonitor.rate_mode.actual"),
	PREDICTED("Predict", "itemflowmonitor.rate_mode.predicted");

	private final String label;
	private final String translationKey;

	RateMode(String label, String translationKey) {
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
