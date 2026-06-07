import { registerPlugin } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';

export interface Stats {
  upload: number;
  download: number;
  total: number;
  expire: number;
  name?: string;
  email?: string;
}

export interface VpnPluginInterface {
  startVPN(options: { url: string }): Promise<{ success: boolean }>;
  stopVPN(): Promise<void>;
  getStatus(): Promise<{ status: string }>;
  getLogs(): Promise<{ logs: string }>;
  fetchStats(options: { url: string }): Promise<Stats>;
  getSubLink(): Promise<{ link: string }>;
  setSubLink(options: { link: string }): Promise<void>;
  addListener(eventName: string, listenerFunc: (event: any) => void): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}

export const VpnPlugin = registerPlugin<VpnPluginInterface>('SpicyVPN');
