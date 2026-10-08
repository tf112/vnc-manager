"""
高性能 VNC Server (RFB 协议实现)
使用 dxcam/mss 截帧 + pynput 控制输入
支持：VNC 密码认证、ZRLE 高效编码、脏区域追踪、增量更新
"""
import socket
import select
import struct
import os
import zlib
import logging
import threading
import time

import numpy as np

logger = logging.getLogger("vnc_server")

# ---- 截屏后端自动选择 ----
_CAPTURE_BACKEND = None

def _init_capture_backend():
    """初始化最佳截屏后端"""
    global _CAPTURE_BACKEND
    try:
        import dxcam
        camera = dxcam.create()
        camera.grab()  # 测试
        del camera
        _CAPTURE_BACKEND = "dxcam"
        logger.info("截屏后端: dxcam (DXGI Desktop Duplication, GPU 加速)")
        return
    except Exception:
        pass
    try:
        import mss
        with mss.mss() as sct:
            _ = sct.monitors[1]
        _CAPTURE_BACKEND = "mss"
        logger.info("截屏后端: mss (GDI)")
        return
    except Exception:
        pass
    _CAPTURE_BACKEND = "none"
    logger.error("无可用截屏后端!")

_init_capture_backend()

# ZRLE encoding ID
ZRLE_ENCODING = 16


def _reverse_bits(byte):
    """反转字节中的位顺序（VNC DES 认证需要）"""
    result = 0
    for i in range(8):
        if byte & (1 << i):
            result |= 1 << (7 - i)
    return result


class _ClientSession:
    """每个 VNC 客户端连接的独立会话状态"""

    def __init__(self, sock: socket.socket, width: int, height: int, view_only: bool = False):
        self.sock = sock
        self.width = width
        self.height = height
        self.view_only = view_only

        # 像素格式
        self.bpp = 32
        self.depth = 24
        self.big_endian = 0
        self.true_color = 1
        self.red_max = 255
        self.green_max = 255
        self.blue_max = 255
        self.red_shift = 16
        self.green_shift = 8
        self.blue_shift = 0

        # 帧缓冲
        self.prev_frame = None

        # 当前按下的鼠标按钮集合（只在状态变化时发送 press/release，
        # 避免每次移动都对所有按钮发 release 而触发意外的右键菜单）
        self._pressed_buttons = set()

        # 帧率控制
        self._last_frame_time = 0
        self._min_frame_interval = 0.05  # 最高 20 FPS

        # 脏区域追踪 + ZRLE 编码 (64x64 tile，符合 ZRLE 规范)
        self.tile_w = 64
        self.tile_h = 64
        self.tiles_x = (width + 63) // 64
        self.tiles_y = (height + 63) // 64

        # zlib 持久压缩器：noVNC 的 ZRLE inflater 在整个会话中持续且不会在
        # 矩形间重置，因此服务端必须使用持久压缩器，每个矩形末尾用
        # Z_SYNC_FLUSH 刷出，保证流保持对齐
        self._compressor = None

        # 客户端支持的编码列表
        self.encodings = [0]  # Raw 作为保底

        # 截屏后端
        self._capture = None
        self._init_capture()

    def _init_capture(self):
        """初始化截屏器"""
        if _CAPTURE_BACKEND == "dxcam":
            try:
                import dxcam
                self._capture = dxcam.create()
                self._backend = "dxcam"
                return
            except Exception:
                pass
        if _CAPTURE_BACKEND == "mss":
            import mss
            self._capture = mss.mss()
            self._backend = "mss"
            return
        self._backend = "none"

    def capture_screen(self) -> np.ndarray:
        """截取屏幕，返回 RGB numpy 数组"""
        try:
            if self._backend == "dxcam":
                frame = self._capture.grab()
                if frame is not None:
                    # dxcam 返回 BGRA numpy array
                    return frame[:, :, :3][:, :, ::-1].copy()
                return None
            elif self._backend == "mss":
                monitor = self._capture.monitors[1]
                img = self._capture.grab(monitor)
                return np.array(img)[:, :, :3][:, :, ::-1].copy()
        except Exception as e:
            logger.error(f"屏幕截取失败: {e}")
        return None

    def get_dirty_tiles(self, frame: np.ndarray) -> list:
        """比较当前帧与上一帧，返回脏 tile 坐标列表 [(tx, ty), ...]"""
        if self.prev_frame is None:
            # 首帧：所有 tile 都是脏的
            return [(tx, ty) for ty in range(self.tiles_y)
                    for tx in range(self.tiles_x)]

        dirty = []
        for ty in range(self.tiles_y):
            y0 = ty * 64
            y1 = min(y0 + 64, self.height)
            for tx in range(self.tiles_x):
                x0 = tx * 64
                x1 = min(x0 + 64, self.width)
                old_tile = self.prev_frame[y0:y1, x0:x1]
                new_tile = frame[y0:y1, x0:x1]
                if not np.array_equal(old_tile, new_tile):
                    dirty.append((tx, ty))
        return dirty

    def encode_zrle_tile(self, tile: np.ndarray) -> bytes:
        """
        编码单个 64x64 tile 为 ZRLE 子编码数据
        Solid (纯色, 1+3 字节) 或 Raw (原始 RGB, 1+W*H*3 字节)
        ZRLE 规范: 像素数据为 3 字节 RGB 格式
        """
        h, w = tile.shape[:2]
        first_r, first_g, first_b = int(tile[0, 0, 0]), int(tile[0, 0, 1]), int(tile[0, 0, 2])
        if np.all(tile[:, :, 0] == first_r) and np.all(tile[:, :, 1] == first_g) and np.all(tile[:, :, 2] == first_b):
            # Solid 子编码 (subencoding=1): 1 字节标记 + 3 字节 RGB
            return struct.pack("BBBB", 1, first_r, first_g, first_b)
        # Raw 子编码 (subencoding=0): 1 字节标记 + W*H*3 字节 RGB
        return b"\x00" + tile.astype(np.uint8).tobytes()

    def _rgb_to_pixels(self, tile: np.ndarray) -> np.ndarray:
        """RGB tile → uint32 像素数组 (XRGB8888)，仅用于内部比较"""
        h, w = tile.shape[:2]
        if self.bpp == 32 and self.red_shift == 16 and self.blue_shift == 0:
            rgba = np.zeros((h, w), dtype=np.uint32)
            rgba |= tile[:, :, 0].astype(np.uint32) << 16
            rgba |= tile[:, :, 1].astype(np.uint32) << 8
            rgba |= tile[:, :, 2].astype(np.uint32)
            return rgba
        r = (tile[:, :, 0].astype(np.uint32) * self.red_max // 255) << self.red_shift
        g = (tile[:, :, 1].astype(np.uint32) * self.green_max // 255) << self.green_shift
        b = (tile[:, :, 2].astype(np.uint32) * self.blue_max // 255) << self.blue_shift
        return (r | g | b).astype(np.uint32)

    def cleanup(self):
        """清理资源"""
        if self._backend == "mss" and self._capture:
            try:
                self._capture.close()
            except Exception:
                pass
        elif self._backend == "dxcam" and self._capture:
            try:
                del self._capture
            except Exception:
                pass


class VncServer:
    """
    高性能 VNC Server

    使用 dxcam/mss 截取屏幕，通过 RFB 协议提供给 VNC 客户端。
    支持 VNC 密码认证、ZRLE 高效编码、脏区域追踪。
    """

    def __init__(self, port: int = 5900, password: str = None,
                 max_port: int = 5905, display_name: str = "RemoteVNC",
                 view_only: bool = False):
        self.port = port
        self.password = password
        self.max_port = max_port
        self.display_name = display_name
        self.view_only = view_only
        self._running = False
        self._server_socket = None
        self._actual_port = None

    @property
    def actual_port(self) -> int:
        return self._actual_port

    @property
    def is_running(self) -> bool:
        return self._running

    def start(self) -> int:
        """启动 VNC Server，返回监听端口"""
        self._actual_port = self._find_available_port()
        if self._actual_port is None:
            logger.error(f"端口 {self.port}-{self.max_port} 均被占用")
            return None

        self._server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._server_socket.settimeout(1.0)

        try:
            self._server_socket.bind(("0.0.0.0", self._actual_port))
            self._server_socket.listen(3)
            self._running = True

            t = threading.Thread(target=self._accept_loop, daemon=True)
            t.start()

            logger.info(f"VNC Server 已启动，端口: {self._actual_port}")
            return self._actual_port
        except Exception as e:
            logger.error(f"VNC Server 启动失败: {e}")
            self._server_socket.close()
            self._server_socket = None
            return None

    def stop(self):
        """停止 VNC Server"""
        self._running = False
        if self._server_socket:
            try:
                self._server_socket.close()
            except Exception:
                pass
            self._server_socket = None
        logger.info("VNC Server 已停止")

    def set_password(self, password: str):
        """动态更新 VNC 密码（仅影响之后建立的新连接）"""
        self.password = password or None
        logger.info("VNC 密码已更新" + ("（已清空）" if not self.password else ""))

    def set_view_only(self, view_only: bool):
        """动态更新浏览模式（仅影响之后建立的新连接）"""
        self.view_only = view_only
        logger.info(f"VNC 模式已更新: {'浏览' if view_only else '控制'}模式")

    # ------------------------------------------------------------------
    # 内部方法
    # ------------------------------------------------------------------

    def _find_available_port(self) -> int:
        """在 port ~ max_port 范围内查找可用端口"""
        for port in range(self.port, self.max_port + 1):
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                s.settimeout(1)
                result = s.connect_ex(("127.0.0.1", port))
                s.close()
                if result != 0:
                    return port
            except Exception:
                pass
        return None

    def _accept_loop(self):
        """主循环：接受客户端连接"""
        while self._running:
            try:
                client_sock, addr = self._server_socket.accept()
                logger.info(f"VNC 客户端连接: {addr}")
                t = threading.Thread(
                    target=self._handle_client,
                    args=(client_sock, addr),
                    daemon=True
                )
                t.start()
            except socket.timeout:
                continue
            except OSError:
                if self._running:
                    logger.error("VNC Server socket 异常关闭")
                break

    def _handle_client(self, sock: socket.socket, addr):
        """处理单个 VNC 客户端的完整 RFB 会话"""
        session = None
        try:
            if not self._handshake(sock):
                logger.warning(f"VNC 握手失败: {addr}")
                sock.close()
                return

            if not self._authenticate(sock):
                logger.warning(f"VNC 认证失败: {addr}")
                sock.close()
                return

            session = self._server_init(sock)
            if session is None:
                sock.close()
                return

            self._protocol_loop(sock, session)
        except Exception as e:
            logger.warning(f"VNC 客户端断开: {addr}, {e}")
        finally:
            if session:
                session.cleanup()
            try:
                sock.close()
            except Exception:
                pass
            logger.info(f"VNC 客户端已断开: {addr}")

    # ---- RFB 握手 ----

    def _handshake(self, sock: socket.socket) -> bool:
        """RFB 版本协商"""
        sock.sendall(b"RFB 003.008\n")
        data = self._recv_exact(sock, 12, timeout=30)
        if not data or not data.startswith(b"RFB "):
            return False
        logger.debug(f"客户端 RFB 版本: {data[:11].decode()}")
        return True

    # ---- 认证 ----

    def _authenticate(self, sock: socket.socket) -> bool:
        """安全类型协商 + 认证"""
        if self.password:
            sock.sendall(struct.pack("B", 1) + struct.pack("B", 2))
        else:
            sock.sendall(struct.pack("B", 1) + struct.pack("B", 1))

        choice = self._recv_exact(sock, 1, timeout=30)
        if not choice:
            return False
        sec_type = struct.unpack("B", choice)[0]

        if self.password and sec_type == 2:
            return self._do_vnc_auth(sock)
        elif not self.password and sec_type == 1:
            sock.sendall(struct.pack(">I", 0))
            return True
        else:
            sock.sendall(struct.pack(">I", 1))
            return False

    def _do_vnc_auth(self, sock: socket.socket) -> bool:
        """执行 VNC 密码认证 (DES challenge-response)"""
        from pyDes import des, ECB

        challenge = os.urandom(16)
        sock.sendall(challenge)

        response = self._recv_exact(sock, 16, timeout=30)
        if not response:
            return False

        key = (self.password + "\0" * 8)[:8]
        key_bytes = bytes([_reverse_bits(b) for b in key.encode("latin-1")])

        d = des(key_bytes, ECB)
        expected = d.encrypt(challenge)

        if response == expected:
            sock.sendall(struct.pack(">I", 0))
            return True
        else:
            sock.sendall(struct.pack(">I", 1))
            return False

    # ---- ServerInit ----

    def _server_init(self, sock: socket.socket):
        """发送 ServerInit 消息，创建并返回 ClientSession"""
        # RFB 协议：ServerInit 之前，客户端发送 1 字节 shared flag
        shared = self._recv_exact(sock, 1, timeout=30)
        if not shared:
            return None
        logger.debug(f"Client shared flag: {shared[0]}")

        # 获取屏幕分辨率
        import mss as _mss
        with _mss.mss() as sct:
            mon = sct.monitors[1]
            width = mon["width"]
            height = mon["height"]

        # 像素格式: 32bpp, 24bit depth, XRGB8888
        pixel_format = struct.pack("!BBBBHHHBBBxxx",
                                   32, 24, 0, 1,
                                   255, 255, 255,
                                   16, 8, 0)

        name = self.display_name.encode("utf-8")
        sock.sendall(struct.pack("!HH", width, height)
                      + pixel_format
                      + struct.pack("!I", len(name))
                      + name)

        session = _ClientSession(sock, width, height, view_only=self.view_only)
        logger.info(f"ServerInit: {width}x{height}, bpp=32, "
                    f"tiles={session.tiles_x}x{session.tiles_y}, "
                    f"backend={session._backend}")
        return session

    # ---- 协议主循环 ----

    def _protocol_loop(self, sock: socket.socket, session: _ClientSession):
        """处理客户端消息的主循环"""
        sock.settimeout(None)

        while self._running:
            r, _, _ = select.select([sock], [], [], 0.5)
            if not r:
                continue

            try:
                msg_type = sock.recv(1)
            except Exception:
                break

            if not msg_type:
                break

            msg = msg_type[0]

            if msg == 0:
                self._handle_set_pixel_format(sock, session)
            elif msg == 2:
                self._handle_set_encodings(sock, session)
            elif msg == 3:
                self._handle_framebuffer_update(sock, session)
            elif msg == 4:
                self._handle_key_event(sock, session)
            elif msg == 5:
                self._handle_pointer_event(sock, session)
            elif msg == 6:
                self._handle_client_cut_text(sock)
            else:
                logger.warning(f"未知 RFB 消息类型: {msg}")
                break

    # ---- 消息处理 ----

    def _handle_set_pixel_format(self, sock: socket.socket, session: _ClientSession):
        """处理 SetPixelFormat (msg 0)"""
        data = self._recv_exact(sock, 19, timeout=10)
        if not data:
            return
        (bpp, depth, big_endian, true_color,
         red_max, green_max, blue_max,
         red_shift, green_shift, blue_shift) = struct.unpack("!xxxBBBBHHHBBBxxx", data)

        session.bpp = bpp
        session.depth = depth
        session.big_endian = big_endian
        session.true_color = true_color
        session.red_max = red_max
        session.green_max = green_max
        session.blue_max = blue_max
        session.red_shift = red_shift
        session.green_shift = green_shift
        session.blue_shift = blue_shift

    def _handle_set_encodings(self, sock: socket.socket, session: _ClientSession):
        """处理 SetEncodings (msg 2)"""
        data = self._recv_exact(sock, 3, timeout=10)
        if not data:
            return
        n = struct.unpack("!xH", data)[0]
        if n > 0:
            enc_data = self._recv_exact(sock, 4 * n, timeout=10)
            if enc_data:
                session.encodings = list(struct.unpack(f"!{n}i", enc_data))
                has_zrle = ZRLE_ENCODING in session.encodings
                logger.debug(f"SetEncodings: {session.encodings}, "
                             f"ZRLE={'Y' if has_zrle else 'N'}")

    def _handle_framebuffer_update(self, sock: socket.socket, session: _ClientSession):
        """处理 FramebufferUpdateRequest (msg 3) — ZRLE + 脏区域 + 帧率控制

        RFB 协议要求服务端必须响应每个 FramebufferUpdateRequest（即使没有
        变化也要发一个 0 矩形的 FramebufferUpdate）。noVNC 是严格的一问一答
        模式：收到上一个完整 FBU 后才会发下一个请求，因此任何一次静默丢弃
        都会导致前端画面永久冻结（输入仍正常，画面不再刷新）。
        """
        data = self._recv_exact(sock, 9, timeout=10)
        if not data:
            return
        (incremental, x, y, w, h) = struct.unpack("!BHHHH", data)

        # 帧率限制：增量请求节流。被节流的请求也必须响应空 FBU
        now = time.time()
        if incremental and (now - session._last_frame_time) < session._min_frame_interval:
            self._send_empty_update(sock)
            return
        session._last_frame_time = now

        # 截取当前屏幕
        frame = session.capture_screen()
        if frame is None:
            frame = np.zeros((session.height, session.width, 3), dtype=np.uint8)

        # 获取脏 tile 列表
        all_dirty = session.get_dirty_tiles(frame)

        # 过滤到请求范围内
        tx_start = x // 64
        ty_start = y // 64
        tx_end = min((x + w + 63) // 64, session.tiles_x)
        ty_end = min((y + h + 63) // 64, session.tiles_y)
        dirty_in_rect = [(tx, ty) for tx, ty in all_dirty
                         if tx_start <= tx < tx_end and ty_start <= ty < ty_end]

        # 更新 prev_frame
        session.prev_frame = frame.copy()

        # 无脏 tile → 响应空 FBU（0 个矩形），保持 noVNC 请求-响应循环
        if not dirty_in_rect:
            self._send_empty_update(sock)
            return

        # 优先使用 ZRLE，回退到 Raw
        use_zrle = (ZRLE_ENCODING in session.encodings)

        if use_zrle:
            self._send_zrle_update(sock, session, frame, dirty_in_rect)
        else:
            self._send_raw_update(sock, session, frame, x, y, w, h)

    def _send_empty_update(self, sock: socket.socket):
        """发送空 FramebufferUpdate（0 个矩形）"""
        try:
            sock.sendall(struct.pack("!BxH", 0, 0))
        except Exception as e:
            logger.error(f"Send empty update failed: {e}")

    def _send_zrle_update(self, sock: socket.socket, session: _ClientSession,
                          frame: np.ndarray, dirty_tiles: list):
        """发送 ZRLE 编码的 FramebufferUpdate。

        每个脏 tile 对应一个独立矩形，矩形内是单个 64x64 tile 的 ZRLE 数据
        （像素为 3 字节 RGB 格式）。不能用一个全屏矩形只装部分 tile——
        ZRLE 要求矩形内的每个 tile 都有编码数据。
        noVNC 的 ZRLE inflater 在整个会话中持续且不会在矩形间重置，因此
        使用持久 zlib 压缩器，每个矩形末尾用 Z_SYNC_FLUSH 刷出。
        """
        w, h = session.width, session.height

        if session._compressor is None:
            session._compressor = zlib.compressobj(1)

        total_raw = 0
        total_compressed = 0
        rects = b""

        for tx, ty in dirty_tiles:
            x0 = tx * 64
            y0 = ty * 64
            x1 = min(x0 + 64, w)
            y1 = min(y0 + 64, h)
            tile = frame[y0:y1, x0:x1]  # RGB uint8 array, shape (th, tw, 3)

            first_r, first_g, first_b = int(tile[0, 0, 0]), int(tile[0, 0, 1]), int(tile[0, 0, 2])
            if (np.all(tile[:, :, 0] == first_r) and
                    np.all(tile[:, :, 1] == first_g) and
                    np.all(tile[:, :, 2] == first_b)):
                # Solid 子编码 (subencoding=1): 1 字节标记 + 3 字节 RGB
                tile_data = struct.pack("BBBB", 1, first_r, first_g, first_b)
            else:
                # Raw 子编码 (subencoding=0): 1 字节标记 + W*H*3 字节 RGB
                tile_data = b"\x00" + tile.astype(np.uint8).tobytes()

            compressed = session._compressor.compress(tile_data)
            compressed += session._compressor.flush(zlib.Z_SYNC_FLUSH)

            rects += struct.pack("!HHHHiI", x0, y0, x1 - x0, y1 - y0,
                                 ZRLE_ENCODING, len(compressed))
            rects += compressed

            total_raw += len(tile_data)
            total_compressed += len(compressed)

        update = struct.pack("!BxH", 0, len(dirty_tiles)) + rects

        try:
            sock.sendall(update)
            # 记录前 20 字节（FBU header + 第一个 rect header）用于验证数据完整性
            header_hex = update[:20].hex()
            logger.debug(f"ZRLE: {len(dirty_tiles)} dirty tiles, "
                         f"raw={total_raw}, compressed={total_compressed}, "
                         f"ratio={total_raw/max(total_compressed,1):.1f}x, "
                         f"header20=[{header_hex}], total={len(update)}")
        except Exception as e:
            logger.error(f"Send ZRLE update failed: {e}")

    def _send_raw_update(self, sock: socket.socket, session: _ClientSession,
                         frame: np.ndarray, x: int, y: int, w: int, h: int):
        """发送 Raw 编码的 FramebufferUpdate（回退方案）"""
        crop = frame[y:y + h, x:x + w]
        rect_data = self._encode_raw_pixels(crop, session)

        update = struct.pack("!BxH", 0, 1)
        update += struct.pack("!HHHH", x, y, w, h)
        update += struct.pack("!i", 0)  # Raw encoding
        update += rect_data

        try:
            sock.sendall(update)
        except Exception as e:
            logger.error(f"Send raw update failed: {e}")

    def _encode_raw_pixels(self, image: np.ndarray, session: _ClientSession) -> bytes:
        """将 RGB 图像编码为 Raw 像素数据
        根据 session 的像素格式生成对应字节序
        noVNC 默认发送 SetPixelFormat: red_shift=0, green_shift=8, blue_shift=16 (XBGR)
        """
        h, w = image.shape[:2]
        # 通用路径：根据 shift 计算每个像素的 uint32 值，转为字节
        r = (image[:, :, 0].astype(np.uint32) * session.red_max // 255) << session.red_shift
        g = (image[:, :, 1].astype(np.uint32) * session.green_max // 255) << session.green_shift
        b = (image[:, :, 2].astype(np.uint32) * session.blue_max // 255) << session.blue_shift
        pixel = (r | g | b)
        bpp = session.bpp // 8
        dtype = np.uint32 if bpp == 4 else (np.uint16 if bpp == 2 else np.uint8)
        return pixel.astype(dtype).tobytes()

    # ---- 输入事件 ----

    def _handle_key_event(self, sock: socket.socket, session: _ClientSession):
        """处理 KeyEvent (msg 4)"""
        data = self._recv_exact(sock, 7, timeout=10)
        if not data:
            return
        if session.view_only:
            return
        try:
            from pynput.keyboard import Controller, Key, KeyCode
            down_flag, keysym = struct.unpack("!BxxxI", data)
            kb = Controller()
            key = self._keysym_to_key(keysym)
            if key is not None:
                if down_flag:
                    kb.press(key)
                else:
                    kb.release(key)
        except Exception as e:
            logger.debug(f"键盘事件处理失败: {e}")

    def _handle_pointer_event(self, sock: socket.socket, session: _ClientSession):
        """处理 PointerEvent (msg 5)

        RFB 的 button-mask 表示"当前按下的按钮集合"而非边沿事件，需要与
        上一状态对比，只在变化时发送 press/release。若每次移动(mask=0)都
        对所有按钮 release，Windows 会把单独的 WM_RBUTTONUP 解释成右键
        （桌面会弹出右键菜单）。滚轮由 bit3~bit6 表示，属于瞬时事件。
        """
        data = self._recv_exact(sock, 5, timeout=10)
        if not data:
            return
        if session.view_only:
            return
        try:
            from pynput.mouse import Controller, Button
            button_mask, px, py = struct.unpack("!BHH", data)
            ms = Controller()
            ms.position = (px, py)

            # 左/中/右键：只在状态变化时发送事件
            desired = set()
            if button_mask & 1:
                desired.add(Button.left)
            if button_mask & 2:
                desired.add(Button.middle)
            if button_mask & 4:
                desired.add(Button.right)

            for btn in desired - session._pressed_buttons:
                ms.press(btn)
            for btn in session._pressed_buttons - desired:
                ms.release(btn)
            session._pressed_buttons = desired

            # 滚轮：bit3=上(0x08) bit4=下(0x10) bit5=左(0x20) bit6=右(0x40)
            if button_mask & 0x08:
                ms.scroll(0, 1)
            if button_mask & 0x10:
                ms.scroll(0, -1)
            if button_mask & 0x20:
                ms.scroll(-1, 0)
            if button_mask & 0x40:
                ms.scroll(1, 0)
        except Exception as e:
            logger.debug(f"鼠标事件处理失败: {e}")

    def _handle_client_cut_text(self, sock: socket.socket):
        """处理 ClientCutText (msg 6)"""
        header = self._recv_exact(sock, 7, timeout=10)
        if not header:
            return
        length = struct.unpack("!xxxI", header)[0]
        if length > 0:
            self._recv_exact(sock, length, timeout=10)

    # ---- 工具方法 ----

    @staticmethod
    def _recv_exact(sock: socket.socket, n: int, timeout: int = 10) -> bytes:
        """精确接收 n 字节"""
        sock.settimeout(timeout)
        data = b""
        while len(data) < n:
            try:
                chunk = sock.recv(n - len(data))
            except socket.timeout:
                return None
            except Exception:
                return None
            if not chunk:
                return None
            data += chunk
        return data

    @staticmethod
    def _keysym_to_key(keysym: int):
        """将 X11 keysym 映射为 pynput Key"""
        from pynput.keyboard import Key, KeyCode

        if 0x20 <= keysym <= 0x7E:
            return KeyCode.from_char(chr(keysym))
        if 0xA0 <= keysym <= 0xFF:
            return KeyCode.from_char(chr(keysym))

        _map = {
            0xFF08: Key.backspace, 0xFF09: Key.tab, 0xFF0D: Key.enter,
            0xFF1B: Key.esc, 0xFF50: Key.home, 0xFF51: Key.left,
            0xFF52: Key.up, 0xFF53: Key.right, 0xFF54: Key.down,
            0xFF55: Key.page_up, 0xFF56: Key.page_down, 0xFF57: Key.end,
            0xFF63: Key.insert, 0xFF64: Key.num_lock,
            0xFF65: Key.menu,
            0xFFBE: Key.f1, 0xFFBF: Key.f2, 0xFFC0: Key.f3,
            0xFFC1: Key.f4, 0xFFC2: Key.f5, 0xFFC3: Key.f6,
            0xFFC4: Key.f7, 0xFFC5: Key.f8, 0xFFC6: Key.f9,
            0xFFC7: Key.f10, 0xFFC8: Key.f11, 0xFFC9: Key.f12,
            0xFFE1: Key.shift, 0xFFE2: Key.shift_r,
            0xFFE3: Key.ctrl, 0xFFE4: Key.ctrl_r,
            0xFFE5: Key.caps_lock,
            0xFFE7: Key.cmd, 0xFFE8: Key.cmd_r,
            0xFFE9: Key.alt, 0xFFEA: Key.alt_r,
            0xFF0A: Key.enter, 0xFFFF: Key.delete,
            0xFF61: Key.print_screen, 0xFF13: Key.pause,
        }
        return _map.get(keysym)
