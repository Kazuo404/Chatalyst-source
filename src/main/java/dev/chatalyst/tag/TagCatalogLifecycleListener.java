package dev.chatalyst.tag;

import dev.chatalyst.api.title.TitleCatalogChangeType;

/** Internal port for durable catalog-definition changes, including GUI mutations. */
interface TagCatalogLifecycleListener {

    TagCatalogLifecycleListener NOOP = (type, previous, current) -> {
    };

    void changed(TitleCatalogChangeType type, TagDefinition previous,
                 TagDefinition current);
}
