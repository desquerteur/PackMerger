package sh.pcx.packmerger.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sh.pcx.packmerger.PackMergerBootstrap;
import sh.pcx.packmerger.config.MessageManager;
import sh.pcx.packmerger.datapack.DatapackPackManager;
import sh.pcx.packmerger.datapack.ExternalPackStore;
import sh.pcx.packmerger.merge.MergeProvenance;
import sh.pcx.packmerger.merge.PackValidator;
import sh.pcx.packmerger.remote.FetchResult;
import sh.pcx.packmerger.remote.RemotePackManager;
import sh.pcx.packmerger.remote.RemoteSpec;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Registers and handles all PackMerger admin commands using Paper's Brigadier command API.
 *
 * <p>Commands are registered via Paper's lifecycle event system rather than {@code plugin.yml},
 * which provides better tab completion and argument parsing. All commands require the
 * {@code packmerger.admin} permission (default: op).</p>
 *
 * <p>Available subcommands:</p>
 * <ul>
 *   <li>{@code /packmerger reload} — reloads config, re-initializes the upload provider,
 *       and triggers a full merge-upload cycle</li>
 *   <li>{@code /packmerger validate} — runs validation on the current merged pack</li>
 *   <li>{@code /packmerger status} — displays current plugin state (server name, provider,
 *       last merge time, pack URL, hash, size, etc.)</li>
 *   <li>{@code /packmerger apply [player]} — force-sends the pack to a specific player
 *       or all online players, bypassing the cache</li>
 * </ul>
 *
 * <p>The command is also registered with the alias {@code /pm} for convenience.</p>
 *
 * @see PackMerger
 */
@SuppressWarnings("UnstableApiUsage") // Paper's Brigadier API is marked as experimental
public class PackMergerCommand {

    /** Reference to the owning plugin for component access. */
    private final PackMergerBootstrap plugin;

    /**
     * Creates the command handler and registers all commands.
     *
     * <p>Registration happens immediately in the constructor via Paper's lifecycle
     * event system.</p>
     *
     * @param plugin the owning PackMergerBootstrap plugin
     */
    public PackMergerCommand(PackMergerBootstrap plugin) {
        this.plugin = plugin;
        register();
    }

    /**
     * Registers the {@code /packmerger} command tree with Paper's Brigadier registrar.
     *
     * <p>The command tree structure:</p>
     * <pre>
     * /packmerger (requires packmerger.admin)
     *   ├── reload    — reload config and re-merge
     *   ├── merge     — re-stage plugin packs, merge and upload (no config reload)
     *   ├── validate  — validate current merged pack
     *   ├── status    — show plugin status
     *   ├── verbose [on|off] — show or toggle debug logging (persisted as log-level)
     *   ├── fixup [itemsadder|oraxen|nexo|dismiss] — hand pack hosting to PackMerger
     *   └── apply     — force-send pack
     *       └── [player] — optional player selector
     * </pre>
     */
    private void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            commands.register(
                    Commands.literal("packmerger")
                            .requires(source -> source.getSender().hasPermission("packmerger.admin"))
                            .then(Commands.literal("reload")
                                    .executes(this::handleReload))
                            .then(Commands.literal("merge")
                                    .executes(this::handleMerge))
                            .then(Commands.literal("validate")
                                    .executes(this::handleValidate))
                            .then(Commands.literal("status")
                                    .executes(this::handleStatus))
                            .then(Commands.literal("verbose")
                                    .executes(this::handleVerboseShow)
                                    .then(Commands.literal("on")
                                            .executes(ctx -> handleVerboseSet(ctx, true)))
                                    .then(Commands.literal("off")
                                            .executes(ctx -> handleVerboseSet(ctx, false))))
                            .then(Commands.literal("fixup")
                                    .executes(this::handleFixupList)
                                    .then(Commands.literal("dismiss")
                                            .executes(this::handleFixupDismiss))
                                    .then(Commands.literal("itemsadder")
                                            .executes(ctx -> handleFixup(ctx, "itemsadder")))
                                    .then(Commands.literal("oraxen")
                                            .executes(ctx -> handleFixup(ctx, "oraxen")))
                                    .then(Commands.literal("nexo")
                                            .executes(ctx -> handleFixup(ctx, "nexo"))))
                            .then(Commands.literal("apply")
                                    .executes(this::handleApplyAll)
                                    .then(Commands.argument("player", ArgumentTypes.player())
                                            .executes(this::handleApplyPlayer)))
                            .then(Commands.literal("inspect")
                                    .executes(this::handleInspectSummary)
                                    .then(Commands.literal("collisions")
                                            .executes(this::handleInspectCollisions))
                                    .then(Commands.literal("export")
                                            .executes(this::handleInspectExport))
                                    .then(Commands.argument("pack", StringArgumentType.string())
                                            .executes(this::handleInspectPack)))
                            .then(Commands.literal("conflicts")
                                    .executes(ctx -> handleConflicts(ctx, null))
                                    .then(Commands.argument("pack", StringArgumentType.string())
                                            .executes(ctx -> handleConflicts(ctx, StringArgumentType.getString(ctx, "pack")))))
                            .then(Commands.literal("rebuild")
                                    .executes(this::handleRebuild))
                            .then(Commands.literal("scan")
                                    .executes(ctx -> handleScan(ctx, false))
                                    .then(Commands.literal("refresh")
                                            .executes(ctx -> handleScan(ctx, true))))
                            .then(Commands.literal("sources")
                                    .executes(ctx -> handleSources(ctx, null))
                                    .then(Commands.literal("datapacks")
                                            .executes(ctx -> handleSources(ctx, "datapacks")))
                                    .then(Commands.literal("pending")
                                            .executes(this::handleSourcesPending))
                                    .then(Commands.literal("info")
                                            .then(Commands.argument("id", StringArgumentType.word())
                                                    .executes(this::handleSourcesInfo)))
                                    .then(Commands.literal("approve")
                                            .then(Commands.argument("id", StringArgumentType.word())
                                                    .executes(this::handleSourcesApprove)))
                                    .then(Commands.literal("reject")
                                            .then(Commands.argument("id", StringArgumentType.word())
                                                    .executes(this::handleSourcesReject))))
                            .then(Commands.literal("fetch")
                                    .executes(this::handleFetchAll)
                                    .then(Commands.argument("alias", StringArgumentType.string())
                                            .executes(this::handleFetchOne)))
                            .then(Commands.literal("profile")
                                    .executes(this::handleProfileList)
                                    .then(Commands.literal("list")
                                            .executes(this::handleProfileList))
                                    .then(Commands.literal("switch")
                                            .then(Commands.argument("name", StringArgumentType.string())
                                                    .executes(this::handleProfileSwitch))))
                            .then(Commands.literal("priority")
                                    .then(Commands.literal("list")
                                            .executes(this::handlePriorityList))
                                    .then(Commands.literal("up")
                                            .then(Commands.argument("pack", StringArgumentType.string())
                                                    .executes(ctx -> handlePriorityMove(ctx, "up"))))
                                    .then(Commands.literal("down")
                                            .then(Commands.argument("pack", StringArgumentType.string())
                                                    .executes(ctx -> handlePriorityMove(ctx, "down"))))
                                    .then(Commands.literal("top")
                                            .then(Commands.argument("pack", StringArgumentType.string())
                                                    .executes(ctx -> handlePriorityMove(ctx, "top"))))
                                    .then(Commands.literal("bottom")
                                            .then(Commands.argument("pack", StringArgumentType.string())
                                                    .executes(ctx -> handlePriorityMove(ctx, "bottom"))))
                                    .then(Commands.literal("set")
                                            .then(Commands.argument("pack", StringArgumentType.string())
                                                    .then(Commands.argument("position", IntegerArgumentType.integer(1))
                                                            .executes(this::handlePrioritySet)))))
                            .build(),
                    "PackMerger admin commands",
                    List.of("pm") // Register /pm as an alias for /packmerger
            );
        });
    }

    /**
     * Handles {@code /packmerger reload} — reloads the config and triggers a full
     * merge-upload cycle.
     *
     * @param ctx the Brigadier command context
     * @return 1 (success)
     */
    private int handleReload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();
        sender.sendMessage(msg.getMessage("reload.starting"));

        plugin.reload();
        plugin.mergeAndUpload(sender, PackMergerBootstrap.MergeOptions.FORCED);

        return 1;
    }

    /**
     * Handles {@code /packmerger merge} — runs the merge + upload pipeline without
     * reloading the config. Plugin packs are re-staged as part of the merge, so
     * this picks up a plugin's freshly rebuilt pack.
     *
     * @param ctx the Brigadier command context
     * @return 1 (success)
     */
    private int handleMerge(CommandContext<CommandSourceStack> ctx) {
        plugin.mergeAndUpload(ctx.getSource().getSender(), PackMergerBootstrap.MergeOptions.FORCED);
        return 1;
    }

    /**
     * Handles {@code /packmerger validate} — runs pack validation asynchronously
     * and reports results to the sender.
     *
     * <p>Validation runs on an async thread to avoid blocking the main thread for
     * large packs. Results are dispatched back to the main thread for messaging.</p>
     *
     * @param ctx the Brigadier command context
     * @return 1 if validation started, 0 if no merged pack exists
     */
    private int handleValidate(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();

        File outputFile = plugin.getOutputFile();
        if (!outputFile.exists()) {
            sender.sendMessage(msg.getMessage("validate.no-pack"));
            return 0;
        }

        sender.sendMessage(msg.getMessage("validate.starting"));

        // Run validation off the main thread so large packs don't block ticks.
        // Adventure's sendMessage is thread-safe, so we can message the sender
        // directly from the async task — no main-thread hop needed.
        Bukkit.getAsyncScheduler().runNow(plugin.getLoader(), asyncTask -> {
            PackValidator.ValidationResult result = plugin.getValidator().validate(outputFile);
            sender.sendMessage(msg.getMessage("validate.complete",
                    "warnings", String.valueOf(result.warnings()),
                    "errors", String.valueOf(result.errors())));
            if (!result.messages().isEmpty()) {
                for (String line : result.messages()) {
                    if (line.startsWith("ERROR:")) {
                        sender.sendMessage(msg.getMessage("validate.error-line", "message", line));
                    } else {
                        sender.sendMessage(msg.getMessage("validate.warning-line", "message", line));
                    }
                }
            }
        });

        return 1;
    }

    /**
     * Handles {@code /packmerger status} — displays comprehensive plugin state information.
     *
     * @param ctx the Brigadier command context
     * @return 1 (success)
     */
    private int handleStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();

        sender.sendMessage(msg.getMessage("status.header"));
        sender.sendMessage(msg.getMessage("status.server",
                "server", plugin.getConfigManager().getServerName()));
        sender.sendMessage(msg.getMessage("status.upload-provider",
                "provider", plugin.getConfigManager().getUploadProvider()));
        sender.sendMessage(msg.getMessage("status.last-merge",
                "time", plugin.getFormattedLastMergeTime()));

        String url = plugin.getCurrentPackUrl();
        sender.sendMessage(msg.getMessage("status.pack-url",
                "url", url != null ? url : "N/A"));
        sendHostingStatus(sender, msg);
        var attempt = plugin.getLastUploadAttempt();
        if (attempt == null) {
            sender.sendMessage(msg.getMessage("status.last-upload-none"));
        } else {
            sender.sendMessage(msg.getMessage(attempt.ok() ? "status.last-upload-ok" : "status.last-upload-failed",
                    "time", clock(attempt.at()), "detail", attempt.detail()));
        }

        String hash = plugin.getCurrentPackHashHex();
        sender.sendMessage(msg.getMessage("status.sha1",
                "hash", hash != null ? hash : "N/A"));

        // Display the output file size if it exists
        File outputFile = plugin.getOutputFile();
        if (outputFile.exists()) {
            long size = outputFile.length();
            String sizeStr;
            if (size < 1024) sizeStr = size + " B";
            else if (size < 1024 * 1024) sizeStr = String.format("%.1f KB", size / 1024.0);
            else sizeStr = String.format("%.1f MB", size / (1024.0 * 1024));
            sender.sendMessage(msg.getMessage("status.pack-size",
                    "size", sizeStr));
        }

        sender.sendMessage(msg.getMessage("status.merging",
                "status", plugin.isMerging() ? "Yes" : "No"));

        // Count and display the number of packs in the packs folder
        File packsFolder = plugin.getPacksFolder();
        File[] packs = packsFolder.listFiles();
        if (packs != null) {
            int count = 0;
            for (File f : packs) {
                // Skip override files and hidden files
                if (f.getName().equals("pack.mcmeta") || f.getName().equals("pack.png")) continue;
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory() || f.getName().endsWith(".zip")) count++;
            }
            sender.sendMessage(msg.getMessage("status.packs-in-folder",
                    "count", String.valueOf(count)));
        }

        if (plugin.getPluginPackManager() != null && plugin.getConfigManager().isPluginPacksEnabled()) {
            List<String> merged = plugin.getPluginPackManager().getMergedAliases();
            sender.sendMessage(msg.getMessage("status.plugin-packs",
                    "packs", merged.isEmpty() ? "none" : String.join(", ", merged)));
            for (String state : plugin.getPluginPackManager().getIntegrationStates()) {
                sender.sendMessage(msg.getMessage("status.integration", "state", state));
            }
        } else {
            sender.sendMessage(msg.getMessage("status.plugin-packs-off"));
        }
        sendApiPackStatus(sender, msg);
        sendDatapackStatus(sender, msg);
        sendCompetingHostStatus(sender, msg);
        sendBedrockStatus(sender, msg);
        if (plugin.getProxyLink() != null) {
            sender.sendMessage(msg.getMessage("status.proxy", "state", plugin.getProxyLink().describe()));
        }
        boolean verbose = "debug".equalsIgnoreCase(plugin.getConfigManager().getLogLevel());
        sender.sendMessage(msg.getMessage("status.logging",
                "level", plugin.getConfigManager().getLogLevel(), "verbose", verbose ? "on" : "off"));

        return 1;
    }

    private static String clock(java.time.Instant at) {
        // LocalTime.toString() drops the seconds when they are 00; always print HH:mm:ss.
        return java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                .format(java.time.LocalTime.ofInstant(at, java.time.ZoneId.systemDefault()));
    }

    /** {@code /pm status}: the datapack line (and pending approvals). */
    private void sendDatapackStatus(CommandSender sender, MessageManager msg) {
        DatapackPackManager dp = plugin.getDatapackPackManager();
        if (dp == null || !plugin.getConfigManager().isDatapacksEnabled()) {
            sender.sendMessage(msg.getMessage("status.datapacks-off"));
            return;
        }
        sender.sendMessage(msg.getMessage("status.datapacks", "state", dp.statusLine() + " (/pm sources datapacks)"));
        int pending = dp.pendingEntries().size();
        if (pending > 0) {
            sender.sendMessage(msg.getMessage("status.pending-approval", "count", String.valueOf(pending)));
        }
    }

    // ---- /pm scan, /pm sources -------------------------------------------

    /** Most conflicts listed in chat; the rest are counted. */
    private static final int MAX_CONFLICT_LINES = 20;

    /** {@code /pm conflicts [pack]} — files where packs really disagree (not identical, not cleanly merged). */
    private int handleConflicts(CommandContext<CommandSourceStack> ctx, String pack) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();
        MergeProvenance prov = plugin.getLastMergeProvenance();
        if (prov == null) {
            sender.sendMessage(msg.getMessage("conflicts.no-merge"));
            return 0;
        }
        List<ConflictRenderer.Conflict> conflicts = ConflictRenderer.conflicts(prov,
                sh.pcx.packmerger.merge.PackMergeEngine.datapackAliases(plugin.getPacksFolder()), pack);
        if (conflicts.isEmpty()) {
            sender.sendMessage(msg.getMessage("conflicts.none"));
            return 1;
        }
        sender.sendMessage(msg.getMessage("conflicts.header", "count", String.valueOf(conflicts.size())));
        for (int i = 0; i < conflicts.size() && i < MAX_CONFLICT_LINES; i++) {
            ConflictRenderer.Conflict c = conflicts.get(i);
            sender.sendMessage(msg.getMessage("conflicts.line", "path", c.path(), "winner", c.winner(),
                    "losers", String.join(", ", c.losers()) + (c.note() == null ? "" : " (" + c.note() + ")")));
        }
        if (conflicts.size() > MAX_CONFLICT_LINES) {
            sender.sendMessage(msg.getMessage("conflicts.more", "count", String.valueOf(conflicts.size() - MAX_CONFLICT_LINES)));
        }
        sender.sendMessage(msg.getMessage("conflicts.hint"));
        return 1;
    }

    /** {@code /pm rebuild} — re-scan the datapacks, then merge even if nothing changed. */
    private int handleRebuild(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(plugin.getMessageManager().getMessage("rebuild.starting"));
        plugin.mergeAndUpload(sender, PackMergerBootstrap.MergeOptions.REBUILD);
        return 1;
    }

    /** {@code /pm scan [refresh]} — re-scan the datapacks and report what was found. */
    private int handleScan(CommandContext<CommandSourceStack> ctx, boolean refresh) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();
        DatapackPackManager dp = plugin.getDatapackPackManager();
        if (dp == null || !plugin.getConfigManager().isDatapacksEnabled()) {
            sender.sendMessage(msg.getMessage("scan.disabled"));
            return 1;
        }
        sender.sendMessage(msg.getMessage("scan.starting"));
        dp.scan(DatapackPackManager.ScanTrigger.MANUAL, refresh).whenComplete((report, error) -> {
            if (error != null) {
                sender.sendMessage(msg.getMessage("merge.failed", "error", String.valueOf(error.getMessage())));
                return;
            }
            sendScanReport(sender, msg, report);
        });
        return 1;
    }

    private static void sendScanReport(CommandSender sender, MessageManager msg, DatapackPackManager.ScanReport report) {
        sender.sendMessage(msg.getMessage("scan.summary",
                "count", String.valueOf(report.datapacks()),
                "folders", String.valueOf(report.folders().size()),
                "paths", report.folders().isEmpty() ? "none" : String.join(", ", report.folders())));
        for (String line : report.lines()) {
            String[] p = line.split("\\|", 3);
            String key = switch (p[0]) {
                case "merged" -> "scan.line-merged";
                case "pending" -> "scan.line-pending";
                case "problem" -> "scan.line-problem";
                default -> "scan.line-error";
            };
            sender.sendMessage(msg.getMessage(key, "alias", p[1], "id", p[1], "detail", p.length > 2 ? p[2] : ""));
        }
        if (report.dataOnly() > 0) {
            sender.sendMessage(msg.getMessage("scan.line-data-only", "count", String.valueOf(report.dataOnly())));
        }
        sender.sendMessage(msg.getMessage(report.changed() ? "scan.merge-requested" : "scan.unchanged"));
    }

    /**
     * {@code /pm sources [datapacks]} — every pack source and its state: packs/ folder,
     * remote packs, plugin packs, API packs, datapacks (and external packs).
     */
    private int handleSources(CommandContext<CommandSourceStack> ctx, String filter) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();
        DatapackPackManager dp = plugin.getDatapackPackManager();
        List<String[]> rows = new java.util.ArrayList<>();
        if (filter == null) {
            File[] local = plugin.getPacksFolder().listFiles();
            if (local != null) {
                java.util.Arrays.sort(local);
                for (File f : local) {
                    String n = f.getName();
                    if (n.startsWith(".") || n.equals("pack.mcmeta") || n.equals("pack.png")) continue;
                    if (!f.isDirectory() && !n.endsWith(".zip")) continue;
                    rows.add(new String[]{n, "packs", "present", "packs/" + n});
                }
            }
            for (RemoteSpec r : plugin.getConfigManager().getRemotePacks()) {
                boolean cached = new File(new File(plugin.getPacksFolder(), ".remote-cache"), r.alias() + ".zip").isFile();
                rows.add(new String[]{r.alias(), "remote", cached ? "cached" : "not downloaded", r.url()});
            }
            if (plugin.getPluginPackManager() != null && plugin.getConfigManager().isPluginPacksEnabled()) {
                for (String state : plugin.getPluginPackManager().getIntegrationStates()) {
                    int colon = state.lastIndexOf(": ");
                    rows.add(new String[]{colon > 0 ? state.substring(0, colon) : state, "plugin",
                            colon > 0 ? state.substring(colon + 2) : "", "packs/.plugin-packs"});
                }
            }
            if (plugin.getApiPackManager() != null) {
                List<String> merged = plugin.getApiPackManager().getMergedAliases();
                for (var r : plugin.getRegisteredPacks()) {
                    rows.add(new String[]{r.alias(), "api", merged.contains(r.alias()) ? "merged" : "not merged yet",
                            "registered by " + r.owner()});
                }
            }
        }
        if (dp != null && plugin.getConfigManager().isDatapacksEnabled()) {
            for (DatapackPackManager.SourceView v : dp.sources()) {
                rows.add(new String[]{v.id(), v.kind(), v.state(), v.origin() + (v.detail() == null ? "" : " — " + v.detail())});
            }
        } else if ("datapacks".equals(filter)) {
            sender.sendMessage(msg.getMessage("scan.disabled"));
            return 1;
        }
        sender.sendMessage(msg.getMessage(filter == null ? "sources.header" : "sources.datapacks-header"));
        if (rows.isEmpty()) {
            sender.sendMessage(msg.getMessage("sources.none"));
            return 1;
        }
        for (String[] r : rows) {
            sender.sendMessage(msg.getMessage("sources.line", "id", r[0], "kind", r[1], "state", r[2], "detail", r[3]));
        }
        return 1;
    }

    /** @return the datapack manager when external packs are usable, else tells the sender why not */
    private DatapackPackManager externalPacks(CommandSender sender, MessageManager msg) {
        DatapackPackManager dp = plugin.getDatapackPackManager();
        if (dp == null || !plugin.getConfigManager().isDatapacksEnabled()) {
            sender.sendMessage(msg.getMessage("scan.disabled"));
            return null;
        }
        if (!plugin.getConfigManager().isModrinthEnabled()) {
            sender.sendMessage(msg.getMessage("sources.modrinth-off"));
        }
        return dp;
    }

    /** {@code /pm sources pending} — external packs waiting for approval. */
    private int handleSourcesPending(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();
        DatapackPackManager dp = externalPacks(sender, msg);
        if (dp == null) return 1;
        List<ExternalPackStore.Entry> pending = dp.pendingEntries();
        if (pending.isEmpty()) {
            sender.sendMessage(msg.getMessage("sources.none-pending"));
            return 1;
        }
        sender.sendMessage(msg.getMessage("sources.pending-header"));
        for (ExternalPackStore.Entry e : pending) {
            sender.sendMessage(msg.getMessage("sources.pending-line", "id", e.id(), "provider", "Modrinth",
                    "project", e.projectName(), "version", String.valueOf(e.versionNumber()),
                    "size", e.size() < 0 ? "size unknown" : sh.pcx.packmerger.datapack.ZipLimits.describe(e.size()),
                    "datapack", String.valueOf(e.datapack()), "reason", String.valueOf(e.reason())));
            sender.sendMessage(msg.getMessage("sources.pending-hint", "id", e.id()));
        }
        return 1;
    }

    /** {@code /pm sources info <id>} — everything known about one external pack. */
    private int handleSourcesInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();
        DatapackPackManager dp = externalPacks(sender, msg);
        if (dp == null) return 1;
        String id = StringArgumentType.getString(ctx, "id");
        ExternalPackStore.Entry e = dp.externalStore().get(id).orElse(null);
        if (e == null) {
            sender.sendMessage(msg.getMessage("sources.unknown-id", "id", id));
            return 0;
        }
        String[][] rows = {
                {"id", e.id()}, {"state", e.state() + (e.decidedBy() == null ? "" : " by " + e.decidedBy())},
                {"datapack", e.datapack()}, {"reason", e.reason()},
                {"project", e.projectName() + " (" + e.projectId() + ")"},
                {"version", e.versionNumber() + " (" + e.versionId() + ")"},
                {"file", e.fileName()}, {"url", e.url()},
                {"size", e.size() < 0 ? "unknown" : sh.pcx.packmerger.datapack.ZipLimits.describe(e.size())},
                {"sha1", e.sha1()}, {"sha512", e.sha512()},
                {"first seen", String.valueOf(e.firstSeen())}, {"last seen", String.valueOf(e.lastSeen())}};
        for (String[] r : rows) {
            sender.sendMessage(msg.getMessage("sources.info-line", "key", r[0], "value", String.valueOf(r[1])));
        }
        return 1;
    }

    /** {@code /pm sources approve <id|all>} — approve, download (verified), re-scan and merge. */
    private int handleSourcesApprove(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();
        if (!mayDecideSources(ctx.getSource(), msg)) return 0;
        DatapackPackManager dp = externalPacks(sender, msg);
        if (dp == null) return 1;
        String id = StringArgumentType.getString(ctx, "id");
        List<String> ids = "all".equalsIgnoreCase(id)
                ? dp.pendingEntries().stream().map(ExternalPackStore.Entry::id).toList() : List.of(id);
        if (ids.isEmpty()) {
            sender.sendMessage(msg.getMessage("sources.approved-none"));
            return 1;
        }
        for (String one : ids) {
            ExternalPackStore.Entry e = dp.externalStore().get(one).orElse(null);
            if (e == null) {
                sender.sendMessage(msg.getMessage("sources.unknown-id", "id", one));
                continue;
            }
            String host;
            try {
                host = java.net.URI.create(e.url()).getHost();
            } catch (IllegalArgumentException ex) {
                host = "?";
            }
            sender.sendMessage(msg.getMessage("sources.approved", "id", e.id(), "host", String.valueOf(host)));
            dp.approve(e.id(), sender.getName()).whenComplete((r, t) -> {
                if (t != null || r == null || r.isFailure()) {
                    sender.sendMessage(msg.getMessage("sources.download-failed", "id", e.id(),
                            "error", t != null ? String.valueOf(t.getMessage()) : r == null ? "unknown id" : r.detail()));
                } else {
                    sender.sendMessage(msg.getMessage("sources.download-ok", "algorithm", "sha512",
                            "size", e.size() < 0 ? "?" : sh.pcx.packmerger.datapack.ZipLimits.describe(e.size())));
                }
            });
        }
        return 1;
    }

    /**
     * Checks permission and who is asking: approving a download is a human decision, so
     * it is refused for command blocks, non-player entities and anything run "as" or
     * "at" another entity (a datapack function using {@code /execute as}).
     */
    private boolean mayDecideSources(CommandSourceStack source, MessageManager msg) {
        CommandSender sender = source.getSender();
        if (!sender.hasPermission("packmerger.sources.approve")) {
            sender.sendMessage(msg.getMessage("sources.no-permission"));
            return false;
        }
        if (!isHumanSource(sender, source.getExecutor())) {
            sender.sendMessage(msg.getMessage("sources.not-allowed"));
            plugin.getPluginLogger().warning("[DATAPACK] refused /pm sources approve|reject from " + sender.getName()
                    + " (" + sender.getClass().getSimpleName() + "): only a player or the console may decide");
            return false;
        }
        return true;
    }

    /**
     * @param executor the command's executing entity ({@code /execute as}), or {@code null}
     * @return {@code true} for a player running the command themself, or the server
     *         console / RCON without an {@code /execute as} entity
     */
    static boolean isHumanSource(CommandSender sender, Object executor) {
        if (sender instanceof Player) return executor == null || executor == sender;
        boolean console = sender instanceof org.bukkit.command.ConsoleCommandSender
                || sender instanceof org.bukkit.command.RemoteConsoleCommandSender;
        return console && executor == null;
    }

    /** {@code /pm sources reject <id>} — never ask for this exact file again. */
    private int handleSourcesReject(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();
        if (!mayDecideSources(ctx.getSource(), msg)) return 0;
        DatapackPackManager dp = externalPacks(sender, msg);
        if (dp == null) return 1;
        String id = StringArgumentType.getString(ctx, "id");
        if (!dp.reject(id, sender.getName())) {
            sender.sendMessage(msg.getMessage("sources.unknown-id", "id", id));
            return 0;
        }
        sender.sendMessage(msg.getMessage("sources.rejected", "id", id));
        return 1;
    }

    /** {@code /pm status}: packs registered by other plugins through the API. */
    private void sendApiPackStatus(CommandSender sender, MessageManager msg) {
        var registered = plugin.getRegisteredPacks();
        if (registered.isEmpty()) return;
        List<String> merged = plugin.getApiPackManager().getMergedAliases();
        List<String> parts = new java.util.ArrayList<>();
        for (var r : registered) {
            parts.add(r.alias() + " (" + r.owner() + (merged.contains(r.alias()) ? "" : ", not merged yet") + ")");
        }
        sender.sendMessage(msg.getMessage("status.api-packs", "packs", String.join(", ", parts)));
    }

    /** {@code /pm status}: item plugins that host/send/protect their own pack. */
    private void sendCompetingHostStatus(CommandSender sender, MessageManager msg) {
        sh.pcx.packmerger.integration.CompetingHostManager hosts = plugin.getCompetingHostManager();
        if (hosts == null) return;
        var findings = hosts.getFindings();
        boolean any = false;
        for (var entry : findings.entrySet()) {
            for (var f : entry.getValue()) {
                any = true;
                sender.sendMessage(msg.getMessage(hosts.isDismissed(f) ? "status.host-conflict-dismissed"
                                : "status.host-conflict",
                        "plugin", f.target().pluginName(), "key", f.key(), "value", String.valueOf(f.current()),
                        "fix", String.valueOf(f.fix()), "id", f.target().id()));
            }
        }
        if (!any) {
            sender.sendMessage(msg.getMessage("status.hosts-clean",
                    "checked", findings.isEmpty() ? "none installed" : String.join(", ", findings.keySet())));
        }
    }

    /** {@code /pm status}: Bedrock conversion and Floodgate state. */
    private void sendBedrockStatus(CommandSender sender, MessageManager msg) {
        boolean floodgate = Bukkit.getPluginManager().getPlugin("floodgate") != null;
        String java = plugin.getConfigManager().isSkipBedrockPlayers() ? "not sent to Bedrock players"
                : "sent to Bedrock players too";
        if (!plugin.getConfigManager().isBedrockEnabled()) {
            sender.sendMessage(msg.getMessage("status.bedrock-off",
                    "floodgate", floodgate ? "installed" : "not installed", "java", java));
            return;
        }
        var b = plugin.getLastBedrock();
        String last = b == null ? "not run yet"
                : b.items() + " item(s)" + (b.manifestVersion() == null ? "" : ", manifest " + b.manifestVersion())
                + " at " + clock(b.at()) + " (" + b.detail() + ")";
        java.io.File geyser = plugin.resolveGeyserFolder();
        sender.sendMessage(msg.getMessage("status.bedrock-on",
                "last", last,
                "geyser", geyser == null ? "not found" : geyser.getPath() + (geyser.isDirectory() ? "" : " (missing)"),
                "floodgate", floodgate ? "installed" : "not installed", "java", java));
    }

    // ---- /pm verbose ----------------------------------------------------

    /** {@code /pm verbose} — show whether debug logging is on. */
    private int handleVerboseShow(CommandContext<CommandSourceStack> ctx) {
        boolean on = "debug".equalsIgnoreCase(plugin.getConfigManager().getLogLevel());
        ctx.getSource().getSender().sendMessage(plugin.getMessageManager().getMessage("verbose.state",
                "state", on ? "on" : "off", "level", plugin.getConfigManager().getLogLevel()));
        return 1;
    }

    /** {@code /pm verbose on|off} — set log-level to debug/info and save it to config.yml. */
    private int handleVerboseSet(CommandContext<CommandSourceStack> ctx, boolean on) {
        plugin.setVerbose(on);
        ctx.getSource().getSender().sendMessage(plugin.getMessageManager().getMessage(
                on ? "verbose.enabled" : "verbose.disabled"));
        plugin.getPluginLogger().info("Verbose logging " + (on ? "enabled" : "disabled")
                + " by " + ctx.getSource().getSender().getName() + " (log-level: "
                + plugin.getConfigManager().getLogLevel() + ")");
        return 1;
    }

    // ---- /pm fixup ------------------------------------------------------

    /** {@code /pm fixup} — list what would be changed. */
    private int handleFixupList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();
        Bukkit.getAsyncScheduler().runNow(plugin.getLoader(), t -> {
            var hosts = plugin.getCompetingHostManager();
            hosts.scan();
            boolean any = false;
            for (var entry : hosts.getFindings().entrySet()) {
                for (var f : entry.getValue()) {
                    any = true;
                    sender.sendMessage(msg.getMessage("fixup.finding", "line", f.describe(), "id", f.target().id()));
                }
            }
            if (!any) sender.sendMessage(msg.getMessage("fixup.none"));
        });
        return 1;
    }

    /** {@code /pm fixup <plugin>} — back up and rewrite the plugin's config. */
    private int handleFixup(CommandContext<CommandSourceStack> ctx, String id) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();
        var target = sh.pcx.packmerger.integration.CompetingHosts.target(id);
        if (Bukkit.getPluginManager().getPlugin(target.pluginName()) == null) {
            sender.sendMessage(msg.getMessage("fixup.not-installed", "plugin", target.pluginName()));
            return 0;
        }
        Bukkit.getAsyncScheduler().runNow(plugin.getLoader(), t -> {
            try {
                var r = plugin.getCompetingHostManager().fixup(target);
                if (!r.changed()) {
                    sender.sendMessage(msg.getMessage("fixup.nothing", "plugin", target.pluginName(),
                            "detail", r.message()));
                    return;
                }
                sender.sendMessage(msg.getMessage("fixup.done", "plugin", target.pluginName(),
                        "file", target.pluginName() + "/" + target.configFile()));
                r.applied().forEach((k, v) -> sender.sendMessage(msg.getMessage("fixup.change",
                        "key", k, "value", String.valueOf(v))));
                sender.sendMessage(msg.getMessage("fixup.backup", "file", r.backup().getName()));
                sender.sendMessage(msg.getMessage("fixup.next", "reload", target.reloadHint()));
            } catch (Exception e) {
                sender.sendMessage(msg.getMessage("fixup.failed", "plugin", target.pluginName(),
                        "error", String.valueOf(e.getMessage())));
            }
        });
        return 1;
    }

    /** {@code /pm fixup dismiss} — stop warning about the current findings (persisted). */
    private int handleFixupDismiss(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();
        Bukkit.getAsyncScheduler().runNow(plugin.getLoader(), t -> {
            try {
                int n = plugin.getCompetingHostManager().dismissAll();
                sender.sendMessage(msg.getMessage("fixup.dismissed", "count", String.valueOf(n)));
            } catch (Exception e) {
                sender.sendMessage(msg.getMessage("fixup.failed", "plugin", "PackMerger",
                        "error", String.valueOf(e.getMessage())));
            }
        });
        return 1;
    }

    /**
     * The hosting part of {@code /pm status}: which provider published the live pack (and
     * why a fallback was used), and for self-host the port, the resolved host with the
     * setting to change when it is not routable, and the last reachability verification.
     */
    private void sendHostingStatus(CommandSender sender, MessageManager msg) {
        sh.pcx.packmerger.upload.UploadChain.Outcome outcome = plugin.getLastUploadOutcome();
        sender.sendMessage(msg.getMessage("status.hosting-route",
                "route", outcome != null ? outcome.describe() : "nothing published yet"));

        sh.pcx.packmerger.upload.SelfHostProvider selfHost = plugin.getSelfHostProvider();
        if (selfHost == null) return;
        sender.sendMessage(msg.getMessage("status.self-host",
                "port", String.valueOf(selfHost.getBoundPort()),
                "state", selfHost.isRunning() ? "listening" : "NOT listening (port could not be bound)"));
        sh.pcx.packmerger.upload.SelfHostAddress.Resolution resolution = selfHost.getResolution();
        if (resolution != null && selfHost.isPerPlayer()) {
            sender.sendMessage(msg.getMessage("status.per-player-url",
                    "port", String.valueOf(selfHost.getBoundPort()), "fallback", resolution.url()));
        } else if (resolution != null) {
            sender.sendMessage(msg.getMessage("status.resolved-host",
                    "host", resolution.host(), "source", resolution.source().description()));
            if (!resolution.routable()) {
                sender.sendMessage(msg.getMessage("status.host-not-routable", "advice", resolution.advice()));
            }
        }
        sh.pcx.packmerger.upload.SelfHostProvider.Verification v = selfHost.getVerification();
        String time = clock(v.at());
        String key = switch (v.state()) {
            case PASSED -> "status.verification-passed";
            case FAILED -> "status.verification-failed";
            case SKIPPED -> "status.verification-skipped";
            case PENDING -> "status.verification-pending";
        };
        sender.sendMessage(msg.getMessage(key, "detail", v.detail(), "time", time));
    }

    /**
     * Handles {@code /packmerger apply} (no player argument) — force-sends the pack
     * to all online players, bypassing the cache.
     *
     * @param ctx the Brigadier command context
     * @return 1 if the pack was sent, 0 if no merged pack is available
     */
    private int handleApplyAll(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();

        if (plugin.getCurrentPackUrl() == null) {
            sender.sendMessage(msg.getMessage("apply.no-pack"));
            return 0;
        }

        int count = Bukkit.getOnlinePlayers().size();
        plugin.getDistributor().sendToAll(true);
        sender.sendMessage(msg.getMessage("apply.sent-all",
                "count", String.valueOf(count)));
        return 1;
    }

    /**
     * Handles {@code /packmerger apply <player>} — force-sends the pack to a specific
     * player (or players matching the selector), bypassing the cache.
     *
     * @param ctx the Brigadier command context with a "player" argument
     * @return 1 if the pack was sent, 0 if no pack is available or no player matched
     */
    private int handleApplyPlayer(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();

        if (plugin.getCurrentPackUrl() == null) {
            sender.sendMessage(msg.getMessage("apply.no-pack"));
            return 0;
        }

        try {
            // Resolve the player selector argument (supports @a, @p, player names, etc.)
            PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
            List<Player> players = resolver.resolve(ctx.getSource());

            if (players.isEmpty()) {
                sender.sendMessage(msg.getMessage("apply.no-match"));
                return 0;
            }

            for (Player player : players) {
                plugin.getDistributor().sendPack(player, true);
                sender.sendMessage(msg.getMessage("apply.sent-player",
                        "player", player.getName()));
            }
        } catch (Exception e) {
            sender.sendMessage(msg.getMessage("apply.player-error",
                    "error", e.getMessage()));
            return 0;
        }

        return 1;
    }

    /** Shared MiniMessage parser for the inspect subcommands' styled output. */
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Sends each rendered line through MiniMessage to the given sender. */
    private static void sendAll(CommandSender sender, List<String> lines) {
        for (String line : lines) {
            sender.sendMessage(MINI.deserialize(line));
        }
    }

    /** {@code /pm inspect} — top-line summary of the last merge. */
    private int handleInspectSummary(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MergeProvenance prov = plugin.getLastMergeProvenance();
        sendAll(sender, InspectRenderer.summary(prov));
        return 1;
    }

    /** {@code /pm inspect collisions} — list every path contributed by 2+ packs. */
    private int handleInspectCollisions(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MergeProvenance prov = plugin.getLastMergeProvenance();
        sendAll(sender, InspectRenderer.collisions(prov));
        return 1;
    }

    /** {@code /pm inspect <pack>} — what this pack won + contributed but lost on. */
    private int handleInspectPack(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String packName = StringArgumentType.getString(ctx, "pack");
        MergeProvenance prov = plugin.getLastMergeProvenance();
        sendAll(sender, InspectRenderer.packDetail(prov, packName));
        return 1;
    }

    // ---- /pm fetch ------------------------------------------------------

    /** {@code /pm fetch} — refetch every remote pack, ignoring refresh policy. */
    private int handleFetchAll(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        RemotePackManager rpm = plugin.getRemotePackManager();
        List<RemoteSpec> specs = plugin.getConfigManager().getRemotePacks();
        if (specs.isEmpty()) {
            sender.sendMessage(MINI.deserialize("<gray>No remote packs configured.</gray>"));
            return 1;
        }
        Bukkit.getAsyncScheduler().runNow(plugin.getLoader(), asyncTask -> {
            List<FetchResult> results = rpm.fetchAll(specs, RemotePackManager.Trigger.MANUAL);
            for (FetchResult r : results) {
                sender.sendMessage(MINI.deserialize(fetchLine(r)));
            }
            sender.sendMessage(MINI.deserialize("<gray>Triggering merge with refreshed packs…</gray>"));
            plugin.mergeAndUpload(sender, PackMergerBootstrap.MergeOptions.FORCED);
        });
        return 1;
    }

    /** {@code /pm fetch <alias>} — refetch one named remote pack. */
    private int handleFetchOne(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String alias = StringArgumentType.getString(ctx, "alias");
        RemoteSpec spec = plugin.getConfigManager().getRemotePacks().stream()
                .filter(s -> s.alias().equals(alias))
                .findFirst()
                .orElse(null);
        if (spec == null) {
            sender.sendMessage(MINI.deserialize("<red>Unknown remote pack alias: <white>"
                    + alias + "</white></red>"));
            return 0;
        }
        Bukkit.getAsyncScheduler().runNow(plugin.getLoader(), asyncTask -> {
            FetchResult result = plugin.getRemotePackManager().fetch(spec);
            sender.sendMessage(MINI.deserialize(fetchLine(result)));
            sender.sendMessage(MINI.deserialize("<gray>Triggering merge with refreshed pack…</gray>"));
            plugin.mergeAndUpload(sender, PackMergerBootstrap.MergeOptions.FORCED);
        });
        return 1;
    }

    private static String fetchLine(FetchResult r) {
        return switch (r.status()) {
            case FETCHED -> "<green>✓</green> <white>" + r.alias() + "</white> <gray>— fetched ("
                    + r.detail() + ")</gray>";
            case NOT_MODIFIED -> "<green>✓</green> <white>" + r.alias() + "</white> <gray>— cached (304)</gray>";
            case ERROR_USING_CACHE -> "<yellow>!</yellow> <white>" + r.alias()
                    + "</white> <gray>— fetch failed; using cache (" + r.detail() + ")</gray>";
            case ERROR_NO_CACHE -> "<red>✗</red> <white>" + r.alias()
                    + "</white> <gray>— fetch failed, no cache (" + r.detail() + ")</gray>";
            case SKIPPED_BY_POLICY -> "<gray>· " + r.alias() + " — skipped (" + r.detail() + ")</gray>";
        };
    }

    // ---- /pm profile ----------------------------------------------------

    /** {@code /pm profile [list]} — list defined profiles and mark the active one. */
    private int handleProfileList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        var profiles = plugin.getConfigManager().getProfiles();
        if (profiles.isEmpty()) {
            sender.sendMessage(MINI.deserialize("<gray>No profiles defined. "
                    + "Add a <white>profiles:</white> section to config.yml to use this feature.</gray>"));
            return 1;
        }
        String active = plugin.getConfigManager().getActiveProfile();
        sender.sendMessage(MINI.deserialize("<aqua>━━━ Profiles ━━━</aqua>"));
        profiles.forEach((name, cfg) -> {
            String marker = name.equals(active) ? " <green>(active)</green>" : "";
            sender.sendMessage(MINI.deserialize("  <white>" + name + "</white>"
                    + " <gray>[" + cfg.priority().size() + " packs]</gray>" + marker));
        });
        return 1;
    }

    /** {@code /pm profile switch <name>} — activate a profile and re-merge. */
    private int handleProfileSwitch(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        var profiles = plugin.getConfigManager().getProfiles();
        if (!profiles.containsKey(name)) {
            sender.sendMessage(MINI.deserialize("<red>Unknown profile: <white>" + name + "</white></red>"));
            sender.sendMessage(MINI.deserialize("<gray>Known profiles:</gray> <white>"
                    + String.join(", ", profiles.keySet()) + "</white>"));
            return 0;
        }
        plugin.getConfig().set("active-profile", name);
        plugin.saveConfig();
        plugin.getConfigManager().load();
        sender.sendMessage(MINI.deserialize("<green>Switched to profile <white>" + name + "</white></green>"));
        sender.sendMessage(MINI.deserialize("<gray>Triggering merge with new profile…</gray>"));
        plugin.mergeAndUpload(sender, PackMergerBootstrap.MergeOptions.FORCED);
        return 1;
    }

    // ---- /pm priority ---------------------------------------------------

    /** {@code /pm priority list} — show current priority order with 1-based indices. */
    private int handlePriorityList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        List<String> priority = plugin.getConfigManager().getPriority();
        if (priority.isEmpty()) {
            sender.sendMessage(MINI.deserialize("<gray>Priority list is empty.</gray>"));
            return 1;
        }
        sender.sendMessage(MINI.deserialize("<aqua>━━━ Priority (highest first) ━━━</aqua>"));
        for (int i = 0; i < priority.size(); i++) {
            sender.sendMessage(MINI.deserialize(
                    "<yellow>" + (i + 1) + ".</yellow> <white>" + priority.get(i) + "</white>"));
        }
        return 1;
    }

    /** Shared handler for up/down/top/bottom ops. */
    private int handlePriorityMove(CommandContext<CommandSourceStack> ctx, String op) {
        CommandSender sender = ctx.getSource().getSender();
        String pack = StringArgumentType.getString(ctx, "pack");
        List<String> current = plugin.getConfigManager().getPriority();

        List<String> updated;
        try {
            updated = switch (op) {
                case "up" -> PriorityMutator.up(current, pack);
                case "down" -> PriorityMutator.down(current, pack);
                case "top" -> PriorityMutator.top(current, pack);
                case "bottom" -> PriorityMutator.bottom(current, pack);
                default -> throw new IllegalStateException("unknown op: " + op);
            };
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MINI.deserialize("<red>" + e.getMessage() + "</red>"));
            return 0;
        }

        persistPriorityAndReMerge(sender, updated, "moved <white>" + pack + "</white> " + op);
        return 1;
    }

    /** {@code /pm priority set <pack> <position>} — absolute 1-based placement. */
    private int handlePrioritySet(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String pack = StringArgumentType.getString(ctx, "pack");
        int position = IntegerArgumentType.getInteger(ctx, "position");
        List<String> current = plugin.getConfigManager().getPriority();

        List<String> updated;
        try {
            updated = PriorityMutator.set(current, pack, position);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MINI.deserialize("<red>" + e.getMessage() + "</red>"));
            return 0;
        }

        persistPriorityAndReMerge(sender, updated, "set <white>" + pack + "</white> to position " + position);
        return 1;
    }

    /**
     * Writes the updated priority list back to {@code config.yml} via Bukkit's
     * config API (into the active profile's list when a profile is active),
     * invalidates the cached ConfigManager state, and triggers a re-merge. Comments in the on-disk config are lost — Bukkit's YAML writer
     * doesn't preserve them. Documented behaviour; the alternative (manual
     * YAML mutation) is a rabbit hole we're not opening for this feature.
     */
    private void persistPriorityAndReMerge(CommandSender sender, List<String> updated, String what) {
        // An active profile's priority shadows the root list (that's what getPriority()
        // returned and the mutation was computed from), so write the change there.
        String activeProfile = plugin.getConfigManager().getActiveProfile();
        String key = activeProfile != null ? "profiles." + activeProfile + ".priority" : "priority";
        plugin.getConfig().set(key, updated);
        plugin.saveConfig();
        plugin.getConfigManager().load();
        sender.sendMessage(MINI.deserialize("<green>Priority updated:</green> <gray>" + what + "</gray>"));
        sender.sendMessage(MINI.deserialize("<gray>Triggering merge with new priority…</gray>"));
        plugin.mergeAndUpload(sender, PackMergerBootstrap.MergeOptions.FORCED);
    }

    /** {@code /pm inspect export} — write the full report to output/last-merge-report.txt. */
    private int handleInspectExport(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MergeProvenance prov = plugin.getLastMergeProvenance();
        if (prov == null) {
            sender.sendMessage(MINI.deserialize("<red>No merge has completed yet.</red>"));
            return 0;
        }

        File reportFile = new File(plugin.getOutputFolder(), "last-merge-report.txt");
        try {
            List<String> lines = InspectRenderer.fullReport(prov);
            Files.writeString(reportFile.toPath(), String.join(System.lineSeparator(), lines),
                    StandardCharsets.UTF_8);
            sender.sendMessage(MINI.deserialize("<green>Wrote merge report to <white>"
                    + reportFile.getAbsolutePath() + "</white></green>"));
            return 1;
        } catch (Exception e) {
            sender.sendMessage(MINI.deserialize("<red>Failed to write report: "
                    + e.getMessage() + "</red>"));
            return 0;
        }
    }
}
