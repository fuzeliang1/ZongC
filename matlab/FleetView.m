classdef FleetView < handle
    % FleetView  Matlab 端的实时视图：地图 + 各模拟车的位置、路线和授权停车点。
    % 地图通过 HTTP 从服务端获取（GET /api/map）。

    properties
        Fig
        Ax
        CarPatch = {}
        RouteLine = {}
        StopMark = {}
        Labels = {}
    end

    methods
        function obj = FleetView(host, httpPort, cars)
            url = sprintf('http://%s:%d/api/map', char(host), httpPort);
            m = webread(url, weboptions('Timeout', 5));
            rows = m.rows;
            if ischar(rows), rows = cellstr(rows); end
            H = numel(rows); W = numel(rows{1});
            img = ones(H, W, 3);
            buildingColor = [0.66 0.71 0.77];
            for r = 1:H
                y = H - r + 1;                       % rows 第一行是最北边
                isB = rows{r} == '#';
                for ch = 1:3
                    layer = img(y, :, ch);
                    layer(isB) = buildingColor(ch);
                    img(y, :, ch) = layer;
                end
            end
            res = m.resolution;
            obj.Fig = figure('Name', 'Matlab 模拟车视图', 'NumberTitle', 'off', 'Color', 'w');
            obj.Ax = axes(obj.Fig);
            image(obj.Ax, 'XData', [0.5 W - 0.5] * res, 'YData', [0.5 H - 0.5] * res, 'CData', img);
            axis(obj.Ax, 'xy');
            axis(obj.Ax, 'equal');
            axis(obj.Ax, 'tight');
            hold(obj.Ax, 'on');
            title(obj.Ax, sprintf('%s（%d×%d 格）', m.name, W, H));
            xlabel(obj.Ax, 'x (m)'); ylabel(obj.Ax, 'y (m)');
            colors = lines(numel(cars));
            for k = 1:numel(cars)
                obj.RouteLine{k} = plot(obj.Ax, nan, nan, '-', 'Color', colors(k, :), 'LineWidth', 1.2);
                obj.StopMark{k} = plot(obj.Ax, nan, nan, 'x', 'Color', colors(k, :), 'MarkerSize', 8, 'LineWidth', 2);
                obj.CarPatch{k} = patch(obj.Ax, nan, nan, colors(k, :), 'EdgeColor', 'k');
                obj.Labels{k} = text(obj.Ax, nan, nan, '', 'FontSize', 9, 'FontWeight', 'bold');
            end
        end

        function ok = isOpen(obj)
            ok = ~isempty(obj.Fig) && isvalid(obj.Fig);
        end

        function update(obj, cars)
            if ~obj.isOpen(), return; end
            for k = 1:numel(cars)
                c = cars{k};
                if ~c.Ready, continue; end
                % 车身：长 2.6r、宽 1.8r 的矩形，按航向旋转
                L = c.R * 1.3; Wd = c.R * 0.9;
                corners = [L Wd; L -Wd; -L -Wd; -L Wd];
                Rm = [cos(c.Th) -sin(c.Th); sin(c.Th) cos(c.Th)];
                p = (Rm * corners')';
                set(obj.CarPatch{k}, 'XData', p(:, 1) + c.X, 'YData', p(:, 2) + c.Y);
                if ~isempty(c.Wp)
                    set(obj.RouteLine{k}, 'XData', c.Wp(:, 1), 'YData', c.Wp(:, 2));
                    sp = c.stopPoint();
                    set(obj.StopMark{k}, 'XData', sp(1), 'YData', sp(2));
                end
                set(obj.Labels{k}, 'Position', [c.X + 0.4, c.Y + 0.4, 0], ...
                    'String', sprintf('%s %.1fm/s', c.AgentId, c.V));
            end
            drawnow limitrate;
        end
    end
end
