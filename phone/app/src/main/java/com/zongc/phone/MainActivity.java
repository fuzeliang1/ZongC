package com.zongc.phone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.zongc.phone.core.DwConnection;
import com.zongc.phone.core.Endpoint;
import com.zongc.phone.core.Fmt;
import com.zongc.phone.core.Geo;
import com.zongc.phone.core.MapData;
import com.zongc.phone.core.PdrConfig;
import com.zongc.phone.core.PdrState;
import com.zongc.phone.core.WorldSnapshot;

import java.io.IOException;

/**
 * 主界面（纯代码搭建，不依赖任何第三方库）。
 *
 * <pre>
 * ┌ 服务端 IP ─────────────── [连接] ┐  深色顶栏 + 连接状态
 * │            小地图                 │  城市、出租车、我的位置和轨迹
 * │ 步数 / 位置朝向 / 传感器 / 卫星     │  实时数据
 * │ [设位置][对方向][步长标定][模拟行走] │
 * └ [室外模式][记录数据][叫车][设置]   ┘
 * </pre>
 */
public class MainActivity extends Activity implements MiniMapView.TapListener {
    private static final int REQ_LOCATION = 1;
    private static final int MODE_NONE = 0;
    private static final int MODE_SET_POSITION = 1;
    private static final int MODE_FACE_POINT = 2;
    private static final int MODE_HAIL = 3;

    private static final int TOP_BG = 0xff1f2a36;
    private static final int PANEL_BG = 0xfff7f9fb;
    private static final int INK = 0xff17212b;
    private static final int MUTED = 0xff5e6b78;
    private static final int OK_GREEN = 0xff5ad08a;
    private static final int WARN = 0xfff0b23a;
    private static final int BAD = 0xffff7a70;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private AppCore core;
    private float dp;
    private int mode = MODE_NONE;

    private EditText hostInput;
    private Button connectBtn;
    private TextView statusLine;
    private MiniMapView mapView;
    private TextView motionLine;
    private TextView poseLine;
    private TextView sensorLine;
    private TextView gnssLine;
    private TextView taskLine;
    private Button positionBtn;
    private Button headingBtn;
    private Button calibBtn;
    private Button simBtn;
    private Button outdoorBtn;
    private Button recordBtn;
    private Button hailBtn;
    private Button settingsBtn;

    // 传感器频率统计
    private long rateT;
    private long lastAcc;
    private long lastGyro;
    private long lastMag;
    private double accHz;
    private double gyroHz;
    private double magHz;
    // 订单完成提示
    private long taskGoneAt;
    private boolean taskSeen;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 100);
        }
    };

    // ================================================================ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        core = AppCore.get(this);
        dp = getResources().getDisplayMetrics().density;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(TOP_BG);
        setContentView(buildUi());
        if (savedInstanceState == null && !core.gnss.permitted()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        core.sensors.start();
        if (core.gnss.permitted()) core.gnss.start();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        core.sensors.stop();
        core.gnss.stop();
        core.savePose();
        core.flushRecording();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) core.shutdown();     // 真正退出：断开连接、保存数据
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOCATION) return;
        if (core.gnss.permitted()) {
            core.gnss.start();
        } else {
            toast("没有“精确位置”权限：卫星定位不可用，室内步行推算（PDR）不受影响");
        }
    }

    // ================================================================ 界面

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);              // 安卓 15 起默认全面屏：留出状态栏 / 导航栏
        root.setBackgroundColor(TOP_BG);

        // ---- 顶栏：服务端地址 + 连接按钮 + 状态
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(px(10), px(6), px(6), 0);
        hostInput = new EditText(this);
        hostInput.setSingleLine(true);
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setHint("电脑的 IP，例如 192.168.1.23");
        hostInput.setTextColor(0xffffffff);
        hostInput.setHintTextColor(0xff8796a8);
        hostInput.setTextSize(15);
        hostInput.setText(core.prefs.host());
        top.addView(hostInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        connectBtn = button("连接", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onConnectClicked();
            }
        });
        top.addView(connectBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(top);

        statusLine = new TextView(this);
        statusLine.setTextSize(13);
        statusLine.setTextColor(0xffc9d3de);
        statusLine.setPadding(px(12), 0, px(12), px(6));
        statusLine.setMaxLines(2);
        root.addView(statusLine);

        // ---- 浅色主体
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(PANEL_BG);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mapView = new MiniMapView(this, core);
        mapView.setTapListener(this);
        body.addView(mapView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(px(10), px(6), px(10), px(4));
        motionLine = infoLine(info);
        poseLine = infoLine(info);
        sensorLine = infoLine(info);
        gnssLine = infoLine(info);
        taskLine = infoLine(info);
        taskLine.setTypeface(Typeface.DEFAULT_BOLD);
        taskLine.setVisibility(View.GONE);
        body.addView(info);

        positionBtn = button("设位置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPositionClicked();
            }
        });
        headingBtn = button("对方向", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onHeadingClicked();
            }
        });
        calibBtn = button("步长标定", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onCalibrationClicked();
            }
        });
        simBtn = button("模拟行走", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSimClicked();
            }
        });
        outdoorBtn = button("室外模式", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onOutdoorClicked();
            }
        });
        recordBtn = button("记录数据", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRecordClicked();
            }
        });
        hailBtn = button("叫车", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onHailClicked();
            }
        });
        settingsBtn = button("设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        body.addView(buttonRow(positionBtn, headingBtn, calibBtn, simBtn));
        body.addView(buttonRow(outdoorBtn, recordBtn, hailBtn, settingsBtn));
        return root;
    }

    private TextView infoLine(LinearLayout parent) {
        TextView t = new TextView(this);
        t.setTextSize(13);
        t.setTextColor(INK);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        parent.addView(t);
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout buttonRow(Button... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(px(4), 0, px(4), 0);
        for (Button b : buttons) {
            row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private int px(float dips) {
        return Math.round(dips * dp);
    }

    // ================================================================ 每 0.1 s 刷新

    private void refresh() {
        long now = AppCore.now();
        PdrState s = core.engine.state(now);
        DwConnection.Status cs = core.connStatus();
        MapData map = core.map();
        WorldSnapshot snap = core.snapshot();
        core.addTrail(s.x, s.y);
        core.logPose(s, cs);

        updateStatusLine(cs, snap);
        updateRates(now);

        motionLine.setText("步数 " + s.steps + " · 步长 " + Fmt.show(s.stepLength, 2) + " m · "
                + Fmt.show(s.cadence, 0) + " 步/分 · 共 " + Fmt.show(s.distance, 1) + " m");
        double deg = Math.toDegrees(s.theta);
        poseLine.setText("位置 (" + Fmt.show(s.x, 1) + ", " + Fmt.show(s.y, 1) + ") · 朝向 "
                + Fmt.show(deg, 0) + "°" + Geo.arrow(s.theta) + " · " + Fmt.show(s.v, 1) + " m/s"
                + (s.scale != 1 ? " · ×" + Fmt.show(s.scale, 1) : ""));
        String sensors = "加速度 " + rate(core.sensors.hasAccel, accHz)
                + " 陀螺 " + rate(core.sensors.hasGyro, gyroHz)
                + " 磁力 " + rate(core.sensors.hasMag, magHz)
                + " · 倾角 " + Fmt.show(s.tiltDeg, 0) + "°";
        if (s.calibrating) sensors = "标定中：已走 " + s.calibSteps + " 步（" + Fmt.show(s.calibDistance, 1) + " m）";
        sensorLine.setText(sensors);
        sensorLine.setTextColor(core.sensors.hasAccel ? INK : 0xffcf3f36);
        gnssLine.setText(gnssText(s));

        String task = taskText(snap);
        taskLine.setVisibility(task == null ? View.GONE : View.VISIBLE);
        if (task != null) taskLine.setText(task);

        connectBtn.setText(core.active() ? "断开" : "连接");
        calibBtn.setText(s.calibrating ? "完成标定" : "步长标定");
        simBtn.setText(s.simWalk ? "停止模拟" : "模拟行走");
        outdoorBtn.setText(s.outdoor ? "室外：开" : "室外模式");
        recordBtn.setText(core.recording() ? "停止记录" : "记录数据");
        positionBtn.setText(mode == MODE_SET_POSITION ? "取消" : "设位置");
        hailBtn.setText(mode == MODE_HAIL ? "取消" : "叫车");

        mapView.update(map, snap, s, cs == null ? null : cs.agentId, core.myTaskId, hint());
    }

    private void updateStatusLine(DwConnection.Status cs, WorldSnapshot snap) {
        if (cs == null) {
            statusLine.setText("未连接：只在手机上推算位置。填入运行服务端的电脑 IP，点“连接”接入数字世界");
            statusLine.setTextColor(0xffc9d3de);
            return;
        }
        Endpoint ep = core.endpoint();
        String where = ep == null ? "" : ep.host + ":" + ep.tcpPort;
        switch (cs.state) {
            case CONNECTED: {
                StringBuilder sb = new StringBuilder("● 已接入 ").append(cs.agentId)
                        .append(" · 往返 ").append(Fmt.show(cs.rttMs, 0)).append(" ms · 已上报 ").append(cs.sent);
                if (snap != null) sb.append(" · 出租车 ").append(snap.taxiCount()).append(" 辆");
                String pe = core.pollError();
                if (pe != null) sb.append("\n地图/快照获取失败：").append(pe);
                if (cs.serverError != null) sb.append("\n服务端提示：").append(cs.serverError);
                statusLine.setText(sb);
                statusLine.setTextColor(pe == null && cs.serverError == null ? OK_GREEN : WARN);
                break;
            }
            case CONNECTING:
                statusLine.setText("正在连接 " + where + " …" + (cs.error != null ? "\n上次失败：" + cs.error : ""));
                statusLine.setTextColor(WARN);
                break;
            case WAITING:
                statusLine.setText("连接 " + where + " 失败，稍后自动重试：" + cs.error);
                statusLine.setTextColor(BAD);
                break;
            default:
                statusLine.setText("已断开");
                statusLine.setTextColor(0xffc9d3de);
        }
    }

    private void updateRates(long now) {
        double dt = (now - rateT) * 1e-9;
        if (dt < 2) return;
        core.flushRecording();
        long a = core.sensors.accelCount, g = core.sensors.gyroCount, m = core.sensors.magCount;
        if (rateT != 0) {
            accHz = (a - lastAcc) / dt;
            gyroHz = (g - lastGyro) / dt;
            magHz = (m - lastMag) / dt;
        }
        lastAcc = a;
        lastGyro = g;
        lastMag = m;
        rateT = now;
    }

    private static String rate(boolean present, double hz) {
        return present ? Fmt.show(hz, 0) + "Hz" : "无";
    }

    private String gnssText(PdrState s) {
        GnssSource g = core.gnss;
        if (!g.permitted()) return "卫星：未授权精确位置";
        if (g.error != null) return "卫星：" + g.error;
        if (!g.running()) return "卫星：未开启";
        StringBuilder sb = new StringBuilder("卫星 ").append(g.used).append('/').append(g.visible)
                .append("（北斗 ").append(g.beidouUsed).append('/').append(g.beidouVisible).append('）');
        if (!Double.isNaN(s.gnssAccuracy) && s.gnssAge < 5) {
            sb.append(" · 精度 ").append(Fmt.show(s.gnssAccuracy, 0)).append(" m");
            if (s.outdoor) sb.append(s.gnssUsed ? " · 已融合" : " · 未融合");
        } else {
            sb.append(s.outdoor ? " · 等待定位…" : " · 室内模式不融合");
        }
        return sb.toString();
    }

    /** 我叫的车现在怎样了；没有订单时返回 null。 */
    private String taskText(WorldSnapshot snap) {
        String id = core.myTaskId;
        if (id == null) return null;
        if (snap == null) return "订单 " + id + "：等待服务端数据…";
        WorldSnapshot.Task t = snap.task(id);
        if (t == null) {
            if (!taskSeen) return "订单 " + id + "：已提交";
            if (taskGoneAt == 0) taskGoneAt = System.currentTimeMillis();
            if (System.currentTimeMillis() - taskGoneAt > 15000) {
                core.myTaskId = null;
                taskSeen = false;
                taskGoneAt = 0;
                return null;
            }
            return "订单 " + id + "：已送达（或已取消）";
        }
        taskSeen = true;
        taskGoneAt = 0;
        if ("WAITING".equals(t.state)) return "订单 " + id + "：等待派车（车都在忙）· 已等 " + Fmt.show(t.age, 0) + " s";
        WorldSnapshot.Agent taxi = snap.agent(t.agent);
        if ("ASSIGNED".equals(t.state)) {
            String left = taxi != null && taxi.route != null ? "，还有约 " + taxi.route.length / 2 + " m" : "";
            return "订单 " + id + "：" + t.agent + " 正在赶来" + left + "（站在路边别挡道）";
        }
        if ("PICKED".equals(t.state)) return "订单 " + id + "：" + t.agent + " 已接到乘客，前往目的地";
        return "订单 " + id + "：" + t.state;
    }

    private String hint() {
        switch (mode) {
            case MODE_SET_POSITION:
                return "点地图：我现在站在哪里";
            case MODE_FACE_POINT:
                return "点地图：我现在面朝哪个地方";
            case MODE_HAIL:
                return "点地图：我要去哪里";
            default:
                return null;
        }
    }

    // ================================================================ 按钮

    private void onConnectClicked() {
        mode = MODE_NONE;
        if (core.active()) {
            core.disconnect();
            toast("已断开，手机智能体会从数字世界中移除");
            return;
        }
        String text = hostInput.getText().toString().trim();
        Endpoint ep;
        try {
            ep = Endpoint.parse(text);
        } catch (IllegalArgumentException e) {
            toast(e.getMessage());
            return;
        }
        core.prefs.setHost(text);
        core.connect(ep);
        hostInput.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(hostInput.getWindowToken(), 0);
    }

    private void onPositionClicked() {
        if (mode == MODE_SET_POSITION) {
            mode = MODE_NONE;
            return;
        }
        if (core.map() == null) {
            core.setPosition(0, 0);
            toast("还没有地图：已把当前位置设为本地原点");
            return;
        }
        mode = MODE_SET_POSITION;
    }

    private void onHeadingClicked() {
        mode = MODE_NONE;
        final String[] items = {"→ 地图右方（+x）", "↑ 地图上方（+y）", "← 地图左方（−x）", "↓ 地图下方（−y）",
                "在地图上点一下我面朝的地方…"};
        new AlertDialog.Builder(this)
                .setTitle("手机机头朝前，我现在面朝：")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which < 4) {
                            core.engine.alignHeading(which * Math.PI / 2);
                            toast("方向已对准：" + items[which]);
                        } else if (core.map() == null) {
                            toast("请先连接服务端获取地图");
                        } else {
                            mode = MODE_FACE_POINT;
                        }
                    }
                })
                .show();
    }

    private void onCalibrationClicked() {
        mode = MODE_NONE;
        final PdrState s = core.engine.state(AppCore.now());
        if (!s.calibrating) {
            new AlertDialog.Builder(this)
                    .setTitle("步长标定")
                    .setMessage("1. 在地上量出一段直线距离（建议 10 m 以上）。\n"
                            + "2. 站在起点，像平时看手机一样拿好，点“开始”。\n"
                            + "3. 用平常的步伐走到终点，再点“完成标定”，输入实际距离。")
                    .setPositiveButton("开始", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int which) {
                            core.engine.startCalibration();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        final EditText dist = numberField("10", false);
        new AlertDialog.Builder(this)
                .setTitle("走完了吗？")
                .setMessage("刚才走了 " + s.calibSteps + " 步，按当前参数估计 " + Fmt.show(s.calibDistance, 2)
                        + " m。\n请输入实际距离（米）：")
                .setView(padded(dist))
                .setPositiveButton("完成", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        double meters = parse(dist);
                        int steps = core.engine.state(AppCore.now()).calibSteps;
                        double k = core.engine.finishCalibration(meters);
                        if (Double.isNaN(k)) {
                            toast("标定失败：步数太少（至少 4 步）或距离不合理，参数未改变");
                        } else {
                            core.prefs.setConfig(core.engine.config());
                            toast("标定完成：K = " + Fmt.show(k, 3) + "，平均步长 " + Fmt.show(meters / steps, 2) + " m");
                        }
                    }
                })
                .setNeutralButton("放弃标定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        core.engine.cancelCalibration();
                    }
                })
                .setNegativeButton("继续走", null)
                .show();
    }

    private void onSimClicked() {
        mode = MODE_NONE;
        long now = AppCore.now();
        boolean on = !core.engine.state(now).simWalk;
        core.engine.setSimWalk(on, now);
        if (on) toast("模拟行走：按设定步频沿手机朝向前进。手机平放在桌上，转动手机就能转弯");
    }

    private void onOutdoorClicked() {
        mode = MODE_NONE;
        PdrConfig c = core.engine.config();
        c.outdoor = !c.outdoor;
        core.applyConfig(c);
        if (c.outdoor) {
            toast("室外模式：磁力计定北，卫星定位（含北斗）修正漂移。室内请关闭");
            if (!core.gnss.permitted()) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            }
        } else {
            toast("室内模式：只用陀螺仪定向，不使用卫星定位");
        }
    }

    private void onRecordClicked() {
        mode = MODE_NONE;
        if (core.recording()) {
            LogFiles.Recording r = core.stopRecording();
            if (r != null) {
                String err = r.error();
                toast(err != null ? "记录有错误：" + err
                        : "已保存 pose_" + r.stamp + ".csv 和 imu_" + r.stamp + ".csv\n位置：" + r.where);
            }
            return;
        }
        try {
            String where = core.startRecording();
            toast("开始记录（位姿 10 Hz + 原始传感器 50 Hz），保存到：" + where);
        } catch (IOException e) {
            toast("无法创建记录文件：" + e.getMessage());
        }
    }

    private void onHailClicked() {
        if (mode == MODE_HAIL) {
            mode = MODE_NONE;
            return;
        }
        DwConnection.Status cs = core.connStatus();
        if (cs == null || cs.state != DwConnection.State.CONNECTED || core.map() == null) {
            toast("请先连接服务端");
            return;
        }
        mode = MODE_HAIL;
    }

    // ================================================================ 点地图

    @Override
    public void onMapTap(double x, double y) {
        int m = mode;
        mode = MODE_NONE;
        switch (m) {
            case MODE_SET_POSITION:
                core.setPosition(x, y);
                toast("我的位置已设为 (" + Fmt.show(x, 1) + ", " + Fmt.show(y, 1) + ")");
                break;
            case MODE_FACE_POINT:
                core.engine.faceToward(x, y);
                toast("方向已对准");
                break;
            case MODE_HAIL:
                hail(x, y);
                break;
            default:
                mapView.toggleFollow();         // 平时轻点地图：在"跟随我"和"全图"之间切换
                break;
        }
    }

    private void hail(double tx, double ty) {
        MapData map = core.map();
        WorldSnapshot snap = core.snapshot();
        if (map == null) return;
        PdrState me = core.engine.state(AppCore.now());
        int[] blocked = snap == null ? null : snap.blocked;
        final int[] from = map.nearestLane(me.x, me.y, blocked, 1.2);   // 上车点不能是自己脚下的格子
        final int[] to = map.nearestLane(tx, ty, blocked);
        if (from == null || to == null) {
            toast("地图上找不到可以停车的车道");
            return;
        }
        if (Math.abs(from[0] - to[0]) + Math.abs(from[1] - to[1]) < 3) {
            toast("目的地离你太近了，走过去吧");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String id = core.hail(from[0], from[1], to[0], to[1]);
                    core.sendEvent("叫车 " + id);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            core.myTaskId = id;
                            taskSeen = false;
                            taskGoneAt = 0;
                            toast("已叫车：订单 " + id + "。请站在路边等候，不要站在车道上");
                        }
                    });
                } catch (final IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast("叫车失败：" + e.getMessage());
                        }
                    });
                }
            }
        }, "hail").start();
    }

    // ================================================================ 设置

    private void showSettings() {
        mode = MODE_NONE;
        final PdrConfig c = core.engine.config();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20), px(8), px(20), px(8));
        final EditText id = field(box, "手机编号（数字世界里显示的名字）", core.prefs.phoneId(), false, false);
        final EditText scale = field(box, "比例尺：真实走 1 m = 地图上多少 m（教室里可设 3~5）",
                Fmt.show(c.mapScale, 2), true, false);
        final EditText k = field(box, "步长系数 K（步长 = K·Δa^¼，用“步长标定”自动求）",
                Fmt.show(c.weinbergK, 3), true, false);
        final EditText amp = field(box, "计步阈值 m/s²（走路不计步就调小，晃手机误计步就调大）",
                Fmt.show(c.minStepAmplitude, 2), true, false);
        final EditText simLen = field(box, "模拟行走：步长 m", Fmt.show(c.simStepLength, 2), true, false);
        final EditText simHz = field(box, "模拟行走：步频 步/秒", Fmt.show(c.simCadenceHz, 2), true, false);
        final EditText decl = field(box, "磁偏角 °（东偏为正，上海约 −6.5，仅室外模式用）",
                Fmt.show(c.declinationDeg, 1), true, true);
        final EditText smooth = field(box, "上报位置平滑时间常数 s（0 = 不平滑）", Fmt.show(c.smoothingTau, 2), true, false);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(sv)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        PdrConfig n = c.copy();
                        n.mapScale = positive(scale, c.mapScale);
                        n.weinbergK = positive(k, c.weinbergK);
                        n.minStepAmplitude = positive(amp, c.minStepAmplitude);
                        n.simStepLength = positive(simLen, c.simStepLength);
                        n.simCadenceHz = positive(simHz, c.simCadenceHz);
                        double dv = parse(decl);
                        if (!Double.isNaN(dv)) n.declinationDeg = dv;
                        double sm = parse(smooth);
                        if (!Double.isNaN(sm) && sm >= 0) n.smoothingTau = sm;
                        core.applyConfig(n);
                        String newId = id.getText().toString().trim();
                        if (!newId.isEmpty() && !newId.equals(core.prefs.phoneId())) {
                            core.prefs.setPhoneId(newId);
                            Endpoint ep = core.endpoint();
                            if (core.active() && ep != null) core.connect(ep);   // 用新编号重新接入
                        }
                        toast("已保存");
                    }
                })
                .setNeutralButton("恢复默认", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        PdrConfig n = new PdrConfig();
                        n.outdoor = c.outdoor;
                        core.applyConfig(n);
                        toast("已恢复默认参数（步长系数也恢复为 " + Fmt.show(n.weinbergK, 2) + "）");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText field(LinearLayout box, String label, String value, boolean number, boolean signed) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setTextColor(MUTED);
        t.setPadding(0, px(10), 0, 0);
        box.addView(t);
        EditText e = number ? numberField(value, signed) : new EditText(this);
        if (!number) {
            e.setSingleLine(true);
            e.setText(value);
        }
        box.addView(e);
        return e;
    }

    private EditText numberField(String value, boolean signed) {
        EditText e = new EditText(this);
        int type = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        if (signed) type |= InputType.TYPE_NUMBER_FLAG_SIGNED;
        e.setInputType(type);
        e.setSingleLine(true);
        e.setSelectAllOnFocus(true);
        e.setText(value);
        return e;
    }

    private View padded(View v) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(px(20), px(4), px(20), 0);
        box.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private static double parse(EditText e) {
        String s = e.getText().toString().trim().replace('，', '.').replace('−', '-');
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    private static double positive(EditText e, double fallback) {
        double v = parse(e);
        return v > 0 ? v : fallback;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
