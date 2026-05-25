package dev.tylercash.event.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class VenueCompositeRendererTest {

    private final VenueCompositeRenderer renderer = new VenueCompositeRenderer();

    @Test
    void rendersPngAtCanvasSizeWithPhotoAndMap() throws Exception {
        byte[] photo = solidJpeg(800, 600, new Color(0x33, 0x66, 0x99));
        byte[] map = solidPng(260, 260, new Color(0xF2, 0xEF, 0xE6));

        byte[] out = renderer.render(photo, map, "Voltaire · SW5");

        assertThat(out).isNotEmpty();
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(1040);
        assertThat(decoded.getHeight()).isEqualTo(520);
    }

    @Test
    void rendersPhotoOnlyWhenMapMissing() throws Exception {
        byte[] photo = solidJpeg(400, 400, new Color(0xAA, 0x33, 0x33));

        byte[] out = renderer.render(photo, null, "Somewhere");

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(1040);
    }

    @Test
    void rendersBackgroundOnlyWhenPhotoMissing() throws Exception {
        byte[] map = solidPng(260, 260, new Color(0xF2, 0xEF, 0xE6));

        byte[] out = renderer.render(null, map, "Somewhere");

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(1040);
    }

    @Test
    void rendersWhenLabelIsBlank() throws Exception {
        byte[] photo = solidJpeg(400, 400, new Color(0xAA, 0x33, 0x33));

        byte[] out = renderer.render(photo, null, "");

        assertThat(out).isNotEmpty();
    }

    private static byte[] solidJpeg(int w, int h, Color c) throws Exception {
        return solid(w, h, c, "jpg", BufferedImage.TYPE_INT_RGB);
    }

    private static byte[] solidPng(int w, int h, Color c) throws Exception {
        return solid(w, h, c, "png", BufferedImage.TYPE_INT_ARGB);
    }

    private static byte[] solid(int w, int h, Color c, String fmt, int imgType) throws Exception {
        BufferedImage img = new BufferedImage(w, h, imgType);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(c);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, fmt, baos);
        return baos.toByteArray();
    }
}
