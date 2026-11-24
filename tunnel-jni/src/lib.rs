//! Embedded userspace WireGuard + loopback HTTP bridge for LocalBeat Android.
//!
//! Exposes a minimal JNI surface to `com.jimboyo.localbeat.server.NativeTunnel`:
//!   - `nativeStart(configJson) -> handle (jlong, 0 on error)`
//!   - `nativeBaseUrl(handle, targetPort) -> "http://127.0.0.1:<port>/p/<token>/<targetPort>"`
//!   - `nativeState(handle) -> 0 Connecting | 1 Connected(Lan) | 2 Connected(Public) | 3 Stalled`
//!   - `nativeReprobe(handle)` (called from Android ConnectivityManager.NetworkCallback)
//!   - `nativeStop(handle)`
//!   - `nativeGenerateWgKeypair() -> "{\"private\":\"...\",\"public\":\"...\"}"`

use std::collections::{HashMap, VecDeque};
use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream, ToSocketAddrs, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::{Arc, mpsc};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, anyhow, bail};
use base64::Engine as _;
use boringtun::noise::{Tunn, TunnResult};
use boringtun::x25519::{PublicKey as WgPublicKey, StaticSecret as WgStaticSecret};
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use serde::{Deserialize, Serialize};
use smoltcp::iface::{Config as IfaceConfig, Interface, SocketHandle, SocketSet};
use smoltcp::phy::{Device, DeviceCapabilities, Medium, RxToken, TxToken};
use smoltcp::socket::tcp::{self, Socket as TcpSocket, SocketBuffer};
use smoltcp::time::Instant as SmolInstant;
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr, Ipv4Address};

const SERVER_TUNNEL_IP: &str = "10.8.0.1";
const WG_MTU: usize = 1280;
const LAN_HEAD_START: Duration = Duration::from_millis(150);
const STALL_REPROBE_AFTER: Duration = Duration::from_secs(3);
const STALL_REPORT_AFTER: Duration = Duration::from_secs(20);
const TCP_BUF_SIZE: usize = 64 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TunnelConfig {
    pub private_key: String,
    pub address: String,
    pub server_key: String,
    #[serde(default)]
    pub preshared_key: String,
    #[serde(default)]
    pub endpoint_public: Option<String>,
    #[serde(default)]
    pub endpoint_lan: Option<String>,
    #[serde(default)]
    pub allowed_ips: String,
}

impl TunnelConfig {
    pub fn has_endpoint(&self) -> bool {
        self.endpoint_public.as_deref().is_some_and(|s| !s.trim().is_empty())
            || self.endpoint_lan.as_deref().is_some_and(|s| !s.trim().is_empty())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LinkState {
    Connecting = 0,
    ConnectedLan = 1,
    ConnectedPublic = 2,
    Stalled = 3,
}

impl LinkState {
    fn from_u8(v: u8) -> Self {
        match v {
            1 => LinkState::ConnectedLan,
            2 => LinkState::ConnectedPublic,
            3 => LinkState::Stalled,
            _ => LinkState::Connecting,
        }
    }
}

pub struct Bridge {
    port: u16,
    token: String,
    state: Arc<AtomicU8>,
    stop: Arc<AtomicBool>,
    reprobe_flag: Arc<AtomicBool>,
    listener_thread: Option<JoinHandle<()>>,
    engine_thread: Option<JoinHandle<()>>,
}

impl Bridge {
    pub fn start(cfg: &TunnelConfig) -> Result<Self> {
        if !cfg.has_endpoint() {
            bail!("tunnel config has no LAN or public endpoint");
        }
        let priv_bytes = decode_key_32(&cfg.private_key, "private_key")?;
        let srv_bytes = decode_key_32(&cfg.server_key, "server_key")?;
        let psk_bytes = if cfg.preshared_key.trim().is_empty() {
            None
        } else {
            Some(decode_key_32(&cfg.preshared_key, "preshared_key")?)
        };
        let (local_ip, prefix) = parse_ipv4_cidr(&cfg.address)?;
        let server_ip: Ipv4Addr = SERVER_TUNNEL_IP.parse().expect("valid IP");

        let tunn = Tunn::new(
            WgStaticSecret::from(priv_bytes),
            WgPublicKey::from(srv_bytes),
            psk_bytes,
            Some(25),
            0,
            None,
        );
        let udp = UdpSocket::bind("0.0.0.0:0").context("binding UDP")?;
        udp.set_nonblocking(true)?;

        let listener = TcpListener::bind("127.0.0.1:0").context("binding loopback")?;
        let port = listener.local_addr()?.port();
        listener.set_nonblocking(true)?;

        let token = random_token()?;
        let state = Arc::new(AtomicU8::new(LinkState::Connecting as u8));
        let stop = Arc::new(AtomicBool::new(false));
        let reprobe_flag = Arc::new(AtomicBool::new(false));
        let (conn_tx, conn_rx) = mpsc::channel::<NewConn>();

        let (es, estop, erp) = (Arc::clone(&state), Arc::clone(&stop), Arc::clone(&reprobe_flag));
        let (lan, pub_ep) = (cfg.endpoint_lan.clone(), cfg.endpoint_public.clone());
        let engine_thread = thread::Builder::new()
            .name("lb-wg".into())
            .spawn(move || {
                run_engine(tunn, udp, local_ip, prefix, server_ip, lan, pub_ep, conn_rx, es, estop, erp);
            })?;

        let (ls, lstop, ltok) = (Arc::clone(&state), Arc::clone(&stop), token.clone());
        let listener_thread = thread::Builder::new()
            .name("lb-proxy".into())
            .spawn(move || run_listener(listener, ltok, conn_tx, ls, lstop))?;

        Ok(Bridge {
            port,
            token,
            state,
            stop,
            reprobe_flag,
            listener_thread: Some(listener_thread),
            engine_thread: Some(engine_thread),
        })
    }

    pub fn base_url_for(&self, target_port: u16) -> String {
        format!("http://127.0.0.1:{}/p/{}/{target_port}", self.port, self.token)
    }

    pub fn state(&self) -> LinkState {
        LinkState::from_u8(self.state.load(Ordering::Relaxed))
    }

    pub fn reprobe(&self) {
        self.reprobe_flag.store(true, Ordering::Relaxed);
    }
}

impl Drop for Bridge {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        let _ = TcpStream::connect_timeout(
            &SocketAddr::from(([127, 0, 0, 1], self.port)),
            Duration::from_millis(50),
        );
        if let Some(h) = self.listener_thread.take() {
            let _ = h.join();
        }
        if let Some(h) = self.engine_thread.take() {
            let _ = h.join();
        }
    }
}

pub fn generate_wg_keypair_json() -> Result<String> {
    let mut bytes = [0u8; 32];
    getrandom::getrandom(&mut bytes).map_err(|e| anyhow!("getrandom: {e}"))?;
    let secret = x25519_dalek::StaticSecret::from(bytes);
    let public = x25519_dalek::PublicKey::from(&secret);
    let b64 = base64::engine::general_purpose::STANDARD;
    Ok(serde_json::json!({
        "private": b64.encode(secret.to_bytes()),
        "public": b64.encode(public.as_bytes()),
    })
    .to_string())
}

// ---------------------------------------------------------------------------
// JNI exports for com.jimboyo.localbeat.server.NativeTunnel
// ---------------------------------------------------------------------------

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jimboyo_localbeat_server_NativeTunnel_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jlong {
    let Ok(json_str): Result<String, _> = env.get_string(&config_json).map(Into::into) else {
        return 0;
    };
    let Ok(cfg) = serde_json::from_str::<TunnelConfig>(&json_str) else {
        return 0;
    };
    match Bridge::start(&cfg) {
        Ok(b) => Box::into_raw(Box::new(b)) as jlong,
        Err(_) => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jimboyo_localbeat_server_NativeTunnel_nativeBaseUrl(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    target_port: jint,
) -> jstring {
    if handle == 0 || target_port <= 0 || target_port > 65535 {
        return std::ptr::null_mut();
    }
    let bridge = unsafe { &*(handle as *const Bridge) };
    let url = bridge.base_url_for(target_port as u16);
    env.new_string(url).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jimboyo_localbeat_server_NativeTunnel_nativeState(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return LinkState::Connecting as jint;
    }
    let bridge = unsafe { &*(handle as *const Bridge) };
    bridge.state() as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jimboyo_localbeat_server_NativeTunnel_nativeReprobe(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let bridge = unsafe { &*(handle as *const Bridge) };
    bridge.reprobe();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jimboyo_localbeat_server_NativeTunnel_nativeStop(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    unsafe {
        drop(Box::from_raw(handle as *mut Bridge));
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jimboyo_localbeat_server_NativeTunnel_nativeGenerateWgKeypair(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match generate_wg_keypair_json() {
        Ok(s) => env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

// ---------------------------------------------------------------------------
// Engine internals (mirrors LocalBeatStudio/core/src/tunnel.rs)
// ---------------------------------------------------------------------------

fn random_token() -> Result<String> {
    let mut bytes = [0u8; 18];
    getrandom::getrandom(&mut bytes).map_err(|e| anyhow!("getrandom: {e}"))?;
    Ok(base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes))
}

fn decode_key_32(b64: &str, label: &str) -> Result<[u8; 32]> {
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(b64.trim())
        .with_context(|| format!("invalid base64 for {label}"))?;
    bytes.try_into().map_err(|_| anyhow!("{label} must be 32 bytes"))
}

fn parse_ipv4_cidr(s: &str) -> Result<(Ipv4Addr, u8)> {
    let s = s.trim();
    let (ip_str, prefix) = match s.split_once('/') {
        Some((ip, p)) => (ip, p.parse::<u8>()?),
        None => (s, 32),
    };
    Ok((ip_str.parse()?, prefix))
}

struct NewConn {
    target_port: u16,
    initial_bytes: Vec<u8>,
    stream: TcpStream,
}

fn run_listener(
    listener: TcpListener,
    token: String,
    conn_tx: mpsc::Sender<NewConn>,
    state: Arc<AtomicU8>,
    stop: Arc<AtomicBool>,
) {
    while !stop.load(Ordering::Relaxed) {
        match listener.accept() {
            Ok((stream, _)) => {
                if stop.load(Ordering::Relaxed) {
                    break;
                }
                let (tok, tx, st) = (token.clone(), conn_tx.clone(), Arc::clone(&state));
                let _ = thread::Builder::new()
                    .name("lb-req".into())
                    .spawn(move || handle_loopback(stream, &tok, &tx, &st));
            }
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => thread::sleep(Duration::from_millis(20)),
            Err(_) => thread::sleep(Duration::from_millis(50)),
        }
    }
}

fn write_http(stream: &mut TcpStream, status: &str, body: &str) {
    let resp = format!(
        "HTTP/1.1 {status}\r\nContent-Type: text/plain\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    let _ = stream.write_all(resp.as_bytes());
}

fn handle_loopback(mut stream: TcpStream, token: &str, conn_tx: &mpsc::Sender<NewConn>, state: &AtomicU8) {
    let _ = stream.set_nonblocking(false);
    let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
    let mut buf = Vec::with_capacity(2048);
    let mut chunk = [0u8; 2048];
    let header_end = loop {
        match stream.read(&mut chunk) {
            Ok(0) => return,
            Ok(n) => {
                buf.extend_from_slice(&chunk[..n]);
                if let Some(pos) = find_subslice(&buf, b"\r\n\r\n") {
                    break pos + 4;
                }
                if buf.len() > 64 * 1024 {
                    write_http(&mut stream, "431 Request Header Fields Too Large", "headers too large");
                    return;
                }
            }
            Err(_) => return,
        }
    };
    let Ok(head) = std::str::from_utf8(&buf[..header_end]) else {
        write_http(&mut stream, "400 Bad Request", "bad utf8");
        return;
    };
    let mut lines = head.split("\r\n");
    let Some(req_line) = lines.next() else { return };
    let mut parts = req_line.split_whitespace();
    let (Some(method), Some(path), Some(version)) = (parts.next(), parts.next(), parts.next()) else {
        write_http(&mut stream, "400 Bad Request", "bad request");
        return;
    };
    if let Some(tok) = path.strip_prefix("/health/") {
        if tok != token {
            write_http(&mut stream, "403 Forbidden", "forbidden");
            return;
        }
        let body = match LinkState::from_u8(state.load(Ordering::Relaxed)) {
            LinkState::ConnectedLan => "lan",
            LinkState::ConnectedPublic => "public",
            LinkState::Stalled => "stalled",
            LinkState::Connecting => "connecting",
        };
        write_http(&mut stream, "200 OK", body);
        return;
    }
    let Some((target_port, forwarded_path)) = parse_proxy_path(path, token) else {
        write_http(&mut stream, "403 Forbidden", "forbidden");
        return;
    };
    let mut rewritten = String::with_capacity(header_end + 64);
    rewritten.push_str(&format!("{method} {forwarded_path} {version}\r\n"));
    for line in lines {
        if line.is_empty() {
            break;
        }
        let lower = line.to_ascii_lowercase();
        if lower.starts_with("host:") || lower.starts_with("connection:") {
            continue;
        }
        rewritten.push_str(line);
        rewritten.push_str("\r\n");
    }
    rewritten.push_str(&format!("Host: {SERVER_TUNNEL_IP}:{target_port}\r\nConnection: close\r\n\r\n"));
    let mut initial_bytes = rewritten.into_bytes();
    initial_bytes.extend_from_slice(&buf[header_end..]);
    let _ = stream.set_read_timeout(None);
    let _ = stream.set_nonblocking(true);
    let _ = conn_tx.send(NewConn { target_port, initial_bytes, stream });
}

fn parse_proxy_path<'a>(path: &'a str, expected_token: &str) -> Option<(u16, &'a str)> {
    let rest = path.strip_prefix("/p/")?;
    let (tok, rest) = rest.split_once('/')?;
    if tok != expected_token {
        return None;
    }
    let (port_str, tail) = match rest.split_once('/') {
        Some((p, t)) => (p, Some(t)),
        None => (rest, None),
    };
    let port: u16 = port_str.parse().ok()?;
    let forwarded = match tail {
        Some(t) => &path[path.len() - t.len() - 1..],
        None => "/",
    };
    Some((port, forwarded))
}

fn find_subslice(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack.windows(needle.len()).position(|w| w == needle)
}

struct WgIpDevice {
    rx_queue: VecDeque<Vec<u8>>,
    tx_queue: VecDeque<Vec<u8>>,
}

struct WgRxToken(Vec<u8>);
impl RxToken for WgRxToken {
    fn consume<R, F>(self, f: F) -> R
    where
        F: FnOnce(&[u8]) -> R,
    {
        f(&self.0)
    }
}

struct WgTxToken<'a>(&'a mut VecDeque<Vec<u8>>);
impl<'a> TxToken for WgTxToken<'a> {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        let mut buf = vec![0u8; len];
        let res = f(&mut buf);
        self.0.push_back(buf);
        res
    }
}

impl Device for WgIpDevice {
    type RxToken<'a> = WgRxToken where Self: 'a;
    type TxToken<'a> = WgTxToken<'a> where Self: 'a;

    fn receive(&mut self, _: SmolInstant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        let pkt = self.rx_queue.pop_front()?;
        Some((WgRxToken(pkt), WgTxToken(&mut self.tx_queue)))
    }
    fn transmit(&mut self, _: SmolInstant) -> Option<Self::TxToken<'_>> {
        Some(WgTxToken(&mut self.tx_queue))
    }
    fn capabilities(&self) -> DeviceCapabilities {
        let mut caps = DeviceCapabilities::default();
        caps.medium = Medium::Ip;
        caps.max_transmission_unit = WG_MTU;
        caps
    }
}

struct EndpointSelector {
    lan_spec: Option<String>,
    pub_spec: Option<String>,
    lan_addr: Option<SocketAddr>,
    pub_addr: Option<SocketAddr>,
    active: Option<(LinkState, SocketAddr)>,
    probe_started_at: Option<Instant>,
    pub_probed: bool,
    last_rx_at: Option<Instant>,
    first_unanswered_at: Option<Instant>,
}

impl EndpointSelector {
    fn new(lan_spec: Option<String>, pub_spec: Option<String>) -> Self {
        let mut s = EndpointSelector {
            lan_spec,
            pub_spec,
            lan_addr: None,
            pub_addr: None,
            active: None,
            probe_started_at: Some(Instant::now()),
            pub_probed: false,
            last_rx_at: None,
            first_unanswered_at: None,
        };
        s.resolve();
        s
    }

    fn resolve(&mut self) {
        self.lan_addr = self.lan_spec.as_deref().and_then(resolve_udp);
        self.pub_addr = self.pub_spec.as_deref().and_then(resolve_udp);
    }

    fn start_reprobe(&mut self, now: Instant) {
        self.resolve();
        self.active = None;
        self.probe_started_at = Some(now);
        self.pub_probed = false;
    }

    fn send_packet(&mut self, udp: &UdpSocket, pkt: &[u8], now: Instant) {
        if self.first_unanswered_at.is_none() && self.last_rx_at.is_none() {
            self.first_unanswered_at = Some(now);
        }
        if let Some((_, addr)) = self.active {
            let _ = udp.send_to(pkt, addr);
            return;
        }
        let elapsed = self.probe_started_at.map(|t| now.saturating_duration_since(t)).unwrap_or_default();
        if let Some(lan) = self.lan_addr {
            let _ = udp.send_to(pkt, lan);
        }
        if let Some(pub_a) = self.pub_addr
            && (self.lan_addr.is_none() || elapsed >= LAN_HEAD_START)
        {
            let _ = udp.send_to(pkt, pub_a);
            self.pub_probed = true;
        }
    }

    fn on_valid_rx(&mut self, src: SocketAddr, now: Instant) -> LinkState {
        self.last_rx_at = Some(now);
        self.first_unanswered_at = None;
        let kind = if self.lan_addr == Some(src) {
            LinkState::ConnectedLan
        } else if self.pub_addr == Some(src) {
            LinkState::ConnectedPublic
        } else if let Some((k, _)) = self.active {
            k
        } else {
            LinkState::ConnectedPublic
        };
        self.active = Some((kind, src));
        self.probe_started_at = None;
        kind
    }

    fn check_health(&mut self, has_traffic: bool, now: Instant) -> (bool, LinkState) {
        let mut kick = false;
        if let Some(pt) = self.probe_started_at
            && !self.pub_probed
            && self.lan_addr.is_some()
            && self.pub_addr.is_some()
            && now.saturating_duration_since(pt) >= LAN_HEAD_START
        {
            self.pub_probed = true;
            kick = true;
        }
        if has_traffic
            && let Some(last) = self.last_rx_at
            && now.saturating_duration_since(last) >= STALL_REPROBE_AFTER
            && self.probe_started_at.is_none()
        {
            self.first_unanswered_at.get_or_insert(now);
            self.start_reprobe(now);
            kick = true;
        }
        let st = if let Some(t) = self.first_unanswered_at
            && now.saturating_duration_since(t) >= STALL_REPORT_AFTER
        {
            LinkState::Stalled
        } else if let Some((k, _)) = self.active {
            k
        } else {
            LinkState::Connecting
        };
        (kick, st)
    }
}

fn resolve_udp(spec: &str) -> Option<SocketAddr> {
    spec.trim().to_socket_addrs().ok()?.find(|a| a.is_ipv4()).or_else(|| spec.trim().to_socket_addrs().ok()?.next())
}

struct StreamBridge {
    stream: TcpStream,
    to_tunnel: VecDeque<u8>,
    to_loopback: VecDeque<u8>,
    loopback_eof: bool,
}

#[allow(clippy::too_many_arguments)]
fn run_engine(
    mut tunn: Tunn,
    udp: UdpSocket,
    local_ip: Ipv4Addr,
    prefix: u8,
    server_ip: Ipv4Addr,
    endpoint_lan: Option<String>,
    endpoint_public: Option<String>,
    conn_rx: mpsc::Receiver<NewConn>,
    state: Arc<AtomicU8>,
    stop: Arc<AtomicBool>,
    reprobe_flag: Arc<AtomicBool>,
) {
    let start = Instant::now();
    let mut device = WgIpDevice { rx_queue: VecDeque::new(), tx_queue: VecDeque::new() };
    let mut iface = Interface::new(IfaceConfig::new(HardwareAddress::Ip), &mut device, SmolInstant::from_millis(0));
    let oct = local_ip.octets();
    iface.update_ip_addrs(|addrs| {
        let _ = addrs.push(IpCidr::new(IpAddress::v4(oct[0], oct[1], oct[2], oct[3]), prefix));
    });
    let srv_oct = server_ip.octets();
    let _ = iface.routes_mut().add_default_ipv4_route(Ipv4Address::new(srv_oct[0], srv_oct[1], srv_oct[2], srv_oct[3]));

    let mut sockets = SocketSet::new(vec![]);
    let mut bridges: HashMap<SocketHandle, StreamBridge> = HashMap::new();
    let mut next_port: u16 = 40_000;
    let mut endpoints = EndpointSelector::new(endpoint_lan, endpoint_public);
    let mut udp_buf = [0u8; 4096];
    let mut wg_out = [0u8; 4096];
    let mut last_timer = Instant::now();

    if let TunnResult::WriteToNetwork(pkt) = tunn.format_handshake_initiation(&mut wg_out, false) {
        endpoints.send_packet(&udp, pkt, Instant::now());
    }

    while !stop.load(Ordering::Relaxed) {
        let now = Instant::now();
        let smol_now = SmolInstant::from_millis(now.duration_since(start).as_millis() as i64);

        if reprobe_flag.swap(false, Ordering::Relaxed) {
            endpoints.start_reprobe(now);
            if let TunnResult::WriteToNetwork(pkt) = tunn.format_handshake_initiation(&mut wg_out, true) {
                endpoints.send_packet(&udp, pkt, now);
            }
        }

        while let Ok(nc) = conn_rx.try_recv() {
            let mut sock = TcpSocket::new(SocketBuffer::new(vec![0u8; TCP_BUF_SIZE]), SocketBuffer::new(vec![0u8; TCP_BUF_SIZE]));
            let local_port = next_port;
            next_port = if next_port >= 60_000 { 40_000 } else { next_port + 1 };
            let remote = (IpAddress::v4(srv_oct[0], srv_oct[1], srv_oct[2], srv_oct[3]), nc.target_port);
            if sock.connect(iface.context(), remote, local_port).is_ok() {
                let h = sockets.add(sock);
                bridges.insert(h, StreamBridge { stream: nc.stream, to_tunnel: nc.initial_bytes.into(), to_loopback: VecDeque::new(), loopback_eof: false });
            }
        }

        loop {
            match udp.recv_from(&mut udp_buf) {
                Ok((n, src)) => {
                    let mut res = tunn.decapsulate(Some(src.ip()), &udp_buf[..n], &mut wg_out);
                    loop {
                        match res {
                            TunnResult::Done | TunnResult::Err(_) => break,
                            TunnResult::WriteToNetwork(pkt) => {
                                state.store(endpoints.on_valid_rx(src, now) as u8, Ordering::Relaxed);
                                let _ = udp.send_to(pkt, src);
                                res = tunn.decapsulate(Some(src.ip()), &[], &mut wg_out);
                            }
                            TunnResult::WriteToTunnelV4(ip_pkt, _) => {
                                state.store(endpoints.on_valid_rx(src, now) as u8, Ordering::Relaxed);
                                device.rx_queue.push_back(ip_pkt.to_vec());
                                break;
                            }
                            TunnResult::WriteToTunnelV6(_, _) => break,
                        }
                    }
                }
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => break,
                Err(_) => break,
            }
        }

        iface.poll(smol_now, &mut device, &mut sockets);
        let mut finished = Vec::new();
        let mut tmp = [0u8; 16 * 1024];
        for (&handle, br) in &mut bridges {
            let sock = sockets.get_mut::<TcpSocket>(handle);
            if !br.loopback_eof && br.to_tunnel.len() < TCP_BUF_SIZE {
                match br.stream.read(&mut tmp) {
                    Ok(0) => br.loopback_eof = true,
                    Ok(n) => br.to_tunnel.extend(&tmp[..n]),
                    Err(e) if e.kind() == io::ErrorKind::WouldBlock => {}
                    Err(_) => br.loopback_eof = true,
                }
            }
            if sock.can_send() && !br.to_tunnel.is_empty() {
                let (a, _) = br.to_tunnel.as_slices();
                if let Ok(sent) = sock.send_slice(a) {
                    br.to_tunnel.drain(..sent);
                }
            }
            if br.loopback_eof && br.to_tunnel.is_empty() && sock.state() == tcp::State::Established {
                sock.close();
            }
            if sock.can_recv() && br.to_loopback.len() < TCP_BUF_SIZE {
                let _ = sock.recv(|data| {
                    br.to_loopback.extend(&*data);
                    (data.len(), ())
                });
            }
            while !br.to_loopback.is_empty() {
                let (a, _) = br.to_loopback.as_slices();
                match br.stream.write(a) {
                    Ok(0) => { br.to_loopback.clear(); sock.abort(); break; }
                    Ok(n) => { br.to_loopback.drain(..n); }
                    Err(e) if e.kind() == io::ErrorKind::WouldBlock => break,
                    Err(_) => { br.to_loopback.clear(); sock.abort(); break; }
                }
            }
            if (sock.state() == tcp::State::Closed || sock.state() == tcp::State::TimeWait) && br.to_loopback.is_empty() {
                finished.push(handle);
            }
        }
        for h in finished {
            bridges.remove(&h);
            sockets.remove(h);
        }

        iface.poll(smol_now, &mut device, &mut sockets);
        while let Some(ip_pkt) = device.tx_queue.pop_front() {
            if let TunnResult::WriteToNetwork(pkt) = tunn.encapsulate(&ip_pkt, &mut wg_out) {
                endpoints.send_packet(&udp, pkt, now);
            }
        }

        let (need_kick, st) = endpoints.check_health(!bridges.is_empty(), now);
        state.store(st as u8, Ordering::Relaxed);
        if need_kick
            && let TunnResult::WriteToNetwork(pkt) = tunn.format_handshake_initiation(&mut wg_out, false)
        {
            endpoints.send_packet(&udp, pkt, now);
        }
        if now.duration_since(last_timer) >= Duration::from_millis(250) {
            last_timer = now;
            if let TunnResult::WriteToNetwork(pkt) = tunn.update_timers(&mut wg_out) {
                endpoints.send_packet(&udp, pkt, now);
            }
        }

        let sleep_dur = if bridges.is_empty() {
            Duration::from_millis(25)
        } else {
            iface
                .poll_delay(smol_now, &sockets)
                .map(|d| Duration::from_millis(d.total_millis()))
                .unwrap_or(Duration::from_millis(5))
                .clamp(Duration::from_millis(1), Duration::from_millis(10))
        };
        thread::sleep(sleep_dur);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keypair_json_is_valid() {
        let j: serde_json::Value = serde_json::from_str(&generate_wg_keypair_json().unwrap()).unwrap();
        assert_eq!(j["private"].as_str().unwrap().len(), 44);
        assert_eq!(j["public"].as_str().unwrap().len(), 44);
    }
}
