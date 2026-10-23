package com.zongc.phone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import com.zongc.phone.core.MapData;
import com.zongc.phone.core.PdrState;
import com.zongc.phone.core.WorldSnapshot;

import java.util.ArrayDeque;

/**
 * 手机上的小地图：城市地图、出租车、其他手机、订单、路障，以及"我"（位置、朝向、轨迹）。
 * 颜色与网页端一致。没有连接服务端时显示以"我"为中心的 1 m 网格，方便单独测试 PDR。
 */
final class MiniMapView extends View {
    interface TapListener {
        void onMapTap(double x, double y);
    }

    private static final int[] CAR_COLORS = {
            0xff2f6bd8, 0xffe0533d, 0xff12a189, 0xff8a4fc8, 0xffd2418a, 0xff1e9bd7,
            0xff7a9a1c, 0xffa0652b, 0xff4c55c9, 0xff3e9e3e, 0xffb8323a, 0xff2c8c9e};
    private static final int PAPER = 0xffe6ecf2;
    private static final int ROAD = 0xfffbfcfd;
    private static final int LANE_MARK = 0xffc3ccd6;
    private static final int BLOCK_TOP = 0xffc2ccd7;
    private static final int BLOCK_EDGE = 0xff8d9aa9;
    private static final int INK = 0xff17212b;
    private static final int MUTED = 0xff5e6b78;
    private static final int SIGNAL = 0xffe09b10;
    private static final int DANGER = 0xffcf3f36;
    private static final int PHONE = 0xff7b5cd6;
    private static final int PICKUP = 0xff12a189;

    private final AppCore core;
    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private TapListener tapListener;

    // 要画的数据（界面线程）
    private MapData map;
    private WorldSnapshot snap;
    private PdrState me;
    private String meId;
    private String myTaskId;
    private String hint;

    // 世界坐标 → 屏幕：sx = ox + x·scale，sy = oy − y·scale
    private float scale = 1;
    private float ox;
    private float oy;
    /** true：地图铺满屏幕、跟随"我"平移；false：显示整张地图。 */
    private boolean follow = true;

    // 静态几何（地图变化时重算）：街区矩形 [x0,y0,x1,y1]（格），车道中心线段（米）
    private MapData geomMap;
    private final java.util.List<int[]> blocks = new java.util.ArrayList<int[]>();
    private float[] laneSegs = new float[0];

    private float downX;
    private float downY;
    private boolean tapCandidate;

    MiniMapView(Context ctx, AppCore core) {
        super(ctx);
        this.core = core;
        this.dp = ctx.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        dashed.setStyle(Paint.Style.STROKE);
        dashed.setStrokeWidth(1.5f * dp);
        dashed.setPathEffect(new DashPathEffect(new float[]{3 * dp, 2.5f * dp}, 0));
        text.setTextSize(11 * dp);
        text.setColor(INK);
    }

    void setTapListener(TapListener l) {
        tapListener = l;
    }

    void update(MapData map, WorldSnapshot snap, PdrState me, String meId, String myTaskId, String hint) {
        this.map = map;
        this.snap = snap;
        this.me = me;
        this.meId = meId;
        this.myTaskId = myTaskId;
        this.hint = hint;
        invalidate();
    }

    /** 在"跟随我"和"整张地图"之间切换。 */
    void toggleFollow() {
        follow = !follow;
        invalidate();
    }

    // ================================================================ 坐标

    private float sx(double x) {
        return ox + (float) x * scale;
    }

    private float sy(double y) {
        return oy - (float) y * scale;
    }

    /**
     * 计算显示比例与平移。整图：地图完整放进视图；跟随：地图铺满视图（竖屏时按高度铺满），
     * 视图中心跟着"我"走，但不移出地图边界。正在地图上选点时总是显示整图。
     */
    private void layoutFor(MapData m, int w, int h) {
        float pad = 6 * dp;
        float mw = (float) m.worldWidth(), mh = (float) m.worldHeight();
        float fit = Math.min((w - 2 * pad) / mw, (h - 2 * pad) / mh);
        boolean following = follow && hint == null && me != null;
        if (!following) {
            scale = Math.max(fit, 0.5f);
            ox = (w - mw * scale) / 2;
            oy = (h + mh * scale) / 2;
            return;
        }
        scale = Math.max(Math.max((w - 2 * pad) / mw, (h - 2 * pad) / mh), 0.5f);
        double padW = pad / scale;
        double cx = centerWithin(me.x, w / scale, mw, padW);
        double cy = centerWithin(me.y, h / scale, mh, padW);
        ox = (float) (w / 2.0 - cx * scale);
        oy = (float) (h / 2.0 + cy * scale);
    }

    private static double centerWithin(double c, double view, double world, double pad) {
        double lo = view / 2 - pad, hi = world - view / 2 + pad;
        if (lo >= hi) return world / 2;
        return c < lo ? lo : (c > hi ? hi : c);
    }

    /** 地图变化时预先算好街区矩形和车道中心线，每帧直接画。 */
    private void buildGeometry(MapData m) {
        geomMap = m;
        blocks.clear();
        // 街区：建筑格的连通块；正好是矩形的画成一个圆角矩形，否则逐格画
        boolean[] seen = new boolean[m.width * m.height];
        ArrayDeque<int[]> stack = new ArrayDeque<int[]>();
        for (int y = 0; y < m.height; y++) {
            for (int x = 0; x < m.width; x++) {
                if (seen[y * m.width + x] || !m.isBuilding(x, y)) continue;
                int x0 = x, x1 = x, y0 = y, y1 = y, count = 0;
                java.util.List<int[]> cells = new java.util.ArrayList<int[]>();
                stack.push(new int[]{x, y});
                seen[y * m.width + x] = true;
                while (!stack.isEmpty()) {
                    int[] p = stack.pop();
                    cells.add(p);
                    count++;
                    x0 = Math.min(x0, p[0]);
                    x1 = Math.max(x1, p[0]);
                    y0 = Math.min(y0, p[1]);
                    y1 = Math.max(y1, p[1]);
                    int[][] nb = {{p[0] + 1, p[1]}, {p[0] - 1, p[1]}, {p[0], p[1] + 1}, {p[0], p[1] - 1}};
                    for (int[] q : nb) {
                        if (!m.inBounds(q[0], q[1]) || seen[q[1] * m.width + q[0]] || !m.isBuilding(q[0], q[1])) continue;
                        seen[q[1] * m.width + q[0]] = true;
                        stack.push(q);
                    }
                }
                if (count == (x1 - x0 + 1) * (y1 - y0 + 1)) {
                    blocks.add(new int[]{x0, y0, x1, y1});
                } else {
                    for (int[] p : cells) blocks.add(new int[]{p[0], p[1], p[0], p[1]});
                }
            }
        }
        // 车道中心线：相邻两格车道方向相反时，在它们的公共边上画一段短线（看起来是虚线）
        java.util.List<float[]> segs = new java.util.ArrayList<float[]>();
        float r = (float) m.resolution;
        for (int y = 0; y < m.height; y++) {
            for (int x = 0; x < m.width; x++) {
                char a = m.at(x, y);
                if (a == '>' && m.at(x, y + 1) == '<' || a == '<' && m.at(x, y + 1) == '>') {
                    segs.add(new float[]{(x + 0.25f) * r, (y + 1) * r, (x + 0.75f) * r, (y + 1) * r});
                }
                if (a == '^' && m.at(x + 1, y) == 'v' || a == 'v' && m.at(x + 1, y) == '^') {
                    segs.add(new float[]{(x + 1) * r, (y + 0.25f) * r, (x + 1) * r, (y + 0.75f) * r});
                }
            }
        }
        laneSegs = new float[segs.size() * 4];
        for (int i = 0; i < segs.size(); i++) System.arraycopy(segs.get(i), 0, laneSegs, i * 4, 4);
    }

    // ================================================================ 绘制

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        c.drawColor(PAPER);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (map == null) {
            drawLocal(c, w, h);
        } else {
            if (geomMap != map) buildGeometry(map);
            layoutFor(map, w, h);
            drawStatic(c);
            if (snap != null) drawWorld(c);
            drawTrail(c);
            drawMe(c);
            drawViewLabel(c, h);
        }
        drawHint(c, w);
    }

    private void drawStatic(Canvas g) {
        MapData m = map;
        float cell = (float) m.resolution * scale;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(ROAD);
        g.drawRect(sx(0), sy(m.worldHeight()), sx(m.worldWidth()), sy(0), fill);
        line.setColor(LANE_MARK);
        line.setStrokeWidth(Math.max(1, cell * 0.06f));
        for (int i = 0; i + 3 < laneSegs.length; i += 4) {
            g.drawLine(sx(laneSegs[i]), sy(laneSegs[i + 1]), sx(laneSegs[i + 2]), sy(laneSegs[i + 3]), line);
        }
        for (int[] b : blocks) drawBlock(g, b[0], b[1], b[2], b[3], cell);
    }

    private void drawViewLabel(Canvas c, int h) {
        if (hint != null) return;
        text.setTextSize(10 * dp);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(MUTED);
        c.drawText(follow ? "跟随我 · 轻点地图看全图" : "全图 · 轻点地图跟随我", 8 * dp, h - 6 * dp, text);
    }

    private void drawBlock(Canvas g, int x0, int y0, int x1, int y1, float cell) {
        MapData m = map;
        float inset = cell * 0.12f;
        rect.set(sx(x0 * m.resolution) + inset, sy((y1 + 1) * m.resolution) + inset,
                sx((x1 + 1) * m.resolution) - inset, sy(y0 * m.resolution) - inset);
        float r = Math.min(cell * 0.3f, 6 * dp);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(BLOCK_TOP);
        g.drawRoundRect(rect, r, r, fill);
        line.setColor(BLOCK_EDGE);
        line.setStrokeWidth(Math.max(1, dp));
        g.drawRoundRect(rect, r, r, line);
    }

    private void drawWorld(Canvas c) {
        MapData m = map;
        float cell = (float) m.resolution * scale;

        // 路障
        line.setColor(DANGER);
        line.setStrokeWidth(Math.max(1.5f, cell * 0.12f));
        for (int i = 0; i + 1 < snap.blocked.length; i += 2) {
            float x0 = sx(snap.blocked[i] * m.resolution), y0 = sy((snap.blocked[i + 1] + 1) * m.resolution);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0x33cf3f36);
            c.drawRect(x0, y0, x0 + cell, y0 + cell, fill);
            c.drawLine(x0 + cell * 0.2f, y0 + cell * 0.2f, x0 + cell * 0.8f, y0 + cell * 0.8f, line);
            c.drawLine(x0 + cell * 0.8f, y0 + cell * 0.2f, x0 + cell * 0.2f, y0 + cell * 0.8f, line);
        }

        // 订单：等待中的上车点（橙色）；我的订单画出上车点、目的地和接单车辆的路线
        WorldSnapshot.Task mine = myTaskId == null ? null : snap.task(myTaskId);
        for (WorldSnapshot.Task t : snap.tasks) {
            if (t == mine || !"WAITING".equals(t.state)) continue;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(SIGNAL);
            c.drawCircle(cellCx(t.fromX), cellCy(t.fromY), Math.max(cell * 0.22f, 3 * dp), fill);
        }
        if (mine != null) {
            WorldSnapshot.Agent taxi = snap.agent(mine.agent);
            if (taxi != null && taxi.route != null && taxi.route.length >= 4) {
                path.reset();
                path.moveTo(sx(taxi.x), sy(taxi.y));
                for (int i = 0; i + 1 < taxi.route.length; i += 2) path.lineTo(sx(taxi.route[i]), sy(taxi.route[i + 1]));
                line.setColor(carColor(taxi.num));
                line.setStrokeWidth(Math.max(2, cell * 0.14f));
                c.drawPath(path, line);
            }
            ring(c, cellCx(mine.fromX), cellCy(mine.fromY), Math.max(cell * 0.45f, 7 * dp), PICKUP, "上车");
            ring(c, cellCx(mine.toX), cellCy(mine.toY), Math.max(cell * 0.45f, 7 * dp), DANGER, "目的地");
        }

        // 出租车与其他手机
        text.setTextSize(9 * dp);
        text.setTextAlign(Paint.Align.LEFT);
        for (WorldSnapshot.Agent a : snap.agents) {
            if (a.id.equals(meId)) continue;
            float x = sx(a.x), y = sy(a.y);
            if (a.taxi) {
                float r = Math.max((float) a.r * 1.2f * scale, 3.5f * dp);
                int col = carColor(a.num);
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(a.online ? col : MUTED);
                c.drawCircle(x, y, r, fill);
                line.setColor(0xffffffff);
                line.setStrokeWidth(Math.max(1.2f, r * 0.25f));
                c.drawLine(x, y, x + (float) Math.cos(a.theta) * r, y - (float) Math.sin(a.theta) * r, line);
                if (mine != null && a.id.equals(mine.agent)) {
                    line.setColor(col);
                    line.setStrokeWidth(2 * dp);
                    c.drawCircle(x, y, r + 3 * dp, line);
                }
                if (a.blocked) {
                    fill.setColor(SIGNAL);
                    c.drawCircle(x + r * 0.8f, y - r * 0.8f, Math.max(2 * dp, r * 0.35f), fill);
                }
                text.setColor(INK);
                c.drawText(String.valueOf(a.num), x + r + 1.5f * dp, y + r, text);
            } else {
                float r = Math.max((float) a.r * scale, 5 * dp);
                dashed.setColor(PHONE);
                c.drawCircle(x, y, r, dashed);
                text.setColor(PHONE);
                c.drawText(a.id, x + r + 2 * dp, y + 3 * dp, text);
            }
        }
    }

    private void ring(Canvas c, float x, float y, float r, int color, String label) {
        line.setColor(color);
        line.setStrokeWidth(2.5f * dp);
        c.drawCircle(x, y, r, line);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        c.drawCircle(x, y, 2.5f * dp, fill);
        text.setColor(color);
        text.setTextSize(10 * dp);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(label, x, y - r - 3 * dp, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private float cellCx(int x) {
        return sx((x + 0.5) * map.resolution);
    }

    private float cellCy(int y) {
        return sy((y + 0.5) * map.resolution);
    }

    private static int carColor(int num) {
        return CAR_COLORS[(Math.max(1, num) - 1) % CAR_COLORS.length];
    }

    private void drawTrail(Canvas c) {
        int n = core.trailCount;
        if (n < 2) return;
        path.reset();
        int start = (core.trailHead - n + AppCore.TRAIL) % AppCore.TRAIL;
        for (int k = 0; k < n; k++) {
            int i = (start + k) % AppCore.TRAIL;
            float x = sx(core.trailX[i]), y = sy(core.trailY[i]);
            if (k == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        line.setColor(0x997b5cd6);
        line.setStrokeWidth(2.5f * dp);
        c.drawPath(path, line);
    }

    private void drawMe(Canvas c) {
        if (me == null) return;
        float x = sx(me.x), y = sy(me.y);
        float r = Math.max(0.45f * scale, 7 * dp);
        // 朝向箭头
        float ux = (float) Math.cos(me.theta), uy = (float) -Math.sin(me.theta);
        float tip = r * 2.3f;
        path.reset();
        path.moveTo(x + ux * tip, y + uy * tip);
        path.lineTo(x - uy * r * 0.75f, y + ux * r * 0.75f);
        path.lineTo(x + uy * r * 0.75f, y - ux * r * 0.75f);
        path.close();
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xcc7b5cd6);
        c.drawPath(path, fill);
        fill.setColor(PHONE);
        c.drawCircle(x, y, r, fill);
        line.setColor(0xffffffff);
        line.setStrokeWidth(2 * dp);
        c.drawCircle(x, y, r, line);
        if (me.walking) {
            fill.setColor(0xffffffff);
            c.drawCircle(x, y, r * 0.35f, fill);
        }
    }

    /** 没有地图：以"我"为中心画 1 m 网格和轨迹。 */
    private void drawLocal(Canvas c, int w, int h) {
        double cx = me == null ? 0 : me.x, cy = me == null ? 0 : me.y;
        scale = 22 * dp;
        ox = w / 2f - (float) cx * scale;
        oy = h / 2f + (float) cy * scale;
        double minX = cx - w / 2.0 / scale - 1, maxX = cx + w / 2.0 / scale + 1;
        double minY = cy - h / 2.0 / scale - 1, maxY = cy + h / 2.0 / scale + 1;
        for (int gx = (int) Math.floor(minX); gx <= maxX; gx++) {
            line.setColor(gx % 5 == 0 ? 0xffb4c0cc : 0xffd3dbe3);
            line.setStrokeWidth(gx % 5 == 0 ? 1.5f * dp : 1);
            c.drawLine(sx(gx), 0, sx(gx), h, line);
        }
        for (int gy = (int) Math.floor(minY); gy <= maxY; gy++) {
            line.setColor(gy % 5 == 0 ? 0xffb4c0cc : 0xffd3dbe3);
            line.setStrokeWidth(gy % 5 == 0 ? 1.5f * dp : 1);
            c.drawLine(0, sy(gy), w, sy(gy), line);
        }
        drawTrail(c);
        drawMe(c);
        text.setColor(MUTED);
        text.setTextSize(11 * dp);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText("未连接服务端：本地推算（细线 1 m，粗线 5 m）", 8 * dp, h - 8 * dp, text);
    }

    private void drawHint(Canvas c, int w) {
        if (hint == null || hint.isEmpty()) return;
        text.setTextSize(13 * dp);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        float tw = text.measureText(hint);
        rect.set(w / 2f - tw / 2 - 10 * dp, 6 * dp, w / 2f + tw / 2 + 10 * dp, 34 * dp);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xee17212b);
        c.drawRoundRect(rect, 8 * dp, 8 * dp, fill);
        text.setColor(0xffffffff);
        c.drawText(hint, w / 2f, 25 * dp, text);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextAlign(Paint.Align.LEFT);
    }

    // ================================================================ 触摸

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                tapCandidate = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(e.getX() - downX, e.getY() - downY) > 12 * dp) tapCandidate = false;
                return true;
            case MotionEvent.ACTION_UP:
                if (tapCandidate) {
                    performClick();
                    if (tapListener != null && map != null) {
                        tapListener.onMapTap((e.getX() - ox) / scale, (oy - e.getY()) / scale);
                    }
                }
                tapCandidate = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                tapCandidate = false;
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
