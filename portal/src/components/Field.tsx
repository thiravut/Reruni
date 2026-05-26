import type { InputHTMLAttributes, TextareaHTMLAttributes, ReactNode } from 'react';

interface FieldWrapperProps {
  label: string;
  htmlFor?: string;
  error?: string | null;
  hint?: string;
  children: ReactNode;
  required?: boolean;
}

export function FieldWrapper({
  label,
  htmlFor,
  error,
  hint,
  required,
  children,
}: FieldWrapperProps) {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={htmlFor} className="text-sm font-medium text-slate-700">
        {label}
        {required && <span className="text-rose-600 ml-1">*</span>}
      </label>
      {children}
      {error ? (
        <span className="text-xs text-rose-600">{error}</span>
      ) : hint ? (
        <span className="text-xs text-slate-500">{hint}</span>
      ) : null}
    </div>
  );
}

type InputProps = InputHTMLAttributes<HTMLInputElement> & {
  label: string;
  error?: string | null;
  hint?: string;
};

export function TextField({ label, error, hint, id, ...rest }: InputProps) {
  const autoId = id ?? `f-${rest.name ?? Math.random().toString(36).slice(2)}`;
  return (
    <FieldWrapper label={label} htmlFor={autoId} error={error} hint={hint} required={rest.required}>
      <input
        id={autoId}
        className={[
          'rounded border bg-white px-3 py-2 text-sm outline-none transition-colors',
          error
            ? 'border-rose-400 focus:border-rose-500'
            : 'border-slate-300 focus:border-brand-500',
        ].join(' ')}
        {...rest}
      />
    </FieldWrapper>
  );
}

type TextAreaProps = TextareaHTMLAttributes<HTMLTextAreaElement> & {
  label: string;
  error?: string | null;
  hint?: string;
};

export function TextArea({ label, error, hint, id, ...rest }: TextAreaProps) {
  const autoId = id ?? `f-${rest.name ?? Math.random().toString(36).slice(2)}`;
  return (
    <FieldWrapper label={label} htmlFor={autoId} error={error} hint={hint} required={rest.required}>
      <textarea
        id={autoId}
        className={[
          'rounded border bg-white px-3 py-2 text-sm outline-none transition-colors min-h-[80px]',
          error
            ? 'border-rose-400 focus:border-rose-500'
            : 'border-slate-300 focus:border-brand-500',
        ].join(' ')}
        {...rest}
      />
    </FieldWrapper>
  );
}
