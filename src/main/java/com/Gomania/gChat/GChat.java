package com.Gomania.gChat;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GChat extends JavaPlugin implements Listener {

    private FileConfiguration config;
    private FileConfiguration messagesConfig;
    private File messagesFile;
    private final Map<UUID, String> playerChatModes = new HashMap<>();
    private final Map<UUID, Long> reloadCooldowns = new HashMap<>();
    private final Pattern gradientPattern = Pattern.compile("\\{#([0-9A-Fa-f]{6})>\\}(.*?)\\{#([0-9A-Fa-f]{6})<\\}");
    private boolean hasPlaceholderAPI = false;

    @Override
    public void onEnable() {
        // Создаем конфиги
        saveDefaultConfig();
        config = getConfig();
        setupMessages();
        reloadConfigValues();

        // Регистрируем события
        getServer().getPluginManager().registerEvents(this, this);

        // Проверяем наличие PlaceholderAPI
        hasPlaceholderAPI = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (hasPlaceholderAPI) {
            getLogger().info("PlaceholderAPI found! Placeholders will be supported.");
        } else {
            getLogger().warning("PlaceholderAPI not found! Basic placeholders only.");
        }

        getLogger().info("gChat plugin enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("gChat plugin disabled!");
    }

    private void setupMessages() {
        messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            try (InputStream in = getResource("messages.yml")) {
                if (in != null) {
                    Files.copy(in, messagesFile.toPath());
                } else {
                    getLogger().warning("Could not find messages.yml in resources!");
                }
            } catch (Exception e) {
                getLogger().warning("Failed to create messages.yml: " + e.getMessage());
            }
        }
        reloadMessages();
    }

    public void reloadMessages() {
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public String getMessage(String path) {
        String message = messagesConfig.getString(path);
        if (message == null) {
            return "§cMessage not found: " + path;
        }

        // Заменяем {prefix}
        String prefix = messagesConfig.getString("prefix", "&8[&6gChat&8]");
        message = message.replace("{prefix}", prefix);

        // Применяем все цветовые коды
        return message.replace("&", "§");
    }

    private void reloadConfigValues() {
        reloadConfig();
        config = getConfig();
        reloadMessages();
        // Обновляем статус PlaceholderAPI после перезагрузки
        hasPlaceholderAPI = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }
    
    private String formatMessage(Player player, String message, String chatMode) {
        String baseFormat;

        if (chatMode.equals("local")) {
            baseFormat = config.getString("local-chat.format", "&8[Локально]");
        } else {
            baseFormat = config.getString("global-chat.format", "&8[Глобально]");
        }

        // ИГНОРИРУЕМ OP ПРАВА - определяем формат только по группам
        String playerGroup = getPlayerGroup(player);
        String groupFormat = null;

        getLogger().info("Player " + player.getName() + " (OP: " + player.isOp() + ") detected group: " + playerGroup);

        // Сначала проверяем группу из LuckPerms
        if (playerGroup != null && config.isConfigurationSection("group-formats")) {
            groupFormat = config.getString("group-formats." + playerGroup);
            getLogger().info("Group format for " + playerGroup + ": " + groupFormat);
        }

        // Если не нашли по группе, проверяем пермишены как fallback
        if (groupFormat == null && config.isConfigurationSection("group-formats")) {
            for (String group : config.getConfigurationSection("group-formats").getKeys(false)) {
                if (player.hasPermission("gchat.group." + group)) {
                    groupFormat = config.getString("group-formats." + group);
                    getLogger().info("Found format via permission gchat.group." + group);
                    break;
                }
            }
        }

        String finalFormat = (groupFormat != null) ? groupFormat :
                config.getString("default-format", "%player_name% &7» %message%");

        getLogger().info("Raw format for " + player.getName() + ": " + finalFormat);

        // ЗАМЕНЯЕМ ПЛЕЙСХОЛДЕРЫ В ПРАВИЛЬНОМ ПОРЯДКЕ!

        // 1. Сначала заменяем %gchat_format% на базовый формат
        finalFormat = finalFormat.replace("%gchat_format%", baseFormat != null ? baseFormat : "");

        // 2. Затем заменяем другие плейсхолдеры (player_name, displayname и т.д.)
        finalFormat = replacePlaceholders(player, finalFormat);

        // 3. Заменяем %message% на обработанное сообщение (уже с градиентами и цветами)
        finalFormat = finalFormat.replace("%message%", message);

        // 4. Применяем цветовые коды
        finalFormat = finalFormat.replace("&", "§");

        // 5. Применяем градиенты ко всему формату
        finalFormat = applyGradients(finalFormat);

        // 6. Добавляем reset в конце чтобы избежать проблем с цветами
        finalFormat += "§r";

        getLogger().info("Final formatted message for " + player.getName() + ": " + finalFormat.replace("§", "&"));

        return finalFormat;
    }

    private String getPlayerGroup(Player player) {
        // УБИРАЕМ ПРОВЕРКУ НА OP - игнорируем OP права, ищем только группы

        // Получаем все пермишены игрока и ищем групповые
        try {
            // Этот метод может не работать на всех версиях, но стоит попробовать
            Class<?> permissibleClass = player.getClass();
            java.lang.reflect.Field permField = null;

            // Пытаемся получить доступ к пермишенам через рефлексию
            try {
                permField = permissibleClass.getDeclaredField("perm");
                permField.setAccessible(true);
                Object perm = permField.get(player);

                if (perm != null) {
                    // Пытаемся получить список пермишенов
                    java.lang.reflect.Field permissionsField = perm.getClass().getDeclaredField("permissions");
                    permissionsField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<String, Boolean> permissions = (Map<String, Boolean>) permissionsField.get(perm);

                    for (String permission : permissions.keySet()) {
                        if (permission.startsWith("group.") && permissions.get(permission)) {
                            String group = permission.substring(6); // Убираем "group."
                            if (!group.isEmpty()) {
                                getLogger().info("Found group permission: " + permission + " for player: " + player.getName());
                                return group;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Если рефлексия не работает, используем простой способ
                getLogger().warning("Reflection failed for " + player.getName() + ", using simple group detection: " + e.getMessage());
            }
        } catch (Exception e) {
            getLogger().warning("Error in group detection for " + player.getName() + ": " + e.getMessage());
        }

        // Fallback: простая проверка основных групп (ДАЖЕ ДЛЯ OP ИГРОКОВ)
        return getGroupFromSimpleCheck(player);
    }

    private String getGroupFromSimpleCheck(Player player) {
        // Проверяем группы даже для OP игроков
        String[] groupsPriority = {
                "owner", "admin", "administrator", "mod", "moderator",
                "builder", "vip", "premium", "donator", "helper", "staff",
                "elite", "legend", "titan", "youtuber", "streamer"
        };

        for (String group : groupsPriority) {
            if (player.hasPermission("group." + group)) {
                getLogger().info("Simple check: detected group " + group + " for player " + player.getName());
                return group;
            }
        }

        // Дополнительные проверки других форматов пермишенов
        for (String group : groupsPriority) {
            if (player.hasPermission("groups." + group) ||
                    player.hasPermission("rank." + group) ||
                    player.hasPermission("gchat.group." + group)) {
                getLogger().info("Simple check: detected group " + group + " for player " + player.getName() + " via alternative permission");
                return group;
            }
        }

        getLogger().info("No group detected for player " + player.getName() + " (including OP check)");
        return null;
    }

    private String getGroupFromPermissions(Player player) {
        // Стандартные названия групп для проверки
        String[] commonGroups = {"admin", "administrator", "mod", "moderator", "builder",
                "vip", "premium", "donator", "helper", "staff"};

        for (String group : commonGroups) {
            // LuckPerms дает пермишены вида group.<groupname>
            if (player.hasPermission("group." + group)) {
                return group;
            }
            // Некоторые плагины используют groups.<groupname>
            if (player.hasPermission("groups." + group)) {
                return group;
            }
            // Прямые пермишены
            if (player.hasPermission("gchat.group." + group)) {
                return group;
            }
        }

        return null;
    }

    private String replacePlaceholders(Player player, String text) {
        // Сначала базовые замены
        text = text.replace("%player_name%", player.getName())
                .replace("%player_displayname%", player.getDisplayName());

        // Затем PlaceholderAPI если доступен
        if (hasPlaceholderAPI) {
            try {
                Class<?> papiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                Object result = papiClass.getMethod("setPlaceholders", Player.class, String.class)
                        .invoke(null, player, text);
                if (result instanceof String) {
                    text = (String) result;
                }
            } catch (Exception e) {
                getLogger().warning("Failed to set PlaceholderAPI placeholders: " + e.getMessage());
            }
        }

        return text;
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

                // Конвертируем цвет в Minecraft HEX формат
                gradient.append(colorToMinecraftHex(currentColor));
                gradient.append(text.charAt(i));

                // Сбрасываем цвет после каждого символа чтобы избежать наложения
                if (i < length - 1) {
                    gradient.append("§r");
                }
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
        for (char c : hex.substring(1).toCharArray()) {
            minecraftHex.append("§").append(c);
        }
        return minecraftHex.toString();
    }

    private java.awt.Color interpolateColor(java.awt.Color start, java.awt.Color end, float ratio) {
        int red = (int) (start.getRed() + ratio * (end.getRed() - start.getRed()));
        int green = (int) (start.getGreen() + ratio * (end.getGreen() - start.getGreen()));
        int blue = (int) (start.getBlue() + ratio * (end.getBlue() - start.getBlue()));

        red = Math.max(0, Math.min(255, red));
        green = Math.max(0, Math.min(255, green));
        blue = Math.max(0, Math.min(255, blue));

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
                // НЕ отправляем сообщение отправителю здесь - он получит его отдельно
                if (!player.getUniqueId().equals(sender.getUniqueId())) {
                    player.sendMessage(formattedMessage);
                    recipients++;
                }
            }
        }

        // Отправляем сообщение отправителю ОДИН раз
        sender.sendMessage(formattedMessage);

        // Если никого нет в радиусе (кроме самого отправителя), отправляем сообщение о тишине
        if (recipients == 0 && config.getBoolean("local-chat.show-silent-message", true)) {
            sender.sendMessage(getMessage("local-chat-silent"));
        }
    }

    private void sendGlobalMessage(String formattedMessage) {
        if (!config.getBoolean("global-chat.enabled", true)) {
            return;
        }

        // Отправляем сообщение всем игрокам ОДИН раз
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(formattedMessage);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("gchat")) {
            return handleGChatCommand(sender, args);
        }

        if (command.getName().equalsIgnoreCase("g")) {
            return handleGlobalCommand(sender, args);
        }

        if (command.getName().equalsIgnoreCase("l")) {
            return handleLocalCommand(sender, args);
        }

        return false;
    }

    private boolean handleGChatCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                if (!sender.hasPermission("gchat.reload")) {
                    sender.sendMessage(getMessage("no-permission"));
                    return true;
                }

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

            default:
                sender.sendMessage(getMessage("unknown-command"));
                break;
        }
        return true;
    }

    private boolean handleGlobalCommand(CommandSender sender, String[] args) {
        // НОВАЯ РЕАЛИЗАЦИЯ
        if (!(sender instanceof Player)) {
            sender.sendMessage(getMessage("players-only"));
            return true;
        }

        Player player = (Player) sender;
        // ВСЕГДА только меняем режим, игнорируем аргументы
        playerChatModes.put(player.getUniqueId(), "global");
        player.sendMessage(getMessage("chat-mode-global"));
        return true;
    }

    private boolean handleLocalCommand(CommandSender sender, String[] args) {
        // НОВАЯ РЕАЛИЗАЦИЯ
        if (!(sender instanceof Player)) {
            sender.sendMessage(getMessage("players-only"));
            return true;
        }

        Player player = (Player) sender;
        // ВСЕГДА только меняем режим, игнорируем аргументы
        playerChatModes.put(player.getUniqueId(), "local");
        player.sendMessage(getMessage("chat-mode-local"));
        return true;
    }

    private boolean canUseColors(Player player) {
        return player.hasPermission("gchat.color") || player.hasPermission("gchat.*");
    }

    private boolean canUseGradients(Player player) {
        return player.hasPermission("gchat.gradient") || player.hasPermission("gchat.*");
    }

    private String processMessageColors(Player player, String message) {
        if (canUseColors(player)) {
            // Разрешаем стандартные цветовые коды (&a, &b, и т.д.)
            message = message.replace("&", "§");
        } else {
            // Убираем цветовые коды если нет прав
            message = message.replace("&0", "").replace("&1", "").replace("&2", "")
                    .replace("&3", "").replace("&4", "").replace("&5", "")
                    .replace("&6", "").replace("&7", "").replace("&8", "")
                    .replace("&9", "").replace("&a", "").replace("&b", "")
                    .replace("&c", "").replace("&d", "").replace("&e", "")
                    .replace("&f", "").replace("&k", "").replace("&l", "")
                    .replace("&m", "").replace("&n", "").replace("&o", "")
                    .replace("&r", "");
        }
        return message;
    }

    private String processMessageGradients(Player player, String message) {
        if (canUseGradients(player)) {
            // Разрешаем градиенты если есть права
            message = applyGradients(message);
        } else {
            // Убираем градиенты если нет прав
            message = removeGradients(message);
        }
        return message;
    }

    private String removeGradients(String text) {
        // Удаляем все градиентные теги {#RRGGBB>} и {#RRGGBB<}
        return text.replaceAll("\\{#[0-9A-Fa-f]{6}>\\}", "")
                .replaceAll("\\{#[0-9A-Fa-f]{6}<\\}", "");
    }

    @EventHandler
    public void onAsyncPlayerChatEvent(AsyncPlayerChatEvent event) {
        if (!config.getBoolean("enabled", true)) {
            return;
        }

        Player player = event.getPlayer();
        String originalMessage = event.getMessage();
        String chatMode = playerChatModes.getOrDefault(player.getUniqueId(),
                config.getString("default-chat-mode", "local"));

        // Проверка на глобальное сообщение с !
        boolean isGlobalMessage = originalMessage.startsWith("!");
        if (isGlobalMessage) {
            originalMessage = originalMessage.substring(1).trim();
            chatMode = "global";
        }

        // Отмена стандартного сообщения
        event.setCancelled(true);

        // Обработка цветов и градиентов в сообщении (с проверкой прав)
        String processedMessage = processMessageColors(player, originalMessage);
        processedMessage = processMessageGradients(player, processedMessage);

        getLogger().info("Original message: " + originalMessage);
        getLogger().info("Processed message: " + processedMessage);

        // Форматирование сообщения (передаем уже обработанное сообщение)
        String formattedMessage = formatMessage(player, processedMessage, chatMode);

        // Отправка сообщения ТОЛЬКО ОДИН РАЗ
        if (chatMode.equals("local") && !isGlobalMessage) {
            sendLocalMessage(player, formattedMessage);
        } else {
            sendGlobalMessage(formattedMessage);
        }

        // Логирование в консоль
        if (config.getBoolean("log-to-console", false)) {
            getLogger().info("Final output: " + formattedMessage.replace("§", "&"));
        }
    }



    private void sendHelp(CommandSender sender) {
        if (messagesConfig.contains("help")) {
            for (String line : messagesConfig.getStringList("help")) {
                sender.sendMessage(line.replace("&", "§"));
            }
        } else {
            // Fallback help
            sender.sendMessage("§6§l=== gChat Help ===");
            sender.sendMessage("§e/gchat reload §7- Перезагрузить конфигурацию");
            sender.sendMessage("§e/gchat help §7- Показать эту помощь");
            sender.sendMessage("§e/g §7- Переключиться на глобальный чат");
            sender.sendMessage("§e/g <сообщение> §7- Отправить глобальное сообщение");
            sender.sendMessage("§e/l §7- Переключиться на локальный чат");
            sender.sendMessage("§e/l <сообщение> §7- Отправить локальное сообщение");
            sender.sendMessage("§e!<сообщение> §7- Отправить разовое глобальное сообщение");
            sender.sendMessage("§6Градиенты в конфиге: §7{#RRGGBB>}Text{#RRGGBB<}");
        }
    }
}