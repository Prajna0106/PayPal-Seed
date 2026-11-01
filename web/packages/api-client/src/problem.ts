/**
 * RFC 9457 problem details from the backend (`application/problem+json`). The stable machine code is the last path
 * segment of `type` (`https://problems.paypal-seed.dev/sod-violation` → `sod-violation`), also sent as `code`.
 * Safe to import from client code: it holds no secrets.
 */

export interface ProblemBody {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: string;
  details?: Record<string, unknown>;
}

export class ApiProblem extends Error {
  readonly status: number;
  readonly code: string;
  readonly title: string;
  readonly detail: string | undefined;
  readonly details: Record<string, unknown>;

  constructor(status: number, body: ProblemBody | undefined) {
    const code = body?.code ?? codeFromType(body?.type) ?? `http-${status}`;
    super(body?.detail ?? body?.title ?? `Request failed with status ${status}`);
    this.name = "ApiProblem";
    this.status = status;
    this.code = code;
    this.title = body?.title ?? "Request failed";
    this.detail = body?.detail;
    this.details = body?.details ?? {};
  }

  /** A plain, serialisable copy for Server Action results (Error instances do not cross the boundary). */
  toJSON(): { status: number; code: string; title: string; detail?: string } {
    return { status: this.status, code: this.code, title: this.title, ...(this.detail ? { detail: this.detail } : {}) };
  }
}

function codeFromType(type: string | undefined): string | undefined {
  if (!type) {
    return undefined;
  }
  const segment = type.split("/").filter(Boolean).at(-1);
  return segment && segment !== "about:blank" ? segment : undefined;
}

export function isProblem(error: unknown, code?: string): error is ApiProblem {
  return error instanceof ApiProblem && (code === undefined || error.code === code);
}

/** Plain-language messages for the problem codes a person can act on. Unknown codes fall back to the title. */
export const PROBLEM_MESSAGES: Record<string, string> = {
  "sod-violation": "A second, different officer has to make this decision.",
  "hardship-offer-stale": "The numbers changed since this offer was made. Please review the updated options.",
  "approval-stale": "The plan changed while this was waiting. The request was withdrawn and must be reviewed again.",
  "hardship-policy-violation": "This option is not available for this loan right now.",
  "hardship-not-eligible": "This loan cannot use the safety net right now.",
  "hardship-no-open-offer": "There is no open offer for this loan.",
  "hardship-case-closed": "This request has already been closed.",
  "agent-disabled": "The assistant is switched off. Messages use our standard templates.",
  "idempotency-key-reused": "This action was already sent with different details. Please refresh and try again.",
  "reason-code-required": "Choose a reason for this decision.",
  "insufficient-scope": "Your role cannot do this.",
  "forbidden": "Your role cannot do this.",
  "unauthenticated": "Please sign in again.",
};

export function problemMessage(problem: Pick<ApiProblem, "code" | "title" | "detail">): string {
  return PROBLEM_MESSAGES[problem.code] ?? problem.detail ?? problem.title;
}
