package com.zongc.dw;

import com.zongc.dw.api.HttpApi;
import com.zongc.dw.config.Config;
import com.zongc.dw.device.DeviceGateway;
import com.zongc.dw.device.NetUtil;
import com.zongc.dw.map.GridMap;
import com.zongc.dw.map.MapLoader;
import com.zongc.dw.sim.SimulationEngine;
import com.zongc.dw.util.Log;

import java.net.BindException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * MiniDiDi 数字世界服务端入口。
 *
 * <pre>
 * java -cp build/classes:lib/gson-2.13.1.jar com.zongc.dw.DwServer [--config config/default.json] [--debug]
 * </pre>
 *
 * 启动后：浏览器打开 http://localhost:8080 ；设备连接 TCP 9000（UDP 9001 可选）。
 */
public final class DwServer {

    public static void main(String[] args) throws Exception {
        String configArg = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configArg = args[++i];
                case "--debug" -> Log.setDebug(true);
                case "-h", "--help" -> {
                    System.out.println("用法: DwServer [--config 配置文件] [--debug]");
                    return;
                }
                default -> {
                    System.err.println("未知参数: " + args[i]);
                    System.exit(2);
                }
            }
        }
        Path cfgPath = configArg != null ? Path.of(configArg) : findDefaultConfig();
        if (cfgPath == null || !Files.isRegularFile(cfgPath)) {
            System.err.println("找不到配置文件。请在仓库根目录或 server 目录下运行，或用 --config 指定。");
            System.exit(1);
        }
        Config cfg = Config.load(cfgPath);
        GridMap map = MapLoader.load(cfg.resolve(cfg.map));
        Path webRoot = cfg.resolve(cfg.webRoot);

        SimulationEngine engine = new SimulationEngine(cfg, map);
        DeviceGateway gateway = new DeviceGateway(cfg.devices, engine);
        HttpApi http = new HttpApi(cfg, engine, webRoot);
        try {
            gateway.start();
            http.start();
        } catch (BindException e) {
            System.err.println("端口被占用（HTTP " + cfg.http.port + " / TCP " + cfg.devices.tcpPort + " / UDP "
                    + cfg.devices.udpPort + "）。请关闭已在运行的服务端，或在配置文件中修改端口。");
            System.exit(1);
        }
        engine.start();

        Log.info("配置文件: " + cfgPath.toAbsolutePath().normalize());
        Log.info("地图: " + map.name() + " (" + map.width() + "x" + map.height() + ", " + map.resolution() + " m/格)");
        Log.info("前端目录: " + webRoot + (Files.isDirectory(webRoot) ? "" : "（不存在！）"));
        Log.info("浏览器打开:  http://localhost:" + cfg.http.port);
        for (String ip : NetUtil.lanAddresses()) {
            Log.info("局域网地址:  http://" + ip + ":" + cfg.http.port + "   设备 TCP " + ip + ":" + cfg.devices.tcpPort);
        }
        Log.info("设备接入:    TCP " + cfg.devices.tcpPort + "，UDP " + cfg.devices.udpPort + "（协议见 docs/protocol.md）");

        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("正在关闭...");
            engine.stop();
            http.stop();
            gateway.stop();
            done.countDown();
        }, "shutdown"));
        done.await();
    }

    private static Path findDefaultConfig() {
        for (String p : new String[]{"config/default.json", "server/config/default.json", "../server/config/default.json"}) {
            Path path = Path.of(p);
            if (Files.isRegularFile(path)) return path;
        }
        return null;
    }
}
