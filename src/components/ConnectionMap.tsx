import { useEffect, useState } from "react";
import { MapContainer, TileLayer, Marker, useMap } from "react-leaflet";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import { fetchIpLocation, extractServerIpFromLink } from "../utils/geoip";
import type { GeoLocation } from "../utils/geoip";
import { Server } from "lucide-react";
import { renderToStaticMarkup } from "react-dom/server";

// Fix for default marker icon in Leaflet
delete (L.Icon.Default.prototype as any)._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl: "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-icon-2x.png",
  iconUrl: "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-icon.png",
  shadowUrl: "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-shadow.png",
});



const serverIconHtml = renderToStaticMarkup(<div style={{color: '#fff', background: '#3f3f46', borderRadius: '50%', padding: '4px', border: '1px solid #52525b'}}><Server size={16} /></div>);
const serverIcon = L.divIcon({
  html: serverIconHtml,
  className: 'custom-leaflet-icon',
  iconSize: [24, 24],
  iconAnchor: [12, 12],
});

interface ConnectionMapProps {
  status: "disconnected" | "connecting" | "connected";
  subLink: string;
}

// A component to handle programmatic zooming
function MapController({
  serverLoc,
  status
}: {
  serverLoc: GeoLocation | null;
  status: string;
}) {
  const map = useMap();

  useEffect(() => {
    if (serverLoc) {
      map.flyTo([serverLoc.lat, serverLoc.lon], 5, { duration: 1.5 });
    }
  }, [serverLoc, status, map]);

  return null;
}

export default function ConnectionMap({ status, subLink }: ConnectionMapProps) {
  const [serverLoc, setServerLoc] = useState<GeoLocation | null>(null);

  useEffect(() => {
    async function loadLocations() {
      // Dynamically fetch server location from the link config
      const serverIp = await extractServerIpFromLink(subLink);
      if (serverIp) {
        const sLoc = await fetchIpLocation(serverIp);
        if (sLoc) setServerLoc(sLoc);
      }
    }
    if (subLink) loadLocations();
  }, [subLink]);

  // Fallback / Guarantee: When connected, our traffic goes through the VPN.
  // Wait 2 seconds for tunnel to stabilize, then fetch the active IP!
  useEffect(() => {
    async function fetchLiveServerLocation() {
      if (status === 'connected') {
        // Wait 2 seconds for Android VpnService to route all packets
        await new Promise(resolve => setTimeout(resolve, 2000));
        
        const sLoc = await fetchIpLocation(); // Uses VPN IP
        if (sLoc) {
          setServerLoc(sLoc);
        }
      }
    }
    fetchLiveServerLocation();
  }, [status]);

  const defaultCenter: [number, number] = serverLoc ? [serverLoc.lat, serverLoc.lon] : [20, 0];

  return (
    <div className="w-full h-48 sm:h-64 rounded-2xl overflow-hidden border border-white/10 shadow-sm relative z-0">
      <MapContainer 
        center={defaultCenter} 
        zoom={2} 
        zoomControl={false}
        scrollWheelZoom={false} 
        style={{ height: '100%', width: '100%', background: '#0a0a0a' }}
        attributionControl={false}
      >
        {/* Dark Matter theme from CartoDB */}
        <TileLayer
          url="https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png"
        />

        {serverLoc && (
          <Marker position={[serverLoc.lat, serverLoc.lon]} icon={serverIcon} />
        )}

        <MapController serverLoc={serverLoc} status={status} />
      </MapContainer>
    </div>
  );
}
