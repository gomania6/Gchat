package com.Gomania.gChat;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class GChat extends JavaPlugin implements Listener, TabCompleter {

    private FileConfiguration config;
    private FileConfiguration messagesConfig;
    private File messagesFile;

    private final Map<UUID, String> playerChatModes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> reloadCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> messageCooldowns = new ConcurrentHashMap<>();

    private final Pattern gradientPattern = Pattern.compile("\\{#([0-9A-Fa-f]{6})>\\}(.*?)\\{#([0-9A-Fa-f]{6})<\\}");
    private final Pattern colorPattern = Pattern.compile("&[0-9a-fk-or]", Pattern.CASE_INSENSITIVE);

    private boolean hasPlaceholderAPI = false;
    private boolean hasLuckPerms = false;
    private Object luckPermsAPI = null;

    // Новые поля
    private List<String> blockedCommands = new ArrayList<>();
    private String joinMessage;
    private String quitMessage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();
        setupMessages();
        reloadConfigValues();

        getServer().getPluginManager().registerEvents(this, this);

        // Проверка зависимостей
        hasPlaceholderAPI = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        hasLuckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms") != null;

        if (hasPlaceholderAPI) getLogger().info("PlaceholderAPI found! Placeholders enabled.");
        if (hasLuckPerms) {
            getLogger().info("LuckPerms found! Group support enabled.");
            try {
                Class<?> luckPermsClass = Class.forName("net.luckperms.api.LuckPerms");
                luckPermsAPI = Bukkit.getServicesManager().getRegistration(luckPermsClass).getProvider();
            } catch (Exception e) {
                getLogger().warning("Failed to initialize LuckPerms API: " + e.getMessage());
                hasLuckPerms = false;
            }
        }

        getLogger().info("gChat plugin enabled!");
    }

    @Override
    public void onDisable() {
        playerChatModes.clear();
        reloadCooldowns.clear();
        messageCooldowns.clear();
        getLogger().info("gChat plugin disabled!");
    }

    private void setupMessages() {
        String language = config.getString("language", "ru").toLowerCase();
        messagesFile = new File(getDataFolder(), "messages_" + language + ".yml");

        if (!messagesFile.exists()) {
            try (InputStream in = getResource("messages_" + language + ".yml")) {
                if (in != null) Files.copy(in, messagesFile.toPath());
            } catch (Exception e) {
                getLogger().warning("Failed to create messages file: " + e.getMessage());
            }
        }
        reloadMessages();
    }

    public void reloadMessages() {
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public String getMessage(String path) {
        String message = messagesConfig.getString(path);
        if (message == null) return "§cMessage not found: " + path;

        String prefix = messagesConfig.getString("prefix", "&8[&6gChat&8]");
        message = message.replace("{prefix}", prefix);
        return message.replace("&", "§");
    }

    private void reloadConfigValues() {
        // Создаём дефолтный config.yml, если нет
        if (!new File(getDataFolder(), "config.yml").exists()) {
            saveDefaultConfig();
        }

        reloadConfig();
        config = getConfig();
        reloadMessages();

        // Новые настройки
        blockedCommands = config.getStringList("blocked-commands");
        joinMessage = config.getString("join-message", "");
        quitMessage = config.getString("quit-message", "");

        // Проверка зависимостей
        hasPlaceholderAPI = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        hasLuckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms") != null;

        if (hasLuckPerms) {
            try {
                Class<?> luckPermsClass = Class.forName("net.luckperms.api.LuckPerms");
                luckPermsAPI = Bukkit.getServicesManager().getRegistration(luckPermsClass).getProvider();
            } catch (Exception e) {
                getLogger().warning("Failed to initialize LuckPerms API: " + e.getMessage());
                hasLuckPerms = false;
            }
        }
    }


    // ================== Чат ==================
    private String formatMessage(Player player, String message, String chatMode) {
        String baseFormat = chatMode.equals("local")
                ? config.getString("local-chat.format", "&8[Локально]")
                : config.getString("global-chat.format", "&8[Глобально]");

        String playerGroup = getPlayerGroup(player);
        String groupFormat = null;

        // проверяем только то, что реально есть в group-formats
        if (playerGroup != null
                && config.isConfigurationSection("group-formats")
                && config.getConfigurationSection("group-formats").contains(playerGroup)) {
            groupFormat = config.getString("group-formats." + playerGroup);
        }

        // всё остальное -> default-format
        String finalFormat = (groupFormat != null)
                ? groupFormat
                : config.getString("default-format", "%player_name% &7» %message%");

        if (finalFormat == null || finalFormat.isEmpty())
            finalFormat = "%player_name% » %message%";

        finalFormat = finalFormat.replace("%gchat_format%", baseFormat != null ? baseFormat : "");
        finalFormat = replacePlaceholders(player, finalFormat);
        finalFormat = finalFormat.replace("%message%", message);
        finalFormat = finalFormat.replace("&", "§");
        finalFormat = applyGradients(finalFormat);
        finalFormat += "§r";

        return finalFormat;
    }

    private String getPlayerGroup(Player player) {
        if (!hasLuckPerms || luckPermsAPI == null) return null;

        try {
            // Получаем User из LuckPerms
            Class<?> apiClass = luckPermsAPI.getClass();
            Object userManager = apiClass.getMethod("getUserManager").invoke(luckPermsAPI);
            Object user = userManager.getClass().getMethod("getUser", UUID.class)
                    .invoke(userManager, player.getUniqueId());

            if (user == null) return null;

            // Получаем список групп из конфига
            Set<String> configGroups = new HashSet<>();
            if (config.isConfigurationSection("group-formats")) {
                configGroups.addAll(config.getConfigurationSection("group-formats").getKeys(false)
                        .stream().map(String::toLowerCase).collect(Collectors.toSet()));
            }

            // Основная группа игрока
            String primaryGroup = (String) user.getClass().getMethod("getPrimaryGroup").invoke(user);
            if (primaryGroup != null && configGroups.contains(primaryGroup.toLowerCase())) {
                return primaryGroup.toLowerCase();
            }

            // Если основной группы нет в конфиге, ищем через наследование
            Object nodes = user.getClass().getMethod("getNodes").invoke(user);
            if (nodes instanceof Iterable) {
                for (Object node : (Iterable<?>) nodes) {
                    if (node.getClass().getSimpleName().equals("InheritanceNode")) {
                        String groupName = (String) node.getClass().getMethod("getGroupName").invoke(node);
                        if (groupName != null && configGroups.contains(groupName.toLowerCase())) {
                            return groupName.toLowerCase();
                        }
                    }
                }
            }

        } catch (Exception e) {
            getLogger().warning("Не удалось получить группу игрока через LuckPerms: " + e.getMessage());
        }

        // Если ни одна группа не совпала с конфигом — вернём null, чтобы использовался default-format
        return null;
    }

    private String replacePlaceholders(Player player, String text) {
        if (text == null || text.isEmpty()) return "";

        // Базовые замены
        text = text.replace("%player_name%", player.getName())
                .replace("%player_world%", player.getWorld().getName());

        // ==========================
        // Подставляем суффикс LuckPerms
        if (hasLuckPerms && text.contains("%luckperms_suffix%")) {
            try {
                Class<?> apiClass = luckPermsAPI.getClass();
                Object userManager = apiClass.getMethod("getUserManager").invoke(luckPermsAPI);
                Object user = userManager.getClass().getMethod("getUser", UUID.class)
                        .invoke(userManager, player.getUniqueId());

                if (user != null) {
                    Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
                    Object metaData = cachedData.getClass().getMethod("getMetaData").invoke(cachedData);
                    String suffix = (String) metaData.getClass().getMethod("getSuffix").invoke(metaData);

                    if (suffix != null && !suffix.isEmpty()) {
                        suffix = applyHexColors(suffix); // ✅ преобразуем &#RRGGBB → §x§R§R...
                        text = text.replace("%luckperms_suffix%", suffix);
                    } else {
                        text = text.replace("%luckperms_suffix%", "");
                    }
                } else {
                    text = text.replace("%luckperms_suffix%", "");
                }
            } catch (Exception e) {
                getLogger().warning("Не удалось получить суффикс LuckPerms: " + e.getMessage());
                text = text.replace("%luckperms_suffix%", "");
            }
        }

        // ==========================
        // PlaceholderAPI (если установлен)
        if (hasPlaceholderAPI) {
            try {
                Class<?> papiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                Object result = papiClass.getMethod("setPlaceholders", Player.class, String.class)
                        .invoke(null, player, text);
                if (result instanceof String) text = (String) result;
            } catch (Exception e) {
                getLogger().warning("Failed to set PlaceholderAPI placeholders: " + e.getMessage());
            }
        }

        return text;
    }

    /**
     * Конвертирует Hex-цвета формата &#RRGGBB в §x§R§R...
     */
    private String applyHexColors(String text) {
        if (text == null) return "";

        Pattern pattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
        Matcher matcher = pattern.matcher(text);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append("§").append(c);
            }
            matcher.appendReplacement(buffer, replacement.toString());
        }
        matcher.appendTail(buffer);

        return buffer.toString();
    }

    private String applyGradients(String text) {
        if (text == null || text.isEmpty()) return text;

        Matcher matcher = gradientPattern.matcher(text);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String startColor = matcher.group(1);
            String content = matcher.group(2);
            String endColor = matcher.group(3);

            String gradientText = createGradient(content, startColor, endColor);
            matcher.appendReplacement(result, Matcher.quoteReplacement(gradientText));
        }
        matcher.appendTail(result);

        return result.toString();
    }



    private String createGradient(String text, String startHex, String endHex) {
        if (text == null || text.isEmpty()) return "";

        try {
            java.awt.Color startColor = java.awt.Color.decode("#" + startHex);
            java.awt.Color endColor = java.awt.Color.decode("#" + endHex);

            StringBuilder gradient = new StringBuilder();
            int length = text.length();

            for (int i = 0; i < length; i++) {
                float ratio = (float) i / Math.max(1, length - 1);
                java.awt.Color currentColor = interpolateColor(startColor, endColor, ratio);

                gradient.append(colorToMinecraftHex(currentColor));
                gradient.append(text.charAt(i));
            }

            return gradient.toString();
        } catch (Exception e) {
            getLogger().warning("Failed to create gradient: " + e.getMessage());
            return text;
        }
    }

    private String colorToMinecraftHex(java.awt.Color color) {
        String hex = String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
        StringBuilder minecraftHex = new StringBuilder("§x");
        for (char c : hex.substring(1).toCharArray()) minecraftHex.append("§").append(c);
        return minecraftHex.toString();
    }

    private java.awt.Color interpolateColor(java.awt.Color start, java.awt.Color end, float ratio) {
        int red = Math.max(0, Math.min(255, (int) (start.getRed() + ratio * (end.getRed() - start.getRed()))));
        int green = Math.max(0, Math.min(255, (int) (start.getGreen() + ratio * (end.getGreen() - start.getGreen()))));
        int blue = Math.max(0, Math.min(255, (int) (start.getBlue() + ratio * (end.getBlue() - start.getBlue()))));

        return new java.awt.Color(red, green, blue);
    }

    private void sendLocalMessage(Player sender, String formattedMessage) {
        if (!config.getBoolean("local-chat.enabled", true)) {
            sender.sendMessage(getMessage("local-chat-disabled"));
            return;
        }

        int radius = config.getInt("local-chat.radius", 50);
        double radiusSquared = radius * radius;
        int recipients = 0;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(sender.getWorld()) &&
                    player.getLocation().distanceSquared(sender.getLocation()) <= radiusSquared) {
                player.sendMessage(formattedMessage);
                if (!player.getUniqueId().equals(sender.getUniqueId())) recipients++;
            }
        }

        if (recipients == 0 && config.getBoolean("local-chat.show-silent-message", true)) {
            sender.sendMessage(getMessage("local-chat-silent"));
        }
    }

    private void sendGlobalMessage(String formattedMessage) {
        if (!config.getBoolean("global-chat.enabled", true)) return;

        for (Player player : Bukkit.getOnlinePlayers()) player.sendMessage(formattedMessage);
    }

    // ================== Команды ==================
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("gchat")) return handleGChatCommand(sender, args);
        if (command.getName().equalsIgnoreCase("g")) return handleGlobalCommand(sender, args);
        if (command.getName().equalsIgnoreCase("l")) return handleLocalCommand(sender, args);
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("gchat") && args.length == 1) {
            List<String> completions = new ArrayList<>();
            if (sender.hasPermission("gchat.reload")) completions.add("reload");
            if (sender.hasPermission("gchat.debug")) completions.add("debug");
            completions.add("help");
            return completions.stream().filter(c -> c.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private boolean handleGChatCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                if (!sender.hasPermission("gchat.reload")) { sender.sendMessage(getMessage("no-permission")); return true; }
                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    long currentTime = System.currentTimeMillis();
                    long lastReload = reloadCooldowns.getOrDefault(player.getUniqueId(), 0L);
                    int cooldown = config.getInt("reload-cooldown", 5) * 1000;
                    if (currentTime - lastReload < cooldown) {
                        long remaining = (cooldown - (currentTime - lastReload)) / 1000;
                        sender.sendMessage(getMessage("reload-cooldown").replace("{time}", String.valueOf(remaining)));
                        return true;
                    }
                    reloadCooldowns.put(player.getUniqueId(), currentTime);
                }
                reloadConfigValues();
                sender.sendMessage(getMessage("config-reloaded"));
                break;

            case "help":
                sendHelp(sender);
                break;

            case "debug":
                if (!sender.hasPermission("gchat.debug")) { sender.sendMessage(getMessage("no-permission")); return true; }
                return handleDebugCommand(sender, args);

            default:
                sender.sendMessage(getMessage("unknown-command"));
                break;
        }
        return true;
    }

    private boolean handleDebugCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(getMessage("players-only"));
            return true;
        }
        Player player = (Player) sender;

        player.sendMessage("§6=== Group Debug ===");
        player.sendMessage("§7OP status: §e" + player.isOp());
        player.sendMessage("§7Username: §e" + player.getName());

        if (!hasLuckPerms || luckPermsAPI == null) {
            player.sendMessage("§cLuckPerms не найден или не инициализирован");
            return true;
        }

        try {
            // Получаем пользователя через LuckPerms
            Class<?> apiClass = luckPermsAPI.getClass();
            Object userManager = apiClass.getMethod("getUserManager").invoke(luckPermsAPI);
            Object user = userManager.getClass().getMethod("getUser", UUID.class)
                    .invoke(userManager, player.getUniqueId());

            if (user == null) {
                player.sendMessage("§cИгрок не найден в LuckPerms");
                return true;
            }

            // Основная группа
            String primaryGroup = (String) user.getClass().getMethod("getPrimaryGroup").invoke(user);
            if (primaryGroup != null && !primaryGroup.equalsIgnoreCase("default")) {
                player.sendMessage("§7Primary Group: §e" + primaryGroup);
            } else {
                player.sendMessage("§7Primary Group: §cdefault");
            }

            // Получаем группы, которые реально есть в конфиге group-formats
            Set<String> configGroups = new HashSet<>();
            if (config.isConfigurationSection("group-formats")) {
                configGroups.addAll(config.getConfigurationSection("group-formats").getKeys(false));
            }

            Object nodes = user.getClass().getMethod("getNodes").invoke(user);
            if (nodes instanceof Iterable) {
                player.sendMessage("§7=== Groups in config ===");
                for (Object node : (Iterable<?>) nodes) {
                    if (node.getClass().getSimpleName().equals("InheritanceNode")) {
                        String groupName = (String) node.getClass().getMethod("getGroupName").invoke(node);
                        if (groupName != null && configGroups.contains(groupName.toLowerCase())) {
                            player.sendMessage("§e- " + groupName);
                        }
                    }
                }
            }

            // Суффикс
            Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
            Object metaData = cachedData.getClass().getMethod("getMetaData").invoke(cachedData);
            String suffix = (String) metaData.getClass().getMethod("getSuffix").invoke(metaData);
            player.sendMessage("§7Suffix: §f" + (suffix != null ? suffix : "none"));

        } catch (Exception e) {
            player.sendMessage("§cОшибка при получении данных LuckPerms: " + e.getMessage());
        }

        return true;
    }

    private boolean handleGlobalCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (args.length > 0) processChatMessage(player, String.join(" ", args), "global");
        else { playerChatModes.put(player.getUniqueId(), "global"); player.sendMessage(getMessage("chat-mode-global")); }

        return true;
    }

    private boolean handleLocalCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (args.length > 0) processChatMessage(player, String.join(" ", args), "local");
        else { playerChatModes.put(player.getUniqueId(), "local"); player.sendMessage(getMessage("chat-mode-local")); }

        return true;
    }

    private boolean canUseColors(Player player) { return player.hasPermission("gchat.color"); }
    private boolean canUseGradients(Player player) { return player.hasPermission("gchat.gradient"); }

    private String processMessageColors(Player player, String message) {
        if (canUseColors(player)) return message.replace("&", "§");
        return colorPattern.matcher(message).replaceAll("");
    }

    private String processMessageGradients(Player player, String message) {
        if (canUseGradients(player)) return applyGradients(message);
        return removeGradients(message);
    }

    private String removeGradients(String text) {
        return text.replaceAll("\\{#[0-9A-Fa-f]{6}>\\}", "")
                .replaceAll("\\{#[0-9A-Fa-f]{6}<\\}", "");
    }

    private boolean checkCooldown(Player player) {
        if (!config.getBoolean("message-cooldown.enabled", false)) return true;
        if (player.hasPermission("gchat.bypass.cooldown")) return true;

        long currentTime = System.currentTimeMillis();
        long lastMessage = messageCooldowns.getOrDefault(player.getUniqueId(), 0L);
        int cooldown = config.getInt("message-cooldown.seconds", 3) * 1000;

        if (currentTime - lastMessage < cooldown) {
            long remaining = (cooldown - (currentTime - lastMessage)) / 1000;
            player.sendMessage(getMessage("message-cooldown").replace("{time}", String.valueOf(remaining)));
            return false;
        }

        messageCooldowns.put(player.getUniqueId(), currentTime);
        return true;
    }

    private boolean checkMessageLength(Player player, String message) {
        int maxLength = config.getInt("max-message-length", 256);
        if (message.length() > maxLength && !player.hasPermission("gchat.bypass.length")) {
            player.sendMessage(getMessage("message-too-long").replace("{max}", String.valueOf(maxLength)));
            return false;
        }
        return true;
    }

    private String filterBadWords(Player player, String message) {
        if (!config.getBoolean("filter.enabled", false) || player.hasPermission("gchat.bypass.filter")) return message;

        List<String> badWords = config.getStringList("filter.words");
        String resultMessage = message;

        for (String word : badWords) {
            if (resultMessage.toLowerCase().contains(word.toLowerCase())) {
                if (config.getBoolean("filter.block-message", true)) {
                    player.sendMessage(getMessage("message-blocked"));
                    return null;
                } else {
                    String replacement = String.join("", Collections.nCopies(word.length(), "*"));
                    resultMessage = resultMessage.replaceAll("(?i)" + Pattern.quote(word), replacement);
                }
            }
        }

        return resultMessage;
    }

    private boolean hasChatPermission(Player player, String chatType) {
        if (player.hasPermission("gchat.bypass")) return true;
        return chatType.equalsIgnoreCase("global") || chatType.equalsIgnoreCase("local");
    }

    private void processChatMessage(Player player, String originalMessage, String chatMode) {
        if (!hasChatPermission(player, chatMode)) return;
        if (!checkCooldown(player)) return;
        if (!checkMessageLength(player, originalMessage)) return;

        String filteredMessage = filterBadWords(player, originalMessage);
        if (filteredMessage == null) return;

        String processedMessage = processMessageColors(player, filteredMessage);
        processedMessage = processMessageGradients(player, processedMessage);

        String formattedMessage = formatMessage(player, processedMessage, chatMode);

        if (chatMode.equals("local")) sendLocalMessage(player, formattedMessage);
        else sendGlobalMessage(formattedMessage);

        if (config.getBoolean("log-to-console", false)) {
            String cleanMessage = originalMessage.replace("&", "").replace("§", "");
            String mode = chatMode.toUpperCase();
            Bukkit.getLogger().info("[" + mode + "] " + player.getName() + ": " + cleanMessage);
        }
    }

    // ================== События ==================
    @EventHandler
    public void onAsyncPlayerChatEvent(AsyncPlayerChatEvent event) {
        if (!config.getBoolean("enabled", true)) return;

        Player player = event.getPlayer();
        String originalMessage = event.getMessage();
        String chatMode = playerChatModes.getOrDefault(player.getUniqueId(),
                config.getString("default-chat-mode", "local"));

        if (originalMessage.startsWith("!")) {
            originalMessage = originalMessage.substring(1).trim();
            chatMode = "global";
        } else {
            chatMode = chatMode.toLowerCase();
        }

        // создаём финальные копии для лямбды
        final Player finalPlayer = player;
        final String finalMessage = originalMessage;
        final String finalChatMode = chatMode;

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> processChatMessage(finalPlayer, finalMessage, finalChatMode));
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().toLowerCase();
        Player player = event.getPlayer();

        for (String cmd : blockedCommands) {
            if (message.startsWith(cmd.toLowerCase())) {
                player.sendMessage(getMessage("no-permission"));
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (joinMessage != null && !joinMessage.isEmpty())
            event.setJoinMessage(joinMessage.replace("%player_name%", player.getName()).replace("&", "§"));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (quitMessage != null && !quitMessage.isEmpty())
            event.setQuitMessage(quitMessage.replace("%player_name%", player.getName()).replace("&", "§"));
    }

    private void sendHelp(CommandSender sender) {
        if (messagesConfig.contains("help")) {
            for (String line : messagesConfig.getStringList("help"))
                sender.sendMessage(line.replace("&", "§"));
        } else {
            sender.sendMessage("§6§l=== gChat Help ===");
            sender.sendMessage("§e/gchat reload §7- Перезагрузить конфигурацию");
            sender.sendMessage("§e/gchat help §7- Показать эту помощь");
            sender.sendMessage("§e/gchat debug §7- Debug информация");
            sender.sendMessage("§e/g §7- Переключиться на глобальный чат");
            sender.sendMessage("§e/g <сообщение> §7- Отправить глобальное сообщение");
            sender.sendMessage("§e/l §7- Переключиться на локальный чат");
            sender.sendMessage("§e/l <сообщение> §7- Отправить локальное сообщение");
            sender.sendMessage("§e!<сообщение> §7- Отправить разовое глобальное сообщение");
            sender.sendMessage("§6Градиенты: §7{#RRGGBB>}Text{#RRGGBB<}");

            if (sender.hasPermission("gchat.use")) sender.sendMessage("§a✓ Вы имеете доступ к чату");
            else sender.sendMessage("§c✗ У вас нет доступа к чату");
        }
    }
}
