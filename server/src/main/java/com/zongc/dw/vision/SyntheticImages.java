package com.zongc.dw.vision;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Random;

/** 程序画出的"路面照片"（灰色路面、蓝色汽车、绿树，可选一个橙色锥桶），用于测试、测速和网页示例。 */
public final class SyntheticImages {
    private SyntheticImages() {}

    /**
     * @param coneCx      锥桶中心的横坐标（像素）
     * @param coneHeight  锥桶高度（像素），0 表示不画锥桶
     * @param seed        噪声的随机种子
     */
    public static BufferedImage roadScene(int w, int h, double coneCx, int coneHeight, long seed) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(200, 205, 210));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(90, 95, 100));
        g.fillPolygon(new int[]{(int) (w * 0.35), (int) (w * 0.65), w, 0}, new int[]{(int) (h * 0.42), (int) (h * 0.42), h, h}, 4);
        g.setColor(new Color(235, 235, 235));
        for (int i = 0; i < 6; i++) {
            int y0 = (int) (h * (0.45 + i * 0.09)), y1 = y0 + (int) (h * 0.04);
            g.fillPolygon(new int[]{(int) (w * 0.495), (int) (w * 0.505), (int) (w * 0.51), (int) (w * 0.49)},
                    new int[]{y0, y0, y1, y1}, 4);
        }
        g.setColor(new Color(40, 90, 200));
        g.fillRect((int) (w * 0.05), (int) (h * 0.55), (int) (w * 0.18), (int) (h * 0.12));
        g.setColor(new Color(60, 150, 70));
        g.fillOval((int) (w * 0.8), (int) (h * 0.1), (int) (w * 0.12), (int) (h * 0.2));
        if (coneHeight > 0) {
            int base = (int) (h * 0.85), top = base - coneHeight, half = (int) (coneHeight * 0.32);
            int cx = (int) Math.round(coneCx);
            g.setColor(new Color(255, 110, 20));
            g.fillPolygon(new int[]{cx, cx + half, cx - half}, new int[]{top, base, base}, 3);
            g.setColor(new Color(250, 250, 250));   // 白色反光条
            int b0 = top + coneHeight * 4 / 10, b1 = top + coneHeight * 55 / 100;
            g.fillRect(cx - half, b0, half * 2, b1 - b0);
        }
        g.dispose();
        Random rng = new Random(seed);
        for (int i = 0; i < w * h / 6; i++) {
            int x = rng.nextInt(w), y = rng.nextInt(h);
            int p = img.getRGB(x, y), n = rng.nextInt(41) - 20;
            int r = clamp(((p >> 16) & 0xff) + n), gg = clamp(((p >> 8) & 0xff) + n), b = clamp((p & 0xff) + n);
            img.setRGB(x, y, (r << 16) | (gg << 8) | b);
        }
        return img;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
