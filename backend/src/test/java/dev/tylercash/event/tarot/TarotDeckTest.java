package dev.tylercash.event.tarot;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TarotDeckTest {

    private final TarotDeck deck = new TarotDeck(JsonMapper.builder().build());

    @Test
    void loadsAllSeventyEightCardsWithUniqueIds() {
        assertThat(deck.size()).isEqualTo(78);
        assertThat(deck.all()).filteredOn(c -> "major".equals(c.arcana())).hasSize(22);
        for (String suit : new String[] {"wands", "cups", "swords", "pentacles"}) {
            assertThat(deck.all()).filteredOn(c -> suit.equals(c.suit())).hasSize(14);
        }
        assertThat(deck.byId("major_17")).get().extracting(TarotCard::name).isEqualTo("The Star");
        assertThat(deck.byId("pentacles_14")).get().extracting(TarotCard::name).isEqualTo("King of Pentacles");
    }

    @Test
    void everyCardHasMeaningsAndArt() throws Exception {
        for (TarotCard card : deck.all()) {
            assertThat(card.keywords()).as(card.id()).hasSize(3).allSatisfy(k -> assertThat(k)
                    .isNotBlank());
            assertThat(card.upright()).as(card.id()).hasSize(3).allSatisfy(r -> assertThat(r)
                    .isNotBlank());
            assertThat(card.reversed()).as(card.id()).hasSize(3).allSatisfy(r -> assertThat(r)
                    .isNotBlank());
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(deck.image(card, false)));
            assertThat(img).as(card.id()).isNotNull();
        }
    }

    @Test
    void reversedImageIsRotatedSameSize() throws Exception {
        TarotCard card = deck.byId("major_00").orElseThrow();
        BufferedImage upright = ImageIO.read(new ByteArrayInputStream(deck.image(card, false)));
        BufferedImage reversed = ImageIO.read(new ByteArrayInputStream(deck.image(card, true)));

        assertThat(reversed.getWidth()).isEqualTo(upright.getWidth());
        assertThat(reversed.getHeight()).isEqualTo(upright.getHeight());
    }

    @Test
    void readingIndexPicksFromTheMatchingOrientationAndWraps() {
        TarotCard card = deck.byId("major_16").orElseThrow();

        assertThat(card.reading(false, 1)).isEqualTo(card.upright().get(1));
        assertThat(card.reading(true, 2)).isEqualTo(card.reversed().get(2));
        assertThat(card.reading(true, 5)).isEqualTo(card.reversed().get(2));
    }

    @Test
    void randomAlwaysReturnsADeckCard() {
        Random random = new Random(42);
        for (int i = 0; i < 200; i++) {
            assertThat(deck.all()).contains(deck.random(random));
        }
    }
}
