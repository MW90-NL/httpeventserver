package com.httpeventserver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.inject.Provides;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;


import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.NPCManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.http.api.RuneLiteAPI;

import static java.lang.Integer.parseInt;

@PluginDescriptor(
        name = "Http Event Server",
        description = "Exposes current player stats, actions and inventory as well as current Enemy NPC stats on <PORT>. Defaults to Port:5050",
        tags = {"status", "npc and player stats", "api", "stats"},
        enabledByDefault = false
)

@Slf4j
public class HttpEventServerPlugin extends Plugin {
    @Inject
    private NPCManager npcManager;
    @Inject
    public Client client;

    public long startTime = 0;

    @Inject
    public HttpEventServerConfig config;

    @Inject
    public ClientThread clientThread;
    public HttpServer server;
    public String msg;
    public String msgType;

    private boolean lastBankOpenStatus;
    public Item[] bankItems;
    public String interactingCode;
    public JsonArray lootArray = new JsonArray();

    private ItemContainer lastBankContainer;

    private ItemContainer fishingTrawlerContainer;

    public Boolean bankOpen;

    private boolean lastShopOpenStatus;

    private boolean lastTrawlerRewardOpenStatus;

    public Boolean shopOpen;

    public Boolean trawlerRewardOpen;

    public Integer tickCount = 0;

    public Integer msgTick = 0;

    public JsonObject interactingJson = new JsonObject();
    public JsonObject npcJson = new JsonObject();

    public enum equipmentSlots
    {
        head, back, neck, weapon, chest, shield, placeholderA, legs, placeholderB, gloves, boots, placeholderC, ring, ammo
    }

    @Provides
    private HttpEventServerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(HttpEventServerConfig.class);
    }

    @Override
    protected void startUp() throws Exception {

        String HttpPort = config.apiPort();
        System.out.println("HttpEventServer plugin running on Port: " + HttpPort);

        int HttpPortNumber= parseInt(HttpPort);

        server = HttpServer.create(new InetSocketAddress(HttpPortNumber), 0);
        server.createContext("/inv", handlerForInventory());
        server.createContext("/equip", handlerForEquipment());
        server.createContext("/bank", handlerForBank());
        server.createContext("/events", this::handleEvents);
        server.setExecutor(Executors.newSingleThreadExecutor());
        startTime = System.currentTimeMillis();
        server.start();
    }

    @Override
    protected void shutDown() throws Exception {
        server.stop(1);
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        msg = event.getMessage();
        msgType = String.valueOf(event.getType());
        msgTick = client.getTickCount();
        //System.out.println("onChatmsg:" + msg);
    }

    @Subscribe
    public void onGameTick(final GameTick event) {
        tickCount = client.getTickCount();
        this.detectBankWindowClosing();
        this.detectShopWindowClosing();
        this.detectTrawlerNetClosing();
        this.removeOldItems();
    }

    private void removeOldItems() {
        if(lootArray.size() > 0) {
            JsonArray lootArrayCopy = lootArray.deepCopy();
            for (JsonElement drops : lootArray) {
                int dropTickCount = 0;
                try {
                    dropTickCount = Integer.parseInt(drops.getAsJsonObject().get("tickCount").getAsString());
                }catch(Exception e){
                    dropTickCount = 0;
                }
                //Remove items from lootArray if item older then 200 Ticks
                if (tickCount - dropTickCount > 200) {
                    lootArrayCopy.remove(0);
                }
            }
            lootArray = lootArrayCopy;
        }
    }

    private void detectBankWindowClosing(){
        Widget con = client.getWidget(WidgetInfo.BANK_ITEM_CONTAINER);
        if(con != null) {
            lastBankOpenStatus = true;
            lastBankContainer = client.getItemContainer(InventoryID.BANK);
            bankOpen = Boolean.TRUE;
        }else if(lastBankOpenStatus){
            lastBankOpenStatus = false;
            bankItems = lastBankContainer.getItems();
            bankOpen = Boolean.FALSE;
        }
    }

    private void detectShopWindowClosing(){
        Widget con = client.getWidget(WidgetInfo.SHOP_INVENTORY_ITEMS_CONTAINER);
        if(con != null) {
            lastShopOpenStatus = true;
            shopOpen = Boolean.TRUE;
        }else if(lastShopOpenStatus){
            lastShopOpenStatus = false;
            shopOpen = Boolean.FALSE;
        }
    }

    private void detectTrawlerNetClosing(){
        ItemContainer reward = client.getItemContainer(InventoryID.FISHING_TRAWLER_REWARD);

        if(reward != null) {
            lastTrawlerRewardOpenStatus = true;
            trawlerRewardOpen = Boolean.TRUE;
        }else if(lastTrawlerRewardOpenStatus){
            lastTrawlerRewardOpenStatus = false;
            trawlerRewardOpen = Boolean.FALSE;
        }
    }

    public Client getClient() {
        return client;
    }

    @Subscribe
    public void onNpcLootReceived(final NpcLootReceived npcLootReceived) {

        int count = 0;

        JsonObject loot = new JsonObject();
        JsonArray itemList = new JsonArray();
        String npcId = String.valueOf(npcLootReceived.getNpc());
        JsonElement npcObject = interactingJson.get(interactingCode);
        for(ItemStack item : npcLootReceived.getItems()){
            JsonObject dict = new JsonObject();
            dict.addProperty("id",item.getId());
            dict.addProperty("quantity",item.getQuantity());
            itemList.add(dict);
            count++;
        }
        loot.add("loot", itemList);
        loot.add("npc",npcObject);
        loot.addProperty("tickCount", client.getTickCount());
        loot.addProperty("interactingCode",npcId);
        lootArray.add(loot);

    }

    public Map<String, String> queryToMap(String query) {
        if(query == null) {
            return null;
        }
        Map<String, String> result = new HashMap<>();
        for (String param : query.split("&")) {
            String[] entry = param.split("=");
            if (entry.length > 1) {
                result.put(entry[0], entry[1]);
            }else{
                result.put(entry[0], "");
            }
        }
        return result;
    }
    public void handleEvents(HttpExchange exchange) throws IOException {
        Player player = client.getLocalPlayer();
        Actor npcTarget = player.getInteracting();

        //Reset LootArray by the following /events?resetLootArray=1
        String resetLootArray = "";
        try {
            Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
            if(params.containsKey("resetLootArray")){
                resetLootArray = params.get("resetLootArray");
            }
        }
        catch(Exception e) {
        }

        //Used Variables
        int npcHealthRatio;
        int npcHealthScale;
        int npcMaxHealth = 0;
        int npcCurrentHealth = 0;
        int npcCombatLvl = 0;
        int npcX = 0;
        int npcY = 0;
        int npcPlane = 0;
        int npcRegionID = 0;
        int npcRegionX = 0;
        int npcRegionY = 0;
        int npcId = 0;
        String npcName = "";

        if (npcTarget != null) {
            interactingCode = String.valueOf(player.getInteracting());

            npcHealthRatio = npcTarget.getHealthRatio();
            npcHealthScale = npcTarget.getHealthScale();

            if (npcTarget instanceof NPC)
            {
                npcId = ((NPC) npcTarget).getId();
                npcMaxHealth = npcManager.getHealth(((NPC) npcTarget).getId());
                npcName = npcTarget.getName();
                npcCombatLvl = npcTarget.getCombatLevel();
                npcX = npcTarget.getWorldLocation().getX();
                npcY = npcTarget.getWorldLocation().getY();
                npcPlane = npcTarget.getWorldLocation().getPlane();
                npcRegionID = npcTarget.getWorldLocation().getRegionID();
                npcRegionX = npcTarget.getWorldLocation().getRegionX();
                npcRegionY = npcTarget.getWorldLocation().getRegionY();

                JsonObject dict = new JsonObject();

                if(!interactingJson.has(interactingCode)) {
                    dict.addProperty("name",npcName);
                    dict.addProperty("id",npcId);
                    interactingJson.add(interactingCode, dict);
                }
            }
            npcCurrentHealth = getNpcCurrentHealth(npcHealthRatio, npcHealthScale, npcMaxHealth, npcCurrentHealth);
        }

        JsonObject object = new JsonObject();
        JsonObject camera = new JsonObject();
        JsonObject playerCoordinates = new JsonObject();
        JsonObject npcCoordinates = new JsonObject();
        JsonObject playerObject = new JsonObject();
        JsonObject npcObject = new JsonObject();

        //GENERAL
        object.addProperty("latestMsg", msg);
        object.addProperty("gameCycle", client.getGameCycle());
        object.addProperty("tickCount", tickCount);
        object.addProperty("latestMsg", msg);
        object.addProperty("latestMsgType", msgType);
        object.addProperty("msgTick", msgTick);
        object.addProperty("bankOpen", bankOpen);
        object.addProperty("shopOpen", shopOpen);
        object.addProperty("trawlerRewardOpen", trawlerRewardOpen);
        //PLAYER
        playerObject.addProperty("animation", player.getAnimation());
        playerObject.addProperty("animationPose", player.getPoseAnimation());
        playerObject.addProperty("interactingCode", String.valueOf(player.getInteracting()));
        playerObject.addProperty("runEnergy", client.getEnergy());
        playerObject.addProperty("specialAttackEnergy", client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT));
        playerObject.addProperty("currentPrayer", client.getBoostedSkillLevel(Skill.PRAYER));
        playerObject.addProperty("maxPrayer", client.getRealSkillLevel(Skill.PRAYER));
        playerObject.addProperty("currentHealth", client.getBoostedSkillLevel(Skill.HITPOINTS));
        playerObject.addProperty("maxHealth", client.getRealSkillLevel(Skill.HITPOINTS));
            //PLAYER COORDINATES
            playerCoordinates.addProperty("x", player.getWorldLocation().getX());
            playerCoordinates.addProperty("y", player.getWorldLocation().getY());
            playerCoordinates.addProperty("plane", player.getWorldLocation().getPlane());
            playerCoordinates.addProperty("regionID", player.getWorldLocation().getRegionID());
            playerCoordinates.addProperty("regionX", player.getWorldLocation().getRegionX());
            playerCoordinates.addProperty("regionY", player.getWorldLocation().getRegionY());
        //NPC
        npcObject.addProperty("name", npcName);
        npcObject.addProperty("id", npcId);
        npcObject.addProperty("combatLevel ", npcCombatLvl);
        npcObject.addProperty("currentHealth ", npcCurrentHealth);
        npcObject.addProperty("maxHealth ", npcMaxHealth);
        //PLAYER COORDINATES
            npcCoordinates.addProperty("x", npcX);
            npcCoordinates.addProperty("y", npcY);
            npcCoordinates.addProperty("plane", npcPlane);
            npcCoordinates.addProperty("regionID", npcRegionID);
            npcCoordinates.addProperty("regionX", npcRegionX);
            npcCoordinates.addProperty("regionY", npcRegionY);
        //CAMERA
        camera.addProperty("yaw", client.getCameraYaw());
        camera.addProperty("pitch", client.getCameraPitch());
        camera.addProperty("x", client.getCameraX());
        camera.addProperty("y", client.getCameraY());
        camera.addProperty("z", client.getCameraZ());
        camera.addProperty("x2", client.getCameraX2());
        camera.addProperty("y2", client.getCameraY2());
        camera.addProperty("z2", client.getCameraZ2());

        playerObject.add("playerCoordinates", playerCoordinates);
        npcObject.add("npcCoordinates", npcCoordinates);
        object.add("camera", camera);
        object.add("playerObject", playerObject);
        object.add("npcObject", npcObject);
        object.add("lootArray", lootArray);

        if(resetLootArray.equals("1")){
            lootArray = new JsonArray();
        }

        exchange.sendResponseHeaders(200, 0);
        try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody())) {
            RuneLiteAPI.GSON.toJson(object, out);
        }
    }

    public static int getNpcCurrentHealth(int npcHealthRatio, int npcHealthScale, int npcMaxHealth, int npcCurrentHealth) {
        if (npcHealthRatio > 0)
        {
            int minimumHP = 1;
            int maximumHP;

            if (npcHealthScale > 1)
            {
                if (npcHealthRatio > 1)
                {
                    // This doesn't apply if healthRatio = 1, because of the special case in the server calculation that
                    // health = 0 forces healthRatio = 0 instead of the expected healthRatio = 1
                    minimumHP = (npcMaxHealth * (npcHealthRatio - 1) + npcHealthScale - 2) / (npcHealthScale - 1);
                }
                maximumHP = (npcMaxHealth * npcHealthRatio - 1) / (npcHealthScale - 1);

                if (maximumHP > npcMaxHealth)
                {
                    maximumHP = npcMaxHealth;
                }
            }
            else
            {
                // If healthScale is 1, healthRatio will always be 1 unless health = 0
                // so we know nothing about the upper limit except that it can't be higher than maxHealth
                maximumHP = npcMaxHealth;
            }
            // Take the average of min and max possible healths
            npcCurrentHealth = (minimumHP + maximumHP + 1) / 2;
        }
        return npcCurrentHealth;
    }

    private HttpHandler handlerForInventory() {
        return exchange -> {
            Item[] items = invokeAndWait(() -> {
                ItemContainer itemContainer = client.getItemContainer(InventoryID.INVENTORY);
                if (itemContainer != null) {
                    return itemContainer.getItems();
                }
                return null;
            });

            if (items == null) {
                List<Object> emptyArray = new ArrayList<Object>();
                exchange.sendResponseHeaders(200, 0);
                try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody())) {
                    RuneLiteAPI.GSON.toJson(emptyArray, out);
                }
            }

            List<Object> itemArray = new ArrayList<Object>();

            int count = 0;
                for (Item i : items) {
                    Map<String, Integer> dict = new HashMap<String, Integer>();
                    dict.put("id",i.getId());
                    dict.put("invSlot",count);
                    dict.put("quantity",i.getQuantity());
                    itemArray.add(dict);
                    count++;
                }

            exchange.sendResponseHeaders(200, 0);
            try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody())) {
                RuneLiteAPI.GSON.toJson(itemArray, out);
            }
        };
    }

    private HttpHandler handlerForBank() {
        return exchange -> {
            if (bankItems == null) {
                List<Object> emptyArray = new ArrayList<Object>();
                exchange.sendResponseHeaders(200, 0);
                try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody())) {
                    RuneLiteAPI.GSON.toJson(emptyArray, out);
                }
            }else{
                exchange.sendResponseHeaders(200, 0);
                try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody())) {
                    RuneLiteAPI.GSON.toJson(bankItems, out);
                }
            }
        };
    }

    private HttpHandler handlerForEquipment() {
        return exchange -> {
            Item[] items = invokeAndWait(() -> {
                ItemContainer itemContainer = client.getItemContainer(InventoryID.EQUIPMENT);
                if (itemContainer != null) {
                    return itemContainer.getItems();
                }
                return null;
            });

            if (items == null) {
                List<Object> emptyArray = new ArrayList<Object>();
                exchange.sendResponseHeaders(200, 0);
                try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody())) {
                    RuneLiteAPI.GSON.toJson(emptyArray, out);
                }
            }

            JsonObject equipmentObject = new JsonObject();

            int count = 0;
            for (Item i : items) {
                JsonObject slot = new JsonObject();
                slot.addProperty("id", i.getId());
                slot.addProperty("quantity",i.getQuantity());
                //Do this better, ask Rob
                if(equipmentSlots.values()[count] == equipmentSlots.placeholderA || equipmentSlots.values()[count] == equipmentSlots.placeholderB || equipmentSlots.values()[count] == equipmentSlots.placeholderC) {

                }else{
                    equipmentObject.add(String.valueOf(equipmentSlots.values()[count]), slot);
                }
                count++;
            }

            exchange.sendResponseHeaders(200, 0);
            try (OutputStreamWriter out = new OutputStreamWriter(exchange.getResponseBody())) {
                RuneLiteAPI.GSON.toJson(equipmentObject, out);
            }
        };
    }

    private <T> T invokeAndWait(Callable<T> r) {
        try {
            AtomicReference<T> ref = new AtomicReference<>();
            Semaphore semaphore = new Semaphore(0);
            clientThread.invokeLater(() -> {
                try {

                    ref.set(r.call());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    semaphore.release();
                }
            });
            semaphore.acquire();
            return ref.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
