package dev.tylercash.event.tarot;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import javax.imageio.ImageIO;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The bundled 78-card deck: meanings from {@code tarot/cards.json}, art from {@code tarot/soimoi/}.
 * See {@code tarot/ATTRIBUTION.md} for licensing.
 */
@Component
public class TarotDeck {

    public static final String ATTRIBUTION = "Art: \"Soimoi Tarot\" by Mike Koz (koz.tv) · CC BY 4.0";

    private static final String CARDS_PATH = "tarot/cards.json";
    private static final String IMAGE_DIR = "tarot/soimoi/";

    private final Map<String, TarotCard> cards;

    public TarotDeck(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(CARDS_PATH).getInputStream()) {
            List<TarotCard> list = objectMapper.readValue(in, new TypeReference<>() {});
            Map<String, TarotCard> byId = new LinkedHashMap<>();
            list.forEach(c -> byId.put(c.id(), c));
            this.cards = Map.copyOf(byId);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + CARDS_PATH, e);
        }
        if (cards.size() != 78) {
            throw new IllegalStateException("Expected 78 tarot cards, found " + cards.size());
        }
    }

    public int size() {
        return cards.size();
    }

    public List<TarotCard> all() {
        return List.copyOf(cards.values());
    }

    public Optional<TarotCard> byId(String id) {
        return Optional.ofNullable(cards.get(id));
    }

    public TarotCard random(RandomGenerator random) {
        List<TarotCard> list = all();
        return list.get(random.nextInt(list.size()));
    }

    /** JPEG bytes for the card art, rotated 180° when the card was drawn reversed. */
    public byte[] image(TarotCard card, boolean reversed) {
        try (InputStream in = new ClassPathResource(IMAGE_DIR + card.imageFileName()).getInputStream()) {
            if (!reversed) {
                return in.readAllBytes();
            }
            BufferedImage src = ImageIO.read(in);
            BufferedImage rotated = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rotated.createGraphics();
            try {
                g.rotate(Math.PI, src.getWidth() / 2.0, src.getHeight() / 2.0);
                g.drawImage(src, 0, 0, null);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(rotated, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read tarot image for " + card.id(), e);
        }
    }
}
