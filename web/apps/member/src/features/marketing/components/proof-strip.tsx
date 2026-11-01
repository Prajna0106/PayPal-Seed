import { PROOF_POINTS } from "../content";

export function ProofStrip() {
  return (
    <section aria-label="Seed in numbers" className="border-y border-hairline bg-surface/60">
      <dl className="mx-auto grid max-w-6xl grid-cols-2 gap-x-6 gap-y-8 px-6 py-10 sm:grid-cols-3 lg:grid-cols-5">
        {PROOF_POINTS.map((point) => (
          <div key={point.label} className="flex flex-col-reverse gap-1">
            <dt className="text-sm leading-snug text-body">{point.label}</dt>
            <dd className="text-display-md text-ink tabular-nums">{point.value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
