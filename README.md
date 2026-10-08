# vnc-manager

基于 noVNC 原理重新设计实现的 VNC 远程计算机资源管理系统：客户端采用 Python，服务端采用 Java，并通过 TCP 到 WebSocket 的转发为前端提供远程桌面能力。

## 1. 技术栈

### 1.1 服务端 (Java) & 数据库

- **JDK**：1.8 (Java 8)
- **框架**：Spring Boot 2.7.x（兼容 Java 8）+ Spring WebSocket
- **ORM**：Spring Data JPA / Hibernate
- **数据库**：MySQL 8.0+
- **前端**：Vue 3 + Vite + Element Plus

### 1.2 客户端 (Python)

- **语言版本**：Python 3.9+（必须兼容 3.9）
- **环境管理**：必须使用 uv 管理虚拟环境与依赖安装（`uv venv`、`uv add`）
- **VNC Server**：不实现纯 Python 的 VNC 服务，改为调用系统级 TigerVNC / TightVNC 子进程（subprocess）；Python 仅负责启动/停止、端口探测，以及截图与录屏能力
- **截图**：使用 `mss` 库
- **录屏**：使用 `pyscreenrecorder`（依赖 OpenCV）
- **WebSocket 通信**：使用 `websocket-client`
- **HTTP 文件上传**：使用 `requests`
- **并发**：使用 `threading` 模块，主线程负责心跳与指令监听，工作线程负责录屏/截图等耗时任务

## 2. 项目目录结构

```text
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
```

## 3. 鸣谢

本项目能够跑起来，离不开以下开源项目与社区的支持，在此一并致谢：

- [noVNC](https://github.com/novnc/noVNC)：远程桌面转发方案参考了其设计思路
- [TigerVNC](https://github.com/TigerVNC/tigervnc) / [TightVNC](https://www.tightvnc.com/)：提供系统级 VNC Server 能力
- [Spring Boot](https://spring.io/projects/spring-boot) / [Spring WebSocket](https://docs.spring.io/spring-framework/reference/web/websocket.html)：服务端框架与 WebSocket 支持
- [Vue 3](https://vuejs.org/) / [Vite](https://vite.dev/) / [Element Plus](https://element-plus.org/)：前端框架与 UI 组件库
- [mss](https://github.com/BoboTiG/python-mss)：跨平台截图
- [pyscreenrecorder](https://pypi.org/project/pyscreenrecorder/)：屏幕录制
- [websocket-client](https://github.com/websocket-client/websocket-client) / [requests](https://requests.readthedocs.io/)：客户端通信与文件上传
- [uv](https://github.com/astral-sh/uv)：Python 虚拟环境与依赖管理

也感谢每一位提交 Issue、PR 和反馈建议的使用者。

## 4. 欢迎使用

欢迎使用 **vnc-manager**。它把 VNC 远程桌面接入、客户端资源管理和文件传输整合到一套 Web 界面里，希望能帮你少折腾一点环境、多留一点时间给正事。

如果这个项目对你有帮助，欢迎点一个 ⭐ **Star** 支持一下，也欢迎 Fork、提 Issue 和 PR —— 你的每一个 Star 都是持续维护的动力。
