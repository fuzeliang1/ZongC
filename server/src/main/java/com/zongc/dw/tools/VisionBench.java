package com.zongc.dw.tools;

import com.zongc.dw.vision.ColorBlobDetector;
import com.zongc.dw.vision.SyntheticImages;

import java.awt.image.BufferedImage;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 图像处理加速模块测速：同一张图分别用单线程和多线程（按行并行）处理，比较耗时。
 *
 * <pre>
 * java -cp build/classes:lib/gson-2.13.1.jar com.zongc.dw.tools.VisionBench [--csv out.csv] [--runs 15]
 * </pre>
 * 图越大、CPU 核数越多，并行的收益越明显；服务端默认把照片缩到 640 像素宽再处理（缩小本身就是最有效的加速）。
 */
public final class VisionBench {

    public static void main(String[] args) throws Exception {
        String csv = null;
        int runs = 15;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--csv" -> csv = args[++i];
                case "--runs" -> runs = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("用法: VisionBench [--csv out.csv] [--runs 15]");
                    return;
                }
            }
        }
        int[][] sizes = {{640, 480}, {1280, 960}, {1920, 1440}, {3200, 2400}};
        ColorBlobDetector det = new ColorBlobDetector();
        List<String> rows = new ArrayList<>();
        rows.add("width,height,pixels,serialMs,parallelMs,speedup,threads,blobs");
        int threads = Runtime.getRuntime().availableProcessors();
        System.out.printf("CPU 核数 %d，每种尺寸各跑 %d 次取中位数%n", threads, runs);
        System.out.printf("%-12s %10s %10s %8s %6s%n", "尺寸", "单线程ms", "并行ms", "加速比", "目标");
        for (int[] sz : sizes) {
            int w = sz[0], h = sz[1];
            BufferedImage img = SyntheticImages.roadScene(w, h, w * 0.65, h / 3, 7);
            int[] px = img.getRGB(0, 0, w, h, null, 0, w);
            for (int i = 0; i < 5; i++) {   // 预热（JIT 编译）
                det.detect(px, w, h, false);
                det.detect(px, w, h, true);
            }
            double[] ser = new double[runs], par = new double[runs];
            int blobs = 0;
            for (int i = 0; i < runs; i++) {
                ser[i] = det.detect(px, w, h, false).msTotal();
                ColorBlobDetector.Result r = det.detect(px, w, h, true);
                par[i] = r.msTotal();
                blobs = r.blobs().size();
            }
            double s = median(ser), p = median(par);
            System.out.printf("%-12s %10.2f %10.2f %8.2f %6d%n", w + "x" + h, s, p, s / p, blobs);
            rows.add(w + "," + h + "," + (w * h) + "," + round(s) + "," + round(p) + "," + round(s / p) + "," + threads + "," + blobs);
        }
        if (csv != null) {
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Path.of(csv), StandardCharsets.UTF_8))) {
                rows.forEach(pw::println);
            }
            System.out.println("结果已写入 " + csv);
        }
    }

    private static double median(double[] v) {
        double[] c = v.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
