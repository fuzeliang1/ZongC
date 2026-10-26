function plot_phone_log(poseFile, imuFile)
%PLOT_PHONE_LOG 画出 MiniDiDi 手机 App 记录的实验数据。
%
%   plot_phone_log                          弹出对话框选择 pose_*.csv
%   plot_phone_log('pose_20261023_101500.csv')
%   plot_phone_log('pose_....csv', 'imu_....csv')
%
%   App 点“记录数据”后在手机的 Download/MiniDiDi 文件夹生成两个文件：
%     pose_*.csv  10 Hz 状态：手机车在地图上的位姿（car_*）、手机自己的惯性推算位姿
%                 （px, py, yaw_p）、走路速度、步数、步长、卫星数、往返时延……
%     imu_*.csv   约 50 Hz 原始传感器数据：t_s, sensor, x, y, z
%                 sensor：1 加速度计 m/s²，2 陀螺仪 rad/s，3 磁力计 µT，4 重力 m/s²
%   两个文件的 t_s 从同一时刻起算，可以直接对齐。
%
%   图 1：手机自己的位姿估计——惯性推算轨迹（标出每一步）、航向、走路速度与步频、步长
%   图 2：用与 App 完全相同的滤波器重放加速度信号，标出 App 检测到的每一步，
%         方便调“计步阈值”；以及陀螺仪竖直方向角速度
%   图 3（有数据时）：卫星数量与网络往返时延
%   图 4（连上服务端时）：手机车在数字世界地图上的轨迹；走路速度（油门）与车速；
%         沿路线走过的长度 s 与授权停车位置 stopS（s 追上 stopS = 在等前方让路）

if nargin < 1 || isempty(poseFile)
    [f, p] = uigetfile('pose_*.csv', '选择手机记录的 pose 文件');
    if isequal(f, 0)
        return;
    end
    poseFile = fullfile(p, f);
end
if nargin < 2 || isempty(imuFile)
    [p, f, e] = fileparts(poseFile);
    imuFile = fullfile(p, [strrep(f, 'pose_', 'imu_') e]);
end

P = readtable(poseFile);
if height(P) < 2
    error('pose 文件里没有数据：%s', poseFile);
end
stepIdx = find(diff(P.steps) > 0) + 1;        % 步数增加的时刻 = 检测到新的一步
tStep = P.t_s(stepIdx);

%% 图 1：手机自己的位姿估计（惯性推算）
figure('Name', ['手机位姿估计 ' poseFile], 'Color', 'w');

subplot(2, 2, 1);
plot(P.px, P.py, '-', 'Color', [0.48 0.36 0.84], 'LineWidth', 1.5); hold on;
plot(P.px(stepIdx), P.py(stepIdx), '.', 'Color', [0.2 0.2 0.2], 'MarkerSize', 8);
plot(P.px(1), P.py(1), 'go', 'MarkerFaceColor', 'g');
plot(P.px(end), P.py(end), 'rs', 'MarkerFaceColor', 'r');
axis equal; grid on;
xlabel('x（m）'); ylabel('y（m）');
title(sprintf('惯性推算轨迹：%d 步，%.1f m', P.steps(end) - P.steps(1), P.distance(end) - P.distance(1)));
legend({'轨迹', '每一步', '起点', '终点'}, 'Location', 'best');

subplot(2, 2, 2);
plot(P.t_s, rad2deg(unwrap(P.yaw_p)), 'LineWidth', 1.2); grid on;
xlabel('t（s）'); ylabel('航向（°，逆时针为正）');
title('陀螺仪航向（室外模式下以正东为 0°）');

subplot(2, 2, 3);
yyaxis left; plot(P.t_s, P.walk_v, 'LineWidth', 1.2); ylabel('走路速度（m/s）');
yyaxis right; plot(P.t_s, P.cadence, 'LineWidth', 1.0); ylabel('步频（步/分）');
grid on; xlabel('t（s）'); title('走路速度（= 步长 × 步频）与步频');

subplot(2, 2, 4);
len = P.step_len(stepIdx);
plot(tStep, len, 'o-', 'MarkerSize', 4); grid on;
xlabel('t（s）'); ylabel('步长（m）');
if isempty(len)
    title('步长（没有检测到步伐）');
else
    title(sprintf('每一步的步长：平均 %.2f m，标准差 %.2f m', mean(len), std(len)));
end

%% 图 2：加速度信号重放
if exist(imuFile, 'file')
    S = readmatrix(imuFile);
    A = S(S(:, 2) == 1, :);                   % 加速度计
    G = S(S(:, 2) == 2, :);                   % 陀螺仪
    R = S(S(:, 2) == 4, :);                   % 重力（有的手机没有）
    figure('Name', ['原始传感器 ' imuFile], 'Color', 'w');

    subplot(2, 1, 1);
    if size(A, 1) > 2
        [sig, grav] = accel_filter(A(:, 1), sqrt(sum(A(:, 3:5) .^ 2, 2)));
        mag = sqrt(sum(A(:, 3:5) .^ 2, 2));
        plot(A(:, 1), mag - grav, 'Color', [0.75 0.75 0.75]); hold on;
        plot(A(:, 1), sig, 'b', 'LineWidth', 1.2);
        if ~isempty(tStep)
            plot(tStep, interp1(A(:, 1), sig, tStep, 'nearest'), 'rv', 'MarkerFaceColor', 'r');
        end
        grid on; xlabel('t（s）'); ylabel('m/s²');
        legend({'|a| − 重力估计', '低通后（App 用于计步的信号）', 'App 记下一步的时刻'}, 'Location', 'best');
        title('计步信号：一步对应一个峰；峰谷差小于“计步阈值”（默认 0.8 m/s²）的不算');
    end

    subplot(2, 1, 2);
    if size(G, 1) > 2
        if size(R, 1) > 2
            up = interp1(R(:, 1), R(:, 3:5), G(:, 1), 'linear', 'extrap');
        else
            up = interp1(A(:, 1), movmean(A(:, 3:5), 25), G(:, 1), 'linear', 'extrap');
        end
        up = up ./ sqrt(sum(up .^ 2, 2));
        wz = sum(G(:, 3:5) .* up, 2);           % 竖直方向角速度 ω·up
        yaw = cumtrapz(G(:, 1), wz);
        yyaxis left; plot(G(:, 1), rad2deg(wz)); ylabel('ω·up（°/s）');
        yyaxis right; plot(G(:, 1), rad2deg(yaw), 'LineWidth', 1.2); ylabel('积分航向变化（°）');
        grid on; xlabel('t（s）');
        title('陀螺仪：绕竖直轴的角速度及其积分（未去零偏）');
    end
else
    fprintf('没有找到 %s，跳过原始传感器图\n', imuFile);
end

%% 图 3：卫星与网络
hasGnss = any(P.sats_used > 0);
hasNet = any(P.connected > 0);
if hasGnss || hasNet
    figure('Name', '卫星与网络', 'Color', 'w');
    if hasGnss
        subplot(2, 1, 1);
        plot(P.t_s, P.sats_used, P.t_s, P.bds_used, 'LineWidth', 1.2); grid on;
        legend({'参与定位的卫星', '其中北斗'}, 'Location', 'best');
        xlabel('t（s）'); ylabel('颗'); title('卫星（室外模式）');
    end
    if hasNet
        subplot(2, 1, 2);
        plot(P.t_s, P.rtt_ms, 'LineWidth', 1.0); grid on;
        xlabel('t（s）'); ylabel('ms');
        title(sprintf('与服务端的往返时延：中位数 %.0f ms', median(P.rtt_ms(P.connected > 0), 'omitnan')));
    end
end

%% 图 4：手机车
bound = ~isnan(P.car_x);
if any(bound)
    figure('Name', '手机车', 'Color', 'w');

    subplot(1, 2, 1);
    plot(P.car_x(bound), P.car_y(bound), '-', 'Color', [0.18 0.42 0.85], 'LineWidth', 1.5); hold on;
    i0 = find(bound, 1); i1 = find(bound, 1, 'last');
    plot(P.car_x(i0), P.car_y(i0), 'go', 'MarkerFaceColor', 'g');
    plot(P.car_x(i1), P.car_y(i1), 'rs', 'MarkerFaceColor', 'r');
    axis equal; grid on;
    xlabel('x（地图 m）'); ylabel('y（地图 m）');
    title('手机车在数字世界里的轨迹（沿服务端规划的路线）');

    subplot(2, 2, 2);
    plot(P.t_s, P.walk_v, P.t_s, P.car_v, 'LineWidth', 1.2); grid on;
    legend({'走路速度（油门）', '车速'}, 'Location', 'best');
    xlabel('t（s）'); ylabel('m/s');
    title('车速跟随走路速度，受最高车速、转弯限速与授权限制');

    subplot(2, 2, 4);
    plot(P.t_s, P.car_s, P.t_s, P.car_stop_s, '--', 'LineWidth', 1.2); grid on;
    legend({'沿路线走过的长度 s', '授权停车位置 stopS'}, 'Location', 'best');
    xlabel('t（s）'); ylabel('m');
    title('移动授权：s 始终不超过 stopS（换路线时重新计算）');
end
end

function [sig, grav] = accel_filter(t, m)
% 与 App 的 StepDetector 相同：重力用 2 s 时间常数跟踪，两级一阶低通（截止 3 Hz）
tauG = 2.0;
tau = 1 / (2 * pi * 3.0);
sig = zeros(size(m));
grav = zeros(size(m));
g = m(1);
if abs(g - 9.81) >= 2
    g = 9.81;
end
f1 = 0;
f2 = 0;
grav(1) = g;
for k = 2:numel(m)
    dt = t(k) - t(k - 1);
    if dt <= 0
        sig(k) = f2;
        grav(k) = g;
        continue;
    end
    if dt > 0.5                                % 传感器中断过：与 App 一样重新开始
        g = m(k);
        if abs(g - 9.81) >= 2
            g = 9.81;
        end
        f1 = 0;
        f2 = 0;
        sig(k) = 0;
        grav(k) = g;
        continue;
    end
    g = g + (m(k) - g) * (1 - exp(-dt / tauG));
    a = 1 - exp(-dt / tau);
    f1 = f1 + (m(k) - g - f1) * a;
    f2 = f2 + (f1 - f2) * a;
    sig(k) = f2;
    grav(k) = g;
end
end
