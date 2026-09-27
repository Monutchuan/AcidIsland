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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import com.wasteofplastic.acidisland.ASkyBlock;
import com.wasteofplastic.acidisland.Settings;
import com.wasteofplastic.acidisland.schematics.Schematic;
import com.wasteofplastic.acidisland.util.Util;
import com.wasteofplastic.acidisland.util.VaultHelper;

public class SchematicsPanel implements Listener, InventoryHolder {
    private ASkyBlock plugin;
    private HashMap<UUID, List<SPItem>> schematicItems = new HashMap<UUID, List<SPItem>>();

    /**
     * @param plugin - ASkyBlock plugin object
     */
    public SchematicsPanel(ASkyBlock plugin) {
        this.plugin = plugin;
    }

    /**
     * Returns a customized panel of available Schematics for the player
     * 
     * @param player
     * @return custom Inventory object or null if there are no valid schematics for this player
     */
    public Inventory getPanel(Player player) {
        // Go through the available schematics for this player
        int slot = 0;
        List<SPItem> items = new ArrayList<SPItem>();
        List<Schematic> availableSchems = plugin.getIslandCmd().getSchematics(player, false);
        // Add an info icon
        //items.add(new SPItem(Material.MAP,"Choose your island", "Pick from the selection...",slot++));
        // Generate additional available schematics
        for (Schematic schematic : availableSchems) {
            if (schematic.isVisible()) {
                items.add(new SPItem(schematic, slot++));
            }
        }
        //plugin.getLogger().info("DEBUG: there are " + items.size() + " in the panel");
        // Now create the inventory panel
        if (items.size() > 0) {
            // Save the items for later retrieval when the player clicks on them
            schematicItems.put(player.getUniqueId(), items);
            // Make sure size is a multiple of 9
            int size = items.size() + 8;
            size -= (size % 9);
            // The holder is what identifies this panel later. The title cannot do it:
            // ASkyBlock and AcidIsland both resolve schematics.title to "Select island...".
            Inventory newPanel = Bukkit.createInventory(this, size, plugin.myLocale(player.getUniqueId()).schematicsTitle);
            // Fill the inventory and return
            for (SPItem i : items) {
                newPanel.setItem(i.getSlot(), i.getItem());
            }
            return newPanel;
        } else {
            Util.sendMessage(player, ChatColor.RED + plugin.myLocale().errorCommandNotReady);
        }
        return null;
    }

    /**
     * Handles when the schematics panel is actually clicked
     * @param event
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked(); // The player that
        // clicked the item
        Inventory inventory = event.getInventory(); // The inventory that was clicked in
        int slot = event.getRawSlot();
        // Check this is the right panel
        if (!isOurPanel(inventory)) {
            return;
        }
        if (slot == -999) {
            player.closeInventory();
            inventory.clear();
            schematicItems.remove(player.getUniqueId());
            event.setCancelled(true);
            return;
        }
        if (event.getClick().equals(ClickType.SHIFT_RIGHT)) {
            event.setCancelled(true);
            player.closeInventory();
            inventory.clear();
            schematicItems.remove(player.getUniqueId());
            player.updateInventory();
            return;
        }
        // Get the list of items for this player
        List<SPItem> thisPanel = schematicItems.get(player.getUniqueId());
        if (thisPanel == null) {
            player.closeInventory();
            inventory.clear();
            schematicItems.remove(player.getUniqueId());
            event.setCancelled(true);
            return;
        }
        if (slot >= 0 && slot < thisPanel.size()) {
            event.setCancelled(true);
            // plugin.getLogger().info("DEBUG: slot is " + slot);
            player.closeInventory(); // Closes the inventory
            inventory.clear();
            // Get the item clicked
            SPItem item = thisPanel.get(slot);
            // Check cost
            if (item.getCost() > 0) {
                if (Settings.useEconomy && VaultHelper.setupEconomy() && !VaultHelper.econ.has(player, item.getCost())) {
                    // Too expensive
                    Util.sendMessage(player, ChatColor.RED + plugin.myLocale(player.getUniqueId()).minishopYouCannotAfford.replace("[description]", item.getName()));        
                } else {
                    // Do something
                    if (Settings.useEconomy && VaultHelper.setupEconomy()) {
                        VaultHelper.econ.withdrawPlayer(player, item.getCost());                   
                    } 
                    Util.runCommand(player, Settings.ISLANDCOMMAND + " make " + item.getHeading());
                }
            } else {
                Util.runCommand(player, Settings.ISLANDCOMMAND + " make " + item.getHeading());
            }
            schematicItems.remove(player.getUniqueId());
            thisPanel.clear();   
        }
        return;
    }

    /**
     * True if this plugin built the given inventory.
     *
     * The panel title cannot be used to decide this: ASkyBlock and AcidIsland ship the same
     * class and both resolve schematics.title to "Select island...", so the handler of both
     * plugins ran for the same panel. Both were registered at LOWEST and neither used
     * ignoreCancelled, so the island could be created twice, or created in the other
     * plugin's world by whichever plugin happened to be registered first.
     *
     * Object identity cannot be used either: opening a CHEST-sized custom inventory hands
     * InventoryClickEvent a brand new CraftInventory wrapper built by
     * ContainerChest.getBukkitView(). The holder is the only thing that survives that.
     */
    private boolean isOurPanel(Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof SchematicsPanel;
    }

    /**
     * Required by InventoryHolder. The panel is built per player, so there is no single
     * inventory this object is the holder of - we only need the interface so that the
     * inventory remembers who created it.
     */
    @Override
    public Inventory getInventory() {
        return null;
    }
}