(function () {
  "use strict";

  const REPOS = {
    android: {
      owner: "jimbo-yo",
      repo: "LocalBeat",
      fallbackUrl: "https://github.com/jimbo-yo/LocalBeat/releases/latest",
    },
    studio: {
      owner: "jimbo-yo",
      repo: "LocalBeatStudio",
      fallbackUrl: "https://github.com/jimbo-yo/LocalBeatStudio/releases/latest",
    },
  };

  function detectOS() {
    const ua = navigator.userAgent || "";
    const plat = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || "";
    if (/android/i.test(ua)) return "android";
    if (/iphone|ipad|ipod/i.test(ua)) return "ios";
    if (/win/i.test(plat) || /windows/i.test(ua)) return "windows";
    if (/linux/i.test(plat) || /linux/i.test(ua)) return "linux";
    if (/mac/i.test(plat) || /macintosh/i.test(ua)) return "macos";
    return "other";
  }

  function decodeBase64Url(str) {
    let b64 = str.replace(/-/g, "+").replace(/_/g, "/");
    while (b64.length % 4) b64 += "=";
    const bin = atob(b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return new TextDecoder("utf-8").decode(bytes);
  }

  function parseInviteFromHash() {
    const rawHash = (window.location.hash || "").replace(/^#/, "").trim();
    if (!rawHash) return { state: "none" };
    // Support either `#<base64url>` or `#localbeat://join/<base64url>`.
    const payload = rawHash.replace(/^localbeat:\/\/join\//i, "");
    if (!/^[A-Za-z0-9_-]+$/.test(payload)) {
      return { state: "invalid" };
    }
    try {
      const data = JSON.parse(decodeBase64Url(payload));
      if (!data || typeof data !== "object" || data.v !== 1 || !data.t || (!data.e && !data.l)) {
        return { state: "invalid" };
      }
      return {
        state: "valid",
        payload,
        uri: "localbeat://join/" + payload,
        role: String(data.r || "listener").toLowerCase(),
      };
    } catch (_) {
      return { state: "invalid" };
    }
  }

  function formatBytes(bytes) {
    if (!bytes || bytes <= 0) return "";
    const mb = bytes / (1024 * 1024);
    return mb >= 1 ? mb.toFixed(1) + " MB" : Math.round(bytes / 1024) + " KB";
  }

  function pickAsset(assets, matchers) {
    if (!Array.isArray(assets)) return null;
    for (const test of matchers) {
      const found = assets.find((a) => a && a.name && a.browser_download_url && test(a.name.toLowerCase()));
      if (found) return found;
    }
    return null;
  }

  async function fetchLatestRelease(cfg) {
    const url = `https://api.github.com/repos/${cfg.owner}/${cfg.repo}/releases/latest`;
    const res = await fetch(url, { headers: { Accept: "application/vnd.github+json" } });
    if (!res.ok) throw new Error("HTTP " + res.status);
    return res.json();
  }

  const state = {
    os: detectOS(),
    invite: parseInviteFromHash(),
    downloads: {
      android: {
        id: "android",
        name: "LocalBeat for Android",
        sub: "Android phone & Android Auto (.apk)",
        btnLabel: "Download LocalBeat for Android",
        url: REPOS.android.fallbackUrl,
        meta: "Latest release on GitHub",
        tip: 'When your phone asks to install an app from your browser, tap <strong>Settings → Allow from this source</strong>, then <strong>Install</strong>.',
      },
      windows: {
        id: "windows",
        name: "LocalBeat Studio for Windows",
        sub: "Windows 10 / 11 installer (no admin prompt)",
        btnLabel: "Download LocalBeat Studio for Windows",
        url: REPOS.studio.fallbackUrl,
        meta: "Latest release on GitHub",
        tip: 'Run the installer — it installs just for your account (no administrator password needed) and registers <strong>localbeat://</strong> links automatically.',
      },
      linux: {
        id: "linux",
        name: "LocalBeat Studio for Linux",
        sub: "Fedora RPM & Linux package",
        btnLabel: "Download LocalBeat Studio for Linux",
        url: REPOS.studio.fallbackUrl,
        meta: "Latest release on GitHub",
        tip: "Install the package with your software manager or terminal, then come back here and click <strong>Open in LocalBeat Studio</strong>.",
      },
    },
  };

  function primaryPlatformKey() {
    if (state.invite.state === "valid" && state.invite.role === "admin") {
      return state.os === "linux" ? "linux" : "windows";
    }
    if (state.os === "android" || state.os === "windows" || state.os === "linux") {
      return state.os;
    }
    return null;
  }

  function renderDownloads() {
    const primaryKey = primaryPlatformKey();
    const primaryWrap = document.getElementById("primary-download");
    const otherDetails = document.getElementById("other-platforms");
    const otherList = document.getElementById("other-platform-list");

    if (primaryKey) {
      const d = state.downloads[primaryKey];
      primaryWrap.classList.remove("hidden");
      document.getElementById("primary-dl-btn").href = d.url;
      document.getElementById("primary-dl-btn").textContent = d.btnLabel;
      document.getElementById("primary-dl-meta").textContent = d.meta;
      const tipEl = document.getElementById("primary-dl-tip");
      if (d.tip) {
        tipEl.innerHTML = d.tip;
        tipEl.classList.remove("hidden");
      } else {
        tipEl.classList.add("hidden");
      }
      otherDetails.open = state.invite.state === "none";
    } else {
      primaryWrap.classList.add("hidden");
      otherDetails.open = true;
    }

    otherList.innerHTML = "";
    const keys = ["android", "windows", "linux"].filter((k) => k !== primaryKey);
    for (const k of keys) {
      const d = state.downloads[k];
      const row = document.createElement("div");
      row.className = "platform-row";
      row.innerHTML = `
        <div class="platform-info">
          <strong>${d.name}</strong>
          <span>${d.sub}${d.version ? " · " + d.version : ""}</span>
        </div>
        <a class="dl-pill" href="${d.url}">Download</a>
      `;
      otherList.appendChild(row);
    }
  }

  function renderInviteUI() {
    const inv = state.invite;
    const banner = document.getElementById("status-banner");
    const step1Num = document.getElementById("step1-num");
    const step1Title = document.getElementById("step1-title");
    const step1Lead = document.getElementById("step1-lead");
    const enrollCard = document.getElementById("enroll-card");
    const subtitle = document.getElementById("brand-subtitle");

    banner.classList.add("hidden");
    banner.classList.remove("warn", "ok");

    if (inv.state === "valid") {
      step1Num.classList.remove("hidden");
      enrollCard.classList.remove("hidden");
      const isAdmin = inv.role === "admin";
      subtitle.textContent = isAdmin
        ? "You’ve been invited as an Admin"
        : "You’ve been invited to a LocalBeat music library";
      step1Title.textContent = "Install the LocalBeat app";
      step1Lead.textContent = "Already have it installed? Skip straight to Step 2 below.";

      const openBtn = document.getElementById("open-app-btn");
      openBtn.href = inv.uri;
      openBtn.textContent = isAdmin
        ? "Open in LocalBeat Studio"
        : state.os === "android"
          ? "Open in LocalBeat"
          : state.os === "windows" || state.os === "linux"
            ? "Open in LocalBeat Studio"
            : "Open in LocalBeat";

      if (isAdmin && (state.os === "android" || state.os === "ios")) {
        banner.textContent =
          "This is an Admin invite for LocalBeat Studio on a Windows or Linux computer. Phones use Listener invites.";
        banner.classList.add("warn");
        banner.classList.remove("hidden");
      } else if (state.os === "ios" || state.os === "macos") {
        banner.textContent =
          "LocalBeat runs on Android phones and Windows or Linux computers. Open this link on one of those devices to connect.";
        banner.classList.add("warn");
        banner.classList.remove("hidden");
      }

      const copyBtn = document.getElementById("copy-invite-btn");
      copyBtn.onclick = async function () {
        try {
          await navigator.clipboard.writeText(inv.uri);
          copyBtn.textContent = "Copied! Paste it into LocalBeat";
          setTimeout(() => {
            copyBtn.textContent = "Copy invite link instead";
          }, 3000);
        } catch (_) {
          window.prompt("Copy this invite link and paste it into LocalBeat:", inv.uri);
        }
      };
    } else if (inv.state === "invalid") {
      step1Num.classList.add("hidden");
      enrollCard.classList.add("hidden");
      subtitle.textContent = "Personal music player & library";
      step1Title.textContent = "Download LocalBeat";
      step1Lead.textContent = "Get the app for your phone or computer.";
      banner.textContent =
        "This invite link looks incomplete or corrupted. Try opening the full link again, or ask your server admin for a new invite.";
      banner.classList.add("warn");
      banner.classList.remove("hidden");
    } else {
      step1Num.classList.add("hidden");
      enrollCard.classList.add("hidden");
      subtitle.textContent = "Personal music player & library";
      step1Title.textContent = "Download LocalBeat";
      step1Lead.textContent = "Listen to your personal music library on Android, Windows, or Linux.";
    }
  }

  async function hydrateReleases() {
    await Promise.allSettled([
      fetchLatestRelease(REPOS.android).then((rel) => {
        const apk = pickAsset(rel.assets, [
          (n) => n.endsWith(".apk") && n.includes("connected"),
          (n) => n.endsWith(".apk") && !n.includes("offline"),
          (n) => n.endsWith(".apk"),
        ]);
        if (apk) {
          state.downloads.android.url = apk.browser_download_url;
          state.downloads.android.version = rel.tag_name || "";
          const parts = [rel.tag_name, formatBytes(apk.size)].filter(Boolean);
          state.downloads.android.meta = parts.join(" · ") || "Latest release";
        }
      }),
      fetchLatestRelease(REPOS.studio).then((rel) => {
        const win = pickAsset(rel.assets, [
          (n) => n.endsWith(".exe") && n.includes("setup"),
          (n) => n.endsWith(".exe"),
          (n) => n.endsWith(".msi"),
          (n) => n.endsWith(".zip") && n.includes("win"),
        ]);
        if (win) {
          state.downloads.windows.url = win.browser_download_url;
          state.downloads.windows.version = rel.tag_name || "";
          const parts = [rel.tag_name, formatBytes(win.size)].filter(Boolean);
          state.downloads.windows.meta = parts.join(" · ") || "Latest release";
        }
        const lin = pickAsset(rel.assets, [
          (n) => n.endsWith(".rpm"),
          (n) => n.endsWith(".deb"),
          (n) => n.endsWith(".appimage"),
          (n) => n.endsWith(".tar.gz") && n.includes("linux"),
        ]);
        if (lin) {
          state.downloads.linux.url = lin.browser_download_url;
          state.downloads.linux.version = rel.tag_name || "";
          const parts = [rel.tag_name, formatBytes(lin.size)].filter(Boolean);
          state.downloads.linux.meta = parts.join(" · ") || "Latest release";
        }
      }),
    ]);
    renderDownloads();
  }

  function init() {
    renderInviteUI();
    renderDownloads();
    hydrateReleases();
    window.addEventListener("hashchange", function () {
      state.invite = parseInviteFromHash();
      renderInviteUI();
      renderDownloads();
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
