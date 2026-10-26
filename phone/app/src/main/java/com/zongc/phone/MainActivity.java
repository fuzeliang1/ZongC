package com.zongc.phone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.zongc.phone.core.DwConnection;
import com.zongc.phone.core.Endpoint;
import com.zongc.phone.core.Fmt;
import com.zongc.phone.core.MapData;
import com.zongc.phone.core.PdrConfig;
import com.zongc.phone.core.PdrState;
import com.zongc.phone.core.RouteFollower;
import com.zongc.phone.core.VisionResult;
import com.zongc.phone.core.WorldSnapshot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 主界面（纯代码搭建，不依赖任何第三方库）。手机 = 数字世界里的一辆模拟车，走路就是踩油门。
 *
 * <pre>
 * ┌ 服务端 IP ─────────────── [连接] ┐  深色顶栏 + 连接状态
 * │            小地图                 │  城市、所有车辆、我的车和它的路线、轨迹
 * │ 我的车：状态 / 车速 / 剩余路程     │  实时数据
 * │ 走路 / 手机位姿估计 / 传感器 / 卫星 │
 * │ [步长标定][模拟行走][室外模式]      │
 * └ [记录数据][拍照][设置]             ┘
 * </pre>
 */
public class MainActivity extends Activity implements MiniMapView.TapListener {
    private static final int REQ_LOCATION = 1;

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

    private EditText hostInput;
    private Button connectBtn;
    private TextView statusLine;
    private MiniMapView mapView;
    private TextView carLine;
    private TextView motionLine;
    private TextView poseLine;
    private TextView sensorLine;
    private TextView gnssLine;
    private Button calibBtn;
    private Button simBtn;
    private Button outdoorBtn;
    private Button recordBtn;
    private Button photoBtn;
    private Button settingsBtn;
    private static final int REQ_PHOTO = 41;

    // 传感器频率统计
    private long rateT;
    private long lastAcc;
    private long lastGyro;
    private long lastMag;
    private double accHz;
    private double gyroHz;
    private double magHz;

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
        // 全自主运行：打开 App 就自动连接上次的服务端，考核时不需要再点任何按钮
        if (savedInstanceState == null && core.prefs.autoConnect() && !core.active() && !core.prefs.host().isEmpty()) {
            onConnectClicked();
            toast("已自动连接 " + core.prefs.host() + "（可在“设置”里关闭自动连接）");
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
        carLine = infoLine(info);
        carLine.setTypeface(Typeface.DEFAULT_BOLD);
        carLine.setSingleLine(false);
        carLine.setMaxLines(2);
        motionLine = infoLine(info);
        poseLine = infoLine(info);
        sensorLine = infoLine(info);
        gnssLine = infoLine(info);
        body.addView(info);

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
        photoBtn = button("拍照", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPhotoClicked();
            }
        });
        settingsBtn = button("设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        body.addView(buttonRow(calibBtn, simBtn, outdoorBtn));
        body.addView(buttonRow(recordBtn, photoBtn, settingsBtn));
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
        RouteFollower.Status car = core.car.status();
        DwConnection.Status cs = core.connStatus();
        MapData map = core.map();
        WorldSnapshot snap = core.snapshot();
        core.track(s, car);
        core.logPose(s, car, cs);

        updateStatusLine(cs, snap);
        updateRates(now);

        carLine.setText(carText(car, s, cs, snap));
        double scale = core.engine.config().driveScale;
        motionLine.setText("走路 " + s.steps + " 步 · 步长 " + Fmt.show(s.stepLength, 2) + " m · "
                + Fmt.show(s.cadence, 0) + " 步/分 · " + Fmt.show(s.v, 1) + " m/s"
                + (scale != 1 ? " · 车速 ×" + Fmt.show(scale, 1) : ""));
        poseLine.setText("手机位姿估计 (" + Fmt.show(s.px, 1) + ", " + Fmt.show(s.py, 1) + ") m · 航向 "
                + Fmt.show(Math.toDegrees(s.yawP), 0) + "° · 共走 " + Fmt.show(s.distance, 1) + " m");
        String sensors = "加速度 " + rate(core.sensors.hasAccel, accHz)
                + " 陀螺 " + rate(core.sensors.hasGyro, gyroHz)
                + " 磁力 " + rate(core.sensors.hasMag, magHz)
                + " · 倾角 " + Fmt.show(s.tiltDeg, 0) + "°";
        if (s.calibrating) sensors = "标定中：已走 " + s.calibSteps + " 步（" + Fmt.show(s.calibDistance, 1) + " m）";
        sensorLine.setText(sensors);
        sensorLine.setTextColor(core.sensors.hasAccel ? INK : 0xffcf3f36);
        gnssLine.setText(gnssText(s));

        connectBtn.setText(core.active() ? "断开" : "连接");
        calibBtn.setText(s.calibrating ? "完成标定" : "步长标定");
        simBtn.setText(s.simWalk ? "停止模拟" : "模拟行走");
        outdoorBtn.setText(s.outdoor ? "室外：开" : "室外模式");
        recordBtn.setText(core.recording() ? "停止记录" : "记录数据");

        mapView.update(map, snap, car, s);
    }

    private void updateStatusLine(DwConnection.Status cs, WorldSnapshot snap) {
        if (cs == null) {
            statusLine.setText("未连接：只在手机上做惯性推算。填入运行服务端的电脑 IP，点“连接”，手机就成为数字世界里的一辆车");
            statusLine.setTextColor(0xffc9d3de);
            return;
        }
        Endpoint ep = core.endpoint();
        String where = ep == null ? "" : ep.host + ":" + ep.tcpPort;
        switch (cs.state) {
            case CONNECTED: {
                StringBuilder sb = new StringBuilder("● 已接入，驾驶 ").append(cs.agentId)
                        .append(" · 往返 ").append(Fmt.show(cs.rttMs, 0)).append(" ms · 已上报 ").append(cs.sent);
                if (snap != null) sb.append(" · 共 ").append(snap.agents.size()).append(" 辆车");
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

    /** 我的车现在怎样了。 */
    private static String carText(RouteFollower.Status c, PdrState s, DwConnection.Status cs, WorldSnapshot snap) {
        if (cs == null) return "我的车：未连接。连接后手机绑定数字世界里的一辆车，走路就是踩油门";
        if (!c.ready) return "我的车：等待服务端分配…";
        WorldSnapshot.Agent a = snap == null ? null : snap.agent(c.agentId);
        String status = a != null && a.status != null ? a.status : c.carStatus;   // 快照里的状态更新得更及时
        String task = a != null ? a.taskId : c.taskId;
        StringBuilder sb = new StringBuilder("我的车 ").append(c.agentId).append("：").append(statusLabel(status, task))
                .append(" · ").append(Fmt.show(c.v, 1)).append(" m/s");
        if (!c.hasRoute) {
            sb.append(" · 没有路线");
        } else if (c.arrived()) {
            sb.append(" · 已到达");
        } else {
            sb.append(" · 剩余 ").append(Fmt.show(c.remaining(), 0)).append(" m");
            if (c.waitingPermit()) sb.append(" · 等待放行（前方有车或障碍）");
            else if (!s.walking) sb.append(" · 走起来车就开");
        }
        return sb.toString();
    }

    private static String statusLabel(String status, String task) {
        String t = task == null ? "" : " " + task;
        if ("CRUISING".equals(status)) return "空车巡游";
        if ("TO_PICKUP".equals(status)) return "去接乘客" + t;
        if ("BOARDING".equals(status)) return "乘客上车中" + t;
        if ("TO_DROPOFF".equals(status)) return "载客前往目的地" + t;
        if ("ALIGHTING".equals(status)) return "乘客下车中" + t;
        return status == null ? "等待路线" : status;
    }

    // ================================================================ 按钮

    private void onConnectClicked() {
        if (core.active()) {
            core.disconnect();
            toast("已断开，这辆车由数字世界接管");
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

    private void onCalibrationClicked() {
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
        long now = AppCore.now();
        boolean on = !core.engine.state(now).simWalk;
        core.engine.setSimWalk(on, now);
        if (on) toast("模拟行走：按设定的步长和步频自动“走路”，手机车沿路线开起来（桌面演示用）");
    }

    private void onOutdoorClicked() {
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
            toast("开始记录（车与手机位姿 10 Hz + 原始传感器 50 Hz），保存到：" + where);
        } catch (IOException e) {
            toast("无法创建记录文件：" + e.getMessage());
        }
    }

    // ================================================================ 点地图

    @Override
    public void onMapTap(double x, double y) {
        mapView.toggleFollow();         // 轻点地图：在"跟随我的车"和"全图"之间切换
    }

    // ================================================================ 拍照识别

    /**
     * 拍照识别路障：调用系统相机拍一张照片（只取缩略图，不需要相机或存储权限），上传给服务端的
     * 图像处理模块。服务端识别出橙 / 红色路障后，按手机车当前位姿换算到地图上，出租车随即绕行。
     */
    private void onPhotoClicked() {
        DwConnection.Status cs = core.connStatus();
        if (cs == null || cs.state != DwConnection.State.CONNECTED) {
            toast("请先连接服务端");
            return;
        }
        try {
            startActivityForResult(new Intent(MediaStore.ACTION_IMAGE_CAPTURE), REQ_PHOTO);
        } catch (ActivityNotFoundException e) {
            toast("手机上没有可用的相机应用");
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PHOTO) return;
        if (resultCode != RESULT_OK || data == null || data.getExtras() == null) {
            toast("没有拍照");
            return;
        }
        Object o = data.getExtras().get("data");     // 相机返回的缩略图
        if (!(o instanceof Bitmap)) {
            toast("没有拿到照片");
            return;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ((Bitmap) o).compress(Bitmap.CompressFormat.JPEG, 92, bos);
        final byte[] jpeg = bos.toByteArray();
        toast("正在上传照片，由服务端识别……");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final VisionResult r = core.detectPhoto(jpeg);
                    core.sendEvent("拍照识别：" + r.summary);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast(r.summary + (r.mapped > 0 ? "。出租车会绕开它" : ""));
                        }
                    });
                } catch (final IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast("识别失败：" + e.getMessage());
                        }
                    });
                }
            }
        }, "photo").start();
    }

    // ================================================================ 设置

    private void showSettings() {
        final PdrConfig c = core.engine.config();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20), px(8), px(20), px(8));
        final EditText id = field(box, "车号：想驾驶数字世界里的哪辆车（被占用时服务端另分配一辆）",
                core.prefs.carId(), false, false);
        final EditText scale = field(box, "驾驶比例：人走 1 m，车开几 m（车速上限 1.5 m/s；走得慢可设 1.5~2）",
                Fmt.show(c.driveScale, 2), true, false);
        final EditText k = field(box, "步长系数 K（步长 = K·Δa^¼，用“步长标定”自动求）",
                Fmt.show(c.weinbergK, 3), true, false);
        final EditText amp = field(box, "计步阈值 m/s²（走路不计步就调小，晃手机误计步就调大）",
                Fmt.show(c.minStepAmplitude, 2), true, false);
        final EditText simLen = field(box, "模拟行走：步长 m", Fmt.show(c.simStepLength, 2), true, false);
        final EditText simHz = field(box, "模拟行走：步频 步/秒", Fmt.show(c.simCadenceHz, 2), true, false);
        final EditText decl = field(box, "磁偏角 °（东偏为正，上海约 −6.5，仅室外模式用）",
                Fmt.show(c.declinationDeg, 1), true, true);
        final CheckBox auto = new CheckBox(this);
        auto.setText("打开 App 时自动连接上次的服务端");
        auto.setChecked(core.prefs.autoConnect());
        box.addView(auto);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(sv)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        PdrConfig n = c.copy();
                        n.driveScale = positive(scale, c.driveScale);
                        n.weinbergK = positive(k, c.weinbergK);
                        n.minStepAmplitude = positive(amp, c.minStepAmplitude);
                        n.simStepLength = positive(simLen, c.simStepLength);
                        n.simCadenceHz = positive(simHz, c.simCadenceHz);
                        double dv = parse(decl);
                        if (!Double.isNaN(dv)) n.declinationDeg = dv;
                        core.applyConfig(n);
                        core.prefs.setAutoConnect(auto.isChecked());
                        String newId = id.getText().toString().trim();
                        if (!newId.isEmpty() && !newId.equals(core.prefs.carId())) {
                            core.prefs.setCarId(newId);
                            Endpoint ep = core.endpoint();
                            if (core.active() && ep != null) core.connect(ep);   // 换一辆车重新接入
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
