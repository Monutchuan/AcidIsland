package com.wasteofplastic.acidisland.util;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import com.wasteofplastic.acidisland.ASkyBlock;

/**
 * Supplies player heads for the warp panel and the top ten list.
 *
 * <p>Why this class does its own HTTP: handing a skull to the server was blocking the main
 * thread. {@code CraftMetaSkull.applyToItem()} calls {@code TileEntitySkull.b()} for every
 * skull it converts, and that resolves the skin by asking Mojang over HTTPS - <em>on whatever
 * thread called it</em>. When Mojang is slow or unreachable the server thread stalls until the
 * socket times out, which is what produces the "server has not responded for 10 seconds" dump
 * with {@code fillGameProfile} at the top of the stack.
 *
 * <p>{@code TileEntitySkull.b()} short-circuits before any network access when the profile is
 * complete (has an id and a name) and already carries a {@code textures} property - verified in
 * the 1.12.2 bytecode:
 * <pre>
 *     if (profile.isComplete() &amp;&amp; profile.getProperties().containsKey("textures"))
 *         return profile;                       // no network
 *     else ... skinCache lookup, then HTTPS     // blocks the calling thread
 * </pre>
 * So the rule this class follows is: <b>never put a profile without textures into an item on
 * the main thread</b>. Textures are fetched off-thread with a hard timeout and cached, and only
 * a finished profile is ever applied.
 */
public class HeadGetter {
    private final Map<UUID,HeadInfo> cachedHeads = new HashMap<>();
    private final Map<UUID,String> names = new ConcurrentHashMap<>();
    private final Map<UUID,Set<Requester>> headRequesters = new HashMap<>();
    /** uuid -> {texture value, signature}. Written off the main thread, read from it. */
    private final Map<UUID,String[]> textures = new ConcurrentHashMap<>();
    /** uuid -> epoch ms before which we will not ask Mojang again after a failure. */
    private final Map<UUID,Long> failures = new ConcurrentHashMap<>();
    private final ASkyBlock plugin;
    private static final String CACHE_FILE = "headcache.yml";
    // Long enough to survive a slow handshake, short enough that a stalled request never
    // matters - this runs off the main thread, so the server keeps ticking regardless.
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 6000;
    // Mojang rate-limits the session server, so don't hammer it after a failure.
    private static final long FAILURE_COOLDOWN_MS = 5L * 60L * 1000L;
    private static final boolean DEBUG = false;

    /**
     * @param plugin
     */
    public HeadGetter(ASkyBlock plugin) {
        super();
        this.plugin = plugin;
        loadCache();
        runPlayerHeadGetter();
    }

    /**
     * Resolves one queued head per tick of the timer. This task does the network - and only the
     * network. Every Bukkit call (building the item, handing it to the requester) is deferred to
     * the main thread below, because touching inventories from an async thread is not safe.
     */
    private void runPlayerHeadGetter() {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            UUID uuid = null;
            String name = null;
            synchronized(names) {
                Iterator<Entry<UUID,String>> it = names.entrySet().iterator();
                if (it.hasNext()) {
                    Entry<UUID,String> en = it.next();
                    uuid = en.getKey();
                    name = en.getValue();
                    it.remove();
                }
            }
            if (uuid == null) {
                return;
            }
            String[] texture = textureFor(uuid);
            final UUID finalUuid = uuid;
            final String finalName = name;
            final String[] finalTexture = texture;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                // A head without a texture is still shown - a plain skull beats an empty slot -
                // but it is not cached, so a later request picks the real skin up once the
                // cooldown has passed.
                HeadInfo info = new HeadInfo(finalName, finalUuid, buildHead(finalName, finalUuid, finalTexture));
                if (finalTexture != null) {
                    cachedHeads.put(finalUuid, info);
                }
                Set<Requester> requesters = headRequesters.remove(finalUuid);
                if (requesters != null) {
                    for (Requester req : requesters) {
                        req.setHead(info);
                    }
                }
            });
        }, 0L, 20L);
    }

    /**
     * Returns the skin texture for a player, or null if it could not be obtained right now.
     * Tries, in order: an already logged-in player (their profile was filled at login, so this
     * costs nothing), the on-disk cache, then Mojang. Only the last one touches the network.
     */
    private String[] textureFor(UUID uuid) {
        String[] cached = textures.get(uuid);
        if (cached != null) {
            return cached;
        }
        String[] online = texturesFromOnlinePlayer(uuid);
        if (online != null) {
            textures.put(uuid, online);
            saveCache();
            return online;
        }
        Long retryAfter = failures.get(uuid);
        if (retryAfter != null && System.currentTimeMillis() < retryAfter) {
            return null;
        }
        String[] fetched = fetchTextures(uuid);
        if (fetched != null) {
            textures.put(uuid, fetched);
            saveCache();
        } else {
            failures.put(uuid, System.currentTimeMillis() + FAILURE_COOLDOWN_MS);
        }
        return fetched;
    }

    /**
     * Reads the texture straight off a logged-in player's profile. Never performs network access:
     * the server filled this profile when the player joined.
     */
    private String[] texturesFromOnlinePlayer(UUID uuid) {
        try {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                return null;
            }
            Object profile = player.getClass().getMethod("getProfile").invoke(player);
            if (profile == null) {
                return null;
            }
            return texturesFromProfile(profile);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Pulls {value, signature} of the "textures" property out of an authlib GameProfile. */
    private String[] texturesFromProfile(Object profile) throws Exception {
        Object properties = profile.getClass().getMethod("getProperties").invoke(profile);
        Object found = properties.getClass().getMethod("get", Object.class).invoke(properties, "textures");
        if (!(found instanceof Collection) || ((Collection<?>) found).isEmpty()) {
            return null;
        }
        Object property = ((Collection<?>) found).iterator().next();
        Class<?> propertyClass = property.getClass();
        String value = (String) propertyClass.getMethod("getValue").invoke(property);
        if (value == null || value.isEmpty()) {
            return null;
        }
        String signature = (String) propertyClass.getMethod("getSignature").invoke(property);
        return new String[] { value, signature };
    }

    /**
     * Asks Mojang's session server for the profile belonging to a UUID and returns its texture.
     *
     * This is deliberately our own request rather than a Bukkit/NMS call: it lets us set connect
     * and read timeouts. The NMS path has no timeout at all, which is exactly why it can stall
     * the server thread for as long as it likes. Never call this on the main thread.
     */
    private String[] fetchTextures(UUID uuid) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://sessionserver.mojang.com/session/minecraft/profile/"
                    + uuid.toString().replace("-", "") + "?unsigned=false");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            if (connection.getResponseCode() != 200) {
                return null;
            }
            StringBuilder body = new StringBuilder();
            InputStream in = connection.getInputStream();
            try {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    body.append(new String(buffer, 0, read, "UTF-8"));
                }
            } finally {
                in.close();
            }
            return parseTexture(body.toString());
        } catch (Throwable t) {
            if (DEBUG) {
                plugin.getLogger().info("DEBUG: skin lookup failed for " + uuid + ": " + t);
            }
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Pulls the textures property out of the session server's JSON reply. Hand-rolled rather than
     * pulling in a JSON library: the reply is small and the fields we want are base64, so a plain
     * scan is enough and cannot fail on a missing dependency.
     */
    private String[] parseTexture(String json) {
        String value = jsonString(json, "value", 0);
        if (value == null) {
            return null;
        }
        return new String[] { value, jsonString(json, "signature", 0) };
    }

    /** Value of the first "key":"..." in json at or after fromIndex, or null if absent. */
    private String jsonString(String json, String key, int fromIndex) {
        int at = json.indexOf("\"" + key + "\"", fromIndex);
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at);
        if (colon < 0) {
            return null;
        }
        int open = json.indexOf('"', colon + 1);
        if (open < 0) {
            return null;
        }
        int close = json.indexOf('"', open + 1);
        if (close < 0) {
            return null;
        }
        return json.substring(open + 1, close);
    }

    /**
     * Builds the skull. Must run on the main thread.
     *
     * @param texture textures property, or null for a plain skull
     */
    @SuppressWarnings("deprecation")
    private ItemStack buildHead(String name, UUID uuid, String[] texture) {
        ItemStack playerSkull = new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
        SkullMeta meta = (SkullMeta) playerSkull.getItemMeta();
        meta.setDisplayName(ChatColor.WHITE + name);
        if (texture != null && !applyCompleteProfile(meta, uuid, name, texture)) {
            // Reflection did not find the profile field (different server build). A plain skull
            // is the safe fallback; calling setOwner would reintroduce the blocking fetch.
            plugin.getLogger().warning("Could not apply a cached skin for " + name
                    + " - falling back to a plain head.");
        }
        playerSkull.setItemMeta(meta);
        return playerSkull;
    }

    /**
     * Puts a finished GameProfile (id + name + textures) on the skull meta.
     *
     * This is what keeps the main thread off the network: with the textures already present,
     * {@code TileEntitySkull.b()} returns the profile as-is instead of resolving it.
     *
     * @return true if the profile was applied
     */
    private boolean applyCompleteProfile(SkullMeta meta, UUID uuid, String name, String[] texture) {
        try {
            Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
            Object profile = gameProfileClass.getConstructor(UUID.class, String.class).newInstance(uuid, name);
            Object property = (texture[1] == null || texture[1].isEmpty())
                    ? propertyClass.getConstructor(String.class, String.class).newInstance("textures", texture[0])
                    : propertyClass.getConstructor(String.class, String.class, String.class)
                            .newInstance("textures", texture[0], texture[1]);
            Object properties = gameProfileClass.getMethod("getProperties").invoke(profile);
            putProperty(properties, "textures", property);
            Field field = profileField(meta.getClass(), gameProfileClass);
            if (field == null) {
                return false;
            }
            field.setAccessible(true);
            field.set(meta, profile);
            return true;
        } catch (Throwable t) {
            if (DEBUG) {
                plugin.getLogger().info("DEBUG: could not apply profile for " + name + ": " + t);
            }
            return false;
        }
    }

    /** properties.put(key, value) on an authlib PropertyMap, found reflectively. */
    private void putProperty(Object properties, String key, Object value) throws Exception {
        try {
            properties.getClass().getMethod("put", Object.class, Object.class).invoke(properties, key, value);
            return;
        } catch (NoSuchMethodException e) {
            // fall through - some builds only expose the bridge method
        }
        for (Method method : properties.getClass().getMethods()) {
            if (method.getName().equals("put") && method.getParameterTypes().length == 2) {
                method.invoke(properties, key, value);
                return;
            }
        }
        throw new NoSuchMethodException("put");
    }

    /** The GameProfile field on a SkullMeta implementation, searched up the class hierarchy. */
    private Field profileField(Class<?> start, Class<?> gameProfileClass) {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().equals(gameProfileClass)) {
                    return f;
                }
            }
        }
        return null;
    }

    public void getHead(UUID playerUUID, Requester requester) {
        if (playerUUID == null) {
            return;
        }
        String name = plugin.getPlayers().getName(playerUUID);
        if (name == null || name.isEmpty()) {
            return;
        }
        // Check if in cache
        if (cachedHeads.containsKey(playerUUID)) {
            requester.setHead(cachedHeads.get(playerUUID));
        } else {
            // Get the name
            headRequesters.putIfAbsent(playerUUID, new HashSet<>());
            Set<Requester> requesters = headRequesters.get(playerUUID);
            requesters.add(requester);
            headRequesters.put(playerUUID, requesters);
            names.put(playerUUID, name);
        }
    }

    /** Cached skins survive a restart, so a fresh server does not re-ask Mojang for everyone. */
    private void loadCache() {
        File file = new File(plugin.getDataFolder(), CACHE_FILE);
        if (!file.exists()) {
            return;
        }
        try {
            YamlConfiguration cache = YamlConfiguration.loadConfiguration(file);
            for (String key : cache.getKeys(false)) {
                String value = cache.getString(key + ".value");
                if (value == null || value.isEmpty()) {
                    continue;
                }
                try {
                    textures.put(UUID.fromString(key), new String[] { value, cache.getString(key + ".signature") });
                } catch (IllegalArgumentException notAUuid) {
                    // ignore entries that are not a UUID
                }
            }
            if (!textures.isEmpty()) {
                plugin.getLogger().info("Loaded " + textures.size() + " cached player skin(s) from " + CACHE_FILE + ".");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not read " + CACHE_FILE + ": " + t.getMessage());
        }
    }

    private void saveCache() {
        try {
            YamlConfiguration cache = new YamlConfiguration();
            for (Entry<UUID,String[]> entry : textures.entrySet()) {
                String path = entry.getKey().toString();
                cache.set(path + ".value", entry.getValue()[0]);
                if (entry.getValue()[1] != null) {
                    cache.set(path + ".signature", entry.getValue()[1]);
                }
            }
            cache.save(new File(plugin.getDataFolder(), CACHE_FILE));
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not write " + CACHE_FILE + ": " + t.getMessage());
        }
    }

    public class HeadInfo {
        String name = "";
        UUID uuid;
        ItemStack head;
        /**
         * @param name
         * @param uuid
         * @param head
         */
        public HeadInfo(String name, UUID uuid, ItemStack head) {
            this.name = name;
            this.uuid = uuid;
            this.head = head;
        }
        /**
         * @return the name
         */
        public String getName() {
            return name;
        }
        /**
         * @return the uuid
         */
        public UUID getUuid() {
            return uuid;
        }
        /**
         * @return the head
         */
        public ItemStack getHead() {
            return head.clone();
        }

    }
}
