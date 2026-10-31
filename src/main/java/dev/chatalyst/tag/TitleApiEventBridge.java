package dev.chatalyst.tag;

import dev.chatalyst.ChatalystPlugin;
import dev.chatalyst.api.TitleCatalogChangeEvent;
import dev.chatalyst.api.TitleCollectionCompleteEvent;
import dev.chatalyst.api.TitleEquipEvent;
import dev.chatalyst.api.TitleRevokeEvent;
import dev.chatalyst.api.TitleUnequipEvent;
import dev.chatalyst.api.TitleUnlockEvent;
import dev.chatalyst.api.title.TitleCatalogChangeType;
import dev.chatalyst.api.title.TitleDefinition;
import dev.chatalyst.api.title.TitleMutationContext;
import dev.chatalyst.util.FoliaTasks;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Converts post-commit domain callbacks into public Bukkit lifecycle events. */
final class TitleApiEventBridge implements TagLifecycleListener,
        TagCatalogLifecycleListener {

    private final ChatalystPlugin plugin;
    private final TitleApiSnapshots snapshots;

    TitleApiEventBridge(ChatalystPlugin plugin, TagManager tags,
                        TagCatalog catalog, TitleApiSnapshots snapshots) {
        this.plugin = plugin;
        this.snapshots = snapshots;
        tags.setLifecycleListener(this);
        catalog.setLifecycleListener(this);
    }

    @Override
    public void equipped(UUID playerId, String previousTitleId,
                         TagDefinition previousTitle, TagDefinition title,
                         TitleMutationContext context) {
        call(new TitleEquipEvent(playerId, previousTitleId,
                map(previousTitle), TitleApiMapper.external(title), context));
        refreshOnline(playerId);
    }

    @Override
    public void unequipped(UUID playerId, String previousTitleId,
                           TagDefinition previousTitle,
                           TitleMutationContext context) {
        call(new TitleUnequipEvent(playerId, previousTitleId,
                map(previousTitle), context));
        refreshOnline(playerId);
    }

    @Override
    public void unlocked(UUID playerId, TagDefinition title,
                         TitleMutationContext context) {
        call(new TitleUnlockEvent(
                playerId, TitleApiMapper.external(title), context));
    }

    @Override
    public void revoked(UUID playerId, TagDefinition title,
                        TitleMutationContext context) {
        call(new TitleRevokeEvent(
                playerId, TitleApiMapper.external(title), context));
    }

    @Override
    public void collectionCompleted(UUID playerId, TagCollection collection,
                                    TitleMutationContext context) {
        call(new TitleCollectionCompleteEvent(
                playerId, snapshots.fullCollection(playerId, collection), context));
    }

    @Override
    public void changed(TitleCatalogChangeType type, TagDefinition previous,
                        TagDefinition current) {
        call(new TitleCatalogChangeEvent(type, map(previous), map(current)));
    }

    private void call(org.bukkit.event.Event event) {
        try {
            plugin.getServer().getPluginManager().callEvent(event);
        } catch (RuntimeException rejectedDuringShutdown) {
            plugin.getLogger().fine("Skipped title API event during shutdown: "
                    + event.getEventName());
        }
    }

    private void refreshOnline(UUID playerId) {
        FoliaTasks.global(plugin, () -> {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                FoliaTasks.entity(plugin, player,
                        current -> TagDisplay.refresh(plugin, current));
            }
        });
    }

    private static TitleDefinition map(TagDefinition definition) {
        return definition == null ? null : TitleApiMapper.external(definition);
    }
}
