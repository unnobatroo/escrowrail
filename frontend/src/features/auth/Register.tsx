import { useState } from "react";
import { Dices, ShieldCheck } from "lucide-react";
import { Link, useNavigate } from "react-router-dom";
import { apiError } from "../../api";
import { useAuth } from "./auth";
import { randomPersona } from "../../utils/personaGenerator";

export default function Register() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState({
    email: "",
    password: "",
    legal_name: "",
    ubo_tax_id: "",
    country: "",
  });
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm({ ...form, [k]: e.target.value });

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await register(form);
      navigate(`/verify-email?email=${encodeURIComponent(form.email)}`);
    } catch (err) {
      setError(apiError(err).message ?? "Registration failed");
    } finally {
      setBusy(false);
    }
  };

  const field = (
    key: keyof typeof form,
    label: string,
    type = "text",
    hint?: string,
  ) => (
    <div className="mb-3" key={key}>
      <label className="mb-1 block text-sm font-medium text-[#5C685B]">{label}</label>
      <input
        data-testid={key}
        type={type}
        required
        value={form[key]}
        onChange={set(key)}
        placeholder={hint}
        className="w-full rounded-lg border border-slate-200 px-3 py-2"
      />
    </div>
  );

  return (
    <div className="flex min-h-screen items-center justify-center bg-[#163300]">
      <form
        onSubmit={submit}
        className="w-full max-w-md rounded-xl bg-white p-8 shadow-lg"
      >
        <div className="mb-6 flex items-center justify-between">
          <div className="flex items-center gap-2 text-[#163300]">
            <ShieldCheck size={28} className="text-[#9FE870]" />
            <h1 className="text-xl font-bold">Register your organization</h1>
          </div>
          <button
            type="button"
            data-testid="randomize"
            title="Fill with a random test company"
            onClick={() => {
              const p = randomPersona();
              setForm({
                ...form,
                legal_name: p.legalName,
                ubo_tax_id: p.taxId,
                country: p.country,
              });
            }}
            className="flex items-center gap-1 rounded-full border border-[#163300] px-3 py-1 text-xs font-semibold text-[#163300] hover:bg-[#F4F5F0]"
          >
            <Dices size={14} /> Randomize
          </button>
        </div>
        {field("legal_name", "Organization legal name", "text", "Acme Hardware US")}
        {field("email", "Work email", "email", "you@company.com")}
        {field("password", "Password (8+ chars)", "password")}
        {field("ubo_tax_id", "Ultimate beneficial owner tax ID", "text", "TAX-US-1001")}
        {field("country", "Country (ISO-2)", "text", "US")}
        {error && (
          <div
            data-testid="register-error"
            className="mb-4 rounded-lg bg-[#FFDAD6] px-3 py-2 text-sm text-[#BA1A1A]"
          >
            {error}
          </div>
        )}
        <button
          data-testid="submit"
          type="submit"
          disabled={busy}
          className="w-full rounded-full bg-[#9FE870] py-2.5 font-semibold text-[#163300] hover:bg-[#8fd960] disabled:opacity-50"
        >
          {busy ? "Creating…" : "Create account"}
        </button>
        <p className="mt-4 text-center text-sm text-[#5C685B]">
          Already registered?{" "}
          <Link to="/login" className="font-medium text-[#163300] underline">
            Sign in
          </Link>
        </p>
      </form>
    </div>
  );
}
