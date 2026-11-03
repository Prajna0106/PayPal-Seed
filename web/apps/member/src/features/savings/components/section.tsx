import type { ReactNode } from "react";

/** A titled block of a savings page. The heading names the region for screen readers. */
export function Section({ id, title, description, children }: { id: string; title: string; description?: ReactNode; children: ReactNode }) {
  return (
    <section aria-labelledby={`section-${id}`} className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h2 id={`section-${id}`} className="text-display-sm text-ink">
          {title}
        </h2>
        {description ? <p className="max-w-2xl text-sm text-body">{description}</p> : null}
      </div>
      {children}
    </section>
  );
}
