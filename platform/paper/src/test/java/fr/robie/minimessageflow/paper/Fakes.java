package fr.robie.minimessageflow.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Players, a server and a plugin made of dynamic proxies: each answers the methods its map names (by method name),
 * runs an interface's default methods, and fails on any other, so a test shows what it relies on.
 */
final class Fakes {

    static final List<Player> ONLINE = new ArrayList<>();
    static final Server SERVER = proxy(Server.class, Map.of(
            "getOnlinePlayers", args -> ONLINE,
            "getMaxPlayers", args -> 100,
            "getTPS", args -> new double[]{19.987, 19.5, 19.0},
            "getAverageTickTime", args -> 12.3456,
            "getPluginManager", args -> proxy(PluginManager.class, Map.of("isPluginEnabled", a -> false)),
            "getLogger", args -> Logger.getLogger("fake-server"),
            "getName", args -> "FakeServer",
            "getVersion", args -> "fake",
            "getBukkitVersion", args -> "fake"));

    static {
        // Bukkit's server is set once per JVM.
        if (Bukkit.getServer() == null) {
            Bukkit.setServer(SERVER);
        }
        Viewers.lookup(uuid -> ONLINE.stream().filter(p -> p.getUniqueId().equals(uuid)).findFirst().orElse(null));
    }

    private Fakes() {
    }

    interface Answer {
        Object answer(Object[] args) throws Throwable;
    }

    static Player player(String name, UUID uuid) {
        World world = proxy(World.class, Map.of("getName", args -> "world_nether"));
        Player player = proxy(Player.class, Map.of(
                "getName", args -> name,
                "displayName", args -> Component.text("[" + name + "]", net.kyori.adventure.text.format.NamedTextColor.GOLD),
                "getUniqueId", args -> uuid,
                "getWorld", args -> world,
                "getPing", args -> 42,
                "locale", args -> Locale.FRANCE,
                "getLocation", args -> new Location(world, 10.7, 64.2, -3.5),
                // As a Paper player's: its name and UUID as Adventure pointers.
                "pointers", args -> net.kyori.adventure.pointer.Pointers.builder()
                        .withStatic(net.kyori.adventure.identity.Identity.NAME, name)
                        .withStatic(net.kyori.adventure.identity.Identity.UUID, uuid)
                        .build()));
        return player;
    }

    static Plugin plugin(Logger logger) {
        return proxy(Plugin.class, Map.of("getLogger", args -> logger, "getName", args -> "Test"));
    }

    /**
     * A logger that keeps what it is given, for the warnings a test expects.
     */
    static Logger recordingLogger(List<LogRecord> records) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, Map<String, Answer> answers) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                Answer answer = answers.get(method.getName());
                if (answer != null) {
                    return answer.answer(args);
                }
                switch (method.getName()) {
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "toString":
                        return type.getSimpleName() + "@fake";
                    default:
                        break;
                }
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                throw new UnsupportedOperationException(type.getSimpleName() + "." + method.getName() + " is not faked");
            }
        };
        return (T) Proxy.newProxyInstance(Fakes.class.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
