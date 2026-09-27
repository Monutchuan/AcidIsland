/*******************************************************************************
 * This file is part of AcidIsland.
 *
 *     AcidIsland is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     AcidIsland is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with AcidIsland.  If not, see <http://www.gnu.org/licenses/>.
 *******************************************************************************/
package com.wasteofplastic.acidisland.panels;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import com.wasteofplastic.acidisland.ASkyBlock;
import com.wasteofplastic.acidisland.Settings;
import com.wasteofplastic.acidisland.events.MiniShopEvent;
import com.wasteofplastic.acidisland.events.MiniShopEvent.TransactionType;
import com.wasteofplastic.acidisland.util.Util;
import com.wasteofplastic.acidisland.util.VaultHelper;

import net.milkbowl.vault.economy.EconomyResponse;

/**
 * @author tastybento
 *         Provides a handy control panel and minishop
 */
public class ControlPanel implements Listener, InventoryHolder {

    private static YamlConfiguration miniShopFile;
    private static HashMap<Integer, MiniShopItem> store = new HashMap<Integer, MiniShopItem>();
    private static YamlConfiguration cpFile;
    private ASkyBlock plugin;
    private static boolean allowSelling;
    private static String defaultPanelName;
    /**
     * The MiniShop and the control panels are shared, static inventories, so they are created
     * from static methods that have no "this". Keep the constructed listener around so those
     * methods can still stamp the inventories with a holder that identifies this plugin.
     */
    private static ControlPanel instance;
    /**
     * Title the MiniShop was created with. Kept so a legacy holder-less inventory can still be
     * recognised.
     */
    private static String miniShopTitle;

    /**
     * @param plugin - ASkyBlock plugin object
     */
    public ControlPanel(ASkyBlock plugin) {
        this.plugin = plugin;
        instance = this;
        if (Settings.useEconomy) {
            loadShop();
        }
        loadControlPanel();
    }

    /**
     * Owner handed to every inventory this class creates. It is what lets a click be matched to
     * the plugin that really opened the panel.
     * @return this listener, or null if it has not been constructed yet
     */
    private static InventoryHolder holder() {
        return instance != null ? instance : null;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }

    /**
     * Is this the MiniShop inventory belonging to this plugin?
     * <p>
     * The title alone is not enough: ASkyBlock and AcidIsland can translate
     * {@code minishop.title} to the same text, and whichever plugin registered first would then
     * swallow the other's clicks. The holder decides it instead - each plugin holds a distinct
     * ControlPanel class (different package, different class loader), so {@code instanceof}
     * can only ever be true for the copy of this plugin.
     * @param inventory inventory to test
     * @return true if it is our MiniShop
     */
    private boolean isMiniShop(Inventory inventory) {
        if (inventory == null || miniShop == null || miniShopTitle == null || inventory.getName() == null) {
            return false;
        }
        if (!inventory.getName().equals(miniShopTitle)) {
            return false;
        }
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof ControlPanel || holder == null;
    }

    /**
     * Is this one of the control panel inventories belonging to this plugin?
     * @param inventory inventory to test
     * @return true if it is one of our control panels
     */
    private boolean isControlPanel(Inventory inventory) {
        if (inventory == null || inventory.getName() == null) {
            return false;
        }
        if (!controlPanel.containsKey(inventory.getName())) {
            return false;
        }
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof ControlPanel || holder == null;
    }

    /**
     * Is this the challenge GUI shown to this player?
     * <p>
     * Unlike the MiniShop, the challenge panel is built per player by
     * {@link com.wasteofplastic.acidisland.commands.Challenges} and carries no holder, so the
     * title is all there is to go on. The two plugins use different titles here, so that is safe.
     * @param player player looking at the inventory
     * @param inventory inventory to test
     * @return true if it is our challenge GUI
     */
    private boolean isChallengePanel(Player player, Inventory inventory) {
        return inventory != null && inventory.getName() != null
                && inventory.getName().equals(plugin.myLocale(player.getUniqueId()).challengesguiTitle);
    }

    /**
     * Does the item the player clicked stand a chance of being the icon in this slot?
     * <p>
     * The old code demanded the stacks be exactly equal. Any difference the client or another
     * plugin introduced in lore, NBT or potion data made the comparison fail and the click was
     * silently dropped - the classic symptom being one shop slot that never responds. Comparing
     * the material is enough to reject empty slots and swapped icons.
     * @param clicked item clicked, may be null
     * @param icon icon configured for that slot, may be null
     * @return true if the click should be acted on
     */
    private boolean sameIcon(ItemStack clicked, ItemStack icon) {
        if (clicked == null || icon == null) {
            return false;
        }
        if (clicked.getType() == Material.AIR || icon.getType() == Material.AIR) {
            return false;
        }
        return clicked.getType() == icon.getType();
    }

    /**
     * Map of panel contents by name
     */
    private static HashMap<String, HashMap<Integer, CPItem>> panels = new HashMap<String, HashMap<Integer, CPItem>>();

    /**
     * Map of CP inventories by name
     */
    public static HashMap<String, Inventory> controlPanel = new HashMap<String, Inventory>();

    public static Inventory miniShop;

    // The first parameter, is the inventory owner. I make it null to let
    // everyone use it.
    // The second parameter, is the slots in a inventory. Must be a multiple of
    // 9. Can be up to 54.
    // The third parameter, is the inventory name. This will accept chat colors.

    /**
     * This loads the minishop from the minishop.yml file
     */
    public static void loadShop() {
        // The first parameter is the Material, then the durability (if wanted),
        // slot, descriptions
        // Minishop
        store.clear();
        miniShopFile = Util.loadYamlFile("minishop.yml");
        allowSelling = miniShopFile.getBoolean("config.allowselling", false);
        ConfigurationSection items = miniShopFile.getConfigurationSection("items");
        ASkyBlock plugin = ASkyBlock.getPlugin();
        if (items != null) {
            // Create the store
            // Get how many the store should be
            int size = items.getKeys(false).size() + 8;
            size -= (size % 9);
            miniShopTitle = plugin.myLocale().islandMiniShopTitle;
            miniShop = Bukkit.createInventory(holder(), size, miniShopTitle);
            // Run through items
            int slot = 0;
            for (String item : items.getKeys(false)) {
                try {
                    String m = items.getString(item + ".material");
                    Material material = Material.matchMaterial(m);
                    int quantity = items.getInt(item + ".quantity", 0);
                    String extra = items.getString(item + ".extra", "");
                    double price = items.getDouble(item + ".price", -1D);
                    double sellPrice = items.getDouble(item + ".sellprice", -1D);
                    if (!allowSelling) {
                        sellPrice = -1;
                    }
                    String description = ChatColor.translateAlternateColorCodes('&',items.getString(item + ".description",""));
                    MiniShopItem shopItem = new MiniShopItem(material, extra, slot, description, quantity, price, sellPrice);
                    store.put(slot, shopItem);
                    miniShop.setItem(slot, shopItem.getItem());
                    slot++;
                } catch (Exception e) {
                    plugin.getLogger().warning("Problem loading minishop item #" + slot);
                    plugin.getLogger().warning(e.getMessage());
                    e.printStackTrace();
                }
            }

        }
    }

    /**
     * This loads the control panel from the controlpanel.yml file
     */
    public static void loadControlPanel() {
        ASkyBlock plugin = ASkyBlock.getPlugin();
        // Map of known panel contents by name
        panels.clear();
        // Map of panel inventories by name
        controlPanel.clear();
        cpFile = Util.loadYamlFile("controlpanel.yml");
        ConfigurationSection controlPanels = cpFile.getRoot();
        if (controlPanels == null) {
            plugin.getLogger().severe("Controlpanel.yml is corrupted! Delete so it can be regenerated or fix!");
            return;
        }
        // Go through the yml file and create inventories and panel maps
        for (String panel : controlPanels.getKeys(false)) {
            // plugin.getLogger().info("DEBUG: Panel " + panel);
            ConfigurationSection panelConf = cpFile.getConfigurationSection(panel);
            if (panelConf != null) {
                // New panel map
                HashMap<Integer, CPItem> cp = new HashMap<Integer, CPItem>();
                String panelName = ChatColor.translateAlternateColorCodes('&', panelConf.getString("panelname", "Commands"));
                if (panel.equalsIgnoreCase("default")) {
                    defaultPanelName = panelName;
                }
                ConfigurationSection buttons = cpFile.getConfigurationSection(panel + ".buttons");
                if (buttons != null) {
                    // Get how many buttons can be in the CP
                    int size = buttons.getKeys(false).size() + 8;
                    size -= (size % 9);
                    // Add inventory to map of inventories
                    controlPanel.put(panelName, Bukkit.createInventory(holder(), size, panelName));
                    // Run through buttons
                    int slot = 0;
                    for (String item : buttons.getKeys(false)) {
                        try {
                            String m = buttons.getString(item + ".material", "BOOK");
                            // Split off damage
                            String[] icon = m.split(":");
                            Material material = Material.matchMaterial(icon[0]);
                            if (material == null) {
                                material = Material.PAPER;
                                plugin.getLogger().severe("Error in controlpanel.yml " + icon[0] + " is an unknown material, using paper.");
                            }
                            String description = ChatColor.translateAlternateColorCodes('&',buttons.getString(item + ".description", ""));
                            String command = buttons.getString(item + ".command", "").replace("[island]", Settings.ISLANDCOMMAND);
                            String nextSection = buttons.getString(item + ".nextsection", "");
                            ItemStack i = new ItemStack(material);
                            if (icon.length == 2) {
                                i.setDurability(Short.parseShort(icon[1]));
                            }
                            CPItem cpItem = new CPItem(i, description, command, nextSection);
                            cp.put(slot, cpItem);
                            controlPanel.get(panelName).setItem(slot, cpItem.getItem());
                            slot++;
                        } catch (Exception e) {
                            plugin.getLogger().warning("Problem loading control panel " + panel + " item #" + slot);
                            plugin.getLogger().warning(e.getMessage());
                            e.printStackTrace();
                        }
                    }
                    // Add overall control panel
                    panels.put(panelName, cp);
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled=true)
    public void onInventoryClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked(); // The player that
        // clicked the item
        ItemStack clicked = event.getCurrentItem(); // The item that was clicked
        Inventory inventory = event.getInventory(); // The inventory that was clicked in
        if (inventory.getName() == null) {
            return;
        }
        // ASkyBlock plugin = ASkyBlock.getPlugin();
        int slot = event.getRawSlot();
        // Challenges
        if (inventory.getName().equals(plugin.myLocale(player.getUniqueId()).challengesguiTitle)) {
            event.setCancelled(true);
            if (event.getClick().equals(ClickType.SHIFT_RIGHT)) {
                inventory.clear();
                player.closeInventory();
                player.updateInventory();
                return;
            }
            if (event.getSlotType() == SlotType.OUTSIDE) {
                inventory.clear();
                player.closeInventory();
                return;
            }

            // Get the list of items in this inventory
            List<CPItem> challenges = plugin.getChallenges().getCP(player);
            if (challenges == null) {
                plugin.getLogger().warning("Player was accessing Challenge Inventory, but it had lost state - was server restarted?");
                inventory.clear();
                player.closeInventory();
                Util.runCommand(player, Settings.CHALLENGECOMMAND);
                return;
            }
            if (slot >= 0 && slot < challenges.size()) {
                CPItem item = challenges.get(slot);
                // Check that it is the top items that are being clicked on
                // These two should be identical because it is made before
                if (clicked.equals(item.getItem())) {
                    // Next section indicates the level of panel to open
                    if (item.getNextSection() != null) {
                        inventory.clear();
                        Inventory newInventory = plugin.getChallenges().challengePanel(player, item.getNextSection());
                        // Update inventory
                        if(player.getOpenInventory().getTopInventory() != null) {                        	
                        	if(inventory.equals(player.getOpenInventory().getTopInventory())) {
                        		if(inventory.getSize() == newInventory.getSize()) {
                        			inventory.setContents(newInventory.getContents());
                        			return;
                        		}
                        	}
                        }
                        // Open new Inventory if update is not possible
                        player.closeInventory();
                        player.openInventory(newInventory);
                        
                        
                    } else if (item.getCommand() != null) {
                        Util.runCommand(player, item.getCommand());
                        inventory.clear();                        
                        
                        // Update inventory
                        if(player.getOpenInventory().getTopInventory() != null) {
                        	if(inventory.equals(player.getOpenInventory().getTopInventory())) {
                        		Inventory newInventory = plugin.getChallenges().challengePanel(player);
                        		if(inventory.getSize() == newInventory.getSize()) {
                        			inventory.setContents(newInventory.getContents());
                        			return;
                        		}
                        	}
                        }
                        
                        // Open new Inventory if update is not possible
                        player.closeInventory();
                        Bukkit.getScheduler().runTask(plugin, () -> player.openInventory(plugin.getChallenges().challengePanel(player)));
                        
                        
                        
                    }
                }
            }
            return;
        }
        /*
         * Minishop section
         */
        if (miniShop != null && inventory.getName().equals(miniShop.getName())) {
            String message = "";
            event.setCancelled(true); // Don't let them pick it up
            if (!Settings.useEconomy || slot == -999) {
                player.closeInventory();
                return;
            }
            if (event.getClick().equals(ClickType.SHIFT_RIGHT)) {                    
                player.closeInventory();
                player.updateInventory();
                return;
            }
            if (store.containsKey(slot)) {
                // We have a winner!
                MiniShopItem item = store.get(slot);
                if (sameIcon(clicked, item.getItem())) {
                    // Check what type of click - LEFT = BUY, RIGHT = sell
                    if (event.getClick().equals(ClickType.LEFT)) {
                        // Check if item is for sale
                        if (item.getPrice() > 0D) {
                            // Check they can afford it
                            if (!VaultHelper.econ.has(player, Settings.worldName, item.getPrice())) {
                                message = (plugin.myLocale().minishopYouCannotAfford).replace("[description]", item.getDescription());
                            } else {
                                EconomyResponse r = VaultHelper.econ.withdrawPlayer(player, Settings.worldName, item.getPrice());
                                if (r.transactionSuccess()) {
                                    message = plugin.myLocale().minishopYouBought.replace("[number]", Integer.toString(item.getQuantity()));
                                    message = message.replace("[description]", item.getDescription());
                                    message = message.replace("[price]", VaultHelper.econ.format(item.getPrice()));
                                    Map<Integer, ItemStack> items = player.getInventory().addItem(item.getItemClean());
                                    if (!items.isEmpty()) {
                                        for (ItemStack i : items.values()) {
                                            player.getWorld().dropItem(player.getLocation(), i);
                                        }
                                    }
                                    // Fire event
                                    MiniShopEvent shopEvent = new MiniShopEvent(player.getUniqueId(), item, TransactionType.BUY);
                                    plugin.getServer().getPluginManager().callEvent(shopEvent);
                                } else {
                                    message = (plugin.myLocale().minishopBuyProblem).replace("[description]", item.getDescription());
                                }
                            }
                        }
                    } else if (event.getClick().equals(ClickType.RIGHT) && allowSelling && item.getSellPrice() > 0D) {
                        // Check if they have the item
                        if (player.getInventory().containsAtLeast(item.getItemClean(), item.getQuantity())) {
                            player.getInventory().removeItem(item.getItemClean());
                            VaultHelper.econ.depositPlayer(player, Settings.worldName, item.getSellPrice());
                            message = plugin.myLocale().minishopYouSold.replace("[number]", Integer.toString(item.getQuantity()));
                            message = message.replace("[description]", item.getDescription());
                            message = message.replace("[price]", VaultHelper.econ.format(item.getSellPrice()));
                            // Fire event
                            MiniShopEvent shopEvent = new MiniShopEvent(player.getUniqueId(), item, TransactionType.SELL);
                            plugin.getServer().getPluginManager().callEvent(shopEvent);
                        } else {
                            message = (plugin.myLocale().minishopSellProblem).replace("[description]", item.getDescription());
                            ;
                        }
                    }
                    if (!message.isEmpty()) {
                        Util.sendMessage(player, message);
                    }
                }
            }
            return;
        }
        // Check control panels
        if (isControlPanel(inventory)) {
            event.setCancelled(true);
            if (slot == -999) {
                player.closeInventory();
                return;
            }
            if (event.getClick().equals(ClickType.SHIFT_RIGHT)) {
                player.closeInventory();
                player.updateInventory();
                return;
            }
            HashMap<Integer, CPItem> thisPanel = panels.get(inventory.getName());
            if (thisPanel != null && slot >= 0 && slot < thisPanel.size()) {
                // Do something
                String command = thisPanel.get(slot).getCommand();
                String nextSection = ChatColor.translateAlternateColorCodes('&', thisPanel.get(slot).getNextSection());
                if (!command.isEmpty()) {
                    player.closeInventory(); // Closes the inventory
                    event.setCancelled(true);
                    Util.runCommand(player, command);
                    return;
                }
                if (!nextSection.isEmpty()) {
                    player.closeInventory(); // Closes the inventory
                    Inventory next = controlPanel.get(nextSection);
                    player.openInventory(next);
                    event.setCancelled(true);
                    return;
                }
                player.closeInventory(); // Closes the inventory
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Stop players from dragging icons out of the panels.
     * <p>
     * Cancelling InventoryClickEvent does not cover dragging: a drag never fires a click, so an
     * icon could be pulled into a player's own bag. The MiniShop and the control panels are one
     * shared inventory for the whole server, so a single dragged icon left every other player
     * looking at a hole until the next restart.
     * @param event drag event
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory inventory = event.getInventory();
        if (inventory == null || inventory.getName() == null) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (!isChallengePanel(player, inventory) && !isMiniShop(inventory) && !isControlPanel(inventory)) {
            return;
        }
        // Only block drags that touch the panel itself. A drag confined to the player's own
        // bag is none of our business.
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < inventory.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * @return the defaultPanelName
     */
    public static String getDefaultPanelName() {
        return defaultPanelName;
    }

}