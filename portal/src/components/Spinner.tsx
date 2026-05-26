interface Props {
  label?: string;
  size?: 'sm' | 'md' | 'lg';
}

export function Spinner({ label, size = 'md' }: Props) {
  const px = size === 'sm' ? 'w-4 h-4' : size === 'lg' ? 'w-8 h-8' : 'w-5 h-5';
  return (
    <div className="flex items-center gap-2 text-slate-500">
      <span
        className={`inline-block ${px} rounded-full border-2 border-current border-t-transparent animate-spin`}
        aria-hidden
      />
      {label && <span className="text-sm">{label}</span>}
    </div>
  );
}

export function LoadingPage({ label = 'กำลังโหลด…' }: { label?: string }) {
  return (
    <div className="flex items-center justify-center py-16">
      <Spinner label={label} size="lg" />
    </div>
  );
}
