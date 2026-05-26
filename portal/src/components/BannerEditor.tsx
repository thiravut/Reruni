import { useEffect, useState } from 'react';
import type { Banner, BannerFontSize, BannerInput, BannerSlot } from '../types/api';
import { Button } from './Button';
import { FieldWrapper, TextField } from './Field';
import { ErrorBanner } from './ErrorBanner';
import { validateBannerText } from '../utils/validation';

const SLOTS: { value: BannerSlot; label: string }[] = [
  { value: 'top', label: 'บนสุด' },
  { value: 'top-left', label: 'บน-ซ้าย' },
  { value: 'top-right', label: 'บน-ขวา' },
  { value: 'center', label: 'กลาง' },
  { value: 'bottom-left', label: 'ล่าง-ซ้าย' },
  { value: 'bottom-right', label: 'ล่าง-ขวา' },
  { value: 'bottom', label: 'ล่างสุด' },
];

const SIZES: { value: BannerFontSize; label: string }[] = [
  { value: 'S', label: 'เล็ก' },
  { value: 'M', label: 'กลาง' },
  { value: 'L', label: 'ใหญ่' },
];

interface Props {
  initial?: Partial<Banner> | null;
  // when true, the editor binds to a live session (dynamic); otherwise to a video (static)
  liveSessionId?: number;
  videoId?: number;
  saving?: boolean;
  onSubmit: (input: BannerInput) => Promise<void> | void;
  onCancel?: () => void;
}

export function BannerEditor({
  initial,
  liveSessionId,
  videoId,
  saving = false,
  onSubmit,
  onCancel,
}: Props) {
  const [slot, setSlot] = useState<BannerSlot>(initial?.slot ?? 'top');
  const [text, setText] = useState(initial?.text ?? '');
  const [bgColor, setBgColor] = useState(initial?.bg_color ?? '#FF3D00');
  const [textColor, setTextColor] = useState(initial?.text_color ?? '#FFFFFF');
  const [fontSize, setFontSize] = useState<BannerFontSize>(initial?.font_size ?? 'M');
  const [hasDeadline, setHasDeadline] = useState(!!initial?.deadline);
  const [deadlineLocal, setDeadlineLocal] = useState<string>(
    initial?.deadline ? isoToLocalInput(initial.deadline) : '',
  );
  const [textError, setTextError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  useEffect(() => {
    if (!initial) return;
    setSlot(initial.slot ?? 'top');
    setText(initial.text ?? '');
    setBgColor(initial.bg_color ?? '#FF3D00');
    setTextColor(initial.text_color ?? '#FFFFFF');
    setFontSize(initial.font_size ?? 'M');
    setHasDeadline(!!initial.deadline);
    setDeadlineLocal(initial.deadline ? isoToLocalInput(initial.deadline) : '');
  }, [initial]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    const err = validateBannerText(text);
    setTextError(err);
    if (err) return;

    const input: BannerInput = {
      slot,
      text,
      bg_color: bgColor,
      text_color: textColor,
      font_size: fontSize,
    };
    if (videoId !== undefined) input.video_id = videoId;
    if (liveSessionId !== undefined) input.live_session_id = liveSessionId;
    if (hasDeadline && deadlineLocal) {
      input.deadline = new Date(deadlineLocal).toISOString();
    } else if (hasDeadline && !deadlineLocal) {
      setFormError('กรุณาเลือกเวลาเส้นตาย');
      return;
    }

    try {
      await onSubmit(input);
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'เกิดข้อผิดพลาด');
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4">
      <ErrorBanner message={formError} onDismiss={() => setFormError(null)} />

      <TextField
        label="ข้อความ"
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          setTextError(null);
        }}
        maxLength={80}
        required
        error={textError}
        hint={`${text.length}/80 ตัวอักษร`}
      />

      <div className="grid grid-cols-2 gap-4">
        <FieldWrapper label="ตำแหน่ง">
          <select
            value={slot}
            onChange={(e) => setSlot(e.target.value as BannerSlot)}
            className="rounded border border-slate-300 px-3 py-2 text-sm bg-white"
          >
            {SLOTS.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </FieldWrapper>
        <FieldWrapper label="ขนาดตัวอักษร">
          <select
            value={fontSize}
            onChange={(e) => setFontSize(e.target.value as BannerFontSize)}
            className="rounded border border-slate-300 px-3 py-2 text-sm bg-white"
          >
            {SIZES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </FieldWrapper>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <FieldWrapper label="สีพื้นหลัง">
          <div className="flex items-center gap-2">
            <input
              type="color"
              value={bgColor}
              onChange={(e) => setBgColor(e.target.value)}
              className="h-9 w-12 border border-slate-300 rounded"
              aria-label="เลือกสีพื้นหลัง"
            />
            <input
              type="text"
              value={bgColor}
              onChange={(e) => setBgColor(e.target.value)}
              className="flex-1 rounded border border-slate-300 px-3 py-2 text-sm bg-white font-mono"
              maxLength={9}
            />
          </div>
        </FieldWrapper>
        <FieldWrapper label="สีตัวอักษร">
          <div className="flex items-center gap-2">
            <input
              type="color"
              value={textColor}
              onChange={(e) => setTextColor(e.target.value)}
              className="h-9 w-12 border border-slate-300 rounded"
              aria-label="เลือกสีตัวอักษร"
            />
            <input
              type="text"
              value={textColor}
              onChange={(e) => setTextColor(e.target.value)}
              className="flex-1 rounded border border-slate-300 px-3 py-2 text-sm bg-white font-mono"
              maxLength={9}
            />
          </div>
        </FieldWrapper>
      </div>

      <FieldWrapper
        label="นับถอยหลัง (ตัวเลือก)"
        hint="ใช้สำหรับแบนเนอร์แบบนับเวลา flash sale"
      >
        <div className="flex flex-col gap-2">
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={hasDeadline}
              onChange={(e) => setHasDeadline(e.target.checked)}
            />
            ตั้งเวลาเส้นตาย
          </label>
          {hasDeadline && (
            <input
              type="datetime-local"
              value={deadlineLocal}
              onChange={(e) => setDeadlineLocal(e.target.value)}
              className="rounded border border-slate-300 px-3 py-2 text-sm bg-white"
            />
          )}
        </div>
      </FieldWrapper>

      <BannerPreview
        text={text || '— แสดงตัวอย่าง —'}
        bgColor={bgColor}
        textColor={textColor}
        fontSize={fontSize}
      />

      <div className="flex justify-end gap-2">
        {onCancel && (
          <Button variant="secondary" onClick={onCancel} disabled={saving}>
            ยกเลิก
          </Button>
        )}
        <Button type="submit" loading={saving}>
          บันทึก
        </Button>
      </div>
    </form>
  );
}

interface PreviewProps {
  text: string;
  bgColor: string;
  textColor: string;
  fontSize: BannerFontSize;
}

export function BannerPreview({ text, bgColor, textColor, fontSize }: PreviewProps) {
  const fontPx = fontSize === 'S' ? 14 : fontSize === 'L' ? 22 : 18;
  return (
    <div className="rounded border border-slate-200 bg-slate-100 p-4">
      <p className="text-xs uppercase tracking-wider text-slate-500 mb-2">
        ตัวอย่าง
      </p>
      <div
        className="rounded px-4 py-2 text-center font-semibold"
        style={{
          backgroundColor: bgColor,
          color: textColor,
          fontSize: `${fontPx}px`,
        }}
      >
        {text}
      </div>
    </div>
  );
}

function isoToLocalInput(iso: string): string {
  const d = new Date(iso);
  const tz = d.getTimezoneOffset();
  const local = new Date(d.getTime() - tz * 60 * 1000);
  return local.toISOString().slice(0, 16);
}
