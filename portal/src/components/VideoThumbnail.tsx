import { useEffect, useRef, useState } from 'react';

type Props = {
  src: string;
  className?: string;
  // Seek time to capture (sec). 0.1s skips the black opening frame on
  // most TikTok-style clips.
  at?: number;
};

// Cheap-and-cheerful thumbnail: load metadata only, seek to a frame, let
// the browser paint that frame inside the <video> element. Each row pays
// one HEAD-ish range request to grab the first MOOV atom + a key frame;
// no server-side image generation needed.
export function VideoThumbnail({ src, className = '', at = 0.1 }: Props) {
  const ref = useRef<HTMLVideoElement>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const handleMeta = () => {
      // Some browsers refuse seek to 0 with preload=metadata — clamp.
      try {
        el.currentTime = Math.min(at, Math.max(0, (el.duration || 1) - 0.1));
      } catch {
        // ignore
      }
    };
    el.addEventListener('loadedmetadata', handleMeta);
    return () => el.removeEventListener('loadedmetadata', handleMeta);
  }, [at, src]);

  if (failed) {
    return (
      <div
        className={`flex items-center justify-center bg-slate-100 text-slate-400 text-xs ${className}`}
      >
        ▶
      </div>
    );
  }

  return (
    <video
      ref={ref}
      src={src}
      muted
      playsInline
      preload="metadata"
      onError={() => setFailed(true)}
      className={`bg-slate-900 object-cover ${className}`}
    />
  );
}
