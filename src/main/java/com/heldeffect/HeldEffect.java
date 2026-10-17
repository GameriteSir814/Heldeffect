package com.heldeffect;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class HeldEffect extends JavaPlugin implements TabExecutor {

    private NamespacedKey key;
    private final Map<UUID, Set<PotionEffectType>> applied = new HashMap<>();

    @Override
    public void onEnable() {
        key = new NamespacedKey(this, "held_effects");
        getCommand("he").setExecutor(this);
        getCommand("he").setTabCompleter(this);
        new BukkitRunnable() {
            @Override public void run() { tick(); }
        }.runTaskTimer(this, 10L, 10L);
    }

    // ---- storage: "speed:1,jump_boost:2" (amplifier, 0-based) ----
    private Map<PotionEffectType, Integer> read(ItemStack item) {
        Map<PotionEffectType, Integer> out = new LinkedHashMap<>();
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return out;
        String s = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(",")) {
            String[] kv = part.split(":");
            if (kv.length != 2) continue;
            PotionEffectType t = lookup(kv[0]);
            if (t == null) continue;
            try { out.put(t, Integer.parseInt(kv[1])); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private void write(ItemStack item, Map<PotionEffectType, Integer> map) {
        ItemMeta meta = item.getItemMeta();
        if (map.isEmpty()) {
            meta.getPersistentDataContainer().remove(key);
        } else {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<PotionEffectType, Integer> e : map.entrySet()) {
                if (sb.length() > 0) sb.append(',');
                sb.append(e.getKey().getKey().getKey()).append(':').append(e.getValue());
            }
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, sb.toString());
        }
        item.setItemMeta(meta);
    }

    private PotionEffectType lookup(String name) {
        String n = name.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_');
        NamespacedKey k = NamespacedKey.minecraft(n);
        return Registry.EFFECT.get(k);
    }

    // ---- effect application ----
    private void tick() {
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            Map<PotionEffectType, Integer> held = read(p.getInventory().getItemInMainHand());
            Set<PotionEffectType> prev = applied.getOrDefault(p.getUniqueId(), new HashSet<>());

            for (PotionEffectType t : new ArrayList<>(prev)) {
                if (!held.containsKey(t)) {
                    PotionEffect cur = p.getPotionEffect(t);
                    if (cur != null && cur.getDuration() <= 100) p.removePotionEffect(t);
                    prev.remove(t);
                }
            }
            for (Map.Entry<PotionEffectType, Integer> e : held.entrySet()) {
                p.addPotionEffect(new PotionEffect(e.getKey(), 60, e.getValue(), true, false, true));
                prev.add(e.getKey());
            }
            if (prev.isEmpty()) applied.remove(p.getUniqueId());
            else applied.put(p.getUniqueId(), prev);
        }
    }

    // ---- command ----
    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
        if (!p.isOp()) { p.sendMessage(Component.text("Operators only.")); return true; }
        if (a.length == 0) { usage(p); return true; }

        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) { p.sendMessage(Component.text("Hold an item first.")); return true; }
        Map<PotionEffectType, Integer> map = read(hand);

        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (a.length < 2) { usage(p); return true; }
                PotionEffectType t = lookup(a[1]);
                if (t == null) { p.sendMessage(Component.text("Unknown effect '" + a[1] + "'. Try tab-complete.")); return true; }
                int level = 1;
                if (a.length >= 3) {
                    try { level = Integer.parseInt(a[2]); } catch (NumberFormatException ex) {
                        p.sendMessage(Component.text("Level must be a number (1 = normal).")); return true; }
                }
                level = Math.max(1, Math.min(level, 255));
                map.put(t, level - 1);
                write(hand, map);
                p.getInventory().setItemInMainHand(hand); // write back or the tag is lost
                p.sendMessage(Component.text("Held item now gives " + t.getKey().getKey() + " " + level + "."));
            }
            case "remove" -> {
                if (a.length >= 2) {
                    PotionEffectType t = lookup(a[1]);
                    if (t == null || map.remove(t) == null) { p.sendMessage(Component.text("That item doesn't have that effect.")); return true; }
                } else map.clear();
                write(hand, map);
                p.getInventory().setItemInMainHand(hand);
                p.sendMessage(Component.text("Removed."));
            }
            case "list" -> {
                if (map.isEmpty()) p.sendMessage(Component.text("No effects on this item."));
                else map.forEach((t, amp) -> p.sendMessage(Component.text(t.getKey().getKey() + " " + (amp + 1))));
            }
            default -> usage(p);
        }
        return true;
    }

    private void usage(Player p) {
        p.sendMessage(Component.text("/he add <effect> [level]  |  /he remove [effect]  |  /he list"));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) { out.addAll(List.of("add", "remove", "list")); }
        else if (a.length == 2 && (a[0].equalsIgnoreCase("add") || a[0].equalsIgnoreCase("remove"))) {
            for (PotionEffectType t : Registry.EFFECT) out.add(t.getKey().getKey());
        }
        String prefix = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.startsWith(prefix));
        return out;
    }
}
