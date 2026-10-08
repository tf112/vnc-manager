# vnc-manager
基于novnc原理重新设计实现vnc远程计算机资源管理，客户端采用Python，服务端采用java转发tcp到websocket给前端

1.1 服务端 (Java) & 数据库
JDK：1.8 (Java 8)。

框架：Spring Boot 2.7.x (兼容Java 8) + Spring WebSocket。

ORM：Spring Data JPA / Hibernate。

数据库：MySQL 8.0+。

前端：Vue 3 + Vite + Element Plus。
1.2 客户端 (Python)
语言版本：Python 3.9+ (必须兼容3.9)

环境管理：必须使用 uv 进行虚拟环境管理和依赖安装 (uv venv, uv add)。

VNC Server：不实现纯Python VNC服务，改为调用系统级TigerVNC/TightVNC子进程 (subprocess)，Python仅负责启动/停止/端口探测以及截图、录屏的能力。

截图：使用 mss 库。

录屏：使用 pyscreenrecorder (依赖OpenCV)。

WebSocket通信：使用 websocket-client。

HTTP文件上传：使用 requests。

并发：使用 threading 模块，主线程负责心跳与指令监听，工作线程负责录屏/截图的耗时任务。
2. 项目目录结构
text
remote-vnc-platform/
├── remote-server/                   # Java 服务端
│   ├── src/main/java/com/remote/
│   │   ├── RemoteApplication.java
│   │   ├── config/                  # WebSocket配置, 跨域配置
│   │   ├── controller/              # REST API (客户端管理, 文件管理, 指令下发)
│   │   ├── service/                 # 业务逻辑 (ClientService, FileService)
│   │   ├── websocket/               # WebSocket核心 (Handler, Session管理)
│   │   ├── proxy/                   # ★ VNC代理核心 (WebSocket <-> TCP Socket 桥接)
│   │   ├── entity/                  # JPA实体类
│   │   └── repository/              # JPA Repository
│   ├── src/main/resources/application.yml
│   └── pom.xml
├── remote-client/                   # Python 客户端
│   ├── src/
│   │   ├── main.py                  # 程序入口 (解析参数, 启动线程)
│   │   ├── config.yaml              # 配置文件 (服务端地址, 客户端ID)
│   │   ├── communication/
│   │   │   └── ws_client.py         # WebSocket长连接, 重连机制
│   │   ├── capture/
│   │   │   ├── recorder.py          # 录屏逻辑 (pyscreenrecorder)
│   │   │   └── screenshot.py        # 截图逻辑 (mss)
│   │   ├── vnc/
│   │   │   └── server_ctl.py        # VNC Server子进程管理
│   │   └── upload/
│   │       └── file_uploader.py     # 文件上传实现
│   ├── pyproject.toml               # uv 依赖管理
│   └── README.md
└── remote-web/                      # Vue3 前端 (可选, AI仅需生成基础骨架)
    ├── src/views/                   # 页面 (客户端列表, 远程桌面, 文件管理)
    └── package.json