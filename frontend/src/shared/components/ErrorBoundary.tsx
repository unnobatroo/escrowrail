import { Component, type ReactNode } from "react";
import { TriangleAlert } from "lucide-react";

/** Last-resort render guard: a crashed tree gets a recovery surface, not a white screen. */
export default class ErrorBoundary extends Component<
  { children: ReactNode },
  { failed: boolean }
> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <div className="flex min-h-screen items-center justify-center bg-brand-sand px-6">
        <div className="w-full max-w-sm rounded-xl bg-white p-8 text-center shadow-card">
          <TriangleAlert size={32} className="mx-auto text-brand-forest" />
          <h1 className="mt-4 text-lg font-bold text-brand-charcoal">
            Something went wrong
          </h1>
          <p className="mt-1 text-sm text-escrow-subtext">
            The interface hit an unexpected error. Your funds are unaffected —
            balances live server-side.
          </p>
          <button
            onClick={() => window.location.reload()}
            className="mt-5 rounded-full bg-brand-neon px-5 py-2 text-sm font-semibold text-brand-forest hover:bg-brand-neonHover"
          >
            Reload
          </button>
        </div>
      </div>
    );
  }
}
