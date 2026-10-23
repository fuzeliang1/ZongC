package com.zongc.dw.sim;

/**
 * 最新快照的"信箱"：仿真线程发布，各个浏览器推送线程（SSE）等待新版本后取走。
 * 只保留最新一份——客户端慢了就直接跳到最新状态，不会越积越多。
 */
public final class SnapshotHub {
    private String latest = "{}";
    private long version;

    public synchronized void publish(String json) {
        latest = json;
        version++;
        notifyAll();
    }

    public synchronized String latest() {
        return latest;
    }

    public synchronized long version() {
        return version;
    }

    /**
     * 等待比 knownVersion 更新的快照。
     *
     * @return {版本号, 快照}；超时返回 null
     */
    public synchronized Object[] awaitNewer(long knownVersion, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (version <= knownVersion) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return null;
            wait(left);
        }
        return new Object[]{version, latest};
    }
}
