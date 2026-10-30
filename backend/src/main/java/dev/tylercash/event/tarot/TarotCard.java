package dev.tylercash.event.tarot;

import java.util.List;

/**
 * One card from {@code tarot/cards.json}. {@code suit} is null for the major arcana. {@code upright}
 * and {@code reversed} hold several interchangeable daily readings; a draw stores which one it got.
 */
public record TarotCard(
        String id,
        String name,
        String arcana,
        String suit,
        int number,
        List<String> keywords,
        List<String> upright,
        List<String> reversed) {

    public String imageFileName() {
        return id + ".jpg";
    }

    public List<String> readings(boolean isReversed) {
        return isReversed ? reversed : upright;
    }

    /** The reading at {@code index}, wrapped so a stored index survives the list being shortened. */
    public String reading(boolean isReversed, int index) {
        List<String> options = readings(isReversed);
        return options.get(Math.floorMod(index, options.size()));
    }
}
