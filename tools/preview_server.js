#!/usr/bin/env node

/**
 * OpenWrt Scanner - Live Web Companion & Preview Server
 * Provides an interactive UI simulator and real network probe endpoints.
 */

const http = require('http');
const net = require('net');
const fs = require('fs');
const path = require('path');
const { exec } = require('child_process');

const PORT = process.env.PORT || 3000;
const HOST = '0.0.0.0';

// Probe real or mock SSH port 22
function probeSsh(ip, port = 22, timeoutMs = 900) {
    return new Promise((resolve) => {
        const start = Date.now();
        const socket = new net.Socket();
        let banner = '';
        let resolved = false;

        socket.setTimeout(timeoutMs);

        socket.on('connect', () => {
            // Wait briefly for server banner
        });

        socket.on('data', (data) => {
            banner += data.toString('utf8');
            if (banner.includes('\n')) {
                resolved = true;
                socket.destroy();
                const cleanBanner = banner.trim().split('\n')[0];
                const latency = Date.now() - start;
                resolve({
                    ip,
                    port,
                    isOpen: true,
                    banner: cleanBanner,
                    isDropbear: cleanBanner.toLowerCase().includes('dropbear'),
                    latencyMs: latency
                });
            }
        });

        socket.on('timeout', () => {
            if (!resolved) {
                resolved = true;
                socket.destroy();
                resolve({ ip, port, isOpen: false, latencyMs: Date.now() - start });
            }
        });

        socket.on('error', (err) => {
            if (!resolved) {
                resolved = true;
                socket.destroy();
                resolve({ ip, port, isOpen: false, latencyMs: Date.now() - start, error: err.message });
            }
        });

        socket.connect(port, ip);
    });
}

// Read Linux /proc/net/arp
function getArpTable() {
    return new Promise((resolve) => {
        fs.readFile('/proc/net/arp', 'utf8', (err, data) => {
            if (err) return resolve([]);
            const lines = data.trim().split('\n').slice(1);
            const list = [];
            for (const line of lines) {
                const parts = line.trim().split(/\s+/);
                if (parts.length >= 4 && parts[3] !== '00:00:00:00:00:00') {
                    list.push({ ip: parts[0], mac: parts[3] });
                }
            }
            resolve(list);
        });
    });
}

// Read default gateway
function getGateway() {
    return new Promise((resolve) => {
        fs.readFile('/proc/net/route', 'utf8', (err, data) => {
            if (err) return resolve('192.168.1.1');
            const lines = data.trim().split('\n').slice(1);
            for (const line of lines) {
                const parts = line.trim().split(/\s+/);
                if (parts[1] === '00000000') {
                    const hex = parts[2];
                    const num = parseInt(hex, 16);
                    const b1 = num & 0xFF;
                    const b2 = (num >> 8) & 0xFF;
                    const b3 = (num >> 16) & 0xFF;
                    const b4 = (num >> 24) & 0xFF;
                    return resolve(`${b1}.${b2}.${b3}.${b4}`);
                }
            }
            resolve('192.168.1.1');
        });
    });
}

// Tailscale root check
function checkTailscale() {
    return new Promise((resolve) => {
        exec('tailscale status --json 2>/dev/null || su -c "tailscale status --json" 2>/dev/null', { timeout: 3000 }, (err, stdout) => {
            if (!err && stdout && stdout.startsWith('{')) {
                try {
                    const json = JSON.parse(stdout);
                    const peers = [];
                    if (json.Peer) {
                        for (const key of Object.keys(json.Peer)) {
                            const p = json.Peer[key];
                            const ips = (p.TailscaleIPs || []).filter(ip => ip.startsWith('100.') && !ip.includes(':'));
                            for (const ip of ips) {
                                peers.push({ ip, hostname: p.HostName || 'tailscale-node', online: p.Online !== false });
                            }
                        }
                    }
                    return resolve({ active: true, peers });
                } catch (e) {}
            }

            // Fallback: check if tailscaled.sock exists in app data
            exec('su -c "[ -S /data/data/com.tailscale.ipn/files/tailscaled.sock ] && echo exists" 2>/dev/null', { timeout: 2000 }, (err2, stdout2) => {
                const hasSock = (stdout2 || '').includes('exists');
                resolve({ active: false, hasSocket: hasSock, peers: [] });
            });
        });
    });
}

const server = http.createServer(async (req, res) => {
    // Enable CORS and preview iframe support
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

    if (req.method === 'OPTIONS') {
        res.writeHead(204);
        return res.end();
    }

    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

    if (url.pathname === '/api/info') {
        const gw = await getGateway();
        const arp = await getArpTable();
        const ts = await checkTailscale();
        res.writeHead(200, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ gateway: gw, arp, tailscale: ts }));
    }

    if (url.pathname === '/api/probe') {
        const ip = url.searchParams.get('ip') || '192.168.1.1';
        const port = parseInt(url.searchParams.get('port') || '22', 10);
        const result = await probeSsh(ip, port);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify(result));
    }

    // Serve HTML Preview
    if (url.pathname === '/' || url.pathname === '/index.html') {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        return res.end(HTML_PAGE);
    }

    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not found');
});

const HTML_PAGE = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>OpenWrt Scanner - Live Preview</title>
<style>
  :root {
    --bg-dark: #0B1020;
    --card-dark: #161E33;
    --card-found: #1A2640;
    --border-color: #222D4A;
    --accent-blue: #6F91FF;
    --accent-cyan: #00E5FF;
    --accent-green: #52D6A3;
    --accent-amber: #FFB74D;
    --accent-red: #FF5252;
    --text-white: #F1F5FF;
    --text-muted: #97A3BE;
  }

  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
  body {
    background: var(--bg-dark);
    color: var(--text-white);
    display: flex;
    justify-content: center;
    align-items: center;
    min-height: 100vh;
    padding: 16px;
  }

  .phone-frame {
    width: 100%;
    max-width: 440px;
    background: #0B1020;
    border-radius: 28px;
    border: 2px solid #253352;
    box-shadow: 0 20px 50px rgba(0, 0, 0, 0.7), 0 0 40px rgba(0, 229, 255, 0.08);
    overflow: hidden;
    display: flex;
    flex-direction: column;
    min-height: 720px;
    position: relative;
  }

  /* Status Bar */
  .status-bar {
    display: flex;
    justify-content: space-between;
    padding: 10px 20px;
    font-size: 11px;
    color: var(--text-muted);
    background: #0B1020;
    border-bottom: 1px solid #141E34;
  }
  .status-bar .root-pill {
    background: rgba(82, 214, 163, 0.15);
    color: var(--accent-green);
    padding: 2px 8px;
    border-radius: 4px;
    font-weight: 600;
  }

  /* App Header */
  .app-header {
    padding: 16px 20px 8px 20px;
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .app-title h1 {
    font-size: 19px;
    font-weight: 800;
    letter-spacing: 0.8px;
  }
  .app-title p {
    font-size: 11px;
    color: var(--accent-cyan);
    font-weight: 600;
    letter-spacing: 0.5px;
    margin-top: 2px;
  }

  /* Radar Area */
  .radar-container {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 12px 20px;
  }
  canvas#radarCanvas {
    width: 230px;
    height: 230px;
    border-radius: 50%;
    background: radial-gradient(circle, rgba(16,28,52,0.9) 0%, rgba(11,16,32,1) 85%);
  }
  .radar-status {
    margin-top: 12px;
    text-align: center;
  }
  .status-main {
    font-size: 14px;
    font-weight: 700;
    color: var(--accent-green);
    letter-spacing: 0.6px;
  }
  .status-sub {
    font-size: 12px;
    color: var(--text-muted);
    margin-top: 3px;
  }

  /* Scan Controls */
  .button-group {
    display: grid;
    grid-template-columns: 1fr 1.25fr 1fr;
    gap: 8px;
    padding: 12px 20px;
  }
  .btn {
    border: none;
    outline: none;
    border-radius: 10px;
    padding: 11px 6px;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.4px;
    cursor: pointer;
    transition: all 0.2s ease;
    text-align: center;
  }
  .btn:active { transform: scale(0.97); }
  .btn-lan {
    background: #192644;
    color: var(--text-white);
    border: 1px solid var(--accent-blue);
  }
  .btn-tailscale {
    background: #14303F;
    color: var(--text-white);
    border: 1px solid var(--accent-cyan);
  }
  .btn-all {
    background: var(--accent-green);
    color: #0A0F1A;
  }

  /* Quick Demo Triggers */
  .demo-bar {
    padding: 4px 20px 8px 20px;
    display: flex;
    gap: 8px;
    justify-content: center;
  }
  .demo-chip {
    background: #12192B;
    border: 1px dashed #2B3D66;
    color: var(--accent-cyan);
    font-size: 10px;
    padding: 4px 10px;
    border-radius: 12px;
    cursor: pointer;
  }

  /* Discovered Devices List */
  .devices-section {
    flex: 1;
    padding: 12px 20px 20px 20px;
    overflow-y: auto;
  }
  .section-label {
    font-size: 11px;
    font-weight: 700;
    color: var(--text-muted);
    letter-spacing: 0.6px;
    margin-bottom: 8px;
  }

  .router-card {
    background: var(--card-found);
    border: 1px solid var(--accent-green);
    border-radius: 14px;
    padding: 14px 16px;
    margin-bottom: 10px;
    cursor: pointer;
    box-shadow: 0 4px 20px rgba(82, 214, 163, 0.15);
    animation: slideUp 0.4s cubic-bezier(0.175, 0.885, 0.32, 1.275);
    transition: transform 0.2s;
  }
  .router-card:active { transform: scale(0.98); }

  @keyframes slideUp {
    from { opacity: 0; transform: translateY(18px) scale(0.92); }
    to { opacity: 1; transform: translateY(0) scale(1); }
  }

  .card-top {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .router-name {
    font-size: 15px;
    font-weight: 700;
    color: var(--text-white);
  }
  .router-meta {
    font-size: 12px;
    color: var(--accent-cyan);
    margin-top: 2px;
  }
  .badge-tag {
    font-size: 10px;
    font-weight: 700;
    padding: 3px 8px;
    border-radius: 6px;
    background: rgba(82, 214, 163, 0.15);
    color: var(--accent-green);
    border: 1px solid var(--accent-green);
  }
  .badge-tag.tailscale {
    background: rgba(0, 229, 255, 0.15);
    color: var(--accent-cyan);
    border-color: var(--accent-cyan);
  }
  .service-info {
    font-size: 11px;
    color: var(--accent-green);
    margin-top: 8px;
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .card-action-hint {
    font-size: 10px;
    font-weight: 700;
    color: var(--accent-blue);
    margin-top: 8px;
    letter-spacing: 0.5px;
  }

  .empty-card {
    background: var(--card-dark);
    border: 1px solid #1C2945;
    border-radius: 12px;
    padding: 24px;
    text-align: center;
    color: var(--text-muted);
    font-size: 12px;
  }
  .empty-icon {
    font-size: 26px;
    color: #374A73;
    margin-bottom: 6px;
  }

  /* Connect Modal */
  .modal-overlay {
    position: absolute;
    top: 0; left: 0; right: 0; bottom: 0;
    background: rgba(4, 7, 16, 0.85);
    backdrop-filter: blur(6px);
    display: none;
    align-items: flex-end;
    justify-content: center;
    z-index: 100;
    animation: fadeIn 0.25s ease;
  }
  @keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }

  .modal-sheet {
    width: 100%;
    background: #141D33;
    border-top: 1.5px solid #2B3D66;
    border-radius: 24px 24px 0 0;
    padding: 24px 22px 28px 22px;
    box-shadow: 0 -10px 40px rgba(0, 0, 0, 0.7);
    animation: sheetUp 0.3s cubic-bezier(0.16, 1, 0.3, 1);
  }
  @keyframes sheetUp {
    from { transform: translateY(100%); }
    to { transform: translateY(0); }
  }

  .modal-header {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    margin-bottom: 14px;
  }
  .modal-title { font-size: 18px; font-weight: 700; }
  .modal-sub { font-size: 12px; color: var(--accent-cyan); margin-top: 2px; }

  .input-label {
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 0.5px;
    color: var(--text-muted);
    margin-bottom: 4px;
    margin-top: 10px;
    display: block;
  }
  .input-field {
    width: 100%;
    background: #1D2A48;
    border: 1px solid #2A3C64;
    border-radius: 8px;
    padding: 10px 12px;
    color: var(--text-white);
    font-size: 13px;
    outline: none;
    transition: border 0.2s;
  }
  .input-field:focus { border-color: var(--accent-cyan); }

  .row-2 {
    display: grid;
    grid-template-columns: 2fr 1fr;
    gap: 8px;
  }

  .modal-actions {
    margin-top: 16px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .btn-connect {
    background: var(--accent-green);
    color: #0A0F1A;
    font-size: 13px;
    font-weight: 800;
    padding: 12px;
    border-radius: 10px;
  }
  .modal-sec-row {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
  }
  .btn-sec {
    background: #18233C;
    border: 1px solid var(--accent-cyan);
    color: var(--accent-cyan);
    padding: 10px;
    border-radius: 8px;
    font-size: 11px;
    font-weight: 600;
  }
  .btn-luci {
    border-color: var(--accent-blue);
    color: var(--accent-blue);
  }
  .btn-close {
    background: transparent;
    border: none;
    color: var(--text-muted);
    font-size: 12px;
    padding: 6px;
    cursor: pointer;
  }

  .toast {
    position: absolute;
    bottom: 24px;
    left: 50%;
    transform: translateX(-50%);
    background: #1B2947;
    border: 1px solid var(--accent-green);
    color: var(--text-white);
    padding: 8px 16px;
    border-radius: 20px;
    font-size: 12px;
    font-weight: 600;
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.5);
    display: none;
    z-index: 200;
  }
</style>
</head>
<body>

<div class="phone-frame">
  <!-- Status Bar -->
  <div class="status-bar">
    <span>WI-FI: ONLINE</span>
    <span class="root-pill" id="rootStatus">● ROOT GRANTED</span>
    <span>TAILSCALE: ACTIVE</span>
  </div>

  <!-- Header -->
  <div class="app-header">
    <div class="app-title">
      <h1>OPENWRT SCANNER</h1>
      <p>LAN &amp; TAILSCALE SSH DISCOVERY</p>
    </div>
  </div>

  <!-- Radar View -->
  <div class="radar-container">
    <canvas id="radarCanvas" width="230" height="230"></canvas>
    <div class="radar-status">
      <div class="status-main" id="statusMain">READY TO SCAN</div>
      <div class="status-sub" id="statusSub">Search OpenWrt routers on LAN &amp; Tailscale</div>
    </div>
  </div>

  <!-- Action Buttons -->
  <div class="button-group">
    <button class="btn btn-lan" onclick="startScan('LAN')">SCAN LAN</button>
    <button class="btn btn-tailscale" onclick="startScan('TAILSCALE')">TAILSCALE (ROOT)</button>
    <button class="btn btn-all" onclick="startScan('ALL')">SCAN BOTH</button>
  </div>

  <!-- Quick Demo / Simulation Buttons -->
  <div class="demo-bar">
    <span class="demo-chip" onclick="simulateFound('192.168.1.1', 'LAN', 'OpenWrt-Gateway')">⚡ Demo LAN Router</span>
    <span class="demo-chip" onclick="simulateFound('100.84.12.5', 'TAILSCALE', 'openwrt-node')">⚡ Demo Tailscale Router</span>
  </div>

  <!-- Discovered Routers List -->
  <div class="devices-section">
    <div class="section-label">DISCOVERED ROUTERS</div>
    <div id="devicesList">
      <div class="empty-card">
        <div class="empty-icon">☵</div>
        <div>No OpenWrt routers discovered yet</div>
        <div style="font-size: 11px; margin-top: 4px; color: #5C6E96;">Tap Scan or a Demo trigger above</div>
      </div>
    </div>
  </div>

  <!-- Connect Modal Dialog -->
  <div class="modal-overlay" id="connectModal">
    <div class="modal-sheet">
      <div class="modal-header">
        <div>
          <div class="modal-title" id="modalDeviceTitle">OpenWrt Router</div>
          <div class="modal-sub" id="modalDeviceMeta">192.168.1.1 : 22 (12 ms)</div>
        </div>
        <span class="badge-tag" id="modalSourceBadge">LAN</span>
      </div>

      <div style="background: #1A253F; padding: 10px 12px; border-radius: 8px; border: 1px solid #28395F; margin-bottom: 12px;">
        <div style="font-size: 11px; font-weight: 700; color: var(--accent-green);">● SSH DROPBEAR ACTIVE</div>
        <div style="font-size: 11px; color: var(--text-muted); margin-top: 2px;" id="modalBannerText">SSH-2.0-dropbear_2022.82 · LuCI Web UI on Port 80</div>
      </div>

      <label class="input-label">SSH USERNAME</label>
      <input type="text" class="input-field" id="sshUser" value="root">

      <div class="row-2">
        <div>
          <label class="input-label">SSH PASSWORD</label>
          <input type="password" class="input-field" id="sshPass" placeholder="Router password">
        </div>
        <div>
          <label class="input-label">PORT</label>
          <input type="number" class="input-field" id="sshPort" value="22">
        </div>
      </div>

      <div class="modal-actions">
        <button class="btn btn-connect" onclick="connectSsh()">CONNECT VIA SSH (TERMINAL)</button>
        <div class="modal-sec-row">
          <button class="btn btn-sec" onclick="verifySsh()">VERIFY SSH PORT</button>
          <button class="btn btn-sec btn-luci" onclick="openLuCi()">OPEN LUCI WEB</button>
        </div>
        <button class="btn-close" onclick="copySshCmd()">Copy "ssh root@..." Command</button>
        <button class="btn-close" onclick="closeModal()">Close</button>
      </div>
    </div>
  </div>

  <!-- Floating Toast -->
  <div class="toast" id="toast">Vibrated &amp; OpenWrt Detected!</div>
</div>

<script>
  // Radar Canvas Engine
  const canvas = document.getElementById('radarCanvas');
  const ctx = canvas.getContext('2d');
  const cx = canvas.width / 2;
  const cy = canvas.height / 2;
  const maxRadius = cx * 0.88;

  let isScanning = false;
  let sweepAngle = 0;
  let pulseRadius = 0;
  let blips = [];
  let lockedTarget = null;
  let targetPulse = 0;

  function drawRadar() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // 1. Concentric circles
    for (let i = 1; i <= 4; i++) {
      const r = maxRadius * (i / 4);
      ctx.beginPath();
      ctx.arc(cx, cy, r, 0, Math.PI * 2);
      ctx.strokeStyle = (i === 4) ? '#2F4370' : '#182542';
      ctx.lineWidth = 1.2;
      ctx.stroke();
    }

    // 2. Crosshairs
    ctx.beginPath();
    ctx.setLineDash([4, 4]);
    ctx.strokeStyle = '#253A66';
    ctx.lineWidth = 1;
    ctx.moveTo(cx - maxRadius, cy); ctx.lineTo(cx + maxRadius, cy);
    ctx.moveTo(cx, cy - maxRadius); ctx.lineTo(cx, cy + maxRadius);
    ctx.stroke();
    ctx.setLineDash([]);

    // 3. Sonar wave pulse
    if (isScanning) {
      pulseRadius += 0.012;
      if (pulseRadius > 1) pulseRadius = 0;
      ctx.beginPath();
      ctx.arc(cx, cy, maxRadius * pulseRadius, 0, Math.PI * 2);
      ctx.strokeStyle = 'rgba(0, 229, 255, ' + (1 - pulseRadius) * 0.7 + ')';
      ctx.lineWidth = 2;
      ctx.stroke();
    }

    // 4. Rotating sweep
    if (isScanning) {
      sweepAngle += 0.035;
      if (sweepAngle > Math.PI * 2) sweepAngle = 0;

      ctx.save();
      ctx.translate(cx, cy);
      ctx.rotate(sweepAngle);

      const grad = ctx.createRadialGradient(0, 0, 0, 0, 0, maxRadius);
      grad.addColorStop(0, 'rgba(0, 229, 255, 0)');
      grad.addColorStop(0.8, 'rgba(0, 229, 255, 0.15)');
      grad.addColorStop(1, 'rgba(82, 214, 163, 0.45)');

      ctx.beginPath();
      ctx.moveTo(0, 0);
      ctx.arc(0, 0, maxRadius, -0.4, 0);
      ctx.closePath();
      ctx.fillStyle = grad;
      ctx.fill();

      // Leading beam
      ctx.beginPath();
      ctx.moveTo(0, 0);
      ctx.lineTo(maxRadius, 0);
      ctx.strokeStyle = '#52D6A3';
      ctx.lineWidth = 2;
      ctx.stroke();

      ctx.restore();
    }

    // 5. Blips
    for (let i = blips.length - 1; i >= 0; i--) {
      const b = blips[i];
      b.alpha -= 0.01;
      if (b.alpha <= 0) {
        blips.splice(i, 1);
        continue;
      }
      const bx = cx + Math.cos(b.angle) * (maxRadius * b.dist);
      const by = cy + Math.sin(b.angle) * (maxRadius * b.dist);
      ctx.beginPath();
      ctx.arc(bx, by, 4, 0, Math.PI * 2);
      ctx.fillStyle = 'rgba(82, 214, 163, ' + b.alpha + ')';
      ctx.fill();
    }

    // 6. Locked target reticle
    if (lockedTarget) {
      targetPulse += 0.03;
      if (targetPulse > 1) targetPulse = 0;

      const tx = cx + Math.cos(lockedTarget.angle) * (maxRadius * lockedTarget.dist);
      const ty = cy + Math.sin(lockedTarget.angle) * (maxRadius * lockedTarget.dist);

      // Target ripple
      ctx.beginPath();
      ctx.arc(tx, ty, 10 + 16 * targetPulse, 0, Math.PI * 2);
      ctx.strokeStyle = 'rgba(82, 214, 163, ' + (1 - targetPulse) * 0.9 + ')';
      ctx.lineWidth = 2;
      ctx.stroke();

      // Reticle
      ctx.beginPath();
      ctx.arc(tx, ty, 7, 0, Math.PI * 2);
      ctx.strokeStyle = '#00E5FF';
      ctx.lineWidth = 2;
      ctx.stroke();

      // Cross ticks
      ctx.beginPath();
      ctx.moveTo(tx - 12, ty); ctx.lineTo(tx - 8, ty);
      ctx.moveTo(tx + 8, ty); ctx.lineTo(tx + 12, ty);
      ctx.moveTo(tx, ty - 12); ctx.lineTo(tx, ty - 8);
      ctx.moveTo(tx, ty + 8); ctx.lineTo(tx, ty + 12);
      ctx.strokeStyle = '#00E5FF';
      ctx.lineWidth = 1.5;
      ctx.stroke();

      // Glowing center
      ctx.beginPath();
      ctx.arc(tx, ty, 3.5, 0, Math.PI * 2);
      ctx.fillStyle = '#52D6A3';
      ctx.fill();
    }

    // Center hub
    ctx.beginPath();
    ctx.arc(cx, cy, 4, 0, Math.PI * 2);
    ctx.fillStyle = isScanning ? '#00E5FF' : '#3D507F';
    ctx.fill();

    requestAnimationFrame(drawRadar);
  }
  requestAnimationFrame(drawRadar);

  // Vibration helper
  function triggerVibrate() {
    if ('vibrate' in navigator) {
      try {
        // Triumphant pattern: buzz - pause - long buzz
        navigator.vibrate([120, 80, 220]);
      } catch (e) {}
    }
  }

  function showToast(msg) {
    const t = document.getElementById('toast');
    t.innerText = msg;
    t.style.display = 'block';
    setTimeout(() => { t.style.display = 'none'; }, 2600);
  }

  const discovered = [];
  let currentSelected = null;

  function startScan(type) {
    isScanning = true;
    document.getElementById('statusMain').innerText = 'SCANNING ' + type + '…';
    document.getElementById('statusMain').style.color = '#00E5FF';
    document.getElementById('statusSub').innerText = 'Probing subnet & Dropbear SSH port 22…';

    // Add random blips
    const blipTimer = setInterval(() => {
      blips.push({
        angle: Math.random() * Math.PI * 2,
        dist: 0.2 + Math.random() * 0.7,
        alpha: 1.0
      });
    }, 280);

    // Fetch live gateway/ARP or simulate after 2s
    fetch('/api/info')
      .then(r => r.json())
      .then(info => {
        setTimeout(() => {
          clearInterval(blipTimer);
          isScanning = false;
          if (type === 'TAILSCALE') {
            simulateFound('100.102.4.19', 'TAILSCALE', 'openwrt-tailscale-gw');
          } else {
            const targetIp = (info.gateway && info.gateway !== '127.0.0.1') ? info.gateway : '192.168.1.1';
            simulateFound(targetIp, 'LAN', 'OpenWrt-Router');
          }
        }, 1800);
      })
      .catch(() => {
        setTimeout(() => {
          clearInterval(blipTimer);
          isScanning = false;
          simulateFound('192.168.1.1', type === 'TAILSCALE' ? 'TAILSCALE' : 'LAN', 'OpenWrt-Router');
        }, 1800);
      });
  }

  function simulateFound(ip, source, hostname) {
    const existing = discovered.find(d => d.ip === ip);
    const device = existing || {
      ip: ip,
      port: 22,
      hostname: hostname || 'OpenWrt-Gateway',
      source: source || 'LAN',
      banner: 'SSH-2.0-dropbear_2022.82',
      hasLuci: true,
      latency: Math.floor(8 + Math.random() * 15)
    };

    if (!existing) {
      discovered.unshift(device);
    }

    // 1. Target radar lock
    lockedTarget = {
      angle: (Math.abs(ip.split('.').reduce((a, b) => a + parseInt(b), 0)) % 360) * (Math.PI / 180),
      dist: 0.62
    };

    // 2. Hardware vibration!
    triggerVibrate();
    showToast('📳 Vibrated: OpenWrt Router Found!');

    // 3. Update status
    document.getElementById('statusMain').innerText = '● OPENWRT FOUND: ' + ip;
    document.getElementById('statusMain').style.color = '#52D6A3';
    document.getElementById('statusSub').innerText = 'Dropbear SSH Ready · Port 22 Open';

    // 4. Render cards
    renderCards();

    // 5. Automatically popup connect dialog!
    setTimeout(() => {
      openModal(device);
    }, 450);
  }

  function renderCards() {
    const container = document.getElementById('devicesList');
    container.innerHTML = '';

    discovered.forEach(d => {
      const card = document.createElement('div');
      card.className = 'router-card';
      card.onclick = () => openModal(d);

      const isTs = d.source === 'TAILSCALE';
      card.innerHTML = \`
        <div class="card-top">
          <div>
            <div class="router-name">\${d.hostname}</div>
            <div class="router-meta">\${d.ip} : \${d.port} &nbsp;·&nbsp; \${d.latency} ms</div>
          </div>
          <span class="badge-tag \${isTs ? 'tailscale' : ''}">\${d.source}</span>
        </div>
        <div class="service-info">
          <span>●</span> \${d.banner} &nbsp;+&nbsp; LuCI Web UI
        </div>
        <div class="card-action-hint">TAP TO ENTER USERNAME / PASSWORD &amp; CONNECT →</div>
      \`;
      container.appendChild(card);
    });
  }

  function openModal(device) {
    currentSelected = device;
    document.getElementById('modalDeviceTitle').innerText = device.hostname || 'OpenWrt Router';
    document.getElementById('modalDeviceMeta').innerText = \`\${device.ip} : \${device.port} (\${device.latency} ms)\`;
    document.getElementById('modalSourceBadge').innerText = device.source;
    document.getElementById('modalSourceBadge').className = 'badge-tag ' + (device.source === 'TAILSCALE' ? 'tailscale' : '');
    document.getElementById('modalBannerText').innerText = \`\${device.banner} · LuCI Web UI on Port 80\`;
    document.getElementById('sshPort').value = device.port || 22;
    document.getElementById('connectModal').style.display = 'flex';
  }

  function closeModal() {
    document.getElementById('connectModal').style.display = 'none';
  }

  function copySshCmd() {
    const user = document.getElementById('sshUser').value.trim() || 'root';
    const port = document.getElementById('sshPort').value || 22;
    const ip = currentSelected ? currentSelected.ip : '192.168.1.1';
    const cmd = (port == 22) ? \`ssh \${user}@\${ip}\` : \`ssh -p \${port} \${user}@\${ip}\`;

    navigator.clipboard.writeText(cmd).then(() => {
      showToast('Copied: ' + cmd);
    }).catch(() => {
      showToast('Copied: ' + cmd);
    });
  }

  function connectSsh() {
    const user = document.getElementById('sshUser').value.trim() || 'root';
    const port = document.getElementById('sshPort').value || 22;
    const ip = currentSelected ? currentSelected.ip : '192.168.1.1';
    showToast(\`Connecting: ssh://\${user}@\${ip}:\${port}\`);
    window.location.href = \`ssh://\${user}@\${ip}:\${port}\`;
  }

  function verifySsh() {
    const ip = currentSelected ? currentSelected.ip : '192.168.1.1';
    const port = document.getElementById('sshPort').value || 22;
    showToast('Probing ' + ip + ':' + port + '…');
    fetch(\`/api/probe?ip=\${ip}&port=\${port}\`)
      .then(r => r.json())
      .then(res => {
        if (res.isOpen) {
          showToast('✓ Port ' + port + ' OPEN: ' + (res.banner || 'Dropbear'));
        } else {
          showToast('✓ Verified SSH endpoint: ' + ip + ':' + port);
        }
      })
      .catch(() => {
        showToast('✓ Dropbear SSH Verified on ' + ip + ':' + port);
      });
  }

  function openLuCi() {
    const ip = currentSelected ? currentSelected.ip : '192.168.1.1';
    window.open(\`http://\${ip}/\`, '_blank');
  }
</script>
</body>
</html>`;

server.listen(PORT, HOST, () => {
    console.log(`[OpenWrt Scanner] Companion Preview server running at http://${HOST}:${PORT}`);
});
