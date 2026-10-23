package com.zongc.dw.vision;

import com.zongc.dw.map.GridMap;
import com.zongc.dw.util.Num;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 图像处理加速模块（PPT 第 14 页）：服务端接收手机拍的照片，识别路障，并把它们的位置换算到地图上。
 *
 * <p>为什么放在服务端（"端云互动"）：手机只负责拍照和上传，耗时的图像处理在服务端多核并行完成，
 * 识别结果并入全局地图，所有车辆立即受益。</p>
 *
 * <h3>从像素到地图格子</h3>
 * <pre>
 *  针孔相机：焦距 f = (W/2) / tan(水平视场角/2)（像素）
 *  方位角   β = atan((cx − W/2) / f)              目标在相机光轴右边为正
 *  距离     d = H_real · f / h_px                  H_real：目标真实高度，h_px：外接矩形高度（像素）
 *  地图坐标 p = 相机位置 + d·s·(cos(θ − β), sin(θ − β))   θ：相机朝向（手机航向），s：真实世界到地图的比例尺
 * </pre>
 * 识别是"颜色分割 + 连通域"（{@link ColorBlobDetector}），不需要训练数据，适合教学场景里的
 * 橙色锥桶、红色挡板、黄色警示牌。
 */
public final class VisionService {
    /** 送进检测器之前把图片缩到不超过这个宽度（像素）：手机原图 4000 像素宽，没必要全分辨率处理。 */
    public static final int MAX_WIDTH = 640;
    /** 上传图片大小上限。 */
    public static final int MAX_BYTES = 16 * 1024 * 1024;

    /**
     * 一次识别的参数。
     *
     * @param source       来源（手机的智能体编号，或 "web"）
     * @param pose         相机在地图上的位姿 {x, y, θ}；没有时只识别、不建图
     * @param hfovDeg      相机水平视场角（度），手机后置主摄一般 60°~70°
     * @param objectHeight 目标的真实高度（米，真实世界），用于估计距离
     * @param scale        真实世界 1 米对应地图上多少米（手机 App 设置里的比例尺）
     * @param benchmark    true 时再用单线程跑一遍，报告并行加速比
     */
    public record Params(String source, double[] pose, double hfovDeg, double objectHeight, double scale, boolean benchmark) {}

    /**
     * 一个识别出的目标。
     *
     * @param bbox       原图像素坐标下的外接矩形 [x, y, w, h]
     * @param bearingDeg 方位角（度），相机光轴右边为正
     * @param distance   估计距离（地图米）
     * @param cell       映射到的地图格子 [x, y]，没有相机位姿或落在地图外时为 null
     */
    public record Detection(String label, String kind, int[] bbox, int area, double bearingDeg, double distance, int[] cell) {}

    /**
     * 一次识别的完整结果（GET /api/vision/last 返回它）。
     *
     * @param speedup 并行相对单线程的加速比（benchmark 时才有）
     * @param mapped  写入地图的路障格 [x0,y0,x1,y1,...]，没有时为 null
     */
    public record Report(int seq, String source, long wall, int width, int height, int procWidth, int procHeight,
                         double decodeMs, double detectMs, Double serialMs, Double speedup, int threads,
                         List<Detection> objects, int[] mapped, String summary) {}

    private final GridMap map;
    private final ColorBlobDetector detector = new ColorBlobDetector();
    private int seq;
    private volatile Report last;
    private volatile byte[] lastImage;

    public VisionService(GridMap map) {
        this.map = map;
    }

    public Report last() {
        return last;
    }

    /** 最近一次识别的标注图（JPEG），没有时为 null。 */
    public byte[] lastImage() {
        return lastImage;
    }

    /** 解码、识别、换算到地图。线程安全（可以在 HTTP 线程里直接调用）。 */
    public Report process(byte[] bytes, Params p) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("请求体里没有图片");
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("图片太大（上限 16 MB）");
        long t0 = System.nanoTime();
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
        if (src == null) throw new IllegalArgumentException("无法识别的图片格式（支持 JPEG / PNG）");
        BufferedImage img = downscale(src, MAX_WIDTH);
        int w = img.getWidth(), h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        double decodeMs = (System.nanoTime() - t0) / 1e6;

        ColorBlobDetector.Result r = detector.detect(argb, w, h, true);
        Double serialMs = null, speedup = null;
        if (p.benchmark()) {
            double best = Double.POSITIVE_INFINITY, bestPar = r.msTotal();
            for (int i = 0; i < 3; i++) {
                best = Math.min(best, detector.detect(argb, w, h, false).msTotal());
                bestPar = Math.min(bestPar, detector.detect(argb, w, h, true).msTotal());
            }
            serialMs = Num.r2(best);
            speedup = Num.r2(best / Math.max(1e-3, bestPar));
        }
        double sx = src.getWidth() / (double) w;
        List<Detection> dets = new ArrayList<>();
        Set<Integer> cells = new LinkedHashSet<>();
        double f = (w / 2.0) / Math.tan(Math.toRadians(clamp(p.hfovDeg(), 20, 150)) / 2);
        for (ColorBlobDetector.Blob b : r.blobs()) {
            double bearing = Math.atan((b.cx() - w / 2.0) / f);
            double dReal = p.objectHeight() * f / Math.max(1, b.height());
            double d = clamp(dReal * p.scale(), 0.3, 12);
            int[] cell = null;
            if (p.pose() != null) {
                int c = locate(p.pose(), bearing, d);
                if (c >= 0) {
                    cell = new int[]{map.cx(c), map.cy(c)};
                    if (b.kind() == ColorBlobDetector.Kind.BARRIER) cells.add(c);
                }
            }
            int[] bbox = {(int) Math.round(b.x0() * sx), (int) Math.round(b.y0() * sx),
                    (int) Math.round(b.width() * sx), (int) Math.round(b.height() * sx)};
            dets.add(new Detection(b.kind().label, b.kind().english, bbox, (int) Math.round(b.area() * sx * sx),
                    Num.r1(Math.toDegrees(bearing)), Num.r2(d), cell));
        }
        int[] mapped = new int[cells.size() * 2];
        int k = 0;
        for (int c : cells) {
            mapped[k++] = map.cx(c);
            mapped[k++] = map.cy(c);
        }
        String summary = summarize(dets, cells.size());
        Report rep;
        byte[] annotated = annotate(img, r.blobs(), dets);
        synchronized (this) {
            rep = new Report(++seq, p.source(), System.currentTimeMillis(), src.getWidth(), src.getHeight(), w, h,
                    Num.r2(decodeMs), Num.r2(r.msTotal()), serialMs, speedup,
                    Runtime.getRuntime().availableProcessors(), dets, mapped.length > 0 ? mapped : null, summary);
            last = rep;
            lastImage = annotated;
        }
        return rep;
    }

    /** 映射到地图上的路障格（格索引），供 World.reportVision 使用。 */
    public int[] mappedCells(Report r) {
        if (r.mapped() == null) return new int[0];
        int[] out = new int[r.mapped().length / 2];
        for (int i = 0; i < out.length; i++) out[i] = map.index(r.mapped()[i * 2], r.mapped()[i * 2 + 1]);
        return out;
    }

    /**
     * 沿相机视线把估计的目标点落到地图格子上：目标点落在建筑里时（距离估计偏大、或目标贴着墙），
     * 沿视线往回找第一个可行驶的格子；超出地图返回 −1。
     */
    int locate(double[] pose, double bearing, double d) {
        double ang = pose[2] - bearing;
        double dx = Math.cos(ang), dy = Math.sin(ang);
        for (double t = d; t >= 0.3; t -= 0.1 * map.resolution()) {
            int c = map.cellAt(pose[0] + dx * t, pose[1] + dy * t);
            if (c < 0) continue;
            if (map.type(c).drivable()) return c;
        }
        return -1;
    }

    private static String summarize(List<Detection> dets, int mapped) {
        if (dets.isEmpty()) return "未识别到路障或警示牌";
        StringBuilder sb = new StringBuilder("识别到 " + dets.size() + " 个目标：");
        for (int i = 0; i < dets.size() && i < 3; i++) {
            Detection d = dets.get(i);
            if (i > 0) sb.append("；");
            double b = d.bearingDeg();
            String dir = Math.abs(b) < 5 ? "正前方" : (b > 0 ? "右前方 " : "左前方 ") + (Math.abs(b) < 5 ? "" : Math.round(Math.abs(b)) + "°");
            sb.append(d.label()).append("（").append(dir).append("，约 ").append(d.distance()).append(" m");
            if (d.cell() != null) sb.append("，格子 (").append(d.cell()[0]).append(",").append(d.cell()[1]).append(")");
            sb.append("）");
        }
        if (dets.size() > 3) sb.append("……");
        if (mapped > 0) sb.append("，").append(mapped).append(" 处路障已写入地图");
        return sb.toString();
    }

    private static BufferedImage downscale(BufferedImage src, int maxW) {
        int w = src.getWidth(), h = src.getHeight();
        int nw = w, nh = h;
        if (w > maxW) {
            nw = maxW;
            nh = Math.max(1, (int) Math.round(h * (maxW / (double) w)));
        }
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    /** 在缩小后的图上画出检测框和标签（英文，避免服务器没有中文字体），编码成 JPEG。 */
    private static byte[] annotate(BufferedImage img, List<ColorBlobDetector.Blob> blobs, List<Detection> dets) throws IOException {
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int stroke = Math.max(2, img.getWidth() / 200);
        g.setStroke(new BasicStroke(stroke));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(11, img.getWidth() / 40)));
        for (int i = 0; i < blobs.size(); i++) {
            ColorBlobDetector.Blob b = blobs.get(i);
            Detection d = dets.get(i);
            Color c = b.kind() == ColorBlobDetector.Kind.BARRIER ? new Color(0, 200, 255) : new Color(120, 60, 230);
            g.setColor(c);
            g.drawRect(b.x0(), b.y0(), b.width(), b.height());
            String text = String.format(Locale.ROOT, "%s %.1fm %+.0fdeg", b.kind().english, d.distance(), d.bearingDeg());
            int tw = g.getFontMetrics().stringWidth(text) + 8, th = g.getFontMetrics().getHeight();
            int ty = Math.max(0, b.y0() - th - 2);
            g.fillRect(b.x0(), ty, tw, th + 2);
            g.setColor(Color.WHITE);
            g.drawString(text, b.x0() + 4, ty + th - g.getFontMetrics().getDescent());
        }
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(out, "jpg", bos);
        return bos.toByteArray();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
