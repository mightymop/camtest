#!/usr/bin/env python3
# pcap_forwarder_scapy.py
# Requirements: scapy (pip install scapy)
#
# Reads a PCAP, uses Scapy to decode frames (Ethernet / Radiotap/802.11),
# reassembles IPv4 fragments, forwards UDP payloads to target IP:port,
# and optionally writes reconstructed IP packets to ip_only.pcap.

import sys
import socket
import time
from collections import defaultdict
from datetime import datetime, timezone

from scapy.all import PcapReader, PcapWriter, IP, UDP, Raw, conf

# ---------- config ----------
DEFAULT_TARGET_IP = "192.168.1.20"
DEFAULT_TARGET_PORT = 2224
FRAGMENT_TIMEOUT_SEC = 60.0
WRITE_IP_PCAP = True   # set False if you don't want a file
VERBOSE = True
# ----------------------------

from scapy.all import RadioTap, Dot11, LLC, SNAP, IP, UDP, Raw

def extract_ip_from_80211(pkt):
    """Return IP packet from 802.11 frame, or None."""
    if not pkt.haslayer(Dot11):
        return None
    dot11 = pkt[Dot11]

    # nur Data Frames
    fc = dot11.FCfield
    subtype = dot11.subtype
    type_ = dot11.type
    if type_ != 2:
        return None

    # LLC / SNAP abziehen
    payload = dot11.payload
    if payload.haslayer(LLC):
        llc = payload[LLC]
        if llc.haslayer(SNAP):
            snap = llc[SNAP]
            if snap.code == 0x0800:  # IPv4
                return IP(bytes(snap.payload))
    # manchmal Raw direkt IP
    if payload.haslayer(IP):
        return payload[IP]
    return None
    
def now_ms():
    return int(time.time() * 1000)

class FragmentBucket:
    """Hold fragments for one (src,dst,id,proto)"""
    def __init__(self, first_pkt):
        self.parts = []  # list of (offset_bytes, bytes_payload, mf_flag)
        self.first_pkt = first_pkt  # scapy IP object (header from first seen fragment)
        self.last_seen = time.time()

    def add(self, offset, data_bytes, mf):
        self.parts.append((offset, data_bytes, mf))
        self.last_seen = time.time()

    def try_assemble(self):
        """Return assembled payload bytes if complete, else None"""
        # Need at least one part with mf==False (last fragment)
        if not any(not p[2] for p in self.parts):
            return None

        # find overall size
        max_end = 0
        for off, data, mf in self.parts:
            end = off + len(data)
            if end > max_end:
                max_end = end

        buf = bytearray(max_end)
        present = [False] * max_end
        for off, data, mf in self.parts:
            buf[off:off+len(data)] = data
            for i in range(off, off+len(data)):
                present[i] = True

        if not all(present):
            return None

        return bytes(buf)

def fragment_key(ip_pkt):
    return (ip_pkt.src, ip_pkt.dst, ip_pkt.id, ip_pkt.proto)

def is_fragment(ip_pkt):
    # scapy uses flags (flags.MF) and frag
    mf = bool(ip_pkt.flags.MF)
    offset = int(ip_pkt.frag)  # in 8-byte units
    return (mf or offset != 0)

def run(pcap_path, target_ip, target_port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    # fragment reassembly store
    buckets = dict()  # key -> FragmentBucket
    stats = {
        'frames': 0,
        'ip_seen': 0,
        'udp_seen': 0,
        'udp_forwarded': 0,
        'assembled_ips': 0,
        'written_ip_packets': 0
    }

    ip_pcap_writer = None
    if WRITE_IP_PCAP:
        ip_pcap_writer = PcapWriter("ip_only_reassembled.pcap", append=False, sync=True)

    # stream through pcap
    if VERBOSE:
        print(f"[{datetime.now().isoformat()}] Opening {pcap_path} ... (Scapy v{conf.version})")
    reader = PcapReader(pcap_path)
    last_cleanup = time.time()

    for pkt in reader:
        stats['frames'] += 1

        # Scapy automatically decodes Radiotap/Dot11 -> IP if present
        ip_pkt = None
        if IP in pkt:
            ip_pkt = pkt[IP]
        else:
            ip_pkt = extract_ip_from_80211(pkt)
            # sometimes payload lives in Raw and ip not decoded; attempt decode
            # but normally Scapy handles this, so skip
          
        stats['ip_seen'] += 1

        # Fragment handling
        if is_fragment(ip_pkt):
            key = fragment_key(ip_pkt)
            bucket = buckets.get(key)
            if bucket is None:
                bucket = FragmentBucket(ip_pkt)
                buckets[key] = bucket

            frag_offset_bytes = int(ip_pkt.frag) * 8
            mf_flag = bool(ip_pkt.flags.MF)
            # ip_pkt.payload is the transport payload or raw; get bytes
            try:
                payload_bytes = bytes(ip_pkt.payload)
            except Exception:
                payload_bytes = b''

            bucket.add(frag_offset_bytes, payload_bytes, mf_flag)

            assembled_payload = bucket.try_assemble()
            if assembled_payload is not None:
                # create a synthetic full IP packet based on first header
                base = bucket.first_pkt.copy()
                base.remove_payload()  # remove old payload
                base.len = 20 + len(assembled_payload)
                base.flags = 0
                base.frag = 0
                base.chksum = None
                # set payload to assembled bytes (as Raw so writer can serialize)
                base.add_payload(Raw(assembled_payload))

                # replace ip_pkt with assembled packet
                ip_pkt = base
                stats['assembled_ips'] += 1
                # remove bucket
                del buckets[key]

        # now ip_pkt is either original (not fragmented) or assembled full IP
        # check UDP
        if UDP in ip_pkt:
            stats['udp_seen'] += 1
            udp_layer = ip_pkt[UDP]
            try:
                payload = bytes(udp_layer.payload)
            except Exception:
                payload = b''

            if payload:
                # forward
                try:
                    sock.sendto(payload, (target_ip, int(target_port)))
                    stats['udp_forwarded'] += 1
                except Exception as e:
                    print(f"[WARN] sendto failed: {e}")

        # optionally write the IP packet (with UDP or any)
        if ip_pcap_writer is not None:
            try:
                ip_pcap_writer.write(ip_pkt)
                stats['written_ip_packets'] += 1
            except Exception:
                pass

        # periodic cleanup of old fragment buckets
        if time.time() - last_cleanup > 5.0:
            stale = []
            now = time.time()
            for k, b in buckets.items():
                if now - b.last_seen > FRAGMENT_TIMEOUT_SEC:
                    stale.append(k)
            for k in stale:
                del buckets[k]
            last_cleanup = time.time()

        # progress print
        if VERBOSE and stats['frames'] % 1000 == 0:
            print(f"[{stats['frames']}] frames processed - IP:{stats['ip_seen']}, UDP seen:{stats['udp_seen']}, forwarded:{stats['udp_forwarded']}, assembled:{stats['assembled_ips']}")

    reader.close()
    if ip_pcap_writer:
        ip_pcap_writer.close()

    print("=== Done ===")
    print(f"Frames processed: {stats['frames']}")
    print(f"IP packets seen:  {stats['ip_seen']}")
    print(f"UDP packets seen: {stats['udp_seen']}")
    print(f"UDP forwarded:   {stats['udp_forwarded']}")
    print(f"IP assembled:     {stats['assembled_ips']}")
    print(f"IP written:       {stats['written_ip_packets']} (ip_only_reassembled.pcap)")
    return stats

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("Usage: python3 pcap_forwarder_scapy.py <pcap_file> [target_ip] [target_port]")
        sys.exit(1)
    pcap_file = sys.argv[1]
    target_ip = sys.argv[2] if len(sys.argv) >= 3 else DEFAULT_TARGET_IP
    target_port = int(sys.argv[3]) if len(sys.argv) >= 4 else DEFAULT_TARGET_PORT

    run(pcap_file, target_ip, target_port)
