import { useEffect, useRef } from "react";
import type { RefObject } from "react";

const FOCUSABLE =
  'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])';

// Nested dialogs (e.g. a confirm over a drawer) stack — only the topmost
// owns Tab and Escape, or the parent trap would swallow the child's keys.
const stack: HTMLElement[] = [];

/** Modal a11y: focus in on mount, Tab trapped, Escape closes, focus restored. */
export function useDialogA11y(
  ref: RefObject<HTMLElement | null>,
  onClose: () => void,
) {
  // Inline onClose props change identity every parent render — the effect
  // must stay mounted once or the dialog would re-steal focus constantly.
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null;
    const el = ref.current;
    if (el) stack.push(el);
    el?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (stack[stack.length - 1] !== el) return;
      if (e.key === "Escape") {
        closeRef.current();
        return;
      }
      if (e.key !== "Tab" || !el) return;
      const f = el.querySelectorAll<HTMLElement>(FOCUSABLE);
      if (f.length === 0) return;
      const first = f[0];
      const last = f[f.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        last.focus();
        e.preventDefault();
      } else if (!e.shiftKey && document.activeElement === last) {
        first.focus();
        e.preventDefault();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
      if (el) {
        const i = stack.indexOf(el);
        if (i !== -1) stack.splice(i, 1);
      }
      prev?.focus();
    };
  }, [ref]);
}
