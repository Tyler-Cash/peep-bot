package dev.tylercash.event.tarot;

/** One card from {@code tarot/cards.json}. {@code suit} is null for the major arcana. */
public record TarotCard(
        String id, String name, String arcana, String suit, int number, String upright, String reversed) {

    public String imageFileName() {
        return id + ".jpg";
    }
}
