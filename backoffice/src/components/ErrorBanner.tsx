// Red inline alert for API errors — placed above tables / forms.

interface Props {
  message: string | null
  onDismiss?: () => void
}

export function ErrorBanner({ message, onDismiss }: Props) {
  if (!message) return null
  return (
    <div className="mx-6 mt-4 flex items-start justify-between rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800">
      <span>{message}</span>
      {onDismiss ? (
        <button
          type="button"
          onClick={onDismiss}
          className="ml-3 text-xs font-medium text-red-700 hover:underline"
        >
          ปิด
        </button>
      ) : null}
    </div>
  )
}
