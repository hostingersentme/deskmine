package dev.deskmine.listener;

import dev.deskmine.frames.BookViewer;
import dev.deskmine.frames.FrameService;
import dev.deskmine.fs.FileCategory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * File frames are a read-only projection: looking yes, rotating/stealing no.
 * Right-clicking a text file's frame opens its real contents as a book
 * (request #6); other files show their details in chat.
 */
public final class FrameListener implements Listener {

    private final FrameService frames;
    private final BookViewer viewer;

    public FrameListener(FrameService frames, BookViewer viewer) {
        this.frames = frames;
        this.viewer = viewer;
    }

    @EventHandler
    public void onHangingBreak(HangingBreakEvent e) {
        if (frames.isFileFrame(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (frames.isFileFrame(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        if (!frames.isFileFrame(e.getRightClicked())) return;
        e.setCancelled(true); // no rotating, no swapping

        String path = frames.pathOf(e.getRightClicked());
        if (path != null) {
            Path file = Path.of(path);
            String name = file.getFileName() == null ? path : file.getFileName().toString();
            if (FileCategory.fromName(name) == FileCategory.TEXT
                    && Files.isRegularFile(file) && Files.isReadable(file)) {
                viewer.open(e.getPlayer(), file);
                return;
            }
        }
        String label = frames.labelOf(e.getRightClicked());
        if (label != null) {
            e.getPlayer().sendMessage(Component.text(label, NamedTextColor.GRAY));
        }
    }
}
