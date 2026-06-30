package ru.goidacraft.goidaauth.rules;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class RulesConfig {
    private static final Logger LOG = LoggerFactory.getLogger(RulesConfig.class);
    public static final String FILE_NAME = "goida_rules.json";

    private final Path configPath;
    private volatile List<Category> categories = List.of();
    private volatile List<Link> links = List.of();

    public record Category(String title, List<String> rules) {}
    public record Link(String label, String url, String note) {}

    public RulesConfig(Path configDir) {
        this.configPath = configDir.resolve(FILE_NAME);
    }

    public void loadOrCreate() {
        if (!Files.exists(configPath)) {
            writeDefaults();
        }
        reload();
    }

    public void reload() {
        try (Reader r = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            JsonArray cats = root.getAsJsonArray("categories");
            List<Category> loaded = new ArrayList<>();
            for (JsonElement el : cats) {
                JsonObject cat = el.getAsJsonObject();
                String title = cat.get("title").getAsString();
                List<String> rules = new ArrayList<>();
                for (JsonElement rule : cat.getAsJsonArray("rules")) {
                    rules.add(rule.getAsString());
                }
                loaded.add(new Category(title, Collections.unmodifiableList(rules)));
            }
            categories = Collections.unmodifiableList(loaded);

            List<Link> loadedLinks = new ArrayList<>();
            JsonArray linksArr = root.has("links") ? root.getAsJsonArray("links") : new JsonArray();
            for (JsonElement el : linksArr) {
                JsonObject obj = el.getAsJsonObject();
                String label = obj.get("label").getAsString();
                String url   = obj.get("url").getAsString();
                String note  = obj.has("note") ? obj.get("note").getAsString() : null;
                loadedLinks.add(new Link(label, url, note));
            }
            links = Collections.unmodifiableList(loadedLinks);

            LOG.info("GoidaAuth rules loaded: {} categories, {} links", categories.size(), links.size());
        } catch (Exception e) {
            LOG.error("Failed to load {}", configPath, e);
        }
    }

    public List<Category> categories() { return categories; }
    public List<Link> links() { return links; }
    public int pageCount() { return categories.size(); }
    public Path path() { return configPath; }

    private void writeDefaults() {
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, DEFAULT_JSON, StandardCharsets.UTF_8);
            LOG.info("Created default rules config at {}", configPath);
        } catch (IOException e) {
            LOG.error("Failed to write default rules config", e);
        }
    }

    // language=json
    private static final String DEFAULT_JSON = """
            {
              "categories": [
                {
                  "title": "Общие положения",
                  "rules": [
                    "Предметы в незагруженных чанках удаляются каждые 30 минут.",
                    "Правила вступают в силу только при наличии жалобы. Если пострадавший не обращается — инцидент не рассматривается.",
                    "Твинк-аккаунты запрещены. Твинк получает бан, основной аккаунт — штраф, размер которого определяется индивидуально.",
                    "Цены устанавливаются свободно. Совет вмешивается и задаёт ценовой диапазон только в отдельных случаях."
                  ]
                },
                {
                  "title": "Обращения",
                  "rules": [
                    "Все жалобы, вопросы и просьбы к Администрации и Совету подаются исключительно через тикет — личные сообщения не рассматриваются.",
                    "Жалобы на гриф или воровство направляются в тикет Полиции. Полиция разбирается в произошедшем и передаёт материалы администрации.",
                    "Администрация решает только вопросы, непосредственно связанные с работой сервера.",
                    "Важно: если жалоба на «проблему сервера» окажется ложной, администрация не оказывает никакой помощи в течение двух недель — вне зависимости от обстоятельств."
                  ]
                },
                {
                  "title": "Структура сервера",
                  "rules": [
                    "На сервере действует система Глав — каждый Глава отвечает за отдельное направление (строительство спавна, экономику и т.д.). Идеи и предложения по развитию сервера следует направлять Главе соответствующего раздела.",
                    "За порядком следит Полиция. Именно к ней нужно обращаться при подозрениях на нарушение."
                  ]
                },
                {
                  "title": "Чат и общение",
                  "rules": [
                    "Токсичное поведение и оскорбления в чате или личных сообщениях наказываются мутом. Наказание выносится по жалобе.",
                    "Оскорбление родителей или родственников другого игрока влечёт за собой бан.",
                    "Флуд и повторение сообщений — три и более сообщений с интервалом менее пяти минут — наказываются мутом.",
                    "Разжигание нацизма, расизма, экстремизма, межнациональной, религиозной или политической розни наказывается мутом.",
                    "Неконструктивная критика и публичное обсуждение действий администрации на сервере наказываются мутом.",
                    "При отсутствии Администрации и Полиции на сервере игроки вправе инициировать мут голосованием командой /votemute <игрок> [30m|1h|2h|3h]. Мут выдаётся автоматически при наборе более 66% голосов."
                  ]
                },
                {
                  "title": "Дюп и читы",
                  "rules": [
                    "Дюп дорогостоящих или массовых предметов влечёт за собой перманентный бан.",
                    "Дюп предметов средней ценности наказывается временным баном.",
                    "Дюп незначительных предметов наказывается общественными работами.",
                    "Дюп монет рассматривается с учётом отягчающих обстоятельств."
                  ]
                },
                {
                  "title": "Гриф и воровство",
                  "rules": [
                    "Гриф всей базы наказывается временным баном.",
                    "Гриф сервера или государственных построек с явным умыслом наказывается перманентным баном.",
                    "Локальный гриф обязывает виновного вернуть ресурсы и выплатить компенсацию.",
                    "Воровство средней тяжести обязывает вернуть ресурсы и выплатить компенсацию.",
                    "Крупное воровство наказывается временным баном с обязательным возвратом ресурсов.",
                    "Особо крупное воровство влечёт за собой перманентный бан.",
                    "Убийство игрока обязывает виновного вернуть ресурсы и выплатить компенсацию."
                  ]
                },
                {
                  "title": "Строительство и фермы",
                  "rules": [
                    "Мощные фермы разрешается запускать только ночью. В дневное время они должны быть отключены.",
                    "Физические постройки перед выходом из игры необходимо возвращать в стандартный блочный вид. Исключение составляют случаи, когда это разрушит саму постройку."
                  ]
                },
                {
                  "title": "Экономика и контент",
                  "rules": [
                    "Злоупотребление экономической системой наказывается баном.",
                    "18+ арты разрешены. Откровенный контент в публичных местах — в городах и на общих территориях — должен быть скрыт за явным предупреждением, чтобы другие игроки не видели его без своего согласия."
                  ]
                }
              ],
              "links": [
                {"label": "Сайт сервера",    "url": "https://goidacraft.online"},
                {"label": "Телеграм чат",    "url": "https://t.me/+W-nS71bNZqA4OThi"},
                {"label": "Телеграм канал",  "url": "https://t.me/goidacraft"},
                {"label": "Дискорд",         "url": "https://discord.gg/prJwFwy5ns",              "note": "§c⚠ Авторизация обязательна! Дано 3 дня с момента регистрации."},
                {"label": "YouTube",         "url": "https://www.youtube.com/@goidacraft.online"},
                {"label": "TikTok",          "url": "https://www.tiktok.com/@goidacraft.online"}
              ]
            }
            """;
}
