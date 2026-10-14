import { useState } from "react";
import { Check, Copy } from "lucide-react";

/** Copies `text` to the clipboard; briefly flips to a checkmark. */
export default function CopyButton({ text, label }: { text: string; label: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard unavailable (non-secure context) — no-op */
    }
  };
  return (
    <button
      type="button"
      onClick={copy}
      aria-label={`Copy ${label}`}
      title={`Copy ${label}`}
      className="rounded p-0.5 text-[#5C685B] transition-colors hover:bg-slate-100 hover:text-[#1B1C1D]"
    >
      {copied ? <Check size={12} className="text-[#0F5132]" /> : <Copy size={12} />}
    </button>
  );
}
