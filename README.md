1. Connect mobile to cameras wifi hot spot

2. Start app
   > App will connect to camera on start up


Note: I have extended the app for using it as a replacement for w-car.


The app uses TCP Port 3333 for CTP Protocol and UDP Port 2224 for MJPEG Videostream
with a propietary RTP Protocol.

RTP:

Header:
Type 1Byte | Reserved 1Byte| Blocksize 1Byte| Sequence 4Bytes| Framesize 4Bytes| Offset 4Bytes| Reserved 4Bytes|

Pay attention that there are multiple Frames/Fragments in one UDP Packet 

// First i thought it would be:
// Paket 1: [Header1][Fragment1]
// Paket 2: [Header2][Fragment2] 
// Paket 3: [Header3][Fragment3]

// but in the end it was like:
// Paket 1: [Header1][Fragment1][Header2][Fragment2][Header3][Fragment3]...

additional ressources could be found in the source from this sdk: https://gitee.com/jfzhang5/fw-AC79_AIoT_SDK