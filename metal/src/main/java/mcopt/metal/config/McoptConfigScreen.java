package mcopt.metal.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Dependency-free widgets; only the optional entrypoint depends on Mod Menu. */
public final class McoptConfigScreen extends Screen {
	private final Screen parent;
	private McoptSettings settings;
	private String error = "";
	private int page, rows, pages;
	private record Label(String text, int x, int y) { }
	private final List<Label> labels = new ArrayList<>();

	public McoptConfigScreen(Screen parent) {
		super(Component.literal("mcopt settings"));
		this.parent = parent;
		try { settings = new McoptSettings(FabricLoader.getInstance().getConfigDir().resolve("mcopt.properties")); }
		catch (IOException | IllegalArgumentException e) { error = "Cannot read settings: " + e.getMessage(); }
	}

	@Override protected void init() {
		labels.clear();
		rows = Math.clamp((height - 112) / 42, 1, 4);
		int size = rows * 2;
		pages = (McoptSettings.OPTIONS.length + size - 1) / size;
		page = Math.min(page, pages - 1);
		int gap = 10, w = Math.min(220, (width - 30) / 2), left = (width - 2 * w - gap) / 2;
		if (settings != null) for (int slot = 0; slot < size; slot++) {
			int i = page * size + slot;
			if (i >= McoptSettings.OPTIONS.length) break;
			var o = McoptSettings.OPTIONS[i];
			int x = left + slot % 2 * (w + gap), y = 52 + slot / 2 * 42;
			labels.add(new Label(o.label(), x, y));
			if (o.choices().length > 0) {
				addRenderableWidget(Button.builder(value(settings.values.get(o.key())), b -> {
					String[] choices = o.choices(); int at = List.of(choices).indexOf(settings.values.get(o.key()));
					String next = choices[(at + 1) % choices.length];
					settings.values.put(o.key(), next); b.setMessage(value(next));
				}).bounds(x, y + 12, w, 20).tooltip(Tooltip.create(Component.literal(o.hint()))).build());
			} else {
				EditBox box = new EditBox(font, x, y + 12, w, 20, Component.literal(o.label()));
				box.setMaxLength(20); box.setValue(settings.values.get(o.key()));
				box.setTooltip(Tooltip.create(Component.literal(o.hint())));
				box.setResponder(v -> settings.values.put(o.key(), v));
				addRenderableWidget(box);
			}
		}
		addRenderableWidget(Button.builder(Component.literal("<"), b -> { page = (page + pages - 1) % pages; rebuildWidgets(); }).bounds(width / 2 - 80, height - 55, 30, 20).build());
		addRenderableWidget(Button.builder(Component.literal(">"), b -> { page = (page + 1) % pages; rebuildWidgets(); }).bounds(width / 2 + 50, height - 55, 30, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(width / 2 - 155, height - 28, 150, 20).build());
		Button save = addRenderableWidget(Button.builder(Component.literal("Save and close"), b -> save()).bounds(width / 2 + 5, height - 28, 150, 20).build());
		save.active = settings != null;
	}

	private static Component value(String value) { return Component.literal(value.equals("true") ? "ON" : value.equals("false") ? "OFF" : value); }
	private void save() {
		try { settings.save(); minecraft.gui.setScreen(parent); }
		catch (IOException | IllegalArgumentException e) { error = e.getMessage(); }
	}
	@Override public void onClose() { minecraft.gui.setScreen(parent); }
	@Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
		g.centeredText(font, "Restart Minecraft to apply. JVM flags override saved settings.", width / 2, 29, 0xFFBBBBBB);
		for (Label label : labels) g.text(font, label.text, label.x, label.y, 0xFFFFFFFF);
		g.centeredText(font, (page + 1) + " / " + pages, width / 2, height - 49, 0xFFFFFFFF);
		if (!error.isEmpty()) g.centeredText(font, error, width / 2, height - 69, 0xFFFF7777);
	}
}
