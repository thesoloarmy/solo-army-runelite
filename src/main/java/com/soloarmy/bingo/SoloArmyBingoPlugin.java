package com.soloarmy.bingo;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.task.Schedule;
import net.runelite.client.callback.ClientThread;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@PluginDescriptor(
    name = "Solo Army Bingo",
    description = "Automatically submits relevant Bingo drops for Solo Army clan events",
    tags = {"bingo", "clan", "loot", "drops", "solo army"}
)
public class SoloArmyBingoPlugin extends Plugin
{
    private static final String API = "https://soloarmy.info/api/v1/runelite";
    private static final String PLUGIN_VERSION = "1.0.0";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String TOKEN_KEY = "deviceToken";
    private static final String INSTALL_KEY = "installId";
    private static final Set<String> DT2_BOSSES = Set.of(
        "Duke Sucellus",
        "The Leviathan",
        "The Whisperer",
        "Vardorvis"
    );

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private OkHttpClient http;
    @Inject private Gson gson;
    @Inject private ConfigManager configManager;
    @Inject private SoloArmyBingoConfig config;

    private volatile Set<Integer> trackedItemIds = Collections.emptySet();
    private volatile boolean linking = false;
    private volatile boolean connectedMessageShown = false;

    @Provides
    SoloArmyBingoConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(SoloArmyBingoConfig.class);
    }

    @Override
    protected void startUp()
    {
        ensureInstallId();
        if (client.getGameState() == GameState.LOGGED_IN && config.enabled())
        {
            refreshWhenPlayerReady();
        }
    }

    @Override
    protected void shutDown()
    {
        trackedItemIds = Collections.emptySet();
        linking = false;
        connectedMessageShown = false;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN && config.enabled())
        {
            refreshWhenPlayerReady();
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN)
        {
            trackedItemIds = Collections.emptySet();
        }
    }

    @Schedule(period = 5, unit = ChronoUnit.MINUTES)
    public void refreshManifestPeriodically()
    {
        if (config.enabled() && client.getGameState() == GameState.LOGGED_IN)
        {
            refreshForLoggedInPlayer();
        }
    }

    @Subscribe
    public void onLootReceived(LootReceived event)
    {
        if (!config.enabled() || trackedItemIds.isEmpty() || client.getLocalPlayer() == null)
        {
            return;
        }

        final String rsn = client.getLocalPlayer().getName();
        final String token = token();
        if (rsn == null || rsn.trim().isEmpty() || token == null)
        {
            return;
        }

        for (ItemStack stack : event.getItems())
        {
            int rawId = stack.getId();
            int baseId = ItemVariationMapping.map(rawId);
            int itemId = trackedItemIds.contains(rawId) ? rawId : baseId;
            if (!trackedItemIds.contains(itemId))
            {
                continue;
            }

            if (itemId == ItemID.GOLD_RING && !DT2_BOSSES.contains(event.getName()))
            {
                continue;
            }

            submitDrop(token, itemId, Math.max(1, stack.getQuantity()), event.getName(), String.valueOf(event.getType()));
        }
    }

    private void refreshWhenPlayerReady()
    {
        clientThread.invokeLater(() ->
        {
            if (!config.enabled() || client.getGameState() != GameState.LOGGED_IN)
            {
                return true;
            }
            if (client.getLocalPlayer() == null
                || client.getLocalPlayer().getName() == null
                || client.getLocalPlayer().getName().trim().isEmpty())
            {
                return false;
            }
            refreshForLoggedInPlayer();
            return true;
        });
    }

    private void refreshForLoggedInPlayer()
    {
        if (client.getLocalPlayer() == null || linking)
        {
            return;
        }
        final String rsn = client.getLocalPlayer().getName();
        if (rsn == null || rsn.trim().isEmpty())
        {
            return;
        }
        final String existing = token();
        if (existing == null)
        {
            link(rsn);
        }
        else
        {
            fetchManifest(existing, rsn, true);
        }
    }

    private void link(String rsn)
    {
        linking = true;
        JsonObject body = new JsonObject();
        body.addProperty("rsn", rsn);
        body.addProperty("install_id", installId());
        body.addProperty("plugin_version", PLUGIN_VERSION);
        Request request = new Request.Builder().url(API + "/link").post(RequestBody.create(JSON, gson.toJson(body))).build();
        http.newCall(request).enqueue(new Callback()
        {
            @Override public void onFailure(Call call, IOException e)
            {
                linking = false;
                notifyGame("Solo Army Bingo: could not reach soloarmy.info.");
            }

            @Override public void onResponse(Call call, Response response) throws IOException
            {
                try (Response r = response)
                {
                    if (!r.isSuccessful())
                    {
                        linking = false;
                        if (r.code() == 403)
                        {
                            notifyGame("Solo Army Bingo: this account is not eligible for the active Bingo.");
                        }
                        else if (r.code() == 409)
                        {
                            notifyGame("Solo Army Bingo: there is no active Solo Army Bingo.");
                        }
                        else
                        {
                            notifyGame("Solo Army Bingo: linking is currently unavailable.");
                        }
                        return;
                    }
                    JsonObject json = gson.fromJson(r.body().charStream(), JsonObject.class);
                    String newToken = json.get("token").getAsString();
                    configManager.setConfiguration(SoloArmyBingoConfig.GROUP, TOKEN_KEY, newToken);
                    linking = false;
                    fetchManifest(newToken, rsn, false);
                }
            }
        });
    }

    private void fetchManifest(String token, String rsn, boolean relinkOnUnauthorized)
    {
        Request request = authenticated(API + "/bingo/manifest", token).get().build();
        http.newCall(request).enqueue(new Callback()
        {
            @Override public void onFailure(Call call, IOException e)
            {
                trackedItemIds = Collections.emptySet();
            }

            @Override public void onResponse(Call call, Response response) throws IOException
            {
                try (Response r = response)
                {
                    if (r.code() == 401 && relinkOnUnauthorized)
                    {
                        configManager.unsetConfiguration(SoloArmyBingoConfig.GROUP, TOKEN_KEY);
                        link(rsn);
                        return;
                    }
                    if (!r.isSuccessful())
                    {
                        trackedItemIds = Collections.emptySet();
                        return;
                    }
                    JsonObject json = gson.fromJson(r.body().charStream(), JsonObject.class);
                    JsonArray ids = json.getAsJsonArray("tracked_item_ids");
                    Set<Integer> next = new HashSet<>();
                    if (ids != null)
                    {
                        ids.forEach(value -> next.add(value.getAsInt()));
                    }
                    trackedItemIds = Collections.unmodifiableSet(next);
                    if (!connectedMessageShown)
                    {
                        connectedMessageShown = true;
                        notifyGame("Solo Army Bingo: connected; tracking " + next.size() + " active Bingo item IDs.");
                    }
                }
            }
        });
    }

    private void submitDrop(String token, int itemId, int quantity, String sourceName, String sourceType)
    {
        JsonObject body = new JsonObject();
        body.addProperty("client_event_id", UUID.randomUUID().toString());
        body.addProperty("item_id", itemId);
        body.addProperty("quantity", quantity);
        if (sourceName != null) body.addProperty("source_name", sourceName);
        if (sourceType != null) body.addProperty("source_type", sourceType);
        body.addProperty("occurred_at", Instant.now().toString());
        Request request = authenticated(API + "/bingo/drop", token).post(RequestBody.create(JSON, gson.toJson(body))).build();
        http.newCall(request).enqueue(new Callback()
        {
            @Override public void onFailure(Call call, IOException e) { }

            @Override public void onResponse(Call call, Response response) throws IOException
            {
                try (Response r = response)
                {
                    if (r.code() == 401)
                    {
                        configManager.unsetConfiguration(SoloArmyBingoConfig.GROUP, TOKEN_KEY);
                        trackedItemIds = Collections.emptySet();
                        return;
                    }
                    if (!r.isSuccessful()) return;
                    JsonObject json = gson.fromJson(r.body().charStream(), JsonObject.class);
                    String status = json.has("status") ? json.get("status").getAsString() : "received";
                    if ("accepted".equals(status)) notifyGame("Solo Army Bingo: matching drop accepted automatically.");
                    else if ("review".equals(status)) notifyGame("Solo Army Bingo: matching drop saved for admin review.");
                }
            }
        });
    }

    private Request.Builder authenticated(String url, String token)
    {
        return new Request.Builder().url(url).header("Authorization", "Bearer " + token).header("User-Agent", "SoloArmyBingoRuneLite/" + PLUGIN_VERSION);
    }

    private String token()
    {
        String value = configManager.getConfiguration(SoloArmyBingoConfig.GROUP, TOKEN_KEY);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String installId()
    {
        return configManager.getConfiguration(SoloArmyBingoConfig.GROUP, INSTALL_KEY);
    }

    private void ensureInstallId()
    {
        String value = installId();
        if (value == null || value.length() < 16)
        {
            configManager.setConfiguration(SoloArmyBingoConfig.GROUP, INSTALL_KEY, UUID.randomUUID().toString());
        }
    }

    private void notifyGame(String message)
    {
        clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null));
    }
}
