package dev.tylercash.event.event;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Composites a venue photo + styled map tile into the Discord embed image specified by the design handoff
 * (1040x520 PNG, photo full-bleed, rotated map sticker bottom-right, gradient + label ribbon along the bottom).
 *
 * <p>If only one of the two inputs is present, falls back gracefully: photo-only emits the photo without the sticker;
 * map-only emits the map centered on a paper background.
 */
@Slf4j
@Component
public class VenueCompositeRenderer {

    private static final int CANVAS_W = 1040;
    private static final int CANVAS_H = 520;

    private static final int INSET_SIZE = 260;
    private static final int INSET_MARGIN = 32;
    private static final int INSET_BORDER = 3;
    private static final int INSET_RADIUS = 20;
    private static final int INSET_SHADOW_OFFSET = 6;
    private static final double INSET_ROTATION_DEG = -2.0;

    private static final Color INK = new Color(0x0E, 0x10, 0x0D);
    private static final Color PAPER = new Color(0xF2, 0xEF, 0xE6);
    private static final Color PIN_GREEN = new Color(0x7B, 0xC2, 0x4F);

    private static final int PIN_WIDTH = 52;
    private static final int PIN_HEIGHT = 65;

    public byte[] render(byte[] photoBytes, byte[] mapBytes, String shortLabel) {
        BufferedImage canvas = new BufferedImage(CANVAS_W, CANVAS_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            drawBackground(g, photoBytes);
            drawBottomGradient(g);
            drawLabelRibbon(g, shortLabel);
            if (mapBytes != null && mapBytes.length > 0) {
                drawMapSticker(g, mapBytes);
            }
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(canvas, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode composite PNG", e);
        }
        return out.toByteArray();
    }

    private void drawBackground(Graphics2D g, byte[] photoBytes) {
        g.setColor(PAPER);
        g.fillRect(0, 0, CANVAS_W, CANVAS_H);
        if (photoBytes == null || photoBytes.length == 0) {
            return;
        }
        BufferedImage photo;
        try {
            photo = ImageIO.read(new ByteArrayInputStream(photoBytes));
        } catch (IOException e) {
            log.warn("Failed to decode venue photo for composite: {}", e.getMessage());
            return;
        }
        if (photo == null) {
            return;
        }
        // object-fit: cover
        double scale = Math.max((double) CANVAS_W / photo.getWidth(), (double) CANVAS_H / photo.getHeight());
        int drawW = (int) Math.round(photo.getWidth() * scale);
        int drawH = (int) Math.round(photo.getHeight() * scale);
        int dx = (CANVAS_W - drawW) / 2;
        int dy = (CANVAS_H - drawH) / 2;
        g.drawImage(photo, dx, dy, drawW, drawH, null);
    }

    private void drawBottomGradient(Graphics2D g) {
        int gradientTop = CANVAS_H - 200;
        GradientPaint paint =
                new GradientPaint(0, gradientTop, new Color(0, 0, 0, 0), 0, CANVAS_H, new Color(0, 0, 0, 179));
        g.setPaint(paint);
        g.fillRect(0, gradientTop, CANVAS_W, 200);
    }

    private void drawLabelRibbon(Graphics2D g, String shortLabel) {
        if (shortLabel == null || shortLabel.isBlank()) {
            return;
        }
        // Small drawn pin glyph in place of the 📍 emoji (AWT has no color emoji font).
        int pinX = 28;
        int pinTop = CANVAS_H - 54;
        drawPinGlyph(g, pinX, pinTop, 28);

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        g.setColor(Color.WHITE);
        int textY = CANVAS_H - 24;
        g.drawString(shortLabel, pinX + 36, textY);
    }

    private void drawMapSticker(Graphics2D g, byte[] mapBytes) {
        BufferedImage map;
        try {
            map = ImageIO.read(new ByteArrayInputStream(mapBytes));
        } catch (IOException e) {
            log.warn("Failed to decode static map for composite: {}", e.getMessage());
            return;
        }
        if (map == null) {
            return;
        }

        int x = CANVAS_W - INSET_MARGIN - INSET_SIZE;
        int y = CANVAS_H - INSET_MARGIN - INSET_SIZE;

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            // Rotate around the sticker's bottom-right corner.
            g2.rotate(Math.toRadians(INSET_ROTATION_DEG), x + INSET_SIZE, y + INSET_SIZE);

            // Hard ink shadow (offset rectangle, no blur).
            g2.setColor(INK);
            g2.fill(new RoundRectangle2D.Double(
                    x + INSET_SHADOW_OFFSET,
                    y + INSET_SHADOW_OFFSET,
                    INSET_SIZE,
                    INSET_SIZE,
                    INSET_RADIUS,
                    INSET_RADIUS));

            // Border block.
            g2.setColor(INK);
            g2.fill(new RoundRectangle2D.Double(x, y, INSET_SIZE, INSET_SIZE, INSET_RADIUS, INSET_RADIUS));

            // Inner map tile, clipped to the border-inset radius.
            int innerX = x + INSET_BORDER;
            int innerY = y + INSET_BORDER;
            int innerSize = INSET_SIZE - INSET_BORDER * 2;
            int innerRadius = INSET_RADIUS - INSET_BORDER;
            Graphics2D inner = (Graphics2D) g2.create();
            try {
                inner.setClip(
                        new RoundRectangle2D.Double(innerX, innerY, innerSize, innerSize, innerRadius, innerRadius));
                inner.drawImage(map, innerX, innerY, innerSize, innerSize, null);
            } finally {
                inner.dispose();
            }

            // Pin in the geometric centre, tip anchored at center.
            int pinX = x + INSET_SIZE / 2 - PIN_WIDTH / 2;
            int pinY = (int) Math.round(y + INSET_SIZE / 2.0 - PIN_HEIGHT * 0.85);
            drawPinGlyph(g2, pinX, pinY, PIN_WIDTH);
        } finally {
            g2.dispose();
        }
    }

    /**
     * Renders the chunky teardrop pin from the design system at {@code (x, y)} with the given width. Height follows the
     * 24:30 viewBox.
     */
    private void drawPinGlyph(Graphics2D g, int x, int y, int width) {
        double scale = width / 24.0;

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(x, y);
            g2.scale(scale, scale);

            // Path approximates the SVG: M12 1.2 c-5.6 0-9.6 4-9.6 9.4 0 5 4.7 9.2 8.4 16 .5.9 1.9.9 2.4 0
            // 3.7-6.8 8.4-11 8.4-16 0-5.4-4-9.4-9.6-9.4z
            GeneralPath teardrop = new GeneralPath();
            teardrop.moveTo(12, 1.2);
            teardrop.curveTo(6.4, 1.2, 2.4, 5.2, 2.4, 10.6);
            teardrop.curveTo(2.4, 15.6, 7.1, 19.8, 10.8, 26.6);
            teardrop.curveTo(11.3, 27.5, 12.7, 27.5, 13.2, 26.6);
            teardrop.curveTo(16.9, 19.8, 21.6, 15.6, 21.6, 10.6);
            teardrop.curveTo(21.6, 5.2, 17.6, 1.2, 12, 1.2);
            teardrop.closePath();

            g2.setColor(PIN_GREEN);
            g2.fill(teardrop);
            g2.setColor(INK);
            g2.setStroke(
                    new java.awt.BasicStroke(1.8f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_ROUND));
            g2.draw(teardrop);

            Ellipse2D dot = new Ellipse2D.Double(12 - 3.2, 10.6 - 3.2, 6.4, 6.4);
            g2.setColor(Color.WHITE);
            g2.fill(dot);
            g2.setStroke(new java.awt.BasicStroke(1.4f));
            g2.setColor(INK);
            g2.draw(dot);
        } finally {
            g2.dispose();
        }
    }
}
