package sh.pcx.packmerger.commands;

import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

/** {@code /pm sources approve|reject} only for a player or the real console (security review L5). */
class SourcesApproveSenderTest {

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            return null;
        });
    }

    @Test
    void playerAndConsole_mayDecide() {
        Player player = stub(Player.class);
        assertTrue(PackMergerCommand.isHumanSource(player, null));
        assertTrue(PackMergerCommand.isHumanSource(player, player));
        assertTrue(PackMergerCommand.isHumanSource(stub(ConsoleCommandSender.class), null));
        assertTrue(PackMergerCommand.isHumanSource(stub(RemoteConsoleCommandSender.class), null));
    }

    @Test
    void commandBlocksFunctionsAndExecuteAs_mayNot() {
        assertFalse(PackMergerCommand.isHumanSource(stub(BlockCommandSender.class), null));
        assertFalse(PackMergerCommand.isHumanSource(stub(ProxiedCommandSender.class), null));
        assertFalse(PackMergerCommand.isHumanSource(stub(Zombie.class), null));
        // A function run "as" an entity from the console (e.g. /execute as @e run pm sources approve all).
        assertFalse(PackMergerCommand.isHumanSource(stub(ConsoleCommandSender.class), stub(Zombie.class)));
        // A player's command run "as" another entity.
        assertFalse(PackMergerCommand.isHumanSource(stub(Player.class), stub(Zombie.class)));
        assertFalse(PackMergerCommand.isHumanSource(stub(CommandSender.class), null));
    }
}
