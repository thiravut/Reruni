// Click-to-sort table header. Cycles asc → desc on the same field.
// Pages own the sort state and call back with the new field; this component
// is purely presentational.

export interface SortState<F extends string = string> {
  field: F
  direction: 'asc' | 'desc'
}

interface Props<F extends string> {
  label: string
  field: F
  sort: SortState<F>
  onSort: (field: F) => void
  className?: string
  align?: 'left' | 'right' | 'center'
}

export function SortableHeader<F extends string>({
  label,
  field,
  sort,
  onSort,
  className,
  align = 'left',
}: Props<F>) {
  const active = sort.field === field
  const arrow = active ? (sort.direction === 'asc' ? '↑' : '↓') : '↕'
  const alignCls =
    align === 'right' ? 'text-right' : align === 'center' ? 'text-center' : 'text-left'
  return (
    <th
      scope="col"
      onClick={() => onSort(field)}
      className={`cursor-pointer select-none px-3 py-2 text-xs font-semibold uppercase tracking-wide text-slate-600 hover:bg-slate-200 ${alignCls} ${className ?? ''}`}
    >
      {label}{' '}
      <span className={active ? 'text-blue-600' : 'text-slate-400'}>{arrow}</span>
    </th>
  )
}
