package com.plexon.travel;

import com.plexon.travel.api.PlexonTravelAPI.TravelType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TravelMenus implements Listener {
    private final PlexonTravel plugin;
    private final DestinationRegistry destinations;
    private final TravelEngine engine;
    private final TravelMessages messages;
    private final RtpService rtp;
    private final NamespacedKey actionKey;
    private final NamespacedKey warpKey;

    TravelMenus(PlexonTravel plugin, DestinationRegistry destinations, TravelEngine engine, TravelMessages messages, RtpService rtp) {
        this.plugin = plugin;
        this.destinations = destinations;
        this.engine = engine;
        this.messages = messages;
        this.rtp = rtp;
        this.actionKey = new NamespacedKey(plugin, "menu-action");
        this.warpKey = new NamespacedKey(plugin, "warp-id");
    }

    void openHelp(Player player) {
        MenuHolder holder = new MenuHolder(MenuType.HELP, 0);
        Inventory inventory = create(holder, 27, messages.string("gui.help-title", "<gradient:#22d3ee:#38bdf8><bold>Plexon Travel</bold></gradient>"));
        inventory.setItem(4, item(Material.COMPASS, "<gradient:#22d3ee:#38bdf8><bold>Travel</bold></gradient>",
            List.of("<gray>Fast, safe movement across PlexonCraft.</gray>"), null));
        inventory.setItem(10, item(Material.RESPAWN_ANCHOR, "<aqua><bold>Spawn</bold></aqua>",
            List.of("<gray>Return to this world's spawn.</gray>", "<dark_gray>Click to travel</dark_gray>"), "spawn"));
        inventory.setItem(12, item(Material.NETHER_STAR, "<blue><bold>Hub</bold></blue>",
            List.of("<gray>Return to this world's hub route.</gray>", "<dark_gray>Click to travel</dark_gray>"), "hub"));
        inventory.setItem(14, item(Material.ENDER_PEARL, "<green><bold>Warps</bold></green>",
            List.of("<gray>Browse configured server destinations.</gray>", "<dark_gray>Click to browse</dark_gray>"), "warps"));
        inventory.setItem(16, item(Material.CHORUS_FRUIT, "<light_purple><bold>Random Teleport</bold></light_purple>",
            List.of("<gray>Find a safe location inside this world's RTP boundary.</gray>", "<dark_gray>Click to inspect</dark_gray>"), "rtp"));
        inventory.setItem(20, item(Material.RECOVERY_COMPASS, "<yellow><bold>Back</bold></yellow>",
            List.of("<gray>Return to your previous meaningful location.</gray>", "<dark_gray>Click to travel</dark_gray>"), "back"));
        inventory.setItem(22, item(Material.PLAYER_HEAD, "<gold><bold>Teleport Requests</bold></gold>",
            List.of("<gray>/tpa player</gray>", "<gray>/tpahere player</gray>", "<gray>/tpaccept • /tpdeny</gray>"), "tpa-info"));
        inventory.setItem(24, item(Material.BARRIER, "<red>Close</red>", List.of(), "close"));
        player.openInventory(inventory);
    }

    void openWarps(Player player, int requestedPage) {
        if (!player.hasPermission("plexontravel.warps") && !player.hasPermission("plexontravel.warp")) {
            messages.send(player, "commands.no-permission", "<red>You do not have permission.</red>");
            return;
        }
        List<Warp> available = destinations.availableWarps();
        int pages = Math.max(1, (available.size() + 44) / 45);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        MenuHolder holder = new MenuHolder(MenuType.WARPS, page);
        Inventory inventory = create(holder, 54, messages.string("gui.warps-title", "<gradient:#22d3ee:#38bdf8><bold>Plexon Warps</bold></gradient>"));
        int from = page * 45;
        int to = Math.min(available.size(), from + 45);
        for (int index = from; index < to; index++) {
            Warp warp = available.get(index);
            Material material = Material.matchMaterial(warp.icon());
            if (material == null || material.isAir()) material = Material.ENDER_PEARL;
            boolean locked = warp.permissionRequired() && !warp.permission().isBlank() && !player.hasPermission(warp.permission());
            double fee = player.hasPermission("plexontravel.fee.bypass") ? 0D : engine.policy(TravelType.WARP, -1D).fee();
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + warp.category() + "</gray>");
            lore.add("<dark_gray>World: " + warp.destination().worldName() + "</dark_gray>");
            lore.add(fee > 0D ? String.format(Locale.ROOT, "<gold>Fee: %.2f</gold>", fee) : "<green>Free travel</green>");
            lore.add(locked ? "<red>Locked</red>" : "<aqua>Click to travel</aqua>");
            ItemStack icon = item(material, "<aqua><bold>" + warp.displayName() + "</bold></aqua>", lore, null);
            ItemMeta meta = icon.getItemMeta();
            meta.getPersistentDataContainer().set(warpKey, PersistentDataType.STRING, warp.id());
            icon.setItemMeta(meta);
            inventory.setItem(index - from, icon);
        }
        if (page > 0) inventory.setItem(45, item(Material.ARROW, "<white>Previous</white>", List.of(), "warps-page:" + (page - 1)));
        inventory.setItem(49, item(Material.COMPASS, "<white>Page " + (page + 1) + "/" + pages + "</white>", List.of(), "help"));
        if (page + 1 < pages) inventory.setItem(53, item(Material.ARROW, "<white>Next</white>", List.of(), "warps-page:" + (page + 1)));
        player.openInventory(inventory);
    }

    void openRtp(Player player) {
        MenuHolder holder = new MenuHolder(MenuType.RTP, 0);
        Inventory inventory = create(holder, 27, messages.string("gui.rtp-title", "<gradient:#c084fc:#22d3ee><bold>Random Teleport</bold></gradient>"));
        RtpProfile profile = rtp.profile(player.getWorld());
        boolean permitted = player.hasPermission("plexontravel.rtp");
        boolean allowed = permitted && profile.enabled();
        long cooldown = engine.cooldownRemainingMillis(player, TravelType.RTP);
        String source = profile.source() == RtpProfileSource.EXPLICIT ? "3.1 world profile" : "legacy/default inheritance";
        inventory.setItem(11, item(Material.COMPASS, "<aqua><bold>Search Zone</bold></aqua>",
            List.of("<gray>World: <white>" + player.getWorld().getName() + "</white></gray>",
                "<gray>Mode: <white>" + profile.boundaryMode().name() + "</white></gray>",
                "<gray>Boundary: <white>" + rtp.describe(player.getWorld()) + "</white></gray>",
                "<dark_gray>Source: " + source + "</dark_gray>"), null));
        inventory.setItem(15, item(Material.SHIELD, "<green><bold>Safe Destination</bold></green>",
            List.of("<gray>Async chunk lookup</gray>", "<gray>World-border enforced</gray>", "<gray>Hazard and collision checked</gray>"), null));
        List<String> actionLore = new ArrayList<>();
        if (!permitted) actionLore.add("<red>You do not have permission.</red>");
        else if (!allowed) actionLore.add("<red>Unavailable in this world.</red>");
        else if (rtp.describe(player.getWorld()).startsWith("INVALID")) actionLore.add("<red>Configured boundary has no usable area.</red>");
        else if (cooldown > 0L && !player.hasPermission("plexontravel.cooldown.bypass")) actionLore.add("<yellow>Cooldown: " + Math.max(1L, (cooldown + 999L) / 1000L) + "s</yellow>");
        else actionLore.add("<aqua>Click to find a location</aqua>");
        boolean actionable = allowed && !rtp.describe(player.getWorld()).startsWith("INVALID");
        inventory.setItem(13, item(actionable ? Material.ENDER_EYE : Material.BARRIER,
            actionable ? "<light_purple><bold>Find Location</bold></light_purple>" : "<red><bold>Unavailable</bold></red>", actionLore,
            actionable ? "rtp-go" : null));
        inventory.setItem(22, item(Material.ARROW, "<white>Back</white>", List.of(), "help"));
        player.openInventory(inventory);
    }

    private Inventory create(MenuHolder holder, int size, String title) {
        Inventory inventory = Bukkit.createInventory(holder, size, messages.raw(title));
        holder.inventory = inventory;
        return inventory;
    }

    private ItemStack item(Material material, String name, List<String> lore, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.raw(name));
        if (!lore.isEmpty()) meta.lore(lore.stream().map(messages::raw).toList());
        if (action != null) meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= event.getView().getTopInventory().getSize()) return;
        ItemStack current = event.getCurrentItem();
        if (current == null || !current.hasItemMeta()) return;
        ItemMeta meta = current.getItemMeta();
        String warpId = meta.getPersistentDataContainer().get(warpKey, PersistentDataType.STRING);
        if (warpId != null) {
            player.closeInventory();
            plugin.requestWarp(player, warpId);
            return;
        }
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        route(player, action, holder);
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }

    private void route(Player player, String action, MenuHolder holder) {
        if (action.startsWith("warps-page:")) {
            openWarps(player, Integer.parseInt(action.substring("warps-page:".length())));
            return;
        }
        switch (action) {
            case "spawn" -> { player.closeInventory(); plugin.requestSpawn(player); }
            case "hub" -> { player.closeInventory(); plugin.requestHub(player); }
            case "back" -> { player.closeInventory(); plugin.requestBack(player); }
            case "warps" -> openWarps(player, 0);
            case "rtp" -> openRtp(player);
            case "rtp-go" -> { player.closeInventory(); rtp.begin(player); }
            case "help" -> openHelp(player);
            case "tpa-info" -> messages.sendRaw(player, "<gray>Use <aqua>/tpa player</aqua> to go to someone, <aqua>/tpahere player</aqua> to invite them to you, then <green>/tpaccept</green> or <red>/tpdeny</red>.</gray>");
            case "close" -> player.closeInventory();
            default -> { }
        }
    }

    private enum MenuType { HELP, WARPS, RTP }

    private static final class MenuHolder implements InventoryHolder {
        private final MenuType type;
        private final int page;
        private Inventory inventory;

        private MenuHolder(MenuType type, int page) {
            this.type = type;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
