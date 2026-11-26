package ru.mirtime.souls;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MirTimeSouls extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private static final String GUI_TITLE = "&#0055ff&l⚡ &#00E5FF&lСобиратель Душ &8| &#00FF66Обмен";
    private static final int INPUT_SLOT = 22;
    private static final int CONFIRM_SLOT = 40;
    private static final int CLOSE_SLOT = 42;

    private NamespacedKey soulOwnerKey;
    private NamespacedKey soulMarkerKey;
    private final Map<String, Long> killCooldowns = new HashMap<>();

    public static class SoulHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
        public void setInventory(Inventory inv) { this.inv = inv; }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        soulOwnerKey = new NamespacedKey(this, "soul_killer_uuid");
        soulMarkerKey = new NamespacedKey(this, "is_soul_item");

        if (getCommand("souls") != null) {
            getCommand("souls").setExecutor(this);
            getCommand("souls").setTabCompleter(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("MirTimeSouls v1.0.0 (Привязка душ к убийце + обмен до 32 шт.) запущен!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0) {
            String sub = args[0].toLowerCase();
            if (sub.equals("reload")) {
                if (!sender.hasPermission("mirtime.souls.admin")) {
                    sender.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lДУШИ &8» &cУ вас нет прав!"));
                    return true;
                }
                reloadConfig();
                sender.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lДУШИ &8» &#00FF66Конфигурация config.yml перезагружена!"));
                return true;
            }
            if (sub.equals("give")) {
                if (!sender.hasPermission("mirtime.souls.admin")) {
                    sender.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lДУШИ &8» &cУ вас нет прав!"));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lДУШИ &8» &fИспользование: &#00FF66/souls give <игрок> <кол-во>"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lДУШИ &8» &cИгрок не в сети!"));
                    return true;
                }
                int amount;
                try {
                    amount = Math.max(1, Math.min(32, Integer.parseInt(args[2])));
                } catch (NumberFormatException e) {
                    sender.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lДУШИ &8» &cКоличество должно быть числом от 1 до 32!"));
                    return true;
                }
                ItemStack soul = createSoulItem(target, "Тестовая жертва", "Админ-выдача");
                soul.setAmount(amount);
                target.getInventory().addItem(soul);
                sender.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lДУШИ &8» &#00FF66Выдано &l" + amount + " &#00FF66душ игроку &#00E5FF" + target.getName()));
                return true;
            }
        }

        if (sender instanceof Player p) {
            openSoulsMenu(p);
        }
        return true;
    }

    public ItemStack createSoulItem(Player killer, String victimName, String weaponName) {
        ItemStack is = new ItemStack(Material.ECHO_SHARD);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            String dateStr = ZonedDateTime.now(ZoneId.of("Europe/Moscow"))
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));

            meta.setDisplayName(color("&#0055ff&l✦ &#00E5FF&lДуша игрока &f" + victimName));
            meta.setLore(List.of(
                    color("&#0055ff&m                              "),
                    color(" &#0055ff● &fЖертва: &#00E5FF" + victimName),
                    color(" &#0055ff● &fУбийца: &#00FF66" + killer.getName()),
                    color(" &#0055ff● &fОружие: &f" + weaponName),
                    color(" &#0055ff● &fДата: &7" + dateStr + " МСК"),
                    color("&#0055ff&m                              "),
                    color("&7Подобрать и сдать может только &#00FF66" + killer.getName()),
                    color("&#00E5FF➥ Отнесите Собирателю душ (/souls)")
            ));
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(soulMarkerKey, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(soulOwnerKey, PersistentDataType.STRING, killer.getUniqueId().toString());
            is.setItemMeta(meta);
        }
        return is;
    }

    private boolean isValidSoulFor(ItemStack is, UUID playerUuid) {
        if (is == null || !is.hasItemMeta()) return false;
        var pdc = is.getItemMeta().getPersistentDataContainer();
        if (!pdc.has(soulMarkerKey, PersistentDataType.BYTE)) return false;
        String owner = pdc.get(soulOwnerKey, PersistentDataType.STRING);
        return owner != null && owner.equals(playerUuid.toString());
    }

    private boolean isAnySoul(ItemStack is) {
        if (is == null || !is.hasItemMeta()) return false;
        return is.getItemMeta().getPersistentDataContainer().has(soulMarkerKey, PersistentDataType.BYTE);
    }
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;

        int cdSec = getConfig().getInt("settings.kill-cooldown-seconds", 600);
        String pairKey = killer.getUniqueId() + ":" + victim.getUniqueId();
        long now = System.currentTimeMillis();
        if (cdSec > 0 && killCooldowns.containsKey(pairKey)) {
            long last = killCooldowns.get(pairKey);
            if ((now - last) < cdSec * 1000L) {
                killer.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lДУШИ &8» &7Вы недавно уже забирали душу игрока &f" + victim.getName() + "&7."));
                return;
            }
        }
        killCooldowns.put(pairKey, now);

        ItemStack hand = killer.getInventory().getItemInMainHand();
        String weaponName = formatWeaponName(hand);
        ItemStack soul = createSoulItem(killer, victim.getName(), weaponName);

        victim.getWorld().dropItemNaturally(victim.getLocation(), soul);
        killer.playSound(killer.getLocation(), Sound.PARTICLE_SOUL_ESCAPE, 1.2f, 1.0f);
        killer.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lДУШИ &8» &#00FF66Вы выбили душу игрока &#00E5FF" + victim.getName() + "&#00FF66! Подберите её."));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickupSoul(EntityPickupItemEvent e) {
        ItemStack stack = e.getItem().getItemStack();
        if (!isAnySoul(stack)) return;

        if (!(e.getEntity() instanceof Player p)) {
            e.setCancelled(true);
            return;
        }
        if (!isValidSoulFor(stack, p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    public void openSoulsMenu(Player p) {
        SoulHolder holder = new SoulHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, color(GUI_TITLE));
        holder.setInventory(inv);

        ItemStack blue = makeItem(Material.BLUE_STAINED_GLASS_PANE, "&#0055ff▪", null);
        ItemStack cyan = makeItem(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "&#00E5FF▫", null);
        ItemStack dark = makeItem(Material.BLACK_STAINED_GLASS_PANE, " ", null);

        for (int i = 0; i < 54; i++) inv.setItem(i, dark);
        for (int s : new int[]{0, 1, 7, 8, 9, 17, 18, 26, 27, 35, 36, 44, 45, 46, 52, 53}) inv.setItem(s, blue);
        for (int s : new int[]{2, 3, 5, 6, 12, 13, 14, 21, 23, 30, 31, 32, 47, 48, 50, 51}) inv.setItem(s, cyan);

        inv.setItem(INPUT_SLOT, null);

        inv.setItem(4, makeItem(Material.SOUL_LANTERN, "&#0055ff&l⚡ &#00E5FF&lЛАВКА СОБИРАТЕЛЯ ДУШ", List.of(
                "&7Положите добытые в PvP &#00E5FFДуши игроков",
                "&7в центральную ячейку (максимум &#00FF6632 шт.&7):",
                "",
                " &#00FF66● 1 – 9 душ: &fслучайный обычный предмет",
                " &#00E5FF● 10 – 29 душ: &fслабый кастомный артефакт",
                " &#FFD700● 30 – 32 души: &fмощный кастомный артефакт!",
                "",
                "&cБольше 32 душ за один обмен сдать нельзя."
        )));

        inv.setItem(CLOSE_SLOT, makeItem(Material.BARRIER, "&c&l[✖] Закрыть лавку", List.of(
                "&7Положенные души вернутся в инвентарь."
        )));

        updateTradeButton(inv);
        p.openInventory(inv);
    }

    private void updateTradeButton(Inventory inv) {
        ItemStack in = inv.getItem(INPUT_SLOT);
        int count = (in != null && isAnySoul(in)) ? Math.min(in.getAmount(), 32) : 0;

        String tierDesc;
        if (count == 0) {
            tierDesc = "&7Положите от &#00FF661 &7до &#00FF6632 &7душ в центр";
        } else if (count < 10) {
            tierDesc = "&#00FF66Обычный обмен (1–9 душ)";
        } else if (count < 30) {
            tierDesc = "&#00E5FFРедкий обмен (10–29 душ)";
        } else {
            tierDesc = "&#FFD700&lЭпический обмен (30–32 души)";
        }

        inv.setItem(CONFIRM_SLOT, makeItem(
                count > 0 ? Material.RESPAWN_ANCHOR : Material.GRAY_DYE,
                count > 0 ? "&#0055ff&l⚡ &#00FF66&lОБМЕНЯТЬ ДУШИ (" + count + "/32)" : "&c&l[⏳] ПОЛОЖИТЕ ДУШИ В ЦЕНТР",
                List.of(
                        "&7Текущий уровень награды:",
                        " " + tierDesc,
                        "",
                        count > 0 ? "&#00FF66➥ Нажмите&f, чтобы получить случайный приз!" : "&7Принимаются только ваши личные души."
                )
        ));
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!(e.getView().getTopInventory().getHolder() instanceof SoulHolder)) return;

        Inventory top = e.getView().getTopInventory();
        int raw = e.getRawSlot();

        if (raw >= 54 || raw == INPUT_SLOT) {
            ItemStack current = e.getCurrentItem();
            ItemStack cursor = e.getCursor();

            if ((raw == INPUT_SLOT && cursor != null && !cursor.getType().isAir() && !isValidSoulFor(cursor, p.getUniqueId()))
                    || (raw >= 54 && e.isShiftClick() && current != null && !current.getType().isAir() && !isValidSoulFor(current, p.getUniqueId()))) {
                e.setCancelled(true);
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lДУШИ &8» &cСюда можно класть только ваши личные Души игроков!"));
                return;
            }

            Bukkit.getScheduler().runTask(this, () -> {
                ItemStack inside = top.getItem(INPUT_SLOT);
                if (inside != null && inside.getAmount() > 32) {
                    int extra = inside.getAmount() - 32;
                    inside.setAmount(32);
                    ItemStack back = inside.clone();
                    back.setAmount(extra);
                    HashMap<Integer, ItemStack> left = p.getInventory().addItem(back);
                    for (ItemStack drop : left.values()) {
                        p.getWorld().dropItemNaturally(p.getLocation(), drop);
                    }
                    p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lДУШИ &8» &eМаксимум за один обмен — 32 души! Лишние (" + extra + " шт.) возвращены."));
                }
                updateTradeButton(top);
            });
            return;
        }

        e.setCancelled(true);
        if (raw == CLOSE_SLOT) {
            p.closeInventory();
            return;
        }

        if (raw == CONFIRM_SLOT) {
            ItemStack inside = top.getItem(INPUT_SLOT);
            if (inside == null || inside.getType().isAir() || !isValidSoulFor(inside, p.getUniqueId())) {
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lДУШИ &8» &cПоложите в центральную ячейку от 1 до 32 ваших душ!"));
                return;
            }

            int amount = Math.min(inside.getAmount(), 32);
            String sectionKey = (amount >= 30) ? "tier_30_32" : (amount >= 10 ? "tier_10_29" : "tier_1_9");
            List<Map<?, ?>> list = getConfig().getMapList("rewards." + sectionKey);

            if (list.isEmpty()) {
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lДУШИ &8» &cСписок наград (" + sectionKey + ") в config.yml пуст!"));
                return;
            }

            top.setItem(INPUT_SLOT, null);
            p.closeInventory();

            Map<?, ?> chosen = list.get(new Random().nextInt(list.size()));
            String rewardCmd = chosen.get("cmd") != null ? String.valueOf(chosen.get("cmd")) : "";
            String rewardDisplay = chosen.get("display") != null ? String.valueOf(chosen.get("display")) : "Награда";

            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.4f);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.1f);
            p.getWorld().spawnParticle(Particle.SOUL, p.getLocation().add(0, 1, 0), 45, 0.6, 0.7, 0.6, 0.08);

            if (!rewardCmd.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rewardCmd.replace("%player%", p.getName()));
            }
            p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lДУШИ &8» &#00FF66&lОБМЕН ЗАВЕРШЁН! &fВы сдали &#00E5FF" + amount + " душ &fи получили: " + rewardDisplay));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof SoulHolder)) return;
        for (int raw : e.getRawSlots()) {
            if (raw < 54 && raw != INPUT_SLOT) {
                e.setCancelled(true);
                return;
            }
        }
        Bukkit.getScheduler().runTask(this, () -> updateTradeButton(e.getView().getTopInventory()));
    }

    @EventHandler
    public void onMenuClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!(e.getInventory().getHolder() instanceof SoulHolder)) return;

        ItemStack item = e.getInventory().getItem(INPUT_SLOT);
        if (item != null && !item.getType().isAir()) {
            HashMap<Integer, ItemStack> left = p.getInventory().addItem(item);
            for (ItemStack drop : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), drop);
            }
        }
    }

    private String formatWeaponName(ItemStack item) {
        if (item == null || item.getType().isAir()) return "Кулак";
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }
        return switch (item.getType()) {
            case NETHERITE_SWORD -> "Незеритовый меч";
            case DIAMOND_SWORD -> "Алмазный меч";
            case IRON_SWORD -> "Железный меч";
            case NETHERITE_AXE -> "Незеритовый топор";
            case DIAMOND_AXE -> "Алмазный топор";
            case BOW -> "Лук";
            case CROSSBOW -> "Арбалет";
            case TRIDENT -> "Трезубец";
            case MACE -> "Булава";
            default -> item.getType().name();
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("mirtime.souls.admin")) {
            return List.of("give", "reload");
        }
        return Collections.emptyList();
    }

    private ItemStack makeItem(Material mat, String name, List<String> lore) {
        ItemStack is = new ItemStack(mat);
        ItemMeta m = is.getItemMeta();
        if (m != null) {
            m.setDisplayName(color(name));
            if (lore != null) {
                List<String> l = new ArrayList<>();
                for (String s : lore) l.add(color(s));
                m.setLore(l);
            }
            m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            is.setItemMeta(m);
        }
        return is;
    }

    public static String color(String text) {
        if (text == null) return "";
        Matcher m = Pattern.compile("&#([A-Fa-f0-9]{6})").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray()) rep.append('§').append(c);
            m.appendReplacement(sb, rep.toString());
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }
}
