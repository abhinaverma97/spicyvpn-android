import { useState, useEffect } from "react";
import { Preferences } from "@capacitor/preferences";
import { VpnPlugin } from "./VpnPlugin";
import type { Stats } from "./VpnPlugin";
import { Settings, Activity, Home, ChevronRight, Copy } from "lucide-react";
import { motion, AnimatePresence } from "framer-motion";
import ConnectionMap from "./components/ConnectionMap";

export default function App() {
  const [subLink, setSubLink] = useState("");
  const [activeTab, setActiveTab] = useState<"home" | "logs" | "settings">("home");
  const [status, setStatus] = useState<"disconnected" | "connecting" | "connected">("disconnected");
  const [visualStatus, setVisualStatus] = useState<"disconnected" | "connecting" | "connected">("disconnected");
  const [stats, setStats] = useState<Stats | null>(null);
  const [error, setError] = useState("");

  const [logs, setLogs] = useState<string[]>([]);

  useEffect(() => {
    async function loadSettings() {
      const { value: savedLink } = await Preferences.get({ key: "subLink" });

      if (savedLink) {
        setSubLink(savedLink);
        fetchStats(savedLink).catch(console.error);
      } else {
        setActiveTab("settings");
      }

      try {
        const res = await VpnPlugin.getStatus();
        setStatus(res.status as any);
      } catch (_) {}
    }
    loadSettings();

    const unlistenLogs = VpnPlugin.addListener("vpnLog", (event: any) => {
      setLogs((prev) => [...prev, event.message].slice(-200));
    });

    const unlistenStatus = VpnPlugin.addListener("vpnStatusChanged", (event: any) => {
      setStatus(event.status as any);
    });

    return () => {
      unlistenStatus.then(h => h.remove()).catch(() => {});
      unlistenLogs.then(h => h.remove()).catch(() => {});
    };
  }, []);

  // Smooth out backend status flashes (e.g. connecting -> disconnected -> connecting)
  useEffect(() => {
    let timeoutId: any;
    
    if (status === "disconnected") {
      // If we drop to disconnected, wait 1.5 seconds before showing it visually
      // This hides the backend's initialization 'disconnected' flash
      timeoutId = setTimeout(() => {
        setVisualStatus("disconnected");
      }, 1500);
    } else {
      // Immediately show connecting or connected
      setVisualStatus(status);
    }

    return () => clearTimeout(timeoutId);
  }, [status]);

  async function fetchStats(link: string): Promise<string> {
    let targetUrl = link;
    if (link.startsWith("hy2://") || link.startsWith("dhv2://") || link.startsWith("vless://")) {
      const token = link.split("://")[1]?.split("@")[0];
      if (!token) return link;
      targetUrl = `https://proud-union-953f.octd258.workers.dev/?token=${token}`;
    }

    if (!targetUrl.startsWith("http")) return targetUrl;

    if (targetUrl.includes("spicypepper.app/api/sub")) {
      try {
        const urlObj = new URL(targetUrl);
        const token = urlObj.searchParams.get("token");
        if (token) {
          targetUrl = `https://proud-union-953f.octd258.workers.dev/?token=${token}`;
        }
      } catch (e) {
        console.error("URL parse error", e);
      }
    }

    try {
      const res = await VpnPlugin.fetchStats({ url: targetUrl });
      setStats(res);
      setError("");
      return targetUrl;
    } catch (e: any) {
      console.warn("Worker fetch failed, trying direct fallback...", e);
      if (targetUrl.includes("workers.dev")) {
        try {
          const urlObj = new URL(targetUrl);
          const token = urlObj.searchParams.get("token");
          if (token) {
            const fallbackUrl = `https://spicypepper.app/api/sub?token=${token}`;
            const res = await VpnPlugin.fetchStats({ url: fallbackUrl });
            setStats(res);
            setError("");
            return fallbackUrl;
          }
        } catch (err) {
          console.error("Fallback error", err);
        }
      }
      setError(e.toString());
      throw e;
    }
  }

  async function saveLink(e: React.FormEvent) {
    e.preventDefault();
    if (!subLink) return;
    try {
      await Preferences.set({ key: "subLink", value: subLink });
      setActiveTab("home");
      await fetchStats(subLink);
    } catch (err: any) {
      console.error("Save error", err);
      setError("Failed to save settings: " + err.toString());
    }
  }

  async function resetConfig() {
    if (!confirm("Reset configuration?")) return;
    try {
      await Preferences.remove({ key: "subLink" });
      setSubLink("");
      setStats(null);
      setError("");
      setActiveTab("settings");
      if (status === "connected") await disconnect();
    } catch (err: any) {
      setError("Reset failed: " + err.toString());
    }
  }

  async function toggleVpn() {
    if (!subLink) {
      setActiveTab("settings");
      return;
    }

    if (status === "connected" || status === "connecting") {
      await disconnect();
    } else {
      await connect();
    }
  }

  async function connect() {
    setStatus("connecting");
    setError("");
    setLogs([]);
    try {
      const workingUrl = await fetchStats(subLink);
      await VpnPlugin.startVPN({ url: workingUrl });
    } catch (e: any) {
      setError(e.toString());
      setStatus("disconnected");
    }
  }

  async function disconnect() {
    try {
      await VpnPlugin.stopVPN();
    } catch (e) {
      console.error(e);
    }
    setStatus("disconnected");
  }

  function formatBytes(bytes: number) {
    if (bytes >= 1073741824) return (bytes / 1073741824).toFixed(2) + " GB";
    if (bytes >= 1048576) return (bytes / 1048576).toFixed(2) + " MB";
    return (bytes / 1024).toFixed(0) + " KB";
  }

  function daysLeft(expiresAt: number) {
    const diff = expiresAt * 1000 - Date.now();
    return Math.max(0, Math.floor(diff / (1000 * 60 * 60 * 24)));
  }

  const usedBytes = stats ? (stats.upload + stats.download) : 0;
  const isExpired = stats && stats.expire > 0 && stats.expire * 1000 < Date.now();
  const isOutOfData = stats && stats.total > 0 && usedBytes >= stats.total;

  const displayStatus = () => {
    if (visualStatus === 'disconnected') return 'Not connected';
    if (visualStatus === 'connecting') return 'Connecting...';
    return 'Connected';
  };



  return (
    <div className="relative w-full h-screen bg-black text-white overflow-hidden flex flex-col pt-safe">
      <main className="relative z-10 w-full flex-1 overflow-hidden">
        <AnimatePresence mode="wait">
          {activeTab === "home" && (
            <motion.div key="home" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="flex flex-col h-full bg-black">
              
              {/* Header */}
              <div className="w-full pt-6 pb-4 px-6 flex items-center justify-center border-b border-white/5">
                <span className="text-2xl font-black tracking-tight text-white">
                  spicyvpn
                </span>
              </div>

              <div className="flex-1 overflow-y-auto px-6 py-8 flex flex-col gap-6 scrollbar-hide">
                {/* Status Hero */}
                <div className="flex flex-col items-start gap-2 mb-2">
                  <h1 className="text-4xl font-bold tracking-tight text-white">
                    {displayStatus()}
                  </h1>
                  {(isExpired || isOutOfData) && (
                    <p className="text-sm text-red-500 font-medium">Subscription inactive</p>
                  )}
                  {error && (
                    <p className="text-sm text-red-500 font-medium">{error}</p>
                  )}
                </div>

                {/* Connection Router Map */}
                <ConnectionMap status={status} subLink={subLink} />

                {/* Connection Details Receipt */}
                <div className="w-full flex flex-col border border-white/10 rounded-2xl bg-[#121212] overflow-hidden shadow-sm">
                  <div className="flex justify-between items-center p-5 border-b border-white/5">
                    <span className="text-white/50 font-medium text-sm">Data Usage</span>
                    <span className="text-white font-bold text-sm">{stats ? `${formatBytes(usedBytes)} / ${formatBytes(stats.total)}` : '--'}</span>
                  </div>
                  <div className="flex justify-between items-center p-5">
                    <span className="text-white/50 font-medium text-sm">Validity</span>
                    <span className="text-white font-bold text-sm">{stats ? `${daysLeft(stats.expire)} Days` : '--'}</span>
                  </div>
                </div>

                {/* Quick Actions */}
                <div className="w-full flex flex-col border border-white/10 rounded-2xl bg-[#121212] overflow-hidden shadow-sm">
                  <button onClick={resetConfig} className="flex justify-between items-center p-5 border-b border-white/5 hover:bg-white/5 transition-colors">
                    <span className="text-white/90 font-medium text-sm">Reset Configuration</span>
                    <ChevronRight className="w-4 h-4 text-white/40" />
                  </button>
                  <button onClick={() => window.open("https://spicypepper.app/dashboard", "_blank")} className="flex justify-between items-center p-5 hover:bg-white/5 transition-colors">
                    <span className="text-white/90 font-medium text-sm">Dashboard Website</span>
                    <ChevronRight className="w-4 h-4 text-white/40" />
                  </button>
                </div>
              </div>

              {/* Fixed Bottom Action */}
              <div className="w-full px-6 pt-4 pb-6 bg-gradient-to-t from-black via-black to-transparent">
                <button
                  onClick={toggleVpn}
                  disabled={visualStatus === 'connecting' || !!isExpired || !!isOutOfData}
                  className={`w-full py-4 rounded-xl font-bold tracking-wide flex items-center justify-center gap-3 transition-colors duration-200
                    ${visualStatus === 'connected'
                      ? 'bg-[#1A1A1A] text-white border border-white/10 hover:bg-[#222]'
                      : visualStatus === 'connecting'
                      ? 'bg-white/20 text-white/50 border border-transparent'
                      : isExpired || isOutOfData
                      ? 'bg-red-950 text-red-500/50 border border-transparent cursor-not-allowed'
                      : 'bg-white text-black border border-transparent hover:bg-gray-200'}`}
                >
                  {visualStatus === 'connected' ? 'Disconnect' : 'Connect'}
                </button>
              </div>
            </motion.div>
          )}

          {activeTab === "logs" && (
            <motion.div key="logs" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="flex flex-col h-full bg-black">
              <div className="w-full pt-6 pb-4 px-6 border-b border-white/5 flex justify-between items-center">
                <h2 className="text-2xl font-black text-white">Logs</h2>
                <button 
                  onClick={() => navigator.clipboard.writeText(logs.join(""))} 
                  className="text-white/50 hover:text-white transition-colors p-2"
                  title="Copy Logs"
                >
                  <Copy className="w-5 h-5" />
                </button>
              </div>
              <div className="flex-1 p-6 overflow-hidden flex flex-col">
                <div className="flex-1 bg-[#121212] border border-white/10 rounded-2xl p-4 font-mono text-xs text-white/70 overflow-y-auto whitespace-pre-wrap shadow-sm">
                  {logs.length === 0 ? "No logs available." : logs.join("")}
                </div>
              </div>
            </motion.div>
          )}

          {activeTab === "settings" && (
            <motion.div key="settings" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="flex flex-col h-full bg-black">
              <div className="w-full pt-6 pb-4 px-6 border-b border-white/5">
                <h2 className="text-2xl font-black text-white">Settings</h2>
              </div>
              <div className="flex-1 overflow-y-auto px-6 py-8 flex flex-col gap-8 scrollbar-hide">
                <form onSubmit={saveLink} className="flex flex-col gap-4">
                  <div className="flex flex-col gap-2">
                    <label className="text-sm font-bold text-white/60">Subscription Link</label>
                    <input
                      type="text"
                      value={subLink}
                      onChange={(e) => setSubLink(e.target.value)}
                      placeholder="https://..."
                      className="w-full bg-[#121212] border border-white/10 rounded-xl px-4 py-4 text-sm outline-none focus:border-white/30 transition-colors text-white shadow-sm"
                    />
                  </div>
                  <button type="submit" className="w-full bg-white text-black font-bold rounded-xl py-4 hover:bg-gray-200 transition-colors mt-2 shadow-sm">
                    Save Changes
                  </button>
                </form>
                
                <div className="border-t border-white/10 pt-8 flex flex-col gap-4">
                  <button onClick={resetConfig} className="w-full bg-red-950 text-red-500 font-bold rounded-xl py-4 hover:bg-red-900 transition-colors shadow-sm">
                    Reset Configuration
                  </button>
                </div>
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </main>

      {/* Solid Black Bottom Nav */}
      <div className="w-full bg-black border-t border-white/10 flex items-center justify-around pb-safe pt-2 px-2 z-50">
        <button onClick={() => setActiveTab('home')} className={`flex flex-col items-center gap-1.5 w-20 py-2 transition-colors ${activeTab === 'home' ? 'text-white' : 'text-white/40 hover:text-white/70'}`}>
          <Home className="w-6 h-6" strokeWidth={activeTab === 'home' ? 2.5 : 2} />
          <span className="text-[10px] font-bold">Home</span>
        </button>
        <button onClick={() => setActiveTab('logs')} className={`flex flex-col items-center gap-1.5 w-20 py-2 transition-colors ${activeTab === 'logs' ? 'text-white' : 'text-white/40 hover:text-white/70'}`}>
          <Activity className="w-6 h-6" strokeWidth={activeTab === 'logs' ? 2.5 : 2} />
          <span className="text-[10px] font-bold">Logs</span>
        </button>
        <button onClick={() => setActiveTab('settings')} className={`flex flex-col items-center gap-1.5 w-20 py-2 transition-colors ${activeTab === 'settings' ? 'text-white' : 'text-white/40 hover:text-white/70'}`}>
          <Settings className="w-6 h-6" strokeWidth={activeTab === 'settings' ? 2.5 : 2} />
          <span className="text-[10px] font-bold">Settings</span>
        </button>
      </div>

    </div>
  );
}
