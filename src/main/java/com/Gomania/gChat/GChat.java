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

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.UUID;

public class GChat extends JavaPlugin implements Listener, TabCompleter {

    private FileConfiguration config;
    private FileConfiguration messagesConfig;
    private FileConfiguration broadcastConfig;
    private File messagesFile;
    private File broadcastFile;

    private final Map<UUID, String> playerChatModes = new HashMap<>();

    // ================== Cooldown Managers ==================
    private final CooldownManager messageCooldown = new CooldownManager();
    private final CooldownManager reloadCooldown = new CooldownManager();

    public class CooldownManager {

        private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

        /**
         * Проверяет, может ли игрок выполнить действие.
         * Если cooldown истек или нет записи — возвращает true и обновляет таймер.
         */
        public boolean check(UUID uuid, int seconds) {
            long now = System.currentTimeMillis();
            Long lastTime = cooldowns.get(uuid);
            if (lastTime == null || now - lastTime >= seconds * 1000L) {
                cooldowns.put(uuid, now);
                return true;
            }
            return false;
        }

        /**
         * Возвращает оставшееся время в секундах
         */
        public long getRemaining(UUID uuid, int seconds) {
            Long lastTime = cooldowns.get(uuid);
            if (lastTime == null) return 0;
            long elapsed = System.currentTimeMillis() - lastTime;
            long remaining = seconds * 1000L - elapsed;
            return remaining > 0 ? remaining / 1000L : 0;
        }

        /**
         * Удаляет запись о cooldown
         */
        public void clear() {
            cooldowns.clear();
        }
    }

    // ================== LuckPerms Cache ==================
    private LuckPermsCache lpCache;

    private final Pattern gradientPattern = Pattern.compile("\\{#([0-9A-Fa-f]{6})>\\}(.*?)\\{#([0-9A-Fa-f]{6})<\\}");
    private final Pattern colorPattern = Pattern.compile("&[0-9a-fk-or]", Pattern.CASE_INSENSITIVE);
    private final Pattern urlPattern = Pattern.compile("(?:(https?)://)?([-\\w_.]+\\.[a-z]{2,})(/\\S*)?", Pattern.CASE_INSENSITIVE);

    private boolean hasPlaceholderAPI = false;
    private boolean hasLuckPerms = false;
    private Object luckPermsAPI = null;

    private List<String> blockedCommands = new ArrayList<>();
    private String joinMessage;
    private String quitMessage;
    private boolean handleJoinQuitMessages = true;
    private boolean blockCommandsEnabled = true;

    private int broadcastTaskId = -1;
    private int currentBroadcastIndex = 0;

    @Override
    public void onEnable() {
        // Создаём папку плагина, если не существует
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        // Сохраняем default config.yml если его нет
        saveDefaultConfig();
        config = getConfig();

        setupMessages();
        setupBroadcastConfig();
        reloadConfigValues();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("msg").setExecutor(new MsgCommand(this));

        checkDependencies();
        setupBroadcast();

        getLogger().info("gChat plugin enabled!");
    }

    @Override
    public void onDisable() {
        playerChatModes.clear();
        reloadCooldown.clear();
        messageCooldown.clear();

        if (lpCache != null) {
            lpCache.clear();
        }

        if (broadcastTaskId != -1) {
            Bukkit.getScheduler().cancelTask(broadcastTaskId);
        }

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

    private void setupBroadcastConfig() {
        broadcastFile = new File(getDataFolder(), "broadcast.yml");

        if (!broadcastFile.exists()) {
            try (InputStream in = getResource("broadcast.yml")) {
                if (in != null) {
                    Files.copy(in, broadcastFile.toPath());
                } else {
                    // Создаем default broadcast конфиг
                    broadcastConfig = YamlConfiguration.loadConfiguration(broadcastFile);
                    broadcastConfig.set("enabled", true);
                    broadcastConfig.set("interval", 300);
                    broadcastConfig.set("header", "&6&l=== СЕРВЕРНОЕ ОБЪЯВЛЕНИЕ ===");
                    broadcastConfig.set("footer", "&7Спасибо за игру на нашем сервере!");

                    List<String> messages = new ArrayList<>();
                    messages.add("&aНе забудьте прочитать правила сервера!");
                    messages.add("&bПрисоединяйтесь к нашему Discord: https://discord.gg/example");
                    messages.add("&cВнимание! Запрещено использование читов!");

                    broadcastConfig.set("messages", messages);
                    broadcastConfig.save(broadcastFile);
                }
            } catch (Exception e) {
                getLogger().warning("Failed to create broadcast config: " + e.getMessage());
            }
        }
        reloadBroadcastConfig();
    }

    public void reloadMessages() {
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public void reloadBroadcastConfig() {
        broadcastConfig = YamlConfiguration.loadConfiguration(broadcastFile);
    }

    public String getMessage(String path) {
        String message = messagesConfig.getString(path);
        if (message == null) return "§cMessage not found: " + path;

        String prefix = messagesConfig.getString("prefix", "&8[&6gChat&8]");
        message = message.replace("{prefix}", prefix);
        return ChatColorUtils.translateColors(message, true, true);
    }

    public void reloadConfigValues() {
        reloadConfig();
        config = getConfig();

        blockedCommands = config.getStringList("blocked-commands");
        joinMessage = config.getString("join-message", "");
        quitMessage = config.getString("quit-message", "");
        handleJoinQuitMessages = config.getBoolean("handle-join-quit-messages", true);
        blockCommandsEnabled = config.getBoolean("block-commands-enabled", true);

        reloadMessages();
        reloadBroadcastConfig();

        // Не перезапускаем broadcast при reload, только при onEnable
    }

    private void checkDependencies() {
        hasPlaceholderAPI = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        hasLuckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms") != null;

        if (hasPlaceholderAPI) getLogger().info("PlaceholderAPI found! Placeholders enabled.");
        if (hasLuckPerms) {
            getLogger().info("LuckPerms found! Group support enabled.");
            try {
                Class<?> luckPermsClass = Class.forName("net.luckperms.api.LuckPerms");
                luckPermsAPI = Bukkit.getServicesManager().getRegistration(luckPermsClass).getProvider();
                lpCache = new LuckPermsCache(luckPermsAPI);
            } catch (Exception e) {
                getLogger().warning("Failed to initialize LuckPerms API: " + e.getMessage());
                hasLuckPerms = false;
            }
        }
    }

    private void setupBroadcast() {
        // Cancel existing task if running
        if (broadcastTaskId != -1) {
            Bukkit.getScheduler().cancelTask(broadcastTaskId);
            broadcastTaskId = -1;
        }

        // Start new broadcast task if enabled
        if (broadcastConfig.getBoolean("enabled", false)) {
            List<String> messages = broadcastConfig.getStringList("messages");
            if (messages.isEmpty()) return;

            int interval = broadcastConfig.getInt("interval", 300);

            broadcastTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
                if (messages.isEmpty()) return;

                String message = messages.get(currentBroadcastIndex);
                sendFormattedBroadcast(message);

                currentBroadcastIndex = (currentBroadcastIndex + 1) % messages.size();
            }, interval * 20L, interval * 20L);

            getLogger().info("Broadcast system started with interval: " + interval + " seconds");
        }
    }

    private void sendFormattedBroadcast(String message) {
        if (message == null || message.isEmpty()) return;

        // Apply header if exists
        String header = broadcastConfig.getString("header", "");
        if (header != null && !header.isEmpty()) {
            sendClickableMessage(ChatColorUtils.translateColors(header, true, true));
        }

        // Send main message with clickable links
        sendClickableMessage(ChatColorUtils.translateColors(message, true, true));

        // Apply footer if exists
        String footer = broadcastConfig.getString("footer", "");
        if (footer != null && !footer.isEmpty()) {
            sendClickableMessage(ChatColorUtils.translateColors(footer, true, true));
        }
    }

    private void sendClickableMessage(String message) {
        if (message == null || message.isEmpty()) return;

        // Если нет онлайн игроков, просто выводим в консоль
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            getLogger().info("[BROADCAST] " + message.replace("§", "&"));
            return;
        }

        Matcher matcher = urlPattern.matcher(message);
        List<TextComponent> components = new ArrayList<>();
        int lastEnd = 0;

        while (matcher.find()) {
            // Add text before the URL
            if (matcher.start() > lastEnd) {
                String before = message.substring(lastEnd, matcher.start());
                components.add(new TextComponent(TextComponent.fromLegacyText(before)));
            }

            // Create clickable URL component
            String url = matcher.group();
            String displayUrl = url;

            // Ensure URL has protocol
            if (matcher.group(1) == null) {
                url = "https://" + url;
            }

            TextComponent urlComponent = new TextComponent(TextComponent.fromLegacyText(displayUrl));
            urlComponent.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
            urlComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new Text("§aНажмите чтобы открыть ссылку")));

            components.add(urlComponent);
            lastEnd = matcher.end();
        }

        // Add remaining text
        if (lastEnd < message.length()) {
            String remaining = message.substring(lastEnd);
            components.add(new TextComponent(TextComponent.fromLegacyText(remaining)));
        }

        // Send message to all players
        TextComponent finalMessage = new TextComponent("");
        for (TextComponent component : components) {
            finalMessage.addExtra(component);
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            // Применяем PlaceholderAPI для каждого игрока индивидуально
            String playerSpecificMessage = applyPlaceholderAPI(player, finalMessage.toLegacyText());

            if (!playerSpecificMessage.equals(finalMessage.toLegacyText())) {
                // Если PlaceholderAPI изменил сообщение, отправляем как обычный текст
                player.sendMessage(playerSpecificMessage);
            } else {
                // Иначе отправляем кликабельное сообщение
                player.spigot().sendMessage(finalMessage);
            }
        }
    }

    private String applyPlaceholderAPI(Player player, String message) {
        if (!hasPlaceholderAPI || message == null || message.isEmpty()) {
            return message;
        }

        try {
            Class<?> papiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Object result = papiClass.getMethod("setPlaceholders", Player.class, String.class)
                    .invoke(null, player, message);
            if (result instanceof String) {
                return (String) result;
            }
        } catch (Exception e) {
            getLogger().warning("Failed to set PlaceholderAPI placeholders for player " + player.getName() + ": " + e.getMessage());
        }

        return message;
    }

    // ================== Чат ==================
    private String formatMessage(Player player, String message, String chatMode) {
        String baseFormat = chatMode.equals("local")
                ? config.getString("local-chat.format", "&8[Локально]")
                : config.getString("global-chat.format", "&8[Глобально]");

        String playerGroup = getPlayerGroup(player);
        String groupFormat = null;

        if (playerGroup != null
                && config.isConfigurationSection("group-formats")
                && config.getConfigurationSection("group-formats").contains(playerGroup)) {
            groupFormat = config.getString("group-formats." + playerGroup);
        }

        String finalFormat = (groupFormat != null)
                ? groupFormat
                : config.getString("default-format", "%player_name% &7» %message%");

        if (finalFormat == null || finalFormat.isEmpty())
            finalFormat = "%player_name% » %message%";

        finalFormat = finalFormat.replace("%gchat_format%", baseFormat != null ? baseFormat : "");
        finalFormat = replacePlaceholders(player, finalFormat);
        finalFormat = finalFormat.replace("%message%", message);
        finalFormat = ChatColorUtils.translateColors(finalFormat, true, true);
        finalFormat += "§r";

        return finalFormat;
    }

    private String getPlayerGroup(Player player) {
        if (!hasLuckPerms || luckPermsAPI == null) return null;

        Set<String> validGroups = new HashSet<>();
        if (config.isConfigurationSection("group-formats")) {
            validGroups.addAll(config.getConfigurationSection("group-formats").getKeys(false)
                    .stream().map(String::toLowerCase).collect(Collectors.toSet()));
        }

        return lpCache.getPlayerGroup(player.getUniqueId(), validGroups);
    }

    private String replacePlaceholders(Player player, String text) {
        if (text == null || text.isEmpty()) return "";

        text = text.replace("%player_name%", player.getName())
                .replace("%player_world%", player.getWorld().getName());

        if (hasLuckPerms && text.contains("%luckperms_suffix%")) {
            String suffix = lpCache.getPlayerSuffix(player.getUniqueId());
            text = text.replace("%luckperms_suffix%", suffix);
        }

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

    private void sendLocalMessage(Player sender, String formattedMessage) {
        boolean localChatEnabled = config.getBoolean("local-chat.enabled", true);
        boolean showDisabledMessage = config.getBoolean("local-chat.show-disabled-local-chat", true);

        if (!localChatEnabled) {
            if (showDisabledMessage) {
                sender.sendMessage(getMessage("local-chat-disabled"));
            }
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
        if (command.getName().equalsIgnoreCase("broadcast")) return handleBroadcastCommand(sender, args);
        return false;
    }

    private String filterLinks(Player player, String message) {
        if (!config.getBoolean("filter-links.enabled", true)) return message;
        if (player.hasPermission("gchat.link.bypass")) return message;

        Matcher matcher = urlPattern.matcher(message);
        if (matcher.find()) {
            return getMessage("illegal-link");
        }
        return message;
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
                if (!sender.hasPermission("gchat.reload")) {
                    sender.sendMessage(getMessage("no-permission"));
                    return true;
                }

                reloadPluginConfig(); // <-- вызываем здесь
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
            Set<String> validGroups = new HashSet<>();
            if (config.isConfigurationSection("group-formats")) {
                validGroups.addAll(config.getConfigurationSection("group-formats").getKeys(false));
            }

            String group = lpCache.getPlayerGroup(player.getUniqueId(), validGroups);
            String suffix = lpCache.getPlayerSuffix(player.getUniqueId());

            player.sendMessage("§7Primary Group: §e" + (group != null ? group : "default"));
            player.sendMessage("§7Suffix: §f" + (suffix != null && !suffix.isEmpty() ? suffix : "none"));

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

    private boolean checkCooldown(Player player) {
        if (!config.getBoolean("message-cooldown.enabled", false)) return true;
        if (player.hasPermission("gchat.bypass.cooldown")) return true;

        int cooldownSeconds = config.getInt("message-cooldown.seconds", 3);
        if (!messageCooldown.check(player.getUniqueId(), cooldownSeconds)) {
            long remaining = messageCooldown.getRemaining(player.getUniqueId(), cooldownSeconds);
            player.sendMessage(getMessage("message-cooldown").replace("{time}", String.valueOf(remaining)));
            return false;
        }

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

        // 1. Фильтруем плохие слова
        String filteredMessage = filterBadWords(player, originalMessage);
        if (filteredMessage == null) return;

        // 2. Фильтруем ссылки
        filteredMessage = filterLinks(player, filteredMessage);
        if (filteredMessage == null) return;

        // 3. Проверяем длину сообщения
        if (!checkMessageLength(player, filteredMessage)) return;

        // 4. Обрабатываем цвета и градиенты
        String processedMessage = ChatColorUtils.translateColors(
                filteredMessage,
                this.canUseColors(player),
                this.canUseGradients(player)
        );

        // 5. Форматируем окончательно
        String formattedMessage = formatMessage(player, processedMessage, chatMode);

        // 6. Отправляем
        if (chatMode.equals("local")) sendLocalMessage(player, formattedMessage);
        else sendGlobalMessage(formattedMessage);

        // 7. Логирование
        if (config.getBoolean("log-to-console", false)) {
            String cleanMessage = filteredMessage.replace("&", "").replace("§", "");
            Bukkit.getLogger().info("[" + chatMode.toUpperCase() + "] " + player.getName() + ": " + cleanMessage);
        }
    }

    private static boolean canUseColors(Player player) {
        return player.hasPermission("gchat.color");
    }

    private static boolean canUseGradients(Player player) {
        return player.hasPermission("gchat.gradient");
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

        final Player finalPlayer = player;
        final String finalMessage = originalMessage;
        final String finalChatMode = chatMode;

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> processChatMessage(finalPlayer, finalMessage, finalChatMode));
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!blockCommandsEnabled) return;

        Player player = event.getPlayer();
        String message = event.getMessage().toLowerCase();

        // Проверяем права на обход блокировки
        if (player.isOp() || player.hasPermission("gchat.bcomm.bypass")) {
            return;
        }

        for (String cmd : blockedCommands) {
            // Убираем слэш для сравнения
            String cleanCmd = cmd.startsWith("/") ? cmd.substring(1) : cmd;
            String cleanMessage = message.startsWith("/") ? message.substring(1) : message;

            // Проверяем, начинается ли команда с заблокированной
            if (cleanMessage.startsWith(cleanCmd.toLowerCase() + " ") ||
                    cleanMessage.equals(cleanCmd.toLowerCase())) {
                player.sendMessage(getMessage("no-permission"));
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!handleJoinQuitMessages) return;

        Player player = event.getPlayer();
        if (joinMessage != null && !joinMessage.isEmpty())
            event.setJoinMessage(ChatColorUtils.translateColors(joinMessage.replace("%player_name%", player.getName()), true, true));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!handleJoinQuitMessages) return;

        Player player = event.getPlayer();
        if (quitMessage != null && !quitMessage.isEmpty())
            event.setQuitMessage(ChatColorUtils.translateColors(quitMessage.replace("%player_name%", player.getName()), true, true));

        // Очищаем кэш при выходе игрока
        if (lpCache != null) {
            lpCache.remove(player.getUniqueId());
        }
    }

    private boolean handleBroadcastCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("gchat.broadcast")) {
            sender.sendMessage(getMessage("no-permission"));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§6Использование: /broadcast <номер|next|reload>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                reloadBroadcastConfig();
                setupBroadcast();
                sender.sendMessage("§aBroadcast конфигурация перезагружена!");
                break;

            case "next":
                sendNextBroadcastMessage();
                sender.sendMessage("§aСледующее broadcast сообщение отправлено!");
                break;

            default:
                try {
                    int index = Integer.parseInt(args[0]) - 1; // пользователь видит 1-based индексы
                    List<String> messages = broadcastConfig.getStringList("messages");
                    if (index < 0 || index >= messages.size()) {
                        sender.sendMessage("§cНеверный номер сообщения. Доступно " + messages.size() + " сообщений.");
                        return true;
                    }
                    sendFormattedBroadcast(messages.get(index));
                    sender.sendMessage("§aBroadcast сообщение #" + (index + 1) + " отправлено!");
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cНеверный аргумент. Используйте номер, next или reload.");
                }
                break;
        }

        return true;
    }

    private void sendNextBroadcastMessage() {
        List<String> messages = broadcastConfig.getStringList("messages");
        if (messages.isEmpty()) return;

        String message = messages.get(currentBroadcastIndex);
        sendFormattedBroadcast(message);
        currentBroadcastIndex = (currentBroadcastIndex + 1) % messages.size();
    }

    private void sendHelp(CommandSender sender) {
        if (messagesConfig.contains("help")) {
            for (String line : messagesConfig.getStringList("help"))
                sender.sendMessage(ChatColorUtils.translateColors(line, true, true));
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

    public void reloadPluginConfig() {
        // Если config.yml нет — создаём
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveDefaultConfig();
        }
        reloadConfig();
        config = getConfig();
    }

    // ================== Вспомогательные классы ==================

    /**
     * Кэш LuckPerms для оптимизации запросов
     */
    public static class LuckPermsCache {
        private final Map<UUID, CachedPlayerData> cache = new ConcurrentHashMap<>();
        private final Object luckPermsAPI;

        public LuckPermsCache(Object luckPermsAPI) {
            this.luckPermsAPI = luckPermsAPI;
        }

        public static class CachedPlayerData {
            String primaryGroup;
            String suffix;
            long timestamp; // время последнего обновления
        }

        public String getPlayerGroup(UUID uuid, Set<String> validGroups) {
            long now = System.currentTimeMillis();
            CachedPlayerData cached = cache.get(uuid);

            if (cached != null && now - cached.timestamp < 60000) { // 60 секунд кэш
                return cached.primaryGroup;
            }

            try {
                Class<?> apiClass = luckPermsAPI.getClass();
                Object userManager = apiClass.getMethod("getUserManager").invoke(luckPermsAPI);
                Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, uuid);
                if (user == null) return null;

                String primaryGroup = (String) user.getClass().getMethod("getPrimaryGroup").invoke(user);
                if (primaryGroup != null && validGroups.contains(primaryGroup.toLowerCase())) {
                    CachedPlayerData newData = new CachedPlayerData();
                    newData.primaryGroup = primaryGroup.toLowerCase();

                    Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
                    Object metaData = cachedData.getClass().getMethod("getMetaData").invoke(cachedData);
                    String suffix = (String) metaData.getClass().getMethod("getSuffix").invoke(metaData);
                    newData.suffix = suffix != null ? ChatColorUtils.translateColors(suffix, true, true) : "";

                    newData.timestamp = now;
                    cache.put(uuid, newData);
                    return newData.primaryGroup;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }

        public String getPlayerSuffix(UUID uuid) {
            CachedPlayerData cached = cache.get(uuid);
            return cached != null ? cached.suffix : "";
        }

        public void remove(UUID uuid) {
            cache.remove(uuid);
        }

        public void clear() {
            cache.clear();
        }
    }

    /**
     * Утилиты для работы с цветами и градиентами
     */
    public class ChatColorUtils {

        // Регулярки
        private static final Pattern GRADIENT_PATTERN = Pattern.compile("\\{#([0-9A-Fa-f]{6})>\\}(.*?)\\{#([0-9A-Fa-f]{6})<\\}");
        private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

        /**
         * Основная функция для перевода текста в Minecraft формат
         * Поддерживает: & цвета, hex &#RRGGBB, градиенты {#RRGGBB>}Text{#RRGGBB<}
         */
        public static String translateColors(String text, boolean allowColors, boolean allowGradients) {
            if (text == null) return "";
            if (allowColors) text = replaceStandardColors(text);
            text = applyHexColors(text); // hex всегда можно
            if (allowGradients) text = applyGradients(text);
            return text;
        }


        /** Стандартные цвета & → § */
        private static String replaceStandardColors(String text) {
            return text.replace("&", "§");
        }

        /** Hex цвета &#RRGGBB → §x§R§R§G§G§B§B */
        private static String applyHexColors(String text) {
            if (text == null) return "";
            Matcher matcher = HEX_PATTERN.matcher(text);
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

        /** Градиенты {#RRGGBB>}Text{#RRGGBB<} */
        public static String applyGradients(String text) {
            if (text == null) return text;
            Matcher matcher = GRADIENT_PATTERN.matcher(text);
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

        private static String createGradient(String text, String startHex, String endHex) {
            try {
                java.awt.Color startColor = java.awt.Color.decode("#" + startHex);
                java.awt.Color endColor = java.awt.Color.decode("#" + endHex);
                StringBuilder gradient = new StringBuilder();
                int length = text.length();

                for (int i = 0; i < length; i++) {
                    float ratio = (float) i / Math.max(1, length - 1);
                    java.awt.Color current = interpolateColor(startColor, endColor, ratio);
                    gradient.append(colorToMinecraftHex(current)).append(text.charAt(i));
                }
                return gradient.toString();
            } catch (Exception e) {
                return text;
            }
        }

        private static java.awt.Color interpolateColor(java.awt.Color start, java.awt.Color end, float ratio) {
            int r = Math.max(0, Math.min(255, (int) (start.getRed() + ratio * (end.getRed() - start.getRed()))));
            int g = Math.max(0, Math.min(255, (int) (start.getGreen() + ratio * (end.getGreen() - start.getGreen()))));
            int b = Math.max(0, Math.min(255, (int) (start.getBlue() + ratio * (end.getBlue() - start.getBlue()))));
            return new java.awt.Color(r, g, b);
        }

        private static String colorToMinecraftHex(java.awt.Color color) {
            String hex = String.format("%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
            StringBuilder sb = new StringBuilder("§x");
            for (char c : hex.toCharArray()) sb.append("§").append(c);
            return sb.toString();
        }

        /** Удаляет градиенты из текста, оставляя обычный текст */
        public static String removeGradients(String text) {
            if (text == null) return "";
            return text.replaceAll("\\{#[0-9A-Fa-f]{6}>\\}", "")
                    .replaceAll("\\{#[0-9A-Fa-f]{6}<\\}", "");
        }
    }

    private static java.awt.Color interpolateColor(java.awt.Color start, java.awt.Color end, float ratio) {
        int r = Math.max(0, Math.min(255, (int)(start.getRed() + ratio * (end.getRed() - start.getRed()))));
        int g = Math.max(0, Math.min(255, (int)(start.getGreen() + ratio * (end.getGreen() - start.getGreen()))));
        int b = Math.max(0, Math.min(255, (int)(start.getBlue() + ratio * (end.getBlue() - start.getBlue()))));
        return new java.awt.Color(r, g, b);
    }


    private static String colorToMinecraftHex(java.awt.Color color) {
        String hex = String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
        StringBuilder mcHex = new StringBuilder("§x");
        for (char c : hex.substring(1).toCharArray()) {
            mcHex.append("§").append(c);
        }
        return mcHex.toString();
    }}