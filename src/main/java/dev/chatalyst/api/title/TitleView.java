package dev.chatalyst.api.title;

import java.util.Optional;

/** Player-scoped catalog entry. A concealed secret has an empty definition. */
public record TitleView(
        String id,
        boolean unlocked,
        boolean equipped,
        boolean concealed,
        Optional<TitleDefinition> definition
) {

    public TitleView {
        id = id == null ? "" : id;
        definition = definition == null ? Optional.empty() : definition;
    }
}
