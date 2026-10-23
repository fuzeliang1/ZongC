package com.zongc.dw;

import com.zongc.dw.api.HttpApi;
import com.zongc.dw.config.Config;
import com.zongc.dw.device.DeviceGateway;
import com.zongc.dw.device.NetUtil;
import com.zongc.dw.map.GridMap;
import com.zongc.dw.map.MapLoader;
import com.zongc.dw.sim.SimulationEngine;
import com.zongc.dw.util.Log;
import com.zongc.dw.world.Scenario;

import java.net.BindException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * MiniDiDi 数字世界服务端入口。
 *
 * <pre>
 * java -cp build/classes:lib/gson-2.13.1.jar com.zongc.dw.DwServer [--config config/default.json]
 *      [--scenario scenarios/demo.json] [--debug]
 * </pre>
 *
 * 启动后：浏览器打开 http://localhost:8080 ；设备连接 TCP 9000（UDP 9001 可选）。
 */
public final class DwServer {

    public static void main(String[] args) throws Exception {
        String configArg = null;
        String scenarioArg = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configArg = args[++i];
                case "--scenario" -> scenarioArg = args[++i];
                case "--debug" -> Log.setDebug(true);
                case "-h", "--help" -> {
                    System.out.println("用法: DwServer [--config 配置文件] [--scenario 场景脚本|none] [--debug]");
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
        Path scenarioPath = scenarioPath(cfg, scenarioArg);
        if (scenarioPath != null) {
            try {
                engine.setInitialScenario(Scenario.load(scenarioPath, map), scenarioPath.getFileName().toString());
            } catch (IllegalArgumentException | java.io.IOException e) {
                System.err.println("场景脚本加载失败：" + e.getMessage());
                System.exit(1);
            }
        }
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
        if (engine.scenario() != null) {
            Log.info("场景脚本:    " + engine.scenario().name + "（" + scenarioPath + "）—— 按时间自动触发事件，无需人工操作");
        }

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

    /**
     * 场景脚本的位置：命令行 --scenario 优先（相对当前目录，找不到再到场景目录里找；none 表示不运行），
     * 否则用配置文件里的 scenario（相对配置文件目录）。
     */
    private static Path scenarioPath(Config cfg, String arg) {
        if (arg != null) {
            if (arg.equalsIgnoreCase("none")) return null;
            Path p = Path.of(arg);
            if (Files.isRegularFile(p)) return p;
            Path inDir = cfg.resolve(cfg.scenarioDir).resolve(arg);
            if (Files.isRegularFile(inDir)) return inDir;
            Path withExt = cfg.resolve(cfg.scenarioDir).resolve(arg + ".json");
            if (Files.isRegularFile(withExt)) return withExt;
            System.err.println("找不到场景脚本: " + arg);
            System.exit(1);
        }
        return cfg.scenario == null ? null : cfg.resolve(cfg.scenario);
    }

    private static Path findDefaultConfig() {
        for (String p : new String[]{"config/default.json", "server/config/default.json", "../server/config/default.json"}) {
            Path path = Path.of(p);
            if (Files.isRegularFile(path)) return path;
        }
        return null;
    }
}
