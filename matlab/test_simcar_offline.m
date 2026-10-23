%% test_simcar_offline.m —— 不连服务端，单独测试 SimCar 的控制效果
%
% 构造一条"直行 → 左转 → 直行"的路线，分两次给出行驶授权，检查：
%   1. 车辆在第一次授权的停车点前停住，没有越界；
%   2. 第二次授权后继续行驶，最终停在终点附近；
%   3. 画出实际轨迹与参考路线的对比，以及速度曲线。
% 修改控制算法（例如换成 MPC）后，先用本脚本检查，再接入服务端。

clear; clc;
car = SimCar('car-test');
car.onMessage(struct('type', 'welcome', 'agentId', 'car-test', ...
    'pose', struct('x', 0.5, 'y', 0.5, 'theta', 0, 'v', 0), ...
    'params', struct('bodyRadius', 0.3, 'vMax', 1.5, 'aAcc', 1.0, 'aDec', 1.5, 'vTurn', 0.6)));

% 参考路线：沿 x 轴走到 (6.0, 0.5)，用半径 0.5 的四分之一圆弧左转，再向北走到 (6.5, 6.5)
straight1 = [(0.5:0.25:6.0)', 0.5 * ones(numel(0.5:0.25:6.0), 1)];
ang = linspace(-pi / 2, 0, 7)';
arc = [6.0 + 0.5 * cos(ang), 1.0 + 0.5 * sin(ang)];
straight2 = [6.5 * ones(numel(1.25:0.25:6.5), 1), (1.25:0.25:6.5)'];
wp = [straight1; arc(2:end, :); straight2];
seglen = sqrt(sum(diff(wp, 1, 1).^2, 2));
cum = [0; cumsum(seglen)];
total = cum(end);

car.onMessage(struct('type', 'path', 'routeId', 1, 'purpose', 'CRUISE', 'waypoints', wp));
stop1 = 4.0;
car.onMessage(struct('type', 'permit', 'routeId', 1, 'stopS', stop1, 'final', false));

dt = 0.05;
T = 16;
n = round(T / dt);
rec = zeros(n, 5);   % t, x, y, v, s
for i = 1:n
    t = i * dt;
    if abs(t - 6) < dt / 2
        car.onMessage(struct('type', 'permit', 'routeId', 1, 'stopS', total, 'final', true));
    end
    car.step(dt);
    rec(i, :) = [t, car.X, car.Y, car.V, car.LastS];
end

before = rec(rec(:, 1) < 6, :);
fprintf('第一次授权停车点 s = %.2f m，实际最远 s = %.3f m（应不超过停车点）\n', stop1, max(before(:, 5)));
fprintf('最终位置 (%.2f, %.2f)，终点 (%.2f, %.2f)，距离 %.3f m\n', car.X, car.Y, wp(end, 1), wp(end, 2), ...
    hypot(car.X - wp(end, 1), car.Y - wp(end, 2)));
assert(max(before(:, 5)) <= stop1 + 0.02, '越过了授权停车点！');
assert(hypot(car.X - wp(end, 1), car.Y - wp(end, 2)) < 0.25, '没有到达终点附近！');
disp('测试通过');

figure('Name', 'SimCar 离线测试', 'Color', 'w');
subplot(1, 2, 1);
plot(wp(:, 1), wp(:, 2), 'k--', rec(:, 2), rec(:, 3), 'b-', 'LineWidth', 1.5);
axis equal; grid on; legend('参考路线', '实际轨迹', 'Location', 'northwest');
xlabel('x (m)'); ylabel('y (m)'); title('轨迹');
subplot(1, 2, 2);
plot(rec(:, 1), rec(:, 4), 'LineWidth', 1.5); grid on;
xlabel('t (s)'); ylabel('v (m/s)'); title('速度（6 s 时授权延伸到终点）');
