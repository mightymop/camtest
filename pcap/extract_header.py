import dpkt
import struct
from datetime import datetime

def analyze_headers_to_file(pcap_file, output_file, num_packets=0):
    """Writes UDP Payload headers line by line to a file"""
    
    print(f"=== Writing UDP Headers to {output_file} ===")
    
    with open(pcap_file, 'rb') as f:
        pcap = dpkt.pcap.Reader(f)
        
        with open(output_file, 'w') as out:
            # Output file header
            out.write("# UDP Payload Header Analysis\n")
            out.write(f"# PCAP File: {pcap_file}\n")
            out.write(f"# Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
            out.write("# Format: PacketNumber | TotalSize | Magic/Type | Sequence | Timestamp | Fragment | Reserved | HasJPEG\n")
            out.write("#" + "="*100 + "\n")
            
            packet_count = 0
            
            for timestamp, buf in pcap:
                try:
                    eth = dpkt.ethernet.Ethernet(buf)
                    if isinstance(eth.data, dpkt.ip.IP) and isinstance(eth.data.data, dpkt.udp.UDP):
                        udp = eth.data.data
                        payload = bytes(udp.data)
                        
                        packet_count += 1
                        
                        # Extract header fields
                        if len(payload) >= 20:
                            magic_type = ' '.join(f'{b:02x}' for b in payload[0:4])
                            sequence = ' '.join(f'{b:02x}' for b in payload[4:6])
                            timestamp_field = ' '.join(f'{b:02x}' for b in payload[6:12])
                            fragment = ' '.join(f'{b:02x}' for b in payload[12:14])
                            reserved = ' '.join(f'{b:02x}' for b in payload[14:20])
                            
                            # Check for JPEG marker in payload
                            has_jpeg = "YES" if b'\xff\xd8' in payload[20:100] else "NO"
                            
                            # Write line to file
                            line = f"{packet_count:6d} | {len(payload):6d} | {magic_type:11} | {sequence:5} | {timestamp_field:17} | {fragment:5} | {reserved:17} | {has_jpeg:5}\n"
                            out.write(line)
                            
                            # Show progress
                            if packet_count % 100 == 0:
                                print(f"Processed {packet_count} packets...")
                        
                        if num_packets > 0 and packet_count >= num_packets:
                            break
                            
                except Exception as e:
                    continue
    
    print(f"✅ Completed! Wrote {packet_count} packet headers to {output_file}")

def detailed_header_analysis(pcap_file, output_file, num_packets=50):
    """Detailed header analysis with multiple views"""
    
    print(f"=== Detailed Header Analysis to {output_file} ===")
    
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
            
            for timestamp, buf in pcap:
                try:
                    eth = dpkt.ethernet.Ethernet(buf)
                    if isinstance(eth.data, dpkt.ip.IP) and isinstance(eth.data.data, dpkt.udp.UDP):
                        udp = eth.data.data
                        payload = bytes(udp.data)
                        
                        packet_count += 1
                        
                        if len(payload) >= 20:
                            # Header fields
                            magic_type = payload[0:4]
                            sequence = payload[4:6]
                            timestamp_field = payload[6:12]
                            fragment = payload[12:14]
                            reserved = payload[14:20]
                            
                            # Numeric values (Little Endian)
                            seq_num = struct.unpack('<H', sequence)[0]
                            frag_num = struct.unpack('<H', fragment)[0]
                            
                            # Timestamp as float (if possible)
                            timestamp_float = struct.unpack('<f', timestamp_field[2:6])[0] if len(timestamp_field) >= 6 else 0.0
                            
                            # Collect patterns for statistics
                            sequence_patterns.append(seq_num)
                            fragment_patterns.append(frag_num)
                            
                            # Detailed output
                            out.write(f"Packet {packet_count} - Size: {len(payload)} bytes\n")
                            out.write(f"  Magic/Type:   {' '.join(f'{b:02x}' for b in magic_type)} (constant: {magic_type == b'\x02\x00\xac\x05'})\n")
                            out.write(f"  Sequence:     {' '.join(f'{b:02x}' for b in sequence)} = {seq_num} (0x{seq_num:04x})\n")
                            out.write(f"  Timestamp:    {' '.join(f'{b:02x}' for b in timestamp_field)} = {timestamp_float}\n")
                            out.write(f"  Fragment:     {' '.join(f'{b:02x}' for b in fragment)} = {frag_num} (0x{frag_num:04x})\n")
                            out.write(f"  Reserved:     {' '.join(f'{b:02x}' for b in reserved)}\n")
                            
                            # Payload info
                            payload_data = payload[20:min(30, len(payload))]
                            payload_hex = ' '.join(f'{b:02x}' for b in payload_data)
                            has_jpeg = "YES" if b'\xff\xd8' in payload[20:100] else "NO"
                            out.write(f"  Payload Start: {payload_hex}\n")
                            out.write(f"  Has JPEG:      {has_jpeg}\n")
                            out.write("-" * 80 + "\n\n")
                        
                        if num_packets > 0 and packet_count >= num_packets:
                            break
                            
                except Exception as e:
                    continue
            
            # Statistics at the end
            out.write("\n" + "="*50 + " STATISTICS " + "="*50 + "\n")
            out.write(f"Total Packets Analyzed: {packet_count}\n")
            
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

def create_csv_analysis(pcap_file, output_file, num_packets=0):
    """Creates CSV format for easy analysis"""
    
    print(f"=== Creating CSV Analysis: {output_file} ===")
    
    with open(pcap_file, 'rb') as f:
        pcap = dpkt.pcap.Reader(f)
        
        with open(output_file, 'w') as out:
            # CSV Header
            out.write("PacketNumber,TotalSize,MagicByte,StreamID,SequenceNum,FragmentType,Timestamp,HasJPEG,PayloadStart\n")
            
            packet_count = 0
            
            for timestamp, buf in pcap:
                try:
                    eth = dpkt.ethernet.Ethernet(buf)
                    if isinstance(eth.data, dpkt.ip.IP) and isinstance(eth.data.data, dpkt.udp.UDP):
                        udp = eth.data.data
                        payload = bytes(udp.data)
                        
                        packet_count += 1
                        
                        if len(payload) >= 20:
                            # Extract fields
                            magic_byte = payload[0]
                            stream_id = struct.unpack('<H', payload[2:4])[0]  # AC 05
                            sequence_num = struct.unpack('<H', payload[4:6])[0]
                            fragment_type = struct.unpack('<H', payload[12:14])[0]
                            timestamp_float = struct.unpack('<f', payload[8:12])[0] if len(payload) >= 12 else 0.0
                            
                            has_jpeg = "1" if b'\xff\xd8' in payload[20:100] else "0"
                            payload_start = ' '.join(f'{b:02x}' for b in payload[20:25])
                            
                            # CSV line
                            line = f"{packet_count},{len(payload)},{magic_byte},{stream_id},{sequence_num},{fragment_type},{timestamp_float:.2f},{has_jpeg},\"{payload_start}\"\n"
                            out.write(line)
                        
                        if num_packets > 0 and packet_count >= num_packets:
                            break
                            
                except Exception as e:
                    continue
    
    print(f"✅ CSV analysis completed! Wrote {packet_count} packets to {output_file}")

# Run all analyses
if __name__ == "__main__":
    pcap_file = "mjpeg_stream.pcap"
    
    # 1. Simple header overview
    analyze_headers_to_file(pcap_file, "udp_headers_overview.txt", num_packets=2500)
    
    # 2. Detailed analysis (first 50 packets)
    detailed_header_analysis(pcap_file, "udp_headers_detailed.txt", num_packets=2500)
    
    # 3. CSV for data analysis
    create_csv_analysis(pcap_file, "udp_headers_analysis.csv", num_packets=2500)
    
    print("\n🎉 All analyses completed!")
    print("📁 Files created:")
    print("   - udp_headers_overview.txt (tabular overview)")
    print("   - udp_headers_detailed.txt (detailed analysis)") 
    print("   - udp_headers_analysis.csv (CSV for data analysis)")