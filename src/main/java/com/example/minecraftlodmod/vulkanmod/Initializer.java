package com.example.minecraftlodmod.vulkanmod;

import com.example.minecraftlodmod.vulkanmod.nucleo.VKCUnsafeUtils;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.impl.renderer.RendererAccessImpl;
import net.neoforged.fml.ModList;
import net.neoforged.fml.i18n.MavenVersionTranslator;
import net.neoforged.fml.loading.FMLPaths;
import com.example.minecraftlodmod.vulkanmod.config.Config;
import com.example.minecraftlodmod.vulkanmod.config.Platform;
import com.example.minecraftlodmod.vulkanmod.config.video.VideoModeManager;
import com.example.minecraftlodmod.vulkanmod.render.chunk.build.frapi.VulkanModRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.nio.file.Path;

public class Initializer {
	public static final Logger LOGGER = LogManager.getLogger("VulkanMod");

	private static String VERSION;
	public static Config CONFIG;

	static {

        Platform.init();
		VideoModeManager.init();

		var configPath = FMLPaths.CONFIGDIR.get()
				.resolve("vulkanmod_settings.json");

		CONFIG = loadConfig(configPath);

		if(RendererAccess.INSTANCE.getRenderer() != null && RendererAccess.INSTANCE instanceof RendererAccessImpl rendererAccess) {
            try {
                VKCUnsafeUtils.setFieldValue(rendererAccess, "activeRenderer", null);
            } catch (Exception e) {
                try {
                    Field field = RendererAccessImpl.class.getDeclaredField("activeRenderer");
                    field.setAccessible(true);
                    field.set(rendererAccess,null);
                } catch (NoSuchFieldException | IllegalAccessException ex) {
                    throw new RuntimeException(ex);
                }
            }

        }
		RendererAccess.INSTANCE.registerRenderer(VulkanModRenderer.INSTANCE);
	}

	@SuppressWarnings("OptionalGetWithoutIsPresent")
    public void onInitializeClient() {

        try {
            // Minecraft LOD: integrado, la versión es la del fork del que sale el código.
            VERSION = "0.5.5-dev+3.1 (Minecraft LOD " + MavenVersionTranslator.artifactVersionToString(
                    ModList.get().getModContainerById("minecraftlodmod").get().getModInfo().getVersion()) + ")";
        } catch (Exception e) {
            VERSION = "0.5.5-dev+3.1";

			LOGGER.warn("Failed to get the version: {}",e.getMessage());
        }

        LOGGER.info("== VulkanMod ==");
	}

	private static Config loadConfig(Path path) {
		Config config = Config.load(path);

		if(config == null) {
			config = new Config();
			config.write();
		}

		return config;
	}

	public static String getVersion() {
		return VERSION;
	}
}
