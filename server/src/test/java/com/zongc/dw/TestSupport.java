package com.zongc.dw;

import com.zongc.dw.config.Config;
import com.zongc.dw.map.GridMap;
import com.zongc.dw.map.MapLoader;
import com.zongc.dw.world.EventLog;
import com.zongc.dw.world.World;

import java.nio.file.Files;
import java.nio.file.Path;

/** 测试公用：加载默认配置和地图（测试在 server 目录下运行）。 */
public final class TestSupport {
    private TestSupport() {}

    public static Config config() throws Exception {
        Path p = Path.of("config/default.json");
        if (!Files.exists(p)) p = Path.of("server/config/default.json");
        return Config.load(p);
    }

    public static GridMap map(Config cfg) throws Exception {
        return MapLoader.load(cfg.resolve(cfg.map));
    }

    public static World world(Config cfg) throws Exception {
        EventLog log = new EventLog(200);
        log.setEcho(false);
        return new World(cfg, map(cfg), log);
    }
}
