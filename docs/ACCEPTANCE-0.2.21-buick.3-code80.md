# code80：补强四类诊断证据

在 kaiyue 当前 code79 工作区实现，保留板载 MFi、界面和其他已有未提交改动。未安装车机、未推送远程。

## 行为

- 热点：从 hostapd.conf 的 ctrl_interface 读取实际控制目录，支持 DIR=… GROUP=… 格式，以参数数组执行 hostapd_cli -p <目录> -i <接口> status/all_sta；保留 iw station dump，增加 ap0 info/survey dump。命令结果增加输出字节数，以区分执行成功和没有数据。DHCP、ARP、路由、接口、热点配置及原有证据保留。
- 资源：独立线程每约5秒采样应用、采集器、音频/媒体、蓝牙、显示和相关系统服务的进程/线程 stat、schedstat、I/O 等原始计数，及磁盘、内存、CPU频率和温度；保存 CPU 信息及计数时钟频率。不会用 dumpsys cpuinfo 的旧快照冒充实时负载。新增线程参与停止确认，失败不拖停原有网络采样。
- 蓝牙：读取已有 btsnoop 文件和 bt_stack.conf 中配置的文件路径；不启用 HCI snoop、不修改系统配置。按完整 btsnoop 记录增量复制，以设备/ inode 识别改名后的文件；截断或重写时保留旧输出并新建独立输出。半包等待下次读取；停止时有一次有时限的补读，日志记录完整字节数、剩余字节数及不可用原因。没有已有 HCI 文件时明确记录不可用。
- 音频：在原有低频快照循环中增加 dumpsys media.audio_flinger、media.audio_policy 和 audio，仍通过现有有超时、受停止控制的子进程管理执行。

新增证据写入 resource-state.log、bluetooth-evidence.log、bluetooth-*.btsnoop，热点/音频快照进入 system-snapshots.log，全部由现有报告归档自动包含。不会自动开启额外系统日志，不改变连接超时、音频策略、PCAP时间戳或SLL2格式，不调整时钟关联。

## 验证

- 新增10项诊断测试通过。审查发现的改名轮换重复复制及线程I/O缺失有先失败、修复后通过的回归证据。
- 最终完整仓库检查成功（4m23s）：3557项，0失败、0错误、1跳过；shared/common/home测试及mobile/home/maphost lint和Debug构建通过。
- 原生PCAP格式、内核时间戳测试分别通过。git diff --check通过。
- standalone debug构建成功；核对认证材料存在且非空、APK ZIP完整、v2签名有效。
- 版本：0.2.21-buick.3-debug / versionCode 80；包名 com.shihab.diplay.hudtest。
- APK：/Users/huliehao/headunit/artifacts/apk/BuickPlay-0.2.21-buick.3-debug-code80.apk
- SHA256：`d37700fde6991a4a767b5df56a0c9a5165b73a0bb718e3ef9ccb8e407e5c53b1`。
- 本机验证未代替实车root/驱动/音频服务和采集负载验证。原始code78、code79归档保留。

## 实车待验收

启动应用 → 确认诊断正在采集 → 手动连接 iPhone → 播放音乐并复现 → 停止采集 → 下载报告。重点检查 hostapd 返回实际客户端、resource-state 的实时计数、音频快照，以及 HCI 的可用/不可用状态。不能把主机单元测试通过当作实车驱动支持、HCI已开启或采集负载验证通过。
