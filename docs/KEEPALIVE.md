# 后台保活验收

会话由 `MirrorSessionService`（前台服务）持有，与 Activity 生命周期解耦。

## 机制

1. 前台通知 + `FOREGROUND_SERVICE_CONNECTED_DEVICE`
2. `WifiLock` + `PartialWakeLock`
3. `setSurface(null)` 仅卸显示，不关 socket / 不停 server
4. 读失败触发有限次指数退避重连
5. 引导忽略电池优化 + 通知权限

## 手工验收步骤

1. 控制端与被控端同一 Wi‑Fi，开启无线调试  
2. 本 App 连接成功并出画  
3. 按 Home 切到桌面，等待 **5–10 分钟**  
4. 从最近任务或通知点回 App → 应直接出画，无需重新输入 IP  
5. 镜像中打开微信再返回 → 不断联  
6. 灭屏 1 分钟再亮屏 → 通知仍在，回 App 可继续  
7. 通知栏点「断开」→ 会话结束，回到连接页  

若某品牌仍杀进程：在系统设置中锁定本 App 后台、关闭省电限制。
