function run_fleet(host, ids, showPlot, duration)
%RUN_FLEET  启动多辆 Matlab 模拟车接入数字世界（数字孪生）。
%
%   run_fleet                         % 默认连接本机，car-1 … car-5，显示本地视图
%   run_fleet("192.168.1.20")         % 服务端在另一台电脑上
%   run_fleet("127.0.0.1", ["car-1" "car-2"], false)
%   run_fleet("127.0.0.1", [], false, 180)   % 运行 180 s 后自动结束（自动测试 / CI 用）
%
% 使用前先启动服务端（仓库根目录 scripts/start_server.bat 或 scripts/start_server.sh）。
% 关闭"Matlab 模拟车视图"窗口或按 Ctrl+C 结束，结束后数字世界会自动接管这些车。
%
% 每辆模拟车使用一条独立的 TCP 连接，绑定服务端的一辆数字车。服务端下发路线和
% 行驶授权，SimCar 负责控制车辆并以 10 Hz 上报位姿。需要 MATLAB R2020b 或更新版本。

if nargin < 1 || isempty(host), host = "127.0.0.1"; end
if nargin < 2 || isempty(ids), ids = ["car-1", "car-2", "car-3", "car-4", "car-5"]; end
if nargin < 3, showPlot = true; end
if nargin < 4 || isempty(duration), duration = Inf; end   % 运行多少秒后自动结束，默认一直运行

cfg.host       = string(host);
cfg.port       = 9000;           % 服务端设备接入 TCP 端口
cfg.httpPort   = 8080;           % 服务端网页端口（用于获取地图画图）
cfg.ids        = string(ids);
cfg.dt         = 0.05;           % 控制周期（秒），20 Hz
cfg.stateEvery = 2;              % 每 2 个控制周期上报一次状态，即 10 Hz
cfg.noise      = 0.0;            % 位置噪声（m/s），可模拟真实车辆的误差

N = numel(cfg.ids);
clients = cell(1, N);
cars = cell(1, N);
for k = 1:N
    clients{k} = DwClient(cfg.host, cfg.port);
    cars{k} = SimCar(cfg.ids(k));
    cars{k}.Noise = cfg.noise;
    if clients{k}.connect()
        clients{k}.send(registerMsg(cfg.ids(k), k));
    end
end
cleanupObj = onCleanup(@() closeAll(clients, cars)); %#ok<NASGU>

viewer = [];
if showPlot
    try
        viewer = FleetView(cfg.host, cfg.httpPort, cars);
    catch err
        warning('run_fleet:map', '无法获取地图，不显示本地视图：%s', err.message);
    end
end

fprintf('已启动 %d 辆模拟车，连接 %s:%d。关闭图窗或按 Ctrl+C 结束。\n', N, cfg.host, cfg.port);
tick = 0;
tStart = tic;
lastRetry = tic;
lastTick = tic;
while toc(tStart) < duration
    loopStart = tic;
    dt = min(toc(lastTick), 0.1);       % 用实际经过的时间积分，保证与服务端实时同步
    lastTick = tic;
    if tick == 0, dt = cfg.dt; end

    retryNow = toc(lastRetry) > 2;
    for k = 1:N
        c = clients{k};
        if ~c.Connected
            % 断线后每 2 秒重连一次，重连后重新注册（数字世界会把位姿同步回来）
            if retryNow && c.connect()
                c.send(registerMsg(cfg.ids(k), k));
            end
            continue;
        end
        msgs = c.poll();
        for j = 1:numel(msgs)
            cars{k}.onMessage(msgs{j});
        end
        cars{k}.step(dt);
        if mod(tick, cfg.stateEvery) == 0 && cars{k}.Ready
            c.send(cars{k}.stateMessage(tick));
        end
    end
    if retryNow, lastRetry = tic; end

    if ~isempty(viewer) && mod(tick, 4) == 0
        if ~viewer.isOpen(), break; end
        viewer.update(cars);
    end
    tick = tick + 1;
    pause(max(0.001, cfg.dt - toc(loopStart)));
end
end

% ---------------------------------------------------------------- 局部函数
function msg = registerMsg(id, k)
msg = struct('type', 'register', 'kind', 'matlab', 'agentId', char(id), ...
    'name', sprintf('matlab-%d', k));
end

function closeAll(clients, cars)
fprintf('\n%-8s %6s %6s %12s\n', 'car', 'routes', 'permits', 'distance(m)');
for k = 1:numel(clients)
    fprintf('%-8s %6d %6d %12.1f\n', cars{k}.AgentId, cars{k}.Routes, cars{k}.Permits, cars{k}.Dist);
    clients{k}.close();
end
disp('已断开所有连接，数字世界将接管这些车辆。');
end
