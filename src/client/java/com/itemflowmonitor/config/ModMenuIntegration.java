package com.itemflowmonitor.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Интеграция с Mod Menu — открывает экран настроек IFM по кнопке в списке модов.
 */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return IFMConfigScreen::new;
	}
}
