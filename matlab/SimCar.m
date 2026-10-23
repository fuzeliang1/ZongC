classdef SimCar < handle
    % SimCar  Matlab 模拟车：独轮车（差速）运动学模型 + 纯追踪转向 + 速度 PI 控制。
    %
    % 与数字世界的分工：
    %   服务端负责派单、全局路径规划和交通管控，下发 path（路点）和 permit（行驶授权）；
    %   本类负责"开车"——沿路点行驶、在授权的停车点前停稳、对前方车辆紧急制动，
    %   并把自己的位姿上报回去（服务端的数字车就是本车的数字孪生）。
    %
    % 关键约束：沿路线的弧长 s 绝不能超过 permit 消息里的 stopS。
    % 控制算法可以自由替换（例如 MPC、Stanley、速度障碍法），只要遵守这条约束。
    %
    % 本文件与 tools/mock_devices.py 中的 SimCar 逻辑一一对应，后者已在服务端上测试通过。

    properties
        AgentId = ''
        % 状态：位置 (m)、航向 (rad)、线速度 (m/s)、角速度 (rad/s)
        X = 0
        Y = 0
        Th = 0
        V = 0
        W = 0
        Ready = false            % 收到 welcome 后为 true

        % 车辆与控制参数（welcome 消息会覆盖前五项）
        R = 0.3                  % 车身半径
        VMax = 1.5               % 最高速度
        AAcc = 1.0               % 最大加速度
        ADec = 1.5               % 规划用减速度（计算刹车距离）
        VTurn = 0.6              % 转弯最低限速
        ABrake = 3.0             % 最大制动减速度
        WMax = 3.0               % 最大角速度
        ALat = 0.8               % 最大侧向加速度
        Kp = 3.0                 % 速度环比例系数
        Ki = 0.0                 % 速度环积分系数（可自行调节）
        Noise = 0.0              % 位置噪声标准差（m/s），用于模拟真实误差

        % 路线与授权
        Wp = zeros(0, 2)         % 路点，N×2
        Cum = 0                  % 各路点处的累计长度
        RouteId = -1
        StopS = 0                % 允许到达的最远累计长度
        Final = false            % 授权是否已到路线终点
        Purpose = ''
        Others = []              % 周边其他智能体（world 消息）

        % 统计
        Routes = 0
        Permits = 0
        Dist = 0
        LastS = 0
    end

    properties (Access = private)
        Seg = 1                  % 当前所在路段（第 Seg 到 Seg+1 个路点）
        GlobalSearch = true      % 新路线第一次投影做全局搜索
        IntegralErr = 0
    end

    methods
        function obj = SimCar(agentId)
            if nargin >= 1, obj.AgentId = char(agentId); end
        end

        % ------------------------------------------------------------ 消息处理
        function onMessage(obj, m)
            if ~isfield(m, 'type'), return; end
            switch m.type
                case 'welcome'
                    obj.AgentId = m.agentId;
                    obj.X = m.pose.x;
                    obj.Y = m.pose.y;
                    obj.Th = m.pose.theta;
                    obj.V = m.pose.v;
                    if isfield(m, 'params')
                        p = m.params;
                        if isfield(p, 'bodyRadius'), obj.R = p.bodyRadius; end
                        if isfield(p, 'vMax'), obj.VMax = p.vMax; end
                        if isfield(p, 'aAcc'), obj.AAcc = p.aAcc; end
                        if isfield(p, 'aDec'), obj.ADec = p.aDec; end
                        if isfield(p, 'vTurn'), obj.VTurn = p.vTurn; end
                    end
                    obj.Ready = true;
                    fprintf('[%s] 已接管数字车，起点 (%.2f, %.2f)\n', obj.AgentId, obj.X, obj.Y);

                case 'path'
                    wp = m.waypoints;
                    if isvector(wp) && numel(wp) == 2, wp = reshape(wp, 1, 2); end
                    obj.Wp = wp;
                    seglen = sqrt(sum(diff(wp, 1, 1).^2, 2));
                    obj.Cum = [0; cumsum(seglen)];
                    obj.RouteId = m.routeId;
                    obj.StopS = 0;          % 收到 permit 之前不能走
                    obj.Final = false;
                    obj.Seg = 1;
                    obj.GlobalSearch = true;
                    obj.Purpose = m.purpose;
                    obj.Routes = obj.Routes + 1;

                case 'permit'
                    if m.routeId == obj.RouteId
                        obj.StopS = m.stopS;
                        obj.Final = logical(m.final);
                        obj.Permits = obj.Permits + 1;
                    end

                case 'world'
                    a = m.agents;
                    if isstruct(a)
                        keep = ~strcmp({a.id}, obj.AgentId);
                        obj.Others = a(keep);
                    else
                        obj.Others = [];
                    end

                case 'error'
                    warning('SimCar:server', '[%s] 服务端错误：%s', obj.AgentId, m.message);
            end
        end

        % ------------------------------------------------------------ 几何
        function s = project(obj)
            % 把车辆位置投影到路点折线上，返回累计长度 s（只在当前段附近搜索，保证平稳前进）
            n = size(obj.Wp, 1);
            if n == 1
                s = -hypot(obj.X - obj.Wp(1, 1), obj.Y - obj.Wp(1, 2));
                return;
            end
            if obj.GlobalSearch
                lo = 1; hi = n - 1;
                obj.GlobalSearch = false;
            else
                lo = max(1, obj.Seg - 2); hi = min(n - 1, obj.Seg + 8);
            end
            bestD = inf; bestS = 0; bestI = obj.Seg;
            for i = lo:hi
                a = obj.Wp(i, :);
                b = obj.Wp(i + 1, :);
                L = obj.Cum(i + 1) - obj.Cum(i);
                if L < 1e-9, continue; end
                t = ((obj.X - a(1)) * (b(1) - a(1)) + (obj.Y - a(2)) * (b(2) - a(2))) / (L * L);
                tlo = 0; thi = 1;
                if i == 1, tlo = -10; end        % 允许落在第一个路点之前
                if i == n - 1, thi = 10; end     % 允许落在最后一个路点之后
                t = min(max(t, tlo), thi);
                p = a + (b - a) * t;
                d = hypot(obj.X - p(1), obj.Y - p(2));
                if d < bestD - 1e-9
                    bestD = d; bestS = obj.Cum(i) + t * L; bestI = i;
                end
            end
            obj.Seg = bestI;
            s = bestS;
        end

        function p = pointAt(obj, s)
            % 折线上累计长度 s 处的点
            n = size(obj.Wp, 1);
            if n == 1 || s >= obj.Cum(end)
                p = obj.Wp(end, :);
                return;
            end
            if s <= 0
                p = obj.Wp(1, :);
                return;
            end
            i = find(obj.Cum <= s, 1, 'last');
            i = min(i, n - 1);
            t = (s - obj.Cum(i)) / (obj.Cum(i + 1) - obj.Cum(i));
            p = obj.Wp(i, :) + (obj.Wp(i + 1, :) - obj.Wp(i, :)) * t;
        end

        % ------------------------------------------------------------ 控制 + 运动学
        function step(obj, dt)
            if ~obj.Ready, return; end
            vRef = 0; kappa = 0; alpha = 0; sNow = 0;
            hasPath = ~isempty(obj.Wp);
            if hasPath
                sNow = obj.project();
                % 纯追踪：在前视距离处取目标点，曲率 κ = 2·sin(α)/L
                ld = min(max(0.3 + 0.3 * obj.V, 0.35), 0.6);
                tgt = obj.pointAt(sNow + ld);
                dx = tgt(1) - obj.X; dy = tgt(2) - obj.Y;
                dist = hypot(dx, dy);
                if dist > 0.05
                    alpha = SimCar.wrap(atan2(dy, dx) - obj.Th);
                    kappa = 2 * sin(alpha) / dist;
                end
                % 速度参考：在授权停车点前刹得住（v ≤ √(2·a·d)）+ 转弯限速
                dStop = obj.StopS - sNow;
                vRef = min(obj.VMax, sqrt(2 * obj.ADec * max(0, dStop)));
                if abs(kappa) > 1e-3
                    vRef = min(vRef, max(obj.VTurn, sqrt(obj.ALat / abs(kappa))));
                end
                if dStop < 0.01, vRef = 0; end
                if abs(alpha) > 1.0, vRef = min(vRef, 0.05); end   % 方向偏差太大：先原地转向
                % 本地安全兜底：正前方很近处有其他车则急停（正常情况下服务端授权已保证安全）
                if isstruct(obj.Others)
                    c = cos(obj.Th); sn = sin(obj.Th);
                    for k = 1:numel(obj.Others)
                        o = obj.Others(k);
                        rx = o.x - obj.X; ry = o.y - obj.Y;
                        fwd = rx * c + ry * sn;
                        lat = -rx * sn + ry * c;
                        orad = obj.R;
                        if isfield(o, 'r'), orad = o.r; end
                        if fwd > 0 && fwd < 2 * obj.R + 0.12 + 0.3 * obj.V && abs(lat) < (obj.R + orad) * 0.9
                            vRef = 0;
                            break;
                        end
                    end
                end
            end

            % 速度 PI 控制 + 加速度限幅
            err = vRef - obj.V;
            obj.IntegralErr = min(max(obj.IntegralErr + err * dt, -0.5), 0.5);
            a = obj.Kp * err + obj.Ki * obj.IntegralErr;
            a = min(max(a, -obj.ABrake), obj.AAcc);
            vNew = max(0, obj.V + a * dt);
            if hasPath && sNow + vNew * dt > obj.StopS     % 硬约束：绝不越过授权停车点
                vNew = max(0, (obj.StopS - sNow) / dt);
            end
            obj.V = vNew;
            if abs(alpha) > 1.0 && obj.V < 0.1
                obj.W = sign(alpha) * obj.WMax * 0.6;
            else
                obj.W = min(max(obj.V * kappa, -obj.WMax), obj.WMax);
            end

            % 独轮车运动学积分
            nx = obj.X + obj.V * cos(obj.Th) * dt;
            ny = obj.Y + obj.V * sin(obj.Th) * dt;
            if obj.Noise > 0
                nx = nx + randn() * obj.Noise * dt;
                ny = ny + randn() * obj.Noise * dt;
            end
            obj.Dist = obj.Dist + hypot(nx - obj.X, ny - obj.Y);
            obj.X = nx;
            obj.Y = ny;
            obj.Th = SimCar.wrap(obj.Th + obj.W * dt);
            obj.LastS = sNow;
        end

        function msg = stateMessage(obj, seq)
            % 上报给服务端的状态消息
            msg = struct('type', 'state', 'agentId', obj.AgentId, 'seq', seq, ...
                'x', obj.X, 'y', obj.Y, 'theta', obj.Th, 'v', obj.V, 'w', obj.W);
        end

        function p = stopPoint(obj)
            % 当前授权的停车点（用于绘图）
            if isempty(obj.Wp)
                p = [obj.X, obj.Y];
            else
                p = obj.pointAt(obj.StopS);
            end
        end
    end

    methods (Static)
        function a = wrap(a)
            % 角度归一化到 (-pi, pi]
            a = mod(a + pi, 2 * pi) - pi;
            if a == -pi, a = pi; end
        end
    end
end
