package com.plexon.travel;

import com.plexon.travel.api.PlexonTravelAPI.TravelType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

final class TpaService {
    private final PlexonTravel plugin;
    private final TravelEngine engine;
    private final TravelMessages messages;
    private final Map<UUID, TpaRequest> outgoing = new ConcurrentHashMap<>();
    private final LongAdder sent = new LongAdder();
    private final LongAdder accepted = new LongAdder();
    private final LongAdder denied = new LongAdder();
    private final LongAdder expired = new LongAdder();
    private BukkitTask ticker;

    TpaService(PlexonTravel plugin, TravelEngine engine, TravelMessages messages) {
        this.plugin = plugin;
        this.engine = engine;
        this.messages = messages;
    }

    void startTicker() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::expireOld, 20L, 20L);
    }

    boolean send(Player requester, Player target, TpaMode mode) {
        if (!requester.hasPermission("plexontravel.tpa")) {
            messages.send(requester, "commands.no-permission", "<red>You do not have permission.</red>");
            return false;
        }
        if (!plugin.getConfig().getBoolean("tpa.enabled", true)) {
            messages.send(requester, "tpa.disabled", "<red>Teleport requests are disabled.</red>");
            return false;
        }
        if (requester.getUniqueId().equals(target.getUniqueId())) {
            messages.send(requester, "tpa.self", "<red>You cannot send a teleport request to yourself.</red>");
            return false;
        }
        if (!plugin.getConfig().getBoolean("tpa.allow-cross-world", true)
            && !requester.getWorld().getUID().equals(target.getWorld().getUID())) {
            messages.send(requester, "tpa.cross-world", "<red>Cross-world teleport requests are disabled.</red>");
            return false;
        }

        expireOld();
        TpaRequest existing = outgoing.get(requester.getUniqueId());
        if (existing != null && !existing.expired(System.currentTimeMillis())) {
            messages.send(requester, "tpa.already-pending", "<yellow>You already have an active teleport request.</yellow>");
            return false;
        }

        int timeout = Math.max(5, Math.min(300, plugin.getConfig().getInt("tpa.request-timeout-seconds", 30)));
        long now = System.currentTimeMillis();
        TpaRequest request = new TpaRequest(requester.getUniqueId(), target.getUniqueId(), mode, now, now + timeout * 1000L);
        outgoing.put(requester.getUniqueId(), request);
        sent.increment();

        if (mode == TpaMode.TO_TARGET) {
            messages.send(requester, "tpa.sent", "<gray>Teleport request sent to <aqua>{target}</aqua>.</gray>", Map.of("target", target.getName()));
            messages.send(target, "tpa.received", "<aqua>{player}</aqua> <gray>wants to teleport to you. <green>/tpaccept</green> <dark_gray>or</dark_gray> <red>/tpdeny</red>.</gray>",
                Map.of("player", requester.getName()));
        } else {
            messages.send(requester, "tpa.sent-here", "<gray>Requested <aqua>{target}</aqua> to teleport to you.</gray>", Map.of("target", target.getName()));
            messages.send(target, "tpa.received-here", "<aqua>{player}</aqua> <gray>wants you to teleport to them. <green>/tpaccept</green> <dark_gray>or</dark_gray> <red>/tpdeny</red>.</gray>",
                Map.of("player", requester.getName()));
        }
        return true;
    }

    boolean accept(Player target, String requesterName) {
        TpaRequest request = resolveIncoming(target, requesterName);
        if (request == null) return false;
        if (!outgoing.remove(request.requesterId(), request)) return false;

        Player requester = Bukkit.getPlayer(request.requesterId());
        if (requester == null || !requester.isOnline()) {
            messages.send(target, "tpa.requester-offline", "<red>That player is no longer online.</red>");
            return false;
        }

        Player mover = request.mode() == TpaMode.TO_TARGET ? requester : target;
        Player anchor = request.mode() == TpaMode.TO_TARGET ? target : requester;
        if (!plugin.getConfig().getBoolean("tpa.allow-cross-world", true)
            && !mover.getWorld().getUID().equals(anchor.getWorld().getUID())) {
            messages.send(target, "tpa.cross-world", "<red>Cross-world teleport requests are disabled.</red>");
            messages.send(requester, "tpa.cross-world", "<red>Cross-world teleport requests are disabled.</red>");
            return false;
        }

        accepted.increment();
        messages.send(target, "tpa.accepted", "<green>Teleport request accepted.</green>");
        messages.send(requester, "tpa.accepted-requester", "<green>{player}</green> <gray>accepted your teleport request.</gray>", Map.of("player", target.getName()));
        engine.request(mover, TravelType.TPA, Destination.from(anchor.getLocation()),
            request.mode() == TpaMode.TO_TARGET ? "tpa:" + anchor.getUniqueId() : "tpahere:" + anchor.getUniqueId(), -1D)
            .whenComplete((success, failure) -> runSync(() -> {
                if (failure == null && Boolean.TRUE.equals(success)) return;
                if (mover.isOnline()) {
                    messages.send(mover, "tpa.teleport-failed", "<yellow>The accepted teleport could not be completed.</yellow>");
                }
                Player other = mover.getUniqueId().equals(target.getUniqueId()) ? requester : target;
                if (other.isOnline()) {
                    messages.send(other, "tpa.teleport-failed-other", "<yellow>The accepted teleport could not be completed.</yellow>");
                }
            }));
        return true;
    }

    boolean deny(Player target, String requesterName) {
        TpaRequest request = resolveIncoming(target, requesterName);
        if (request == null) return false;
        if (!outgoing.remove(request.requesterId(), request)) return false;
        denied.increment();
        Player requester = Bukkit.getPlayer(request.requesterId());
        messages.send(target, "tpa.denied", "<gray>Teleport request denied.</gray>");
        if (requester != null && requester.isOnline()) {
            messages.send(requester, "tpa.denied-requester", "<red>{player}</red> <gray>denied your teleport request.</gray>", Map.of("player", target.getName()));
        }
        return true;
    }

    boolean cancel(Player requester) {
        TpaRequest removed = outgoing.remove(requester.getUniqueId());
        if (removed == null) {
            messages.send(requester, "tpa.none-outgoing", "<yellow>You do not have an outgoing teleport request.</yellow>");
            return false;
        }
        messages.send(requester, "tpa.cancelled", "<gray>Teleport request cancelled.</gray>");
        Player target = Bukkit.getPlayer(removed.targetId());
        if (target != null && target.isOnline()) {
            messages.send(target, "tpa.cancelled-target", "<gray>{player} cancelled their teleport request.</gray>", Map.of("player", requester.getName()));
        }
        return true;
    }

    List<String> incomingNames(Player target) {
        expireOld();
        List<String> names = new ArrayList<>();
        for (TpaRequest request : outgoing.values()) {
            if (!request.targetId().equals(target.getUniqueId())) continue;
            Player requester = Bukkit.getPlayer(request.requesterId());
            if (requester != null && requester.isOnline() && target.canSee(requester)) names.add(requester.getName());
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    void onQuit(UUID playerId) {
        outgoing.remove(playerId);
        outgoing.entrySet().removeIf(entry -> entry.getValue().targetId().equals(playerId));
    }

    private TpaRequest resolveIncoming(Player target, String requesterName) {
        expireOld();
        List<TpaRequest> incoming = outgoing.values().stream()
            .filter(request -> request.targetId().equals(target.getUniqueId()))
            .sorted(Comparator.comparingLong(TpaRequest::createdAtMillis).reversed())
            .toList();
        if (requesterName != null && !requesterName.isBlank()) {
            for (TpaRequest request : incoming) {
                Player requester = Bukkit.getPlayer(request.requesterId());
                if (requester != null && target.canSee(requester) && requester.getName().equalsIgnoreCase(requesterName)) return request;
            }
            messages.send(target, "tpa.not-found", "<red>No matching teleport request was found.</red>");
            return null;
        }
        incoming = incoming.stream().filter(request -> {
            Player requester = Bukkit.getPlayer(request.requesterId());
            return requester != null && target.canSee(requester);
        }).toList();
        if (incoming.isEmpty()) {
            messages.send(target, "tpa.none-incoming", "<yellow>You have no pending teleport requests.</yellow>");
            return null;
        }
        if (incoming.size() > 1) {
            messages.send(target, "tpa.multiple", "<yellow>You have multiple requests. Use <white>/tpaccept &lt;player&gt;</white> or <white>/tpdeny &lt;player&gt;</white>.</yellow>");
            return null;
        }
        return incoming.getFirst();
    }

    private void expireOld() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, TpaRequest> entry : List.copyOf(outgoing.entrySet())) {
            TpaRequest request = entry.getValue();
            if (!request.expired(now) || !outgoing.remove(entry.getKey(), request)) continue;
            expired.increment();
            Player requester = Bukkit.getPlayer(request.requesterId());
            if (requester != null && requester.isOnline()) {
                messages.send(requester, "tpa.expired", "<yellow>Your teleport request expired.</yellow>");
            }
        }
    }

    private void runSync(Runnable action) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }

    void shutdown() {
        if (ticker != null) ticker.cancel();
        outgoing.clear();
    }

    int activeRequests() { return outgoing.size(); }
    long sentCount() { return sent.sum(); }
    long acceptedCount() { return accepted.sum(); }
    long deniedCount() { return denied.sum(); }
    long expiredCount() { return expired.sum(); }
}
