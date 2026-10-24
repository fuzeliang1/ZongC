"""与数字世界服务端的 TCP 连接：每行一条 JSON（协议见 docs/protocol.md 第 2 节）。

与 tools/mock_devices.py 的 Link 相同：读线程把收到的消息放进队列，主循环调用 poll() 取走，
这样控制循环永远不会因为网络阻塞。只用 Python 标准库。
"""
import json
import queue
import socket
import sys
import threading


class DwLink:
    def __init__(self, host, port, name):
        self.host, self.port, self.name = host, port, name
        self.sock = None
        self.inbox = queue.Queue()
        self.alive = False
        self.lock = threading.Lock()

    def connect(self, timeout=2.0):
        s = socket.create_connection((self.host, self.port), timeout=timeout)
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        s.settimeout(None)
        self.sock = s
        self.alive = True
        threading.Thread(target=self._reader, name=self.name + '-rx', daemon=True).start()

    def _reader(self):
        buf = b''
        try:
            while self.alive:
                data = self.sock.recv(65536)
                if not data:
                    break
                buf += data
                while b'\n' in buf:
                    line, buf = buf.split(b'\n', 1)
                    line = line.strip()
                    if line:
                        try:
                            self.inbox.put(json.loads(line.decode('utf-8')))
                        except ValueError as e:
                            print(f'[{self.name}] 无法解析服务端消息: {e}', file=sys.stderr)
        except OSError:
            pass
        self.alive = False

    def send(self, obj):
        if not self.alive:
            return False
        try:
            with self.lock:
                self.sock.sendall((json.dumps(obj, separators=(',', ':'), ensure_ascii=False) + '\n').encode('utf-8'))
            return True
        except OSError:
            self.alive = False
            return False

    def poll(self):
        out = []
        while True:
            try:
                out.append(self.inbox.get_nowait())
            except queue.Empty:
                return out

    def close(self):
        self.alive = False
        try:
            self.sock.close()
        except (OSError, AttributeError):
            pass
