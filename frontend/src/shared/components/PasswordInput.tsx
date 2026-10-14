import { useState } from "react";
import { Eye, EyeOff } from "lucide-react";

/** Password field with a visibility toggle; keeps caller-controlled value. */
export default function PasswordInput(
  props: React.InputHTMLAttributes<HTMLInputElement>,
) {
  const [show, setShow] = useState(false);
  const { className, ...rest } = props;
  return (
    <div className="relative">
      <input
        {...rest}
        type={show ? "text" : "password"}
        className={`w-full rounded-lg border border-slate-200 py-2 pl-3 pr-10 ${className ?? ""}`}
      />
      <button
        type="button"
        aria-label={show ? "Hide password" : "Show password"}
        onClick={() => setShow((s) => !s)}
        className="absolute right-2 top-1/2 -translate-y-1/2 rounded p-1 text-[#5C685B] hover:bg-slate-100"
      >
        {show ? <EyeOff size={16} /> : <Eye size={16} />}
      </button>
    </div>
  );
}
