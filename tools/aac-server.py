#!/usr/bin/env python3.14
"""
Option G — PC AAC encoder service.

Pre-encodes an MP4 file's audio track to AAC-LC frames matching TikTok
45.3.2's encoder spec (verified via PLT hook on aacEncEncode in
libvolcenginertc.so):

  Codec        : AAC-LC
  Sample rate  : 48000 Hz
  Channels     : 2 (stereo)
  Bitrate      : 256 kbps
  Frame size   : 1024 samples / frame  (~21.33 ms each)
  Container    : raw AAC AU (no ADTS header on the wire)

ffmpeg outputs ADTS (7-byte sync header per frame). We parse the ADTS
stream, strip the header per frame, and ship raw AU over WebSocket.

Wire protocol (binary):
  [u32 BE: payload_len][u64 BE: pts_us][u8: kind][payload]
    kind = 0x01 for audio AAC raw AU
           (0x02 reserved for video H264 in Phase 6)

Mobile client connects, server streams frames at MP4-real-time pace
(~50 fps for audio). Server loops at file EOS so a single MP4 can drive
a long LIVE broadcast.

Usage:
    ./tools/aac-server.py <input.mp4> [--port 8765] [--bind 0.0.0.0]
"""

import argparse
import asyncio
import logging
import struct
import subprocess
import sys
import time
from pathlib import Path

import websockets

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
)
log = logging.getLogger("aac-server")

# Wire protocol constants.
KIND_AUDIO = 0x01
KIND_VIDEO = 0x02


def extract_aac_frames_ffmpeg_native(mp4_path: Path) -> list[tuple[int, bytes]]:
    """ffmpeg's native AAC encoder. Lower bitrate parity vs TikTok's
    libfdk-aac but produces working frames. Frame size variance is wider
    (~442-768 bytes vs TikTok's tight 682-683)."""
    cmd = [
        "ffmpeg", "-loglevel", "error",
        "-i", str(mp4_path), "-vn",
        "-c:a", "aac", "-profile:a", "aac_low",
        "-b:a", "256k", "-ar", "48000", "-ac", "2",
        "-f", "adts", "-",
    ]
    log.info("ffmpeg native AAC: %s", " ".join(cmd))
    proc = subprocess.run(cmd, capture_output=True, check=True)
    return _parse_adts(proc.stdout)


def extract_aac_frames(mp4_path: Path) -> list[tuple[int, bytes]]:
    """Pipe MP4 → ffmpeg (raw PCM) → fdkaac (AAC-LC, ADTS) → parse frames.

    Why fdkaac (libfdk-aac) and not ffmpeg's native AAC encoder:
      TikTok 45.3.2 uses libfdk-aac via `aacEncEncode`. Matching the same
      encoder gives 1:1 byte-level compatibility — frame sizes ~683 bytes
      (CBR-ish), same psychoacoustic model, same SBR/PS handling. ffmpeg's
      native AAC produces wider size variance (442-768 bytes) and audible
      quality difference on the viewer side.

    Returns:
        Frames in order. PTS is computed as frame_index * 1024 / 48000
        (each AAC frame = 1024 samples at 48 kHz = 21333 us).
    """
    # Step 1: ffmpeg decodes MP4 audio track to raw signed-16 LE PCM at
    # 48 kHz stereo. -vn drops video, -f s16le emits raw bytes, -ar/-ac
    # force the sample format.
    ff_cmd = [
        "ffmpeg",
        "-loglevel", "error",
        "-i", str(mp4_path),
        "-vn",
        "-f", "s16le",
        "-ar", "48000",
        "-ac", "2",
        "-",
    ]
    # Step 2: fdkaac encodes raw PCM to AAC-LC, ADTS-wrapped on stdout.
    #   -p 2   = profile MPEG-4 AAC LC (matches TikTok's aacCodecProfile=AACLC)
    #   -m 0   = CBR mode (matches TikTok's aacBitrateMode=0)
    #   -b 256000 = bitrate 256 kbps (matches TikTok's bitrateKbps=256)
    #   -f 2   = ADTS transport (we strip the 7-byte header per frame)
    #   -R --raw-channels 2 --raw-rate 48000 --raw-format S16L = input is
    #          raw PCM piped from ffmpeg
    #   -o -   = output to stdout
    fdk_cmd = [
        "fdkaac",
        "-p", "2",
        "-m", "0",
        "-b", "256000",
        "-f", "2",
        "-R",
        "--raw-channels", "2",
        "--raw-rate", "48000",
        "--raw-format", "S16L",
        "-o", "-",
        "-",
    ]
    log.info("encoding %s with: %s | %s",
             mp4_path, " ".join(ff_cmd), " ".join(fdk_cmd))
    ff = subprocess.Popen(ff_cmd, stdout=subprocess.PIPE)
    fdk = subprocess.Popen(fdk_cmd,
                            stdin=ff.stdout,
                            stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE)
    if ff.stdout:
        ff.stdout.close()  # let SIGPIPE propagate when fdkaac exits
    adts_blob, fdk_err = fdk.communicate()
    ff.wait()
    if fdk.returncode != 0:
        log.error("fdkaac failed (rc=%d): %s",
                  fdk.returncode, fdk_err.decode(errors="ignore"))
        return []
    log.info("fdkaac produced %d bytes of ADTS", len(adts_blob))
    return _parse_adts(adts_blob)


def _parse_adts(adts_blob: bytes) -> list[tuple[int, bytes]]:
    """Common ADTS → raw AAC AU parser used by both encoder paths."""
    if not adts_blob:
        return []

    frames: list[tuple[int, bytes]] = []
    pos = 0
    blob_len = len(adts_blob)
    while pos + 7 <= blob_len:
        # ADTS header (7 bytes, no CRC):
        # syncword 0xFFF (12 bits) | ID(1) | layer(2) | protection_absent(1)
        # profile(2) | sample_freq_idx(4) | private(1) | channel_cfg(3)
        # original(1) | home(1) | copyright_bit(1) | copyright_start(1)
        # frame_length(13) | buffer_fullness(11) | num_raw_blocks(2)
        if adts_blob[pos] != 0xFF or (adts_blob[pos + 1] & 0xF0) != 0xF0:
            # Resync — should not happen with clean ffmpeg output
            log.warning("ADTS sync lost at pos=%d, byte=%02x", pos, adts_blob[pos])
            pos += 1
            continue
        # frame_length spans header bits 30..43 (across bytes 3,4,5).
        b3 = adts_blob[pos + 3]
        b4 = adts_blob[pos + 4]
        b5 = adts_blob[pos + 5]
        frame_length = ((b3 & 0x03) << 11) | (b4 << 3) | (b5 >> 5)
        if frame_length < 7 or pos + frame_length > blob_len:
            log.warning("bad frame_length=%d at pos=%d", frame_length, pos)
            break
        # protection_absent bit in byte 1 (LSB). If 0, header is 9 bytes
        # (extra 2-byte CRC); if 1, header is 7 bytes. ffmpeg default = no CRC.
        protection_absent = adts_blob[pos + 1] & 0x01
        header_len = 7 if protection_absent else 9
        au_bytes = adts_blob[pos + header_len : pos + frame_length]
        pts_us = (len(frames) * 1024 * 1_000_000) // 48000
        frames.append((pts_us, bytes(au_bytes)))
        pos += frame_length

    log.info("extracted %d AAC AU frames (avg %d bytes)",
             len(frames),
             sum(len(f[1]) for f in frames) // max(1, len(frames)))
    return frames


def encode_frame_for_wire(kind: int, pts_us: int, payload: bytes) -> bytes:
    """Wire format: [u32 BE: payload_len][u64 BE: pts_us][u8: kind][payload]."""
    header = struct.pack(">IQ B", len(payload), pts_us, kind)
    return header + payload


async def stream_to_client(
    websocket,
    frames: list[tuple[int, bytes]],
    frame_interval_s: float,
) -> None:
    """Stream pre-extracted frames at real-time pace, looping at EOS."""
    peer = websocket.remote_address
    log.info("[%s] connected; %d frames queued, %.2fms/frame",
             peer, len(frames), frame_interval_s * 1000)
    sent = 0
    start = time.monotonic()
    try:
        while True:
            for pts_us, au in frames:
                wire_pts = (sent * 1024 * 1_000_000) // 48000
                await websocket.send(encode_frame_for_wire(KIND_AUDIO, wire_pts, au))
                sent += 1
                # Pace to real time. If we fell behind (e.g. client slow
                # consume), skip the sleep.
                expected = start + sent * frame_interval_s
                sleep_s = expected - time.monotonic()
                if sleep_s > 0:
                    await asyncio.sleep(sleep_s)
                if sent % 100 == 0:
                    drift = time.monotonic() - expected
                    log.info("[%s] sent=%d drift=%+.3fs", peer, sent, drift)
    except websockets.exceptions.ConnectionClosed:
        log.info("[%s] disconnected after %d frames", peer, sent)


async def serve(mp4_path: Path, bind: str, port: int, encoder: str) -> None:
    if encoder == "fdkaac":
        frames = extract_aac_frames(mp4_path)
    elif encoder == "native":
        frames = extract_aac_frames_ffmpeg_native(mp4_path)
    else:
        log.error("unknown encoder=%s", encoder)
        sys.exit(1)
    if not frames:
        log.error("no AAC frames extracted; aborting")
        sys.exit(1)
    # 1024 samples / 48000 Hz = 21.333... ms per frame.
    frame_interval_s = 1024 / 48000.0

    async def handler(ws):
        await stream_to_client(ws, frames, frame_interval_s)

    async with websockets.serve(handler, bind, port, max_size=2 ** 20):
        log.info("listening on ws://%s:%d (frames=%d, interval=%.2fms)",
                 bind, port, len(frames), frame_interval_s * 1000)
        await asyncio.Future()  # block forever


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                  formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input_mp4", type=Path, help="MP4 source file")
    ap.add_argument("--bind", default="0.0.0.0", help="server bind address")
    ap.add_argument("--port", type=int, default=8765, help="WebSocket port")
    ap.add_argument(
        "--encoder", choices=["native", "fdkaac"], default="native",
        help="AAC encoder: 'native' (ffmpeg's built-in AAC, verified "
             "working end-to-end with TikTok 45.3.2 on 2026-06-08) or "
             "'fdkaac' (libfdk-aac, tighter CBR but produced viewer-side "
             "artefacts on the same device). Default native.",
    )
    args = ap.parse_args()
    if not args.input_mp4.is_file():
        log.error("%s not found", args.input_mp4)
        sys.exit(1)
    asyncio.run(serve(args.input_mp4, args.bind, args.port, args.encoder))


if __name__ == "__main__":
    main()
