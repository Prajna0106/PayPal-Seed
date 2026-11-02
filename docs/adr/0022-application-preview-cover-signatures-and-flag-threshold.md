# ADR 0022: Application Preview, Cover Request Signatures and the Fraud Flag Threshold

## Status
Accepted. Closes three gaps the web found in the backend of [ADR 0021](0021-wiring-desk-reserve-fraud-record-and-withdrawal-messages.md).

## Context
- A member choosing an amount and a term could not see the monthly payment or the schedule before applying. The web may not compute money, so it showed nothing.
- A cover request carried no signatures and no deadline, so the desk could not show who had signed or that the decision was overdue.
- The fraud flag list carried scores but not the threshold they are read against, so the web made a second call per screen.

## Decision

### `POST /api/v1/loans/applications/preview` (member role)
`MicroloanApplicationService.previewApplication` runs the same code as an application and its disbursement: `buildEligibilityInput`, `EligibilityEngine.checkApplication`, and `LoanRegistration.originalSchedule`, the one method that `LoanRegistration.register` also calls to build schedule version 1. The preview therefore cannot disagree with the loan the member gets. It is `@Transactional(readOnly = true)`, stores nothing and calls no one but the readers eligibility already uses (savings, the loan and application tables, underwriting income, the credit record).

- A request outside the member's limits is a `200` with `valid: false`, a plain-words `reasons` list, the suggested alternative the engine computed, and an `outcome` (`APPROVED`, `NOT_ELIGIBLE`, `AMOUNT_NOT_ALLOWED`, `TERM_OUT_OF_RANGE`, `ABOVE_LIMIT`, `INSTALMENT_ABOVE_CAP`). The outcome is a new field of the engine's `SizingDecision`, set where each rule decides, so the web never has to infer which rule fired from text.
- Only shapes that cannot be a loan at all (an amount of zero or above $10,000,000, a term outside 1 to 60, a due day outside 1 to 28) are `400`.
- Dates are counted from today's business date (`assumedDisbursementDate`). If the loan is paid out on a later day the due dates move with it; the response says so.
- `memberId` in the body is accepted only if it is the caller's own (otherwise `404`); staff get `403`.

### Cover requests: `slaDueAt` and `approvals`
`CoverRequestView` gains `slaDueAt` and `approvals` (role MAKER or CHECKER, `staffId`, `decidedAt`), read from the governance approval request that already carries the SLA (`seed.reserve.cover.decision-sla`, set when the request was opened) and its decisions. Only approvals count as signatures: a rejection closes the request and is not listed, and policy decisions do not occur here. `governance.DecisionView` gains `staffId` beside the pseudonymous `staffRef`: events and read models keep carrying only the pseudonym, while the desk resolves the id to a name with the staff directory, as it does for loan applications. A list page reads one approval request per row (at most 200).

### Fraud flag page: `flagThreshold`
`FlagPage` carries `flagThreshold`, the configured `seed.fraud.flag-threshold`. It is on the page, not on each row, because it is one setting; `FlagDetail` and `FlagEvidence` already had it.

## Consequences
- The member screens can show the monthly payment as the amount or term moves, and the full schedule before "Submit application", with every figure from Java.
- The preview is a read of live state, so two calls can differ if the member's savings change between them; the application re-checks everything when submitted.
- The desk can show maker, checker and an SLA breach on cover requests without an extra call; the cost is one governance read per row on the list.
- `EligibilityEngine.SizingDecision` and `governance.DecisionView` gained a component each; both are built in one place.
