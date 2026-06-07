import { CapacitorHttp } from '@capacitor/core';

export interface GeoLocation {
  query: string;
  status: string;
  country: string;
  city: string;
  lat: number;
  lon: number;
  isp: string;
}

/**
 * Fetches the geographical location of an IP address.
 * If no IP is provided, it fetches the client's current location.
 */
export async function fetchIpLocation(ip: string = ''): Promise<GeoLocation | null> {
  try {
    const url = ip ? `https://ipwho.is/${ip}` : `https://ipwho.is/`;
    const response = await CapacitorHttp.request({ method: 'GET', url });
    
    // CapacitorHttp parses JSON automatically into response.data
    const data = typeof response.data === 'string' ? JSON.parse(response.data) : response.data;

    if (data && data.success && data.latitude && data.longitude) {
      return {
        lat: data.latitude,
        lon: data.longitude,
        city: data.city || '',
        country: data.country || '',
        isp: data.connection?.isp || '',
        query: data.ip || '',
        status: 'success'
      };
    }
  } catch (error) {
    console.error("Failed to fetch IP location:", error);
  }

  // Fallback to empty if not found, let the map handle it
  return null;
}

/**
 * Parses the subscription link or fetches the worker config to extract the server IP/domain dynamically.
 */
export async function extractServerIpFromLink(link: string): Promise<string | null> {
  try {
    let targetUrl = link;
    if (link.startsWith("hy2://") || link.startsWith("dhv2://") || link.startsWith("vless://")) {
      const token = link.split("://")[1]?.split("@")[0];
      if (token) {
        targetUrl = `https://proud-union-953f.octd258.workers.dev/?token=${token}`;
      }
    } else if (link.includes("spicypepper.app/api/sub")) {
      const urlObj = new URL(link);
      const token = urlObj.searchParams.get("token");
      if (token) {
        targetUrl = `https://proud-union-953f.octd258.workers.dev/?token=${token}`;
      }
    }

    // Try direct link regex
    const regex = /:\/\/(?:[^@]+@)?([^:]+):/;
    const match = targetUrl.match(regex);
    if (match && match[1] && match[1].match(/^(\d{1,3}\.){3}\d{1,3}$/)) {
      return match[1];
    }

    // Fetch config to extract dynamic server IP using native HTTP to bypass CORS
    if (targetUrl.startsWith("http")) {
      const response = await CapacitorHttp.request({ method: 'GET', url: targetUrl });
      if (response.status === 200 && response.data) {
        const text = typeof response.data === 'string' ? response.data : JSON.stringify(response.data);
        try {
          const decoded = atob(text.trim()); // Base64 decode
          const vlessMatch = decoded.match(/:\/\/(?:[^@]+@)?([^:]+):/);
          if (vlessMatch && vlessMatch[1]) {
            return vlessMatch[1];
          }
        } catch(e) {
          console.error("Failed to decode base64:", e);
        }
      }
    }
  } catch (e) {
    console.error("Failed to parse sub link:", e);
  }
  return null;
}
