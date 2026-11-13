package sh.pcx.packmerger.distribution;

import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import sh.pcx.packmerger.PackMergerBootstrap;
import sh.pcx.packmerger.PluginLogger;
import sh.pcx.packmerger.api.events.PackSentToPlayerEvent;
import sh.pcx.packmerger.config.ConfigManager;
import sh.pcx.packmerger.upload.PlayerPackUrl;
import sh.pcx.packmerger.upload.SelfHostProvider;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends the published pack to players and keeps delivery reliable.
 *
 * <ul>
 *   <li>The pack is sent on <b>every</b> join. The client drops server packs when it
 *       disconnects, and its own SHA-1 cache already avoids re-downloading an unchanged
 *       pack, so there is nothing to gain from skipping the send.</li>
 *   <li>Players who join before any pack is published are remembered and sent the pack
 *       as soon as the first one goes live ({@link #onPublished}).</li>
 *   <li>The pack carries a stable per-server UUID ({@link PackIdentity}); a resend
 *       replaces PackMerger's own pack and never touches packs from a proxy or other
 *       plugins.</li>
 *   <li>Status reports for PackMerger's pack drive a bounded self-heal resend on
 *       {@code FAILED_DOWNLOAD} / {@code DISCARDED}, and optional kick enforcement for
 *       required packs ({@link #handleStatus}). Failed downloads stop kicking while
 *       hosting looks broken ({@link DeliveryTracker#noteHosting}).</li>
 *   <li>Each player gets the URL {@link PlayerPackUrl} picks: built from the address they
 *       joined with in self-host per-player mode, else the published URL. A URL that only
 *       works on this machine is not sent to remote players at all.</li>
 * </ul>
 *
 * <p>Every Bukkit player call is made on that player's entity scheduler, so methods
 * here can be called from any thread (Folia-safe).</p>
 *
 * @see sh.pcx.packmerger.listeners.PlayerJoinListener
 */
public class PackDistributor {

    private final PackMergerBootstrap plugin;
    private final PluginLogger logger;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final DeliveryTracker tracker;
    private final BedrockPlayers bedrockPlayers = new BedrockPlayers();

    /** The URL each online player was last sent, for diagnostics. */
    private final Map<UUID, String> sentUrls = new ConcurrentHashMap<>();

    /** The unusable URL last warned about, so the warning is logged once and not on every join. */
    private final AtomicReference<String> unusableWarned = new AtomicReference<>();

    public PackDistributor(PackMergerBootstrap plugin) {
        this.plugin = plugin;
        this.logger = plugin.getPluginLogger();
        this.tracker = new DeliveryTracker(plugin.getConfigManager().getRetryMaxAttempts());
    }

    /** Re-reads config values that the tracker caches (called after {@code /pm reload}). */
    public void reloadSettings() {
        tracker.setMaxAttempts(plugin.getConfigManager().getRetryMaxAttempts());
    }

    /** @return the per-session delivery state (awaiting players, resend counters) */
    public DeliveryTracker getTracker() {
        return tracker;
    }

    /** Called when a player joins: a new client session starts with no pack. */
    public void onJoin(Player player) {
        tracker.onJoin(player.getUniqueId());
    }

    /** Called when a player quits. */
    public void onQuit(Player player) {
        tracker.onQuit(player.getUniqueId());
        sentUrls.remove(player.getUniqueId());
    }

    /**
     * Sends the published pack to a player.
     *
     * <p>If nothing is published yet, the player is remembered and receives the pack
     * when the first one goes live. Floodgate (Bedrock) players are skipped when
     * {@code distribution.skip-bedrock-players} is on.</p>
     *
     * @param player the player
     * @param forced {@code true} for sends an admin or a new-pack resend triggered
     *               (reported as {@link PackSentToPlayerEvent#bypassedCache()})
     */
    public void sendPack(Player player, boolean forced) {
        ConfigManager config = plugin.getConfigManager();
        if (!config.isDistributionEnabled()) return;

        if (config.isSkipBedrockPlayers() && bedrockPlayers.isBedrock(player)) {
            logger.debug("Skipping Java resource pack for Bedrock player " + player.getName());
            return;
        }

        PublishedPack pack = plugin.getPublishedPack();
        if (pack == null) {
            tracker.markAwaiting(player.getUniqueId());
            logger.debug("No pack published yet; " + player.getName()
                    + " will be sent the pack as soon as the first one is published");
            return;
        }

        player.getScheduler().run(plugin.getLoader(), scheduled -> deliver(player, config, pack, forced), null);
    }

    /** Runs on the player's region thread. */
    private void deliver(Player player, ConfigManager config, PublishedPack pack, boolean forced) {
        try {
            PlayerPackUrl.Choice choice = urlFor(player, pack);
            if (choice.mode() == PlayerPackUrl.Mode.UNUSABLE) {
                warnUnusable(player, choice.url());
                return;
            }
            String url = choice.url();
            ResourcePackInfo packInfo = ResourcePackInfo.resourcePackInfo()
                    .uri(URI.create(url))
                    .hash(pack.sha1Hex())
                    .id(pack.packId())
                    .build();

            ResourcePackRequest.Builder request = ResourcePackRequest.resourcePackRequest()
                    .packs(packInfo)
                    .replace(false)
                    .required(config.isRequired());

            String promptMsg = config.getPromptMessage();
            if (promptMsg != null && !promptMsg.isEmpty()) {
                request.prompt(miniMessage.deserialize(promptMsg));
            }

            if (!config.isUseAddResourcePack()) {
                // Replace mode: drop PackMerger's previous pack explicitly. Only our own
                // UUID is removed, so packs from a proxy or other plugins stay applied.
                player.removeResourcePacks(pack.packId());
            }
            player.sendResourcePacks(request.build());
            tracker.onSent(player.getUniqueId());
            sentUrls.put(player.getUniqueId(), url);

            logger.debug("Sent resource pack to " + player.getName() + " (URL: " + url
                    + (choice.mode() == PlayerPackUrl.Mode.PER_PLAYER ? ", per-player" : "") + ")");
            Bukkit.getPluginManager().callEvent(new PackSentToPlayerEvent(player, url, pack.sha1Hex(), forced));
        } catch (Exception e) {
            logger.warning("Failed to send resource pack to " + player.getName() + ": " + e.getMessage());
        }
    }

    /** Picks the URL for this player (per-player self-host URL, else the published one). */
    private PlayerPackUrl.Choice urlFor(Player player, PublishedPack pack) {
        SelfHostProvider selfHost = plugin.getSelfHostProvider();
        int port = selfHost == null ? -1 : selfHost.perPlayerPort(pack.url());
        InetSocketAddress virtualHost = player.getVirtualHost();
        return PlayerPackUrl.choose(pack.url(), port, virtualHost == null ? null : virtualHost.getHostString(),
                isOnLoopback(player));
    }

    private static boolean isOnLoopback(Player player) {
        InetSocketAddress address = player.getAddress();
        return address != null && address.getAddress() != null && address.getAddress().isLoopbackAddress();
    }

    /** Logs (once per URL) why a player was not sent a URL that only works on this machine. */
    private void warnUnusable(Player player, String url) {
        String message = "Not sending the resource pack to " + player.getName() + ": its URL " + url
                + " points at this machine itself, which a player connecting from another computer cannot"
                + " reach. Set upload.self-host.public-url (full URL) or upload.self-host.public-host (host"
                + " name or IP players can reach) in config.yml, then /pm reload. Players are not kicked for this.";
        if (!url.equals(unusableWarned.getAndSet(url))) {
            logger.warning(message);
            plugin.alertAdmins("[PackMerger] " + message);
        } else {
            logger.debug(message);
        }
    }

    /**
     * Sends the pack a BungeeCord proxy asked this server to deliver (a verified OFFER,
     * see {@link ProxyLink}). It goes out under the proxy's pack id, so it replaces the
     * proxy's previous pack in place and never touches this server's own pack. Sent
     * regardless of {@code distribution.enabled}; Bedrock players are still skipped.
     */
    public void sendProxyOffer(Player player, sh.pcx.packmerger.common.PackMessage offer) {
        if (plugin.getConfigManager().isSkipBedrockPlayers() && bedrockPlayers.isBedrock(player)) {
            logger.debug("Skipping the proxy's Java resource pack for Bedrock player " + player.getName());
            return;
        }
        player.getScheduler().run(plugin.getLoader(), scheduled -> {
            try {
                ResourcePackInfo.Builder info = ResourcePackInfo.resourcePackInfo()
                        .uri(URI.create(offer.pack().url()))
                        .id(offer.packId());
                if (offer.pack().hasHash()) info.hash(offer.pack().sha1Hex());
                ResourcePackRequest.Builder request = ResourcePackRequest.resourcePackRequest()
                        .packs(info.build())
                        .replace(false)
                        .required(offer.required());
                if (!offer.prompt().isBlank()) request.prompt(Component.text(offer.prompt()));
                player.sendResourcePacks(request.build());
            } catch (Exception e) {
                logger.warning("Failed to send the proxy's resource pack to " + player.getName() + ": " + e.getMessage());
            }
        }, null);
    }

    /** Sends the published pack to every online player. */
    public void sendToAll(boolean forced) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sendPack(player, forced);
        }
    }

    /**
     * Called after a pack record went live.
     *
     * <p>Players who joined before any pack existed are always sent it. If the content
     * changed ({@code isNewPack}), everyone else is handled per {@code on-new-pack.action}.</p>
     *
     * @param isNewPack {@code true} if the SHA-1 differs from the previously published pack
     */
    public void onPublished(boolean isNewPack) {
        List<UUID> awaiting = tracker.drainAwaiting();
        Set<UUID> flushed = new HashSet<>();
        for (UUID id : awaiting) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                logger.info("Sending the newly published pack to " + player.getName()
                        + " (joined before a pack was available)");
                sendPack(player, true);
                flushed.add(id);
            }
        }
        if (isNewPack) {
            onNewPack(flushed);
        }
    }

    /**
     * Called after the live pack was withdrawn because nothing was left to merge: online
     * players drop it and are sent the next pack as soon as one is published, like players
     * who joined before any pack existed.
     *
     * @param previous the pack that was live until now
     */
    public void onUnpublished(PublishedPack previous) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            sentUrls.remove(id);
            tracker.markAwaiting(id);
            player.getScheduler().run(plugin.getLoader(), scheduled -> {
                if (player.isOnline()) player.removeResourcePacks(previous.packId());
            }, null);
        }
    }

    /**
     * Handles online players when the pack content changed, per {@code on-new-pack.action}.
     *
     * @param alreadySent players that were just sent the pack and must not get it twice
     */
    private void onNewPack(Set<UUID> alreadySent) {
        ConfigManager config = plugin.getConfigManager();
        String action = config.getOnNewPackAction();

        switch (action.toLowerCase()) {
            case "resend" -> {
                logger.info("New pack detected, resending to all online players");
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (!alreadySent.contains(player.getUniqueId())) sendPack(player, true);
                }
            }
            case "notify" -> {
                String message = config.getNotifyMessage();
                if (message != null && !message.isEmpty()) {
                    Component component = miniMessage.deserialize(message);
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (!alreadySent.contains(player.getUniqueId())
                                && player.hasPermission("packmerger.notify")) {
                            player.sendMessage(component);
                        }
                    }
                    logger.info("Notified online players about new resource pack");
                }
            }
            case "none" -> logger.debug("New pack detected, but on-new-pack action is 'none'");
            default -> logger.warning("Unknown on-new-pack action: " + action);
        }
    }

    /**
     * @return {@code true} if a status report with this pack id is about PackMerger's pack.
     *         A {@code null} id (should not happen on 1.20.3+) is treated as ours.
     */
    public boolean isOwnPack(UUID reportedId) {
        if (reportedId == null) return true;
        PublishedPack pack = plugin.getPublishedPack();
        if (pack != null && pack.packId().equals(reportedId)) return true;
        return PackIdentity.forServer(plugin.getConfigManager().getServerName()).equals(reportedId);
    }

    /**
     * Reacts to the client's status report: self-heal resends, diagnostics and optional
     * required-pack enforcement. Reports for other plugins' packs are ignored.
     */
    public void handleStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        if (!isOwnPack(event.getID())) {
            logger.debug(name + " resource pack status " + event.getStatus() + " for another pack ("
                    + event.getID() + "); ignored");
            return;
        }

        ConfigManager config = plugin.getConfigManager();
        DeliveryTracker.Decision decision = tracker.onStatus(player.getUniqueId(),
                DeliveryTracker.Status.of(event.getStatus().name()));

        switch (decision.action()) {
            case LOADED -> {
                logger.debug(name + " successfully loaded the resource pack");
                if (tracker.noteHosting(player.getUniqueId(), decision.action()) == DeliveryTracker.HostingChange.RESUMED) {
                    logger.info(name + " loaded the resource pack, so the pack URL works again: players whose"
                            + " download fails are kicked again (distribution.enforce-required).");
                }
            }
            case DECLINED -> {
                logger.info(name + " declined the resource pack");
                enforceRequired(player, decision.action(), "declined the required resource pack");
            }
            case RESEND -> {
                long delay = config.getRetryDelayTicks();
                logger.info("Resource pack " + event.getStatus() + " for " + name + "; resending in "
                        + delay + " ticks (attempt " + decision.attempt() + "/" + decision.maxAttempts() + ")");
                scheduleResend(player, delay);
            }
            case RESEND_ALREADY_PENDING ->
                    logger.debug(name + " reported " + event.getStatus() + " while a resend is already scheduled");
            case GAVE_UP -> {
                logger.warning("Resource pack delivery to " + name + " failed (last status " + event.getStatus()
                        + ") after " + decision.attempt() + " resend(s); giving up for this session. "
                        + "Check that the pack URL is reachable from the player's network ("
                        + urlForLog(player) + "). /pm apply " + name + " sends it again.");
                if (tracker.noteHosting(player.getUniqueId(), decision.action()) == DeliveryTracker.HostingChange.PAUSED) {
                    String problem = hostingProblemMessage();
                    logger.warning(problem);
                    plugin.alertAdmins("[PackMerger] " + problem);
                }
                enforceRequired(player, decision.action(), "could not download the required resource pack");
            }
            case INVALID_URL -> logger.warning(name + " reported INVALID_URL for the resource pack ("
                    + urlForLog(player) + "). This is a hosting/URL configuration problem, not a timing one, "
                    + "so PackMerger does not retry: check upload.self-host.public-url (or your S3/Polymath URL) "
                    + "is a full http(s) URL the client can reach.");
            case FAILED_RELOAD -> logger.warning(name + " downloaded the resource pack but the client failed to "
                    + "apply it (FAILED_RELOAD). Downloading it again cannot fix this, so PackMerger does not retry: "
                    + "check that client's latest.log for the resource reload error and run /pm validate.");
            case NONE -> logger.debug(name + " resource pack status: " + event.getStatus());
        }
    }

    private String urlForLog(Player player) {
        String sent = sentUrls.get(player.getUniqueId());
        if (sent != null) return sent;
        PublishedPack pack = plugin.getPublishedPack();
        return pack == null ? "no pack published" : pack.url();
    }

    /**
     * @return the admin warning for {@link DeliveryTracker.HostingChange#PAUSED}, or
     *         {@code null} while failed downloads still kick
     */
    public String getHostingProblem() {
        return tracker.downloadKicksPaused() ? hostingProblemMessage() : null;
    }

    private String hostingProblemMessage() {
        SelfHostProvider selfHost = plugin.getSelfHostProvider();
        String port = selfHost != null ? "TCP port " + selfHost.getBoundPort() : "the pack URL";
        return "Resource pack downloads failed for " + tracker.failedPlayersSinceLoad() + " different players and"
                + " nobody has loaded the pack since startup or the last successful download. This is a hosting"
                + " problem, not a player problem: " + port + " is probably not reachable (blocked by the server"
                + " machine's firewall, not forwarded on the router, or the server is behind a tunnel/proxy that only"
                + " forwards the Minecraft port). Until a download works again, players whose download fails are NOT"
                + " kicked (declining still kicks). Fix: "
                + (selfHost != null
                        ? "open " + port + " in the firewall of the server machine (LAN players need this too),"
                          + " forward it on your router for internet players, or set upload.self-host.public-url."
                        : "make sure the pack URL can be downloaded from your players' networks.")
                + " /pm status shows the URL.";
    }

    /** Re-sends after a delay on the player's entity scheduler (cancelled with the player). */
    private void scheduleResend(Player player, long delayTicks) {
        UUID id = player.getUniqueId();
        var task = player.getScheduler().runDelayed(plugin.getLoader(), scheduled -> {
            tracker.resendFinished(id);
            if (player.isOnline()) sendPack(player, true);
        }, () -> tracker.resendFinished(id), Math.max(1, delayTicks));
        if (task == null) tracker.resendFinished(id);
    }

    /**
     * Kicks the player when the pack is required and enforcement is enabled, unless failed
     * downloads are paused because hosting looks broken ({@link DeliveryTracker#shouldKick}).
     */
    private void enforceRequired(Player player, DeliveryTracker.Action action, String reason) {
        ConfigManager config = plugin.getConfigManager();
        // A report about a pack that was withdrawn meanwhile: there is nothing to require.
        if (plugin.getPublishedPack() == null) return;
        if (!tracker.shouldKick(action, config.isRequired(), config.isEnforceRequired())) {
            if (DeliveryTracker.kicks(action, config.isRequired(), config.isEnforceRequired())) {
                logger.info("Not kicking " + player.getName() + " (" + reason + "): downloads are failing for "
                        + "several players, which points at a hosting problem (see the warning above)");
            }
            return;
        }
        Component message = miniMessage.deserialize(config.getEnforceKickMessage());
        logger.info("Kicking " + player.getName() + ": " + reason + " (distribution.enforce-required)");
        player.getScheduler().run(plugin.getLoader(), scheduled -> {
            if (player.isOnline()) player.kick(message);
        }, null);
    }
}
