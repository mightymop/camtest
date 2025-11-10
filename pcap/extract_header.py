import dpkt
import struct
import socket
from datetime import datetime
import sys

def debug_print(message, level=1):
    """Debug-Ausgaben mit Level-Steuerung"""
    if level <= 2:  # Nur wichtige Meldungen anzeigen
        print(message)

def extract_udp_payload(payload_bytes):
    """Return payload if it contains UDP packet, else None."""
    try:
        ip = dpkt.ip.IP(payload_bytes)
        if isinstance(ip.data, dpkt.udp.UDP):
            debug_print(f"  ✅ Found UDP packet: {len(ip.data.data)} bytes payload", 2)
            return bytes(ip.data.data)
    except (dpkt.UnpackError, IndexError, Exception) as e:
        debug_print(f"  ❌ IP/UDP parsing failed: {e}", 2)
        return None
    return None

def extract_udp_from_wlan_improved(buf):
    """Verbesserte WLAN-Verarbeitung mit verschiedenen Frame-Types"""
    debug_print(f"🔍 Analyzing WLAN frame ({len(buf)} bytes)", 1)
    
    if len(buf) < 4:
        debug_print("  ❌ Buffer too short for Radiotap header", 1)
        return None

    # Radiotap Header Länge
    try:
        radiotap_len = struct.unpack('<H', buf[2:4])[0]
        debug_print(f"  Radiotap header length: {radiotap_len} bytes", 2)
    except Exception as e:
        debug_print(f"  ❌ Could not read Radiotap length: {e}", 1)
        return None

    if radiotap_len >= len(buf):
        debug_print(f"  ❌ Radiotap length {radiotap_len} >= buffer length {len(buf)}", 1)
        return None

    # WLAN Frame nach Radiotap Header
    wlan_frame = buf[radiotap_len:]
    debug_print(f"  WLAN frame length: {len(wlan_frame)} bytes", 2)

    if len(wlan_frame) < 2:
        debug_print("  ❌ WLAN frame too short", 1)
        return None

    # Frame Control Field
    frame_control = struct.unpack('<H', wlan_frame[0:2])[0]
    frame_type = (frame_control >> 2) & 0x3
    frame_subtype = (frame_control >> 4) & 0xF
    to_ds = (frame_control & 0x0001) != 0
    from_ds = (frame_control & 0x0002) != 0
    
    debug_print(f"  Frame Control: 0x{frame_control:04x}", 2)
    debug_print(f"  Type: {frame_type}, Subtype: 0x{frame_subtype:x}, ToDS: {to_ds}, FromDS: {from_ds}", 2)

    # Nur Data Frames verarbeiten (Type=2)
    if frame_type != 2:
        debug_print(f"  ❌ Not a data frame (type={frame_type})", 1)
        return None

    # WLAN Header Länge berechnen
    # Basis-Header: FC(2) + Duration(2) + Addr1(6) + Addr2(6) + Addr3(6) + SeqCtrl(2) = 24 bytes
    base_header_len = 24
    addr4_present = (to_ds and from_ds)  # Beide DS flags gesetzt = 4 Adressen
    
    if addr4_present:
        base_header_len += 6  # Addr4 hinzufügen
    
    # QoS Field (bei QoS Data Frames)
    if frame_subtype in [0x08, 0x09, 0x0a, 0x0b]:  # QoS Data Frames
        base_header_len += 2
    
    debug_print(f"  Calculated WLAN header length: {base_header_len} bytes", 2)

    if len(wlan_frame) < base_header_len + 8: 
        # + LLC/SNAP
        debug_print(f"  ❌ WLAN frame too short for header+LLC", 1)
        return None

    # LLC/SNAP Header
    llc_start = base_header_len
    llc = wlan_frame[llc_start:llc_start+8]
    
    debug_print(f"  LLC/SNAP: {' '.join(f'{b:02x}' for b in llc)}", 2)
    
    # SNAP Header prüfen
    if llc[0:3] != b'\xaa\xaa\x03':
        debug_print(f"  ❌ Invalid SNAP header", 1)
        # Versuche trotzdem IPv4 zu finden
        debug_print(f"  Trying to find IP packet directly...", 2)
        
        # Suche nach IP Header (Version 4, IHL mindestens 5)
        for offset in range(base_header_len, min(base_header_len + 50, len(wlan_frame) - 20)):
            if wlan_frame[offset] >> 4 == 4:  # IPv4
                debug_print(f"  Found IP packet at offset {offset}", 2)
                ip_packet = wlan_frame[offset:]
                return extract_udp_payload(ip_packet)
        return None

    # Ethernet Type im SNAP
    eth_type = llc[6:8]
    debug_print(f"  Ethernet Type: 0x{eth_type.hex()}", 2)

    if eth_type != b'\x08\x00':  # IPv4
        debug_print(f"  ❌ Not IPv4 (0x0800)", 1)
        return None

    # IP Packet startet nach LLC/SNAP
    ip_start = llc_start + 8
    ip_packet = wlan_frame[ip_start:]
    
    debug_print(f"  IP packet starts at offset {ip_start}, length: {len(ip_packet)} bytes", 2)
    
    return extract_udp_payload(ip_packet)

def process_packet_improved(buf):
    """Verbesserte Paketverarbeitung mit automatischer Format-Erkennung"""
    # Ethernet case (direkt)
    try:
        eth = dpkt.ethernet.Ethernet(buf)
        if isinstance(eth.data, dpkt.ip.IP) and isinstance(eth.data.data, dpkt.udp.UDP):
            debug_print("✅ Found Ethernet + IP + UDP packet", 1)
            return bytes(eth.data.data.data)
    except (dpkt.UnpackError, IndexError, Exception) as e:
        debug_print(f"Ethernet parsing failed: {e}", 2)

    # WLAN/Radiotap case
    return extract_udp_from_wlan_improved(buf)

def check_pcap_format(pcap_file):
    """Überprüft das tatsächliche Format der PCAP-Datei"""
    print(f"\n🔍 Checking PCAP Format: {pcap_file}")
    print("=" * 60)
    
    format_stats = {
        'ethernet': 0,
        'wlan_radiotap': 0,
        'wlan_prism': 0,
        'wlan_pcap': 0,
        'other': 0,
        'total': 0
    }
    
    with open(pcap_file, 'rb') as f:
        try:
            pcap = dpkt.pcap.Reader(f)
            
            for i, (ts, buf) in enumerate(pcap):
                if i >= 100:  # Nur erste 10 Pakete analysieren
                    break
                    
                format_stats['total'] += 1
                print(f"\n--- Packet {i+1} ---")
                print(f"Frametick: {ts}")
                print(f"Length: {len(buf)} bytes")
                
                # Hex dump der ersten 32 Bytes
                hex_dump = ' '.join(f'{b:02x}' for b in buf[:32])
                print(f"Hex (first 32): {hex_dump}")
                
                # Format-Erkennung
                if len(buf) >= 14:
                    # Ethernet frame? (erste 12 Bytes MAC, dann EtherType)
                    eth_type = buf[12:14]
                    if eth_type in [b'\x08\x00', b'\x08\x06', b'\x86\xdd']:  # IPv4, ARP, IPv6
                        format_stats['ethernet'] += 1
                        print("📡 Format: Ethernet")
                        continue
                
                if len(buf) >= 8:
                    # Radiotap? (beginnt mit Version 0x00)
                    if buf[0] == 0x00 and buf[1] == 0x00:
                        format_stats['wlan_radiotap'] += 1
                        print("📡 Format: WLAN with Radiotap header")
                        continue
                    
                    # Prism header? (beginnt oft mit 0x44 oder 0x41)
                    if buf[0] in [0x44, 0x41] and buf[1] == 0x00:
                        format_stats['wlan_prism'] += 1
                        print("📡 Format: WLAN with Prism header")
                        continue
                
                # Plain WLAN pcap?
                if len(buf) >= 24:
                    frame_control = struct.unpack('<H', buf[0:2])[0] if len(buf) >= 2 else 0
                    frame_type = (frame_control >> 2) & 0x3
                    if frame_type in [0, 1, 2]:  # Management, Control, Data
                        format_stats['wlan_pcap'] += 1
                        print("📡 Format: Plain WLAN")
                        continue
                
                format_stats['other'] += 1
                print("📡 Format: Unknown")
                
        except Exception as e:
            print(f"❌ Error reading PCAP: {e}")
            return None
    
    print(f"\n📊 Format Statistics:")
    print(f"   Ethernet: {format_stats['ethernet']}")
    print(f"   WLAN Radiotap: {format_stats['wlan_radiotap']}")
    print(f"   WLAN Prism: {format_stats['wlan_prism']}")
    print(f"   WLAN Plain: {format_stats['wlan_pcap']}")
    print(f"   Other: {format_stats['other']}")
    print(f"   Total: {format_stats['total']}")
    
    return format_stats

def analyze_headers_to_file(pcap_file, output_file, num_packets=0):
    """Writes UDP Payload headers line by line to a file"""
    print(f"\n📝 Writing UDP Headers to {output_file}")
    
    successful_packets = 0
    total_packets = 0

    with open(pcap_file, 'rb') as f:
        pcap = dpkt.pcap.Reader(f)
        with open(output_file, 'w') as out:
            out.write("# UDP Payload Header Analysis\n")
            out.write(f"# PCAP File: {pcap_file}\n")
            out.write(f"# Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
            out.write("# Format: Nr | TotalSize ||| Type | Reserved | Blocksize | Sequence | Frametick | Offset | Reserved ||| HasJPEG\n")
            out.write("#                        CALCULATED||| ---------------- RTP HEADER BYTES ---------------------- ||| PAYLOAD\n")
            out.write("#" + "="*100 + "\n")

            packet_count = 0

            for ts, buf in pcap:
                total_packets += 1
                payload = process_packet_improved(buf)
                if not payload:
                    continue
                    
                if len(payload) < 20:
                    debug_print(f"  ❌ Payload too short: {len(payload)} bytes", 2)
                    continue

                successful_packets += 1
                packet_count += 1
                
                type = ' '.join(f'{b:02x}' for b in payload[0:1])
                reserved = ' '.join(f'{b:02x}' for b in payload[1:2])
                payload_length = ' '.join(f'{b:02x}' for b in payload[2:4])
                sequence = ' '.join(f'{b:02x}' for b in payload[4:8])
                Framesize = ' '.join(f'{b:02x}' for b in payload[8:12])
                fragment = ' '.join(f'{b:02x}' for b in payload[12:16])
                timestamp = ' '.join(f'{b:02x}' for b in payload[16:20])
                has_jpeg = "YES" if b'\xff\xd8' in payload[20:100] else "NO"

                line = f"{packet_count:5d} | {len(payload[20:]):4d} ||| {type:2} | {reserved:2} | {payload_length:5} | {sequence:11} | {Framesize:11} | {fragment:11} | {timestamp:11} ||| {has_jpeg:5}\n"
                out.write(line)

                if packet_count % 100 == 0:
                    print(f"  Processed {packet_count} UDP packets...")

                if num_packets > 0 and packet_count >= num_packets:
                    break

    success_rate = (successful_packets / total_packets * 100) if total_packets > 0 else 0
    print(f"✅ Completed! Wrote {packet_count} UDP payload headers to {output_file}")
    print(f"📊 Success rate: {successful_packets}/{total_packets} packets ({success_rate:.1f}%)")

def detailed_header_analysis(pcap_file, output_file, num_packets=50):
    """Detailed header analysis with multiple views"""
    print(f"\n📊 Detailed Header Analysis to {output_file}")

    successful_packets = 0
    total_packets = 0

    with open(pcap_file, 'rb') as f:
        pcap = dpkt.pcap.Reader(f)
        with open(output_file, 'w') as out:
            out.write("# Detailed UDP Payload Header Analysis\n")
            out.write(f"# PCAP File: {pcap_file}\n")
            out.write(f"# Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
            out.write("#" + "="*120 + "\n\n")

            packet_count = 0
            sequence_patterns = []
            fragment_patterns = []

            for ts, buf in pcap:
                total_packets += 1
                payload = process_packet_improved(buf)
                if not payload or len(payload) < 20:
                    continue

                successful_packets += 1
                packet_count += 1

                magic_type = payload[0:4]
                sequence = payload[4:8]
                frametick = payload[8:12]
                fragment = payload[12:16]
                reserved = payload[16:20]

                try:
                    seq_num = struct.unpack('<H', sequence)[0]
                    frag_num = struct.unpack('<H', fragment)[0]
                    unknown_float = struct.unpack('<f', frametick[2:6])[0] if len(frametick) >= 6 else 0.0
                except:
                    seq_num = 0
                    frag_num = 0
                    unknown_float = 0.0

                sequence_patterns.append(seq_num)
                fragment_patterns.append(frag_num)

                out.write(f"Packet {packet_count} - Size: {len(payload[20:])} bytes\n")
                out.write(f"  Type | Reserved:   {' '.join(f'{b:02x}' for b in payload[0:1])} | {' '.join(f'{b:02x}' for b in payload[1:2])}\n")
                out.write(f"  Blocksize:         {' '.join(f'{b:02x}' for b in payload[2:4])}\n")
                out.write(f"  Sequence:          {' '.join(f'{b:02x}' for b in sequence)} \n")
                out.write(f"  Frametick:         {' '.join(f'{b:02x}' for b in frametick)} \n")
                out.write(f"  Fragment:          {' '.join(f'{b:02x}' for b in fragment)} \n")
                out.write(f"  Unknown:           {' '.join(f'{b:02x}' for b in reserved)}\n")

                payload_data = payload[20:min(30, len(payload))]
                payload_hex = ' '.join(f'{b:02x}' for b in payload_data)
                has_jpeg = "YES" if b'\xff\xd8' in payload[20:100] else "NO"
                has_eof = "YES" if b'\xff\xd9' in payload else "NO"
                out.write(f"  Payload Start:     {payload_hex}\n")
                out.write(f"  Has SOI (FFD8):    {has_jpeg}\n")
                out.write(f"  Has EOI (FFD9):    {has_eof}\n")
                out.write("-" * 80 + "\n\n")

                if packet_count % 10 == 0:
                    print(f"  Processed {packet_count} packets...")

                if num_packets > 0 and packet_count >= num_packets:
                    break

            # Statistics
            success_rate = (successful_packets / total_packets * 100) if total_packets > 0 else 0
            out.write("\n" + "="*50 + " STATISTICS " + "="*50 + "\n")
            out.write(f"Total Packets Analyzed: {packet_count}\n")
            out.write(f"Success Rate: {successful_packets}/{total_packets} ({success_rate:.1f}%)\n")
            
            if sequence_patterns:
                unique_sequences = len(set(sequence_patterns))
                out.write(f"Unique Sequence Numbers: {unique_sequences}\n")
                out.write(f"Sequence Range: {min(sequence_patterns)} - {max(sequence_patterns)}\n")
            if fragment_patterns:
                fragment_types = set(fragment_patterns)
                out.write(f"Fragment Types: {[f'0x{ft:04x}' for ft in fragment_types]}\n")
                for ft in fragment_types:
                    count = fragment_patterns.count(ft)
                    percentage = (count / len(fragment_patterns)) * 100
                    out.write(f"  0x{ft:04x}: {count} packets ({percentage:.1f}%)\n")

    print(f"✅ Detailed analysis completed! Wrote {packet_count} packets to {output_file}")

if __name__ == "__main__":
    pcap_file = "mjpeg_stream.pcap"
    
    print("🚀 Starting WLAN UDP Payload Analyzer")
    print("=" * 50)
    
    # 1. Zuerst das Format überprüfen
    format_stats = check_pcap_format(pcap_file)
    
    if format_stats is None:
        print("❌ Could not read PCAP file. Exiting.")
        sys.exit(1)
    
    # 2. Analysen durchführen
    print("\n" + "=" * 50)
    print("Starting analyses...")
    
    # 1. Simple header overview
    analyze_headers_to_file(pcap_file, "udp_headers_overview.txt", num_packets=2500)

    # 2. Detailed analysis
    detailed_header_analysis(pcap_file, "udp_headers_detailed.txt", num_packets=2500)


    print("\n🎉 All analyses completed!")
    print("📁 Files created:")
    print("   - udp_headers_overview.txt (tabular overview)")
    print("   - udp_headers_detailed.txt (detailed analysis)")
    print("\n💡 If no UDP packets were found, check:")
    print("   - PCAP file format (see above analysis)")
    print("   - WLAN encryption (script only works with unencrypted traffic)")
    print("   - Network configuration and capture settings")