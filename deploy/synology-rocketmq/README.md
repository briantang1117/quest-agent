# 群晖 x86 RocketMQ 5.3.1 单机开发部署

镜像apache/rocketmq:5.3.1已匿名读取远端manifest，确认linux/amd64。不是生产高可用集群；当前不启用ACL，只有在防火墙严格限制可信开发机/应用服务器时才部署。不开放公网或Dashboard。

## 上传配置

```text
/volume1/docker/quest-rocketmq/
├── docker-compose.yml
├── broker.conf
└── .env                 # 从.env.example复制
```

.env的RMQ_BIND_IP与broker.conf的brokerIP1都为192.168.3.40。broker.conf不支持.env插值，换IP必须改两处。无需把数据库/MinIO密码传给MQ。

数据使用此Compose项目独立的Docker命名卷，不与旧RocketMQ共用。但命名卷并不保证属主正确：已在此NAS实际发现Broker两个空卷根目录root:root/755，而镜像进程为UID/GID3000，无法写入。首次启动前必须显式初始化卷权限。不要删除卷或使用chmod777。

## 卷权限初始化（需要明确授权）

Compose新增setup profile，只有显式调用才以root运行一次，将三个项目卷根目录属主设置为3000:3000；不递归、不删数据、不修改权限位。Broker和NameServer仍使用镜像默认rocketmq非root用户。对已有环境执行前先确认这些正是本项目专属卷，不对其他服务目录操作。

```bash
sudo docker compose --profile setup run --rm --no-deps init-permissions
```

普通up不会自动改现有卷属主。Container Manager未执行过setup时，先SSH执行上述一次性命令，再启动服务。2026-11-07用户明确批准后，已远程仅将broker-store与broker-logs两个现有卷根目录chown为3000:3000并启动原Broker。未运行三卷setup、未改NameServer卷，未同步覆盖远端Compose；本地setup用于后续受控部署。启动/注册/开发机Remoting查询已通过，重启计数0。磁盘占用90%警告未解决，消息写入尚未验收。

## 网络与启动

先在群晖防火墙仅允许开发机和应用服务器访问：

| 端口 | 用途 |
|---|---|
| 9876 | NameServer发现 |
| 10911 | Broker remoting |
| 10909 | VIP remoting，客户端启用时使用 |

不要路由器转发这些端口。绑定LAN IP不是客户端白名单。若这些端口已有MQ占用，先不要启动；Broker会向客户端公布brokerIP1:listenPort，不能像Redis那样只把宿主机端口随意改成另一个值，需同步Broker配置/内部端口/映射/VIP。

Container Manager新增项目后启动，或：

```bash
cd /volume1/docker/quest-rocketmq
sudo docker compose config --quiet
# 首次部署，确认授权修改本项目卷根目录属主后执行：
sudo docker compose --profile setup run --rm --no-deps init-permissions
sudo docker compose up -d
sudo docker compose logs --tail=100 namesrv broker
```

至少建议留1.5–2GB可用内存给该项目，JVM heap合计640MB但堆外内存/映射/操作系统缓存另算；当前未做硬性内存上限，避免误配置被OOM杀死。NAS内存不足时先不要硬上MQ，调整部署位置。生产再设计ACL/TLS、主从、备份、监控和限流。

depends_on仅表示启动顺序，不代表MQ就绪；本包没有伪装进程探针为端到端健康检查，使用下面实际管理查询验收。

## 验证与初始化Topic

先看集群是否注册，可能要等数十秒：

```bash
sudo docker compose exec broker sh mqadmin clusterList -n namesrv:9876
```

应有QuestLanCluster、quest-broker，Broker地址192.168.3.40:10911。查询失败不能仅凭容器Running判通过。

显式创建项目5个Topic（会在Broker写入配置）：

```bash
for topic in qbank-question-event qbank-import-task qbank-ai-task qbank-answer-event qbank-notify; do
  sudo docker compose exec -T broker sh mqadmin updateTopic \
    -n namesrv:9876 -b 192.168.3.40:10911 -t "$topic" -r 4 -w 4
done
sudo docker compose exec broker sh mqadmin topicList -n namesrv:9876
```

若群晖无SSH，Container Manager容器终端也可逐条执行（在broker容器中去掉sudo docker compose exec前缀）。NAS需能经自身LAN IP访问发布端口，若该环境hairpin路径不通需按实际路由调整，不能宣称日志正常就可被外部客户端使用。

应用将配置NameServer为192.168.3.40:9876。Java Relay/消费者目前还未实现，本次不能因此认定消息业务链路已通。

## 限制与安全

- 单Broker、异步刷盘无副本，故障可能丢失尚未持久化消息，不能据此承诺恰好一次/零丢失。
- 不启用Dashboard/Proxy；现阶段用remoting Java客户端，无需暴露8080/8081/10912。
- ACL关闭是明确的开发模式，网络可达客户端可能有消息/管理权限；不适合不可信共享网络。上线前必须按实际客户端接ACL，不用默认明文账号冒充已安全配置。
- Broker本机文件日志在命名卷中，Docker logging只轮转stdout，不会自动清理卷内应用日志；后续加保留/容量告警。
- 不自动发送消息、不清理Topic/数据卷；显式初始化命令由用户执行。
- 当前仅完成配置静态检查与远端镜像清单检查，未在NAS启动、注册、发收或重启恢复实测。
