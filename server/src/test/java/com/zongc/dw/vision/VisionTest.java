package com.zongc.dw.vision;

import com.zongc.dw.TestSupport;
import com.zongc.dw.config.Config;
import com.zongc.dw.map.GridMap;
import com.zongc.dw.world.Snapshot;
import com.zongc.dw.world.World;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 图像处理模块：用程序画出的"照片"检验识别、测距、映射到地图和并入感知。 */
public class VisionTest {

    static BufferedImage scene(int w, int h, double coneCx, int coneHeightPx, boolean withCone, long seed) {
        return SyntheticImages.roadScene(w, h, coneCx, withCone ? coneHeightPx : 0, seed);
    }

    private static byte[] jpeg(BufferedImage img) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", bos);
        return bos.toByteArray();
    }

    @Test
    public void detectsConeAndEstimatesBearingAndDistance() throws Exception {
        GridMap map = TestSupport.map(TestSupport.config());
        VisionService vs = new VisionService(map);
        int w = 640, h = 480, coneH = 150;
        double coneCx = 420;   // 光轴右边 100 像素
        BufferedImage img = scene(w, h, coneCx, coneH, true, 1);
        // 相机在 (15.5, 15.5)（向东车道上），朝东
        double[] pose = {15.5, 15.5, 0};
        VisionService.Report r = vs.process(jpeg(img), new VisionService.Params("phone-1", pose, 60, 0.7, 1.0, false));
        assertEquals("应只识别出一个目标（白色反光条不能把锥桶切成两个）：" + r.summary(), 1, r.objects().size());
        VisionService.Detection d = r.objects().get(0);
        assertEquals("barrier", d.kind());
        double f = (w / 2.0) / Math.tan(Math.toRadians(30));
        double expBearing = Math.toDegrees(Math.atan((coneCx - w / 2.0) / f));
        assertEquals("方位角", expBearing, d.bearingDeg(), 2.0);
        double expDist = 0.7 * f / coneH;
        assertEquals("距离", expDist, d.distance(), expDist * 0.12);
        assertNotNull("有相机位姿时应映射到地图", d.cell());
        // 目标在正东偏右（南）约 2.3 m 处 → 格子 (17 或 18, 15 或 14)
        assertTrue("映射的格子 " + d.cell()[0] + "," + d.cell()[1], d.cell()[0] >= 17 && d.cell()[0] <= 18 && d.cell()[1] >= 14 && d.cell()[1] <= 15);
        assertNotNull(r.mapped());
        assertNotNull("应有标注图", vs.lastImage());
        assertTrue(r.summary().contains("路障"));
    }

    @Test
    public void ignoresScenesWithoutTargets() throws Exception {
        VisionService vs = new VisionService(TestSupport.map(TestSupport.config()));
        for (long seed = 1; seed <= 5; seed++) {
            VisionService.Report r = vs.process(jpeg(scene(800, 600, 0, 0, false, seed)),
                    new VisionService.Params("web", null, 63, 0.7, 1, false));
            assertEquals("不应误检：" + r.summary(), 0, r.objects().size());
            assertTrue(r.mapped() == null);
        }
    }

    @Test
    public void parallelAndSerialAgree() throws Exception {
        BufferedImage img = scene(1600, 1200, 1000, 380, true, 3);
        int[] px = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        ColorBlobDetector det = new ColorBlobDetector();
        ColorBlobDetector.Result a = det.detect(px, img.getWidth(), img.getHeight(), false);
        ColorBlobDetector.Result b = det.detect(px, img.getWidth(), img.getHeight(), true);
        assertEquals(a.maskPixels(), b.maskPixels());
        assertEquals(a.blobs(), b.blobs());
        assertEquals(1, a.blobs().size());
    }

    @Test
    public void rejectsGarbage() throws Exception {
        VisionService vs = new VisionService(TestSupport.map(TestSupport.config()));
        try {
            vs.process(new byte[]{1, 2, 3, 4}, new VisionService.Params("web", null, 63, 0.7, 1, false));
            throw new AssertionError("应拒绝不是图片的数据");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("图片"));
        }
    }

    /** 手机拍到的路障：写进地图（车辆绕行），并登记到数字孪生的真实世界；过期后由车辆感知撤掉。 */
    @Test
    public void phonePhotoUpdatesTheMapAndExpires() throws Exception {
        Config cfg = TestSupport.config();
        cfg.taxis = 12;
        cfg.tasks.ratePerMin = 10;
        World w = TestSupport.world(cfg);
        for (int i = 0; i < 20 * 20; i++) w.step();
        GridMap map = w.map();
        int cell = map.index(16, 15);
        int tries = 0;
        while (!clearOfCars(w, cell) && tries++ < 400) w.step();
        w.reportVision("phone-1", 1, "识别到 1 个路障", new int[]{cell}, 2.0);
        Snapshot s = w.snapshot(false, 1);
        assertArrayEquals("识别结果应立即写入地图", new int[]{16, 15}, s.discovered());
        assertTrue(!map.passable(cell));
        assertNotNull(s.vision());
        assertEquals(1, s.vision().seq());
        // 有效期内：车辆的激光雷达也看得到它（真实世界里有），不会被误撤
        for (int i = 0; i < 20 * 60; i++) w.step();
        assertTrue("有效期内不应被撤掉", !map.passable(cell));
        // 过期后视为已搬走，车辆经过时观测到空闲，自动撤掉
        for (int i = 0; i < 20 * 300 && !map.passable(cell); i++) w.step();
        assertTrue("过期后应被感知撤掉", map.passable(cell));
        assertEquals(0, w.metrics().barrierHits);
        assertEquals(0, w.metrics().collisions);
    }

    private static boolean clearOfCars(World w, int cell) {
        for (var a : w.agents()) {
            if (Math.hypot(a.x - w.map().centerX(cell), a.y - w.map().centerY(cell)) < 1.5) return false;
        }
        return true;
    }
}
