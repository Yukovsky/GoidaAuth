package ru.goidacraft.goidaauth;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import ru.goidacraft.goidaauth.twink.HwidPayload;
import ru.goidacraft.goidaauth.twink.TwinkProtection;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;
import ru.goidacraft.goidaauth.auth.AuthSessionManager;
import ru.goidacraft.goidaauth.auth.PasswordHasher;
import ru.goidacraft.goidaauth.commands.AuthCommands;
import ru.goidacraft.goidaauth.database.DatabaseManager;
import ru.goidacraft.goidaauth.events.AuthEventHandler;
import ru.goidacraft.goidaauth.mojang.MojangApi;
import ru.goidacraft.goidaauth.permissions.AuthPermissions;
import ru.goidacraft.goidaauth.rules.RulesCommand;
import ru.goidacraft.goidaauth.rules.RulesConfig;

import java.nio.file.Path;

@Mod(GoidaAuth.MODID)
public final class GoidaAuth {
    public static final String MODID = "goidaauth";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static GoidaAuth instance;

    private final DatabaseManager database = new DatabaseManager();
    private final PasswordHasher hasher = new PasswordHasher();
    private final AuthSessionManager sessions = new AuthSessionManager();
    private final MojangApi mojang = new MojangApi();
    private final RulesConfig rulesConfig = new RulesConfig(Path.of("config"));

    public GoidaAuth(IEventBus modBus, ModContainer container) {
        instance = this;
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC, "goidaauth-common.toml");

        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onRegisterPayloads);

        var gameBus = NeoForge.EVENT_BUS;
        gameBus.addListener(this::onServerAboutToStart);
        gameBus.addListener(this::onServerStopping);
        gameBus.addListener(EventPriority.LOWEST, this::onRegisterCommands);
        gameBus.addListener(AuthPermissions::registerPermissionNodes);

        var handler = new AuthEventHandler(sessions);
        gameBus.addListener(EventPriority.HIGHEST, (PlayerEvent.PlayerLoggedInEvent e) -> handler.onLoggedIn(e));
        gameBus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> handler.onLoggedOut(e));
        gameBus.addListener((PlayerTickEvent.Pre e) -> handler.onTick(e));
        gameBus.addListener((PlayerInteractEvent.RightClickBlock e) -> handler.cancelIfUnauth(e, e.getEntity()));
        gameBus.addListener((PlayerInteractEvent.RightClickItem e) -> handler.cancelIfUnauth(e, e.getEntity()));
        gameBus.addListener((PlayerInteractEvent.LeftClickBlock e) -> handler.cancelIfUnauth(e, e.getEntity()));
        gameBus.addListener((PlayerInteractEvent.EntityInteract e) -> handler.cancelIfUnauth(e, e.getEntity()));
        gameBus.addListener((PlayerInteractEvent.EntityInteractSpecific e) -> handler.cancelIfUnauth(e, e.getEntity()));
        gameBus.addListener((AttackEntityEvent e) -> handler.cancelIfUnauth(e, e.getEntity()));
        gameBus.addListener((ServerChatEvent e) -> handler.onChat(e));
        gameBus.addListener((CommandEvent e) -> handler.onCommand(e));
        gameBus.addListener((LivingIncomingDamageEvent e) -> handler.onDamage(e));
        gameBus.addListener((net.neoforged.neoforge.event.entity.item.ItemTossEvent e) -> handler.onItemToss(e));
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("GoidaAuth starting up");
    }

    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        // Optional serverbound channel: client sends HWID when companion client mod is installed.
        // Server-only — vanilla clients simply never send this packet.
        event.registrar("1").optional().commonToServer(
                HwidPayload.TYPE, HwidPayload.CODEC, TwinkProtection::handleHwidPayload);
    }

    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        try {
            database.start(event.getServer());
            LOGGER.info("GoidaAuth database initialized");
        } catch (Exception e) {
            LOGGER.error("Failed to initialize GoidaAuth database", e);
            throw new RuntimeException(e);
        }
        rulesConfig.loadOrCreate();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        database.shutdown();
        sessions.clear();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        AuthCommands.register(event.getDispatcher(), database, hasher, sessions);
        new RulesCommand(rulesConfig).register(event.getDispatcher());
    }

    public static GoidaAuth get() {
        return instance;
    }

    public DatabaseManager database() { return database; }
    public PasswordHasher hasher() { return hasher; }
    public AuthSessionManager sessions() { return sessions; }
    public MojangApi mojang() { return mojang; }
    public RulesConfig rulesConfig() { return rulesConfig; }
}
