package dev.chunkdiff;

import dev.chunkdiff.debug.DebugCommand;
import dev.chunkdiff.delta.DeltaStore;
import dev.chunkdiff.delta.RestoreListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ChunkDiffPlugin extends JavaPlugin {
    private DeltaStore store;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.store = new DeltaStore(getDataPath().resolve("deltas"));
        getServer().getPluginManager().registerEvents(new RestoreListener(this, store), this);
        getServer().getCommandMap().register("chunkdiff", new DebugCommand(this, store));
        checkVersion();
        getLogger().info("ChunkDiff actif : les chunks vanilla supprimes seront regeneres, les deltas reappliques.");
    }

    /** Les chunks supprimes sont regeneres par le generateur de la version courante : si elle change, le terrain change. */
    private void checkVersion() {
        try {
            Path vf = store.root().resolve("mc-version.txt");
            if (!Files.exists(vf)) return;
            String old = Files.readString(vf).trim();
            String cur = Bukkit.getMinecraftVersion();
            if (!old.equals(cur)) {
                getLogger().severe("!!! Version Minecraft changee (" + old + " -> " + cur + ") depuis la compaction. "
                        + "Les chunks supprimes seront regeneres par le NOUVEAU generateur : le terrain peut differer "
                        + "de l'ancien et presenter des coupures. Restaurez la sauvegarde chunkdiff-backup-* si besoin.");
            }
        } catch (Exception e) {
            getLogger().warning("Verification de version impossible : " + e);
        }
    }

    public DeltaStore store() { return store; }
}
