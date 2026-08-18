export default function Logomark({ size = 20 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="3.2" fill="var(--accent-fg)" />
      <g stroke="var(--accent-fg)" strokeWidth="1.4" strokeLinecap="round">
        <line x1="12" y1="8.6" x2="12" y2="3.6" />
        <line x1="12" y1="15.4" x2="12" y2="20.4" />
        <line x1="8.8" y1="10.2" x2="4.3" y2="7.6" />
        <line x1="15.2" y1="13.8" x2="19.7" y2="16.4" />
        <line x1="8.8" y1="13.8" x2="4.3" y2="16.4" />
      </g>
      <circle cx="12" cy="3.6" r="1.6" fill="var(--accent-fg)" />
      <circle cx="12" cy="20.4" r="1.6" fill="var(--accent-fg)" opacity="0.55" />
      <circle cx="4.3" cy="7.6" r="1.6" fill="var(--accent-fg)" opacity="0.85" />
      <circle cx="19.7" cy="16.4" r="1.6" fill="var(--accent-fg)" opacity="0.7" />
      <circle cx="4.3" cy="16.4" r="1.6" fill="var(--accent-fg)" opacity="0.55" />
    </svg>
  );
}
