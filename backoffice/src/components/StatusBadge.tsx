// Color-coded status pill per agent-task-backoffice.md §7:
//   green  → online / active   (live, online, active)
//   yellow → warning            (idle, pending)
//   red    → error / offline    (offline, error, ended-with-error)
//   gray   → neutral / unknown

type Tone = 'green' | 'yellow' | 'red' | 'gray'

const TONE_CLASS: Record<Tone, string> = {
  green: 'bg-green-100 text-green-800 ring-green-200',
  yellow: 'bg-yellow-100 text-yellow-800 ring-yellow-200',
  red: 'bg-red-100 text-red-800 ring-red-200',
  gray: 'bg-slate-100 text-slate-700 ring-slate-200',
}

export function toneForStatus(status: string | null | undefined): Tone {
  if (!status) return 'gray'
  const s = status.toLowerCase()
  if (s === 'live' || s === 'online' || s === 'active' || s === 'ack') return 'green'
  if (s === 'idle' || s === 'pending' || s === 'warning') return 'yellow'
  if (s === 'offline' || s === 'error' || s === 'failed') return 'red'
  return 'gray'
}

interface Props {
  status: string | null | undefined
  tone?: Tone
}

export function StatusBadge({ status, tone }: Props) {
  const t = tone ?? toneForStatus(status)
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${TONE_CLASS[t]}`}
    >
      {status ?? 'unknown'}
    </span>
  )
}
