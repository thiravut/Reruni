import type { DeviceStatus } from '../types/api';
import { deviceStatusColor, deviceStatusLabel } from '../utils/labels';

export function StatusBadge({ status }: { status: DeviceStatus }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-xs font-medium ${deviceStatusColor(status)}`}
    >
      <span
        className={`inline-block w-1.5 h-1.5 rounded-full ${
          status === 'live'
            ? 'bg-emerald-500 animate-pulse'
            : status === 'error'
              ? 'bg-rose-500'
              : status === 'offline'
                ? 'bg-gray-400'
                : status === 'pairing'
                  ? 'bg-amber-500'
                  : 'bg-slate-400'
        }`}
      />
      {deviceStatusLabel(status)}
    </span>
  );
}
