package com.Gomania.gChat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;

public class MsgCommand implements CommandExecutor {

    private final GChat plugin;

    public MsgCommand(GChat plugin) {
        this.plugin = plugin;
    }

    private String format(String path, Player sender, Player receiver, String message) {
        return ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString(path, "&7[%sender% -> %receiver%]: %message%")
                        .replace("%sender%", sender.getName())
                        .replace("%receiver%", receiver.getName())
                        .replace("%message%", message)
        );
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Только игрок может использовать эту команду.");
            return true;
        }

        Player player = (Player) sender;

        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Использование: /msg <игрок> <сообщение>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            player.sendMessage(ChatColor.RED + "Игрок не найден.");
            return true;
        }

        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        // Отправляем игрокам сообщения по формату
        String toReceiver = format("private-message.format-to-receiver", player, target, message);
        String toSender = format("private-message.format-to-sender", player, target, message);

        target.sendMessage(toReceiver);
        player.sendMessage(toSender);

        return true;
    }
}
