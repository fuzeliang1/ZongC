package com.zongc.dw.vision;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 颜色分割 + 连通域分析：在照片里找出路障（橙 / 红色）和警示牌（黄色）。
 *
 * <p>处理流程（每一步都在整幅图上做，像素之间互不依赖的步骤按行并行，这就是"图像处理加速"）：</p>
 * <ol>
 *   <li><b>颜色分割</b>：RGB → HSV，按色相 H、饱和度 S、亮度 V 的阈值把每个像素标成
 *       0（背景）/ 1（路障色）/ 2（警示色）。按色相分割对光照变化不敏感，比直接比较 RGB 稳；</li>
 *   <li><b>开运算</b>（3×3 腐蚀再膨胀）：去掉零星的噪点，同时保持大块区域的形状；</li>
 *   <li><b>连通域标记</b>（4 邻域广度优先搜索）：把相连的同色像素归成一个目标，统计外接矩形、面积、重心；</li>
 *   <li><b>合并</b>：锥桶上的白色反光条会把一个锥桶切成上下两块，同类、左右重叠、上下间隔很小的块合并成一个；</li>
 *   <li><b>筛选</b>：面积太小（远处的杂色）、填充率太低（细长的线条、花纹）的去掉。</li>
 * </ol>
 * 输入是 ARGB 像素数组（与 {@code BufferedImage#getRGB} 相同），不依赖任何图像库，便于测试。
 */
public final class ColorBlobDetector {

    /** 目标类别。 */
    public enum Kind {
        BARRIER(1, "路障", "barrier"),
        WARNING(2, "警示牌", "warning");

        public final int code;
        public final String label;
        public final String english;

        Kind(int code, String label, String english) {
            this.code = code;
            this.label = label;
            this.english = english;
        }

        static Kind of(int code) {
            return code == 1 ? BARRIER : WARNING;
        }
    }

    /**
     * 一个检测到的目标（像素坐标，原点在左上角）。
     *
     * @param fill 面积 / 外接矩形面积
     */
    public record Blob(Kind kind, int x0, int y0, int x1, int y1, int area, double cx, double cy, double fill) {
        public int width() { return x1 - x0 + 1; }
        public int height() { return y1 - y0 + 1; }
    }

    /** 检测结果与各步骤耗时（毫秒）。 */
    public record Result(int width, int height, List<Blob> blobs, int maskPixels,
                         double msMask, double msMorph, double msLabel, boolean parallel) {
        public double msTotal() { return msMask + msMorph + msLabel; }
    }

    // ---- 阈值（色相单位：度）
    double barrierHueLow = 345, barrierHueHigh = 32;   // 跨 0°：红 → 橙
    double warningHueLow = 32, warningHueHigh = 66;    // 黄
    double minSat = 0.45, minVal = 0.30;
    /** 最小面积：像素数和占整幅图的比例，取较大者。 */
    int minArea = 30;
    double minAreaFrac = 0.0015;
    double minFill = 0.25;
    int maxBlobs = 8;

    /**
     * @param parallel true 时按行并行（ForkJoin 公共线程池），false 时单线程，用于测速对比
     */
    public Result detect(int[] argb, int w, int h, boolean parallel) {
        if (argb.length < w * h) throw new IllegalArgumentException("像素数组长度不足");
        long t0 = System.nanoTime();
        byte[] mask = new byte[w * h];
        rows(h, parallel).forEach(y -> {
            int base = y * w;
            for (int x = 0; x < w; x++) mask[base + x] = classify(argb[base + x]);
        });
        long t1 = System.nanoTime();
        byte[] eroded = new byte[w * h];
        rows(h, parallel).forEach(y -> erodeRow(mask, eroded, w, h, y));
        byte[] opened = new byte[w * h];
        rows(h, parallel).forEach(y -> dilateRow(eroded, opened, w, h, y));
        long t2 = System.nanoTime();
        int on = 0;
        for (byte b : opened) if (b != 0) on++;
        List<Blob> blobs = label(opened, w, h);
        long t3 = System.nanoTime();
        return new Result(w, h, blobs, on, (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6, parallel);
    }

    private static IntStream rows(int h, boolean parallel) {
        IntStream s = IntStream.range(0, h);
        return parallel ? s.parallel() : s;
    }

    /** 像素分类：0 背景，1 路障色（红 / 橙），2 警示色（黄）。 */
    byte classify(int p) {
        int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max == 0) return 0;
        double v = max / 255.0;
        double s = (max - min) / (double) max;
        if (s < minSat || v < minVal) return 0;
        double d = max - min;
        double hue;
        if (max == r) hue = 60 * (((g - b) / d) % 6);
        else if (max == g) hue = 60 * ((b - r) / d + 2);
        else hue = 60 * ((r - g) / d + 4);
        if (hue < 0) hue += 360;
        if (hue >= barrierHueLow || hue < barrierHueHigh) return 1;
        if (hue >= warningHueLow && hue < warningHueHigh) return 2;
        return 0;
    }

    /** 3×3 腐蚀：周围 8 个像素都与中心同类才保留（图像边缘的像素直接去掉）。 */
    private static void erodeRow(byte[] in, byte[] out, int w, int h, int y) {
        int base = y * w;
        if (y == 0 || y == h - 1) return;
        for (int x = 1; x < w - 1; x++) {
            byte c = in[base + x];
            if (c == 0) continue;
            boolean keep = true;
            for (int dy = -1; dy <= 1 && keep; dy++) {
                int row = base + dy * w;
                for (int dx = -1; dx <= 1; dx++) {
                    if (in[row + x + dx] != c) {
                        keep = false;
                        break;
                    }
                }
            }
            if (keep) out[base + x] = c;
        }
    }

    /** 3×3 膨胀：周围有前景像素就把中心设为该类。 */
    private static void dilateRow(byte[] in, byte[] out, int w, int h, int y) {
        int base = y * w;
        for (int x = 0; x < w; x++) {
            byte c = in[base + x];
            if (c == 0) {
                search:
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        byte n = in[yy * w + xx];
                        if (n != 0) {
                            c = n;
                            break search;
                        }
                    }
                }
            }
            out[base + x] = c;
        }
    }

    /** 连通域标记（4 邻域 BFS）+ 统计 + 筛选，结果按面积从大到小。 */
    private List<Blob> label(byte[] m, int w, int h) {
        int[] lab = new int[w * h];
        int[] queue = new int[w * h];
        int next = 0;
        int min = Math.max(minArea, (int) Math.round(minAreaFrac * w * h));
        List<Blob> out = new ArrayList<>();
        for (int i = 0; i < w * h; i++) {
            if (m[i] == 0 || lab[i] != 0) continue;
            next++;
            byte kind = m[i];
            int head = 0, tail = 0;
            queue[tail++] = i;
            lab[i] = next;
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
            long sx = 0, sy = 0;
            while (head < tail) {
                int p = queue[head++];
                int px = p % w, py = p / w;
                x0 = Math.min(x0, px);
                x1 = Math.max(x1, px);
                y0 = Math.min(y0, py);
                y1 = Math.max(y1, py);
                sx += px;
                sy += py;
                if (px > 0 && m[p - 1] == kind && lab[p - 1] == 0) { lab[p - 1] = next; queue[tail++] = p - 1; }
                if (px < w - 1 && m[p + 1] == kind && lab[p + 1] == 0) { lab[p + 1] = next; queue[tail++] = p + 1; }
                if (py > 0 && m[p - w] == kind && lab[p - w] == 0) { lab[p - w] = next; queue[tail++] = p - w; }
                if (py < h - 1 && m[p + w] == kind && lab[p + w] == 0) { lab[p + w] = next; queue[tail++] = p + w; }
            }
            int area = tail;
            if (area < min) continue;
            double fill = area / (double) ((x1 - x0 + 1) * (y1 - y0 + 1));
            if (fill < minFill || y1 - y0 + 1 < 4 || x1 - x0 + 1 < 3) continue;
            out.add(new Blob(Kind.of(kind), x0, y0, x1, y1, area, sx / (double) area, sy / (double) area, fill));
        }
        mergeStacked(out);
        out.sort((a, b) -> Integer.compare(b.area(), a.area()));
        return out.size() > maxBlobs ? new ArrayList<>(out.subList(0, maxBlobs)) : out;
    }

    /**
     * 合并上下叠在一起的同类块：水平方向重叠超过较窄者宽度的一半，且竖直间隔不超过较矮者高度的 0.8 倍。
     * 重复直到没有可合并的块。
     */
    static void mergeStacked(List<Blob> blobs) {
        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int i = 0; i < blobs.size(); i++) {
                for (int j = i + 1; j < blobs.size(); j++) {
                    Blob a = blobs.get(i), b = blobs.get(j);
                    if (a.kind() != b.kind()) continue;
                    int overlap = Math.min(a.x1(), b.x1()) - Math.max(a.x0(), b.x0()) + 1;
                    if (overlap < 0.5 * Math.min(a.width(), b.width())) continue;
                    int gap = Math.max(a.y0(), b.y0()) - Math.min(a.y1(), b.y1()) - 1;
                    if (gap > 0.8 * Math.min(a.height(), b.height())) continue;
                    int x0 = Math.min(a.x0(), b.x0()), y0 = Math.min(a.y0(), b.y0());
                    int x1 = Math.max(a.x1(), b.x1()), y1 = Math.max(a.y1(), b.y1());
                    int area = a.area() + b.area();
                    double cx = (a.cx() * a.area() + b.cx() * b.area()) / area;
                    double cy = (a.cy() * a.area() + b.cy() * b.area()) / area;
                    double fill = area / (double) ((x1 - x0 + 1) * (y1 - y0 + 1));
                    blobs.set(i, new Blob(a.kind(), x0, y0, x1, y1, area, cx, cy, fill));
                    blobs.remove(j);
                    merged = true;
                    break outer;
                }
            }
        }
    }
}
