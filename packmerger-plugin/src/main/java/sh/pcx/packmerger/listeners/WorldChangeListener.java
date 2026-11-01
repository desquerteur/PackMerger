package sh.pcx.packmerger.listeners;

import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import sh.pcx.packmerger.PackMergerBootstrap;
import sh.pcx.packmerger.datapack.DatapackPackManager;
import sh.pcx.packmerger.datapack.DatapackPackManager.ScanTrigger;

/**
 * Re-scans the datapacks when worlds are loaded or unloaded (Multiverse and friends)
 * and after the server reloaded its data ({@code /minecraft:reload},
 * {@code /datapack enable|disable}). Handlers only start the async scan and return;
 * the scan coalesces bursts of events into one extra run.
 */
public class WorldChangeListener implements Listener {

    private final PackMergerBootstrap plugin;

    public WorldChangeListener(PackMergerBootstrap plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        rescan(ScanTrigger.WORLD_CHANGE);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        if (event.isCancelled()) return;
        rescan(ScanTrigger.WORLD_CHANGE);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onResourcesReloaded(ServerResourcesReloadedEvent event) {
        rescan(ScanTrigger.RESOURCES_RELOADED);
    }

    private void rescan(ScanTrigger trigger) {
        DatapackPackManager manager = plugin.getDatapackPackManager();
        if (manager == null || !plugin.getConfigManager().isDatapacksEnabled()) return;
        manager.scan(trigger, false);
        // Watched folders may have appeared or gone with the world.
        manager.restartWatchersLater();
    }
}
