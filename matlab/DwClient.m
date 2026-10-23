classdef DwClient < handle
    % DwClient  与数字世界（DW）服务端的 TCP 连接。
    %
    % 协议：UTF-8 文本，每行一条 JSON 消息（见 docs/protocol.md）。
    % 用 tcpclient 收发，poll() 以非阻塞方式取出所有已到达的完整消息。
    %
    %   c = DwClient("127.0.0.1", 9000);
    %   c.connect();
    %   c.send(struct('type', 'register', 'kind', 'matlab', 'agentId', 'car-1'));
    %   msgs = c.poll();      % 元胞数组，每个元素是 jsondecode 得到的结构体

    properties
        Host = "127.0.0.1"
        Port = 9000
        Connected = false
    end

    properties (Access = private)
        Conn = []
        Buffer = ''
    end

    methods
        function obj = DwClient(host, port)
            if nargin >= 1, obj.Host = host; end
            if nargin >= 2, obj.Port = port; end
        end

        function ok = connect(obj)
            % 建立连接，成功返回 true（失败时不抛异常，便于循环重连）
            try
                obj.Conn = tcpclient(char(obj.Host), obj.Port, 'Timeout', 5);
                obj.Buffer = '';
                obj.Connected = true;
            catch err
                obj.Conn = [];
                obj.Connected = false;
                warning('DwClient:connect', '连接 %s:%d 失败：%s', obj.Host, obj.Port, err.message);
            end
            ok = obj.Connected;
        end

        function send(obj, msg)
            % 发送一条消息（结构体），自动编码为一行 JSON
            if ~obj.Connected, return; end
            try
                txt = jsonencode(msg);
                write(obj.Conn, unicode2native([txt, newline], 'UTF-8'));
            catch err
                obj.Connected = false;
                warning('DwClient:send', '发送失败，连接可能已断开：%s', err.message);
            end
        end

        function msgs = poll(obj)
            % 取出所有已到达的完整消息（不阻塞）
            msgs = {};
            if ~obj.Connected, return; end
            try
                n = obj.bytesAvailable();
                if n > 0
                    data = read(obj.Conn, n);
                    obj.Buffer = [obj.Buffer, native2unicode(uint8(data(:)'), 'UTF-8')];
                end
            catch err
                obj.Connected = false;
                warning('DwClient:read', '读取失败，连接可能已断开：%s', err.message);
                return;
            end
            idx = find(obj.Buffer == newline, 1);
            while ~isempty(idx)
                line = strtrim(obj.Buffer(1:idx-1));
                obj.Buffer = obj.Buffer(idx+1:end);
                if ~isempty(line)
                    try
                        msgs{end+1} = jsondecode(line); %#ok<AGROW>
                    catch err
                        warning('DwClient:parse', '无法解析消息：%s', err.message);
                    end
                end
                idx = find(obj.Buffer == newline, 1);
            end
        end

        function close(obj)
            % 释放 tcpclient 对象即关闭连接
            obj.Conn = [];
            obj.Connected = false;
        end
    end

    methods (Access = private)
        function n = bytesAvailable(obj)
            % R2020b 起属性名为 NumBytesAvailable，更早的版本为 BytesAvailable
            if isprop(obj.Conn, 'NumBytesAvailable')
                n = obj.Conn.NumBytesAvailable;
            else
                n = obj.Conn.BytesAvailable;
            end
        end
    end
end
