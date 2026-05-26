// Tiny pagination footer — always shows the "Showing X-Y of Z" copy
// required by agent-task-backoffice.md §7 ("Always show counts").

interface Props {
  total: number
  limit: number
  offset: number
  onChange: (nextOffset: number) => void
}

export function Pagination({ total, limit, offset, onChange }: Props) {
  const from = total === 0 ? 0 : offset + 1
  const to = Math.min(offset + limit, total)
  const canPrev = offset > 0
  const canNext = offset + limit < total

  return (
    <div className="flex items-center justify-between border-t border-slate-200 px-3 py-2 text-sm text-slate-600">
      <span>
        Showing <b>{from.toLocaleString()}</b>–<b>{to.toLocaleString()}</b> of{' '}
        <b>{total.toLocaleString()}</b>
      </span>
      <div className="flex gap-2">
        <button
          type="button"
          disabled={!canPrev}
          onClick={() => onChange(Math.max(0, offset - limit))}
          className="rounded border border-slate-300 bg-white px-3 py-1 text-xs font-medium hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
        >
          ← Prev
        </button>
        <button
          type="button"
          disabled={!canNext}
          onClick={() => onChange(offset + limit)}
          className="rounded border border-slate-300 bg-white px-3 py-1 text-xs font-medium hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
        >
          Next →
        </button>
      </div>
    </div>
  )
}
