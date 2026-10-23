package com.zongc.phone.core;

import java.util.List;
import java.util.Map;

/**
 * 服务端图像识别（POST /api/vision/detect）的结果摘要。
 * 手机只负责拍照和上传，颜色分割、连通域、测距和建图都在服务端完成（端云互动）。
 */
public final class VisionResult {
    /** 一句话结果，例如"识别到 1 个目标：路障（正前方，约 2.3 m，格子 (17,15)），1 处路障已写入地图"。 */
    public final String summary;
    /** 识别到的目标数（路障 + 警示牌）。 */
    public final int objects;
    /** 写入地图的路障格数。 */
    public final int mapped;
    /** 服务端识别耗时（毫秒）。 */
    public final double detectMs;

    public VisionResult(String summary, int objects, int mapped, double detectMs) {
        this.summary = summary;
        this.objects = objects;
        this.mapped = mapped;
        this.detectMs = detectMs;
    }

    static VisionResult fromJson(String json) {
        Map<String, Object> o = Json.parseObject(json);
        if (o == null) throw new IllegalArgumentException("不是 JSON 对象");
        Object objs = o.get("objects");
        Object mapped = o.get("mapped");
        int n = objs instanceof List ? ((List<?>) objs).size() : 0;
        int m = mapped instanceof List ? ((List<?>) mapped).size() / 2 : 0;
        String s = Json.str(o, "summary");
        return new VisionResult(s != null ? s : "服务端没有返回结果", n, m, Json.num(o, "detectMs", 0));
    }
}
