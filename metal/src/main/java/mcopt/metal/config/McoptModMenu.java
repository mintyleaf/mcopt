package mcopt.metal.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Optional entrypoint; Fabric never loads this class without Mod Menu. */
public final class McoptModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return McoptConfigScreen::new;
	}
}
