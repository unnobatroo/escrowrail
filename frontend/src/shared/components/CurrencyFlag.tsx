const FLAGS: Record<string, React.ReactNode> = {
  USD: (
    <>
      <rect width="20" height="20" fill="#B22234" />
      {[2, 4, 6, 8, 10, 12].map((y) => (
        <rect key={y} y={y * 1.55} width="20" height="1.55" fill="#fff" />
      ))}
      <rect width="9" height="10.85" fill="#3C3B6E" />
    </>
  ),
  EUR: (
    <>
      <rect width="20" height="20" fill="#003399" />
      {Array.from({ length: 12 }, (_, i) => {
        const a = (i * Math.PI) / 6 - Math.PI / 2;
        return (
          <circle
            key={i}
            cx={10 + 4.6 * Math.cos(a)}
            cy={10 + 4.6 * Math.sin(a)}
            r="0.8"
            fill="#FFCC00"
          />
        );
      })}
    </>
  ),
  GBP: (
    <>
      <rect width="20" height="20" fill="#012169" />
      <path d="M0 0l20 20M20 0L0 20" stroke="#fff" strokeWidth="3.4" />
      <path d="M0 0l20 20M20 0L0 20" stroke="#C8102E" strokeWidth="1.4" />
      <path d="M10 0v20M0 10h20" stroke="#fff" strokeWidth="5" />
      <path d="M10 0v20M0 10h20" stroke="#C8102E" strokeWidth="2.6" />
    </>
  ),
};

/** Circular 20px currency flag badge (SVG, no emoji fonts). */
export function CurrencyFlag({ code }: { code: string }) {
  return (
    <svg
      viewBox="0 0 20 20"
      className="h-5 w-5 shrink-0 rounded-full ring-1 ring-black/10"
      aria-hidden="true"
      role="img"
    >
      {FLAGS[code] ?? (
        <>
          <rect width="20" height="20" fill="#E2E6DC" />
          <text
            x="10"
            y="13.5"
            textAnchor="middle"
            fontSize="8"
            fontWeight="700"
            fill="#5D7052"
          >
            {code.slice(0, 2)}
          </text>
        </>
      )}
    </svg>
  );
}

/** Flag + currency code, optionally with a context sublabel underneath. */
export function CurrencyBadge({
  code,
  sublabel,
}: {
  code: string;
  sublabel?: string;
}) {
  return (
    <span className="flex items-center gap-2">
      <CurrencyFlag code={code} />
      <span className="flex flex-col leading-tight">
        <span className="text-sm font-bold text-brand-charcoal">{code}</span>
        {sublabel && (
          <span className="text-[10px] text-brand-subtle">{sublabel}</span>
        )}
      </span>
    </span>
  );
}
