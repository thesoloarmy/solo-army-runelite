package com.soloarmy.bingo;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.io.IOException;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.game.ItemManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
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
    // Gold ring item ID; avoids the deprecated net.runelite.api.ItemID constants class.
    private static final int GOLD_RING_ITEM_ID = 1635;
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
    @Inject private ClientToolbar clientToolbar;
    @Inject private ItemManager itemManager;
    private SoloArmyBingoPanel panel;
    private NavigationButton navigationButton;
    private final AtomicBoolean stateRequestInFlight = new AtomicBoolean();
    private final AtomicReference<Call> stateCall = new AtomicReference<>();
    private volatile boolean running;
    private final AtomicLong sessionGeneration = new AtomicLong();

    private volatile Set<Integer> trackedItemIds = Collections.emptySet();
    private final AtomicBoolean linkRequestInFlight = new AtomicBoolean();
    private final AtomicBoolean manifestRequestInFlight = new AtomicBoolean();
    private final AtomicReference<Call> linkCall = new AtomicReference<>();
    private final AtomicReference<Call> manifestCall = new AtomicReference<>();
    private volatile long nextLinkAttemptAtMillis;
    private volatile int linkFailureCount;
    private volatile boolean linking = false;
    private volatile boolean connectedMessageShown = false;
    // LOADING -> LOGGED_IN happens on teleports and region changes too.
    private boolean loginRefreshCompleted;

    @Provides
    SoloArmyBingoConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(SoloArmyBingoConfig.class);
    }

    @Override
    protected void startUp()
    {
        running = true;
        nextLinkAttemptAtMillis = 0;
        linkFailureCount = 0;
        sessionGeneration.incrementAndGet();
        loginRefreshCompleted = false;
        panel = new SoloArmyBingoPanel(this::fetchState, itemManager, this::notifyGame);
        BufferedImage icon;
        try (java.io.InputStream stream = SoloArmyBingoPlugin.class.getResourceAsStream("solo-army-icon.png"))
        {
            icon = stream == null ? null : javax.imageio.ImageIO.read(stream);
        }
        catch (IOException e)
        {
            icon = null;
        }
        if (icon == null)
        {
            icon = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        }
        navigationButton = NavigationButton.builder()
            .tooltip("Solo Army Bingo")
            .icon(icon)
            .priority(7)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navigationButton);
        ensureInstallId();
        if (client.getGameState() == GameState.LOGGED_IN && config.enabled())
        {
            refreshWhenPlayerReady();
        }
    }

    @Override
    protected void shutDown()
    {
        running = false;
        sessionGeneration.incrementAndGet();
        cancelSessionRequests();
        if (navigationButton != null) clientToolbar.removeNavigation(navigationButton);
        if (panel != null) panel.dispose();
        panel = null;
        trackedItemIds = Collections.emptySet();
        linking = false;
        connectedMessageShown = false;
        loginRefreshCompleted = false;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN && config.enabled() && !loginRefreshCompleted)
        {
            refreshWhenPlayerReady();
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN)
        {
            sessionGeneration.incrementAndGet();
            cancelSessionRequests();
            if (panel != null) panel.resetSession();
            trackedItemIds = Collections.emptySet();
            loginRefreshCompleted = false;
            connectedMessageShown = false;
        }
    }

    @Schedule(period = 5, unit = ChronoUnit.MINUTES)
    public void refreshManifestPeriodically()
    {
        if (config.enabled() && client.getGameState() == GameState.LOGGED_IN)
        {
            // Periodic manifest sync must not also trigger a state request.
            final String credential = token();
            if (credential != null && client.getLocalPlayer() != null
                && client.getLocalPlayer().getName() != null)
            {
                fetchManifest(credential, client.getLocalPlayer().getName(), true);
            }
            else
            {
                refreshWhenPlayerReady();
            }
        }
    }

    @Schedule(period = 1, unit = ChronoUnit.MINUTES)
    public void refreshStatePeriodically()
    {
        fetchState();
    }

    private void fetchState()
    {
        if (!running || !config.enabled() || client.getGameState() != GameState.LOGGED_IN)
        {
            if (running && panel != null) panel.showStatus("Enable tracking and log in to sync");
            return;
        }
        String credential = token();
        if (credential == null)
        {
            if (panel != null) panel.showStatus("Waiting for device link");
            return;
        }
        if (!RequestSafety.begin(stateRequestInFlight)) return;
        final long generation = sessionGeneration.get();
        Request request = authenticated(API + "/bingo/state", credential).get().build();
        Call call = http.newCall(request);
        stateCall.set(call);
        call.enqueue(new Callback()
        {
            @Override public void onFailure(Call call, IOException e)
            {
                RequestSafety.finishOwned(stateCall, stateRequestInFlight, call);
                if (validStateResponse(generation, credential) && panel != null) panel.showStatus("Offline · showing last synced state");
            }

            @Override public void onResponse(Call call, Response response) throws IOException
            {
                try (Response r = response)
                {
                    if (!validStateResponse(generation, credential)) return;
                    if (!r.isSuccessful())
                    {
                        if (r.code() == 401 || r.code() == 403)
                        {
                            if (panel != null) panel.showStatus("Device authorization failed · check tracking/link status");
                        }
                        else if (panel != null) panel.showStatus("Sync failed (HTTP " + r.code() + ") · showing last state");
                        return;
                    }
                    JsonObject data = gson.fromJson(r.body().charStream(), JsonObject.class);
                    if (data != null && panel != null) panel.showState(data);
                }
                catch (RuntimeException ex)
                {
                    if (validStateResponse(generation, credential) && panel != null) panel.showStatus("Invalid server response");
                }
                finally
                {
                    RequestSafety.finishOwned(stateCall, stateRequestInFlight, call);
                }
            }
        });
    }

    private boolean validStateResponse(long generation, String credential)
    {
        return RequestSafety.isCurrentToken(generation, sessionGeneration.get(), running,
            config.enabled(), client.getGameState() == GameState.LOGGED_IN, credential, token());
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

            if (itemId == GOLD_RING_ITEM_ID && (event.getName() == null || !DT2_BOSSES.contains(event.getName())))
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
            if (!loginRefreshCompleted)
            {
                loginRefreshCompleted = true;
                refreshForLoggedInPlayer();
            }
            return true;
        });
    }

    private void refreshForLoggedInPlayer()
    {
        if (!running || !config.enabled() || client.getLocalPlayer() == null)
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
            fetchState();
        }
    }

    private void link(String rsn)
    {
        if (!running || !config.enabled() || System.currentTimeMillis() < nextLinkAttemptAtMillis
            || !RequestSafety.begin(linkRequestInFlight)) return;
        linking = true;
        final long generation = sessionGeneration.get();
        JsonObject body = new JsonObject();
        body.addProperty("rsn", rsn);
        body.addProperty("install_id", installId());
        body.addProperty("plugin_version", PLUGIN_VERSION);
        Request request = new Request.Builder().url(API + "/link").post(RequestBody.create(JSON, gson.toJson(body))).build();
        Call linkRequest = http.newCall(request);
        linkCall.set(linkRequest);
        linkRequest.enqueue(new Callback()
        {
            @Override public void onFailure(Call call, IOException e)
            {
                finishLink(call);
                if (!validSession(generation)) return;
                delayLinkRetry();
                notifyGame("Solo Army Bingo: could not reach soloarmy.info.");
            }

            @Override public void onResponse(Call call, Response response) throws IOException
            {
                try (Response r = response)
                {
                    if (!validSession(generation)) return;
                    if (!r.isSuccessful())
                    {
                        delayLinkRetry();
                        if (r.code() == 403) notifyGame("Solo Army Bingo: this account is not eligible for the active Bingo.");
                        else if (r.code() == 409) notifyGame("Solo Army Bingo: there is no active Solo Army Bingo.");
                        else notifyGame("Solo Army Bingo: linking is currently unavailable.");
                        return;
                    }
                    JsonObject json = gson.fromJson(r.body().charStream(), JsonObject.class);
                    if (json == null || !json.has("token") || json.get("token").getAsString().isEmpty())
                    {
                        delayLinkRetry();
                        return;
                    }
                    String newToken = json.get("token").getAsString();
                    if (!validSession(generation)) return;
                    configManager.setConfiguration(SoloArmyBingoConfig.GROUP, TOKEN_KEY, newToken);
                    linkFailureCount = 0;
                    nextLinkAttemptAtMillis = 0;
                    // Run follow-up requests after the link guard has been released.
                    clientThread.invokeLater(() -> {
                        if (validSession(generation) && newToken.equals(token()))
                        {
                            fetchManifest(newToken, rsn, false);
                            fetchState();
                        }
                    });
                }
                catch (RuntimeException ex)
                {
                    if (validSession(generation)) delayLinkRetry();
                }
                finally
                {
                    finishLink(call);
                }
            }
        });
    }

    private void fetchManifest(String credential, String rsn, boolean relinkOnUnauthorized)
    {
        if (!running || !config.enabled() || !RequestSafety.begin(manifestRequestInFlight)) return;
        final long generation = sessionGeneration.get();
        Request request = authenticated(API + "/bingo/manifest", credential).get().build();
        Call manifestRequest = http.newCall(request);
        manifestCall.set(manifestRequest);
        manifestRequest.enqueue(new Callback()
        {
            @Override public void onFailure(Call call, IOException e)
            {
                finishManifest(call);
                // Keep the last known manifest during temporary network failures.
            }

            @Override public void onResponse(Call call, Response response) throws IOException
            {
                try (Response r = response)
                {
                    if (!validSession(generation) || !credential.equals(token())) return;
                    if (r.code() == 401 && relinkOnUnauthorized)
                    {
                        configManager.unsetConfiguration(SoloArmyBingoConfig.GROUP, TOKEN_KEY);
                        trackedItemIds = Collections.emptySet();
                        clientThread.invokeLater(() -> {
                            if (validSession(generation)) link(rsn);
                        });
                        return;
                    }
                    if (!r.isSuccessful()) return;
                    JsonObject json = gson.fromJson(r.body().charStream(), JsonObject.class);
                    JsonArray ids = json == null ? null : json.getAsJsonArray("tracked_item_ids");
                    if (ids == null) return;
                    Set<Integer> next = new HashSet<>();
                    ids.forEach(value -> next.add(value.getAsInt()));
                    if (!validSession(generation) || !credential.equals(token())) return;
                    trackedItemIds = Collections.unmodifiableSet(next);
                    if (!connectedMessageShown)
                    {
                        connectedMessageShown = true;
                        notifyGame("Solo Army Bingo: connected; tracking " + next.size() + " active Bingo item IDs.");
                    }
                }
                catch (RuntimeException ex)
                {
                    // Malformed responses must not replace a working manifest.
                }
                finally
                {
                    finishManifest(call);
                }
            }
        });
    }

    private boolean validSession(long generation)
    {
        return RequestSafety.isCurrent(generation, sessionGeneration.get(), running, config.enabled(),
            client.getGameState() == GameState.LOGGED_IN);
    }

    private void delayLinkRetry()
    {
        int failures = Math.min(6, ++linkFailureCount);
        nextLinkAttemptAtMillis = System.currentTimeMillis() + RequestSafety.retryDelayMillis(failures);
    }

    private void finishLink(Call call)
    {
        if (RequestSafety.finishOwned(linkCall, linkRequestInFlight, call))
        {
            linking = false;
        }
    }

    private void finishManifest(Call call)
    {
        RequestSafety.finishOwned(manifestCall, manifestRequestInFlight, call);
    }

    private void cancelSessionRequests()
    {
        // Release ownership immediately: OkHttp cancellation callbacks may arrive later.
        // A stale callback must never release a guard belonging to a newer request.
        cancelOwned(stateCall, stateRequestInFlight);
        cancelOwned(manifestCall, manifestRequestInFlight);
        cancelOwned(linkCall, linkRequestInFlight);
        linking = false;
    }

    private static void cancelOwned(AtomicReference<Call> reference, AtomicBoolean guard)
    {
        Call pending = reference.getAndSet(null);
        guard.set(false);
        if (pending != null) pending.cancel();
    }

    private void submitDrop(String token, int itemId, int quantity, String sourceName, String sourceType)
    {
        final long generation = sessionGeneration.get();
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
                    // A drop response from an earlier login or token must not mutate this session.
                    if (!validStateResponse(generation, token)) return;
                    if (r.code() == 401)
                    {
                        if (!validStateResponse(generation, token)) return;
                        configManager.unsetConfiguration(SoloArmyBingoConfig.GROUP, TOKEN_KEY);
                        trackedItemIds = Collections.emptySet();
                        return;
                    }
                    if (!r.isSuccessful()) return;
                    JsonObject json = gson.fromJson(r.body().charStream(), JsonObject.class);
                    if (json == null || !validStateResponse(generation, token)) return;
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
