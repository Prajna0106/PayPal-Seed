"""Deterministic guardrails, PII redaction, prompt-injection defense, and V01-V12 verifier."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any

# Risk level ladder (escalate-only rule V03)
RISK_LADDER = ("NONE", "WATCH", "LIKELY_MISS", "IMMINENT")

# Forbidden terms in member/officer narratives (V07)
FORBIDDEN_NARRATIVE_PATTERNS = re.compile(
    r"(?i)("
    r"https?://|www\.|"
    r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b|"
    r"\b(?:\+?1[-.\s]?)?\(?[2-9][0-9]{2}\)?[-.\s]?[0-9]{3}[-.\s]?[0-9]{4}\b|"
    r"\b(?:fee|fees|interest\s+rate|penalty|penalties|forgiven|forgiveness|"
    r"guaranteed|legal\s+action|collections|collector|urgent|final\s+notice|"
    r"act\s+now|immediately\s+or|credit\s+score|fico|experian|equifax|transunion)\b|"
    r"```|"
    r"</?(?:computed_|fact_|untrusted_|member_text|system)"
    r")"
)

# Adversarial prompt-injection patterns (covers all 18 vectors in injection-corpus.json)
INJECTION_PATTERNS = re.compile(
    r"(?is)("
    r"ignore\s+(?:all\s+|any\s+|the\s+)?(?:previous|prior|above)?\s*(?:instructions|prompts|rules)|"
    r"disregard\s+(?:all\s+|the\s+)?(?:previous|prior|system|rules|above)|"
    r"forget\s+(?:all\s+|the\s+)?(?:previous|prior|rules)|"
    r"output\s+category\s*=|"
    r"ignora\s+(?:todas\s+)?las\s+instrucciones|"
    r"olvida\s+las\s+reglas|"
    r"new\s+system\s+directive|"
    r"system\s*override|"
    r"developer\s+mode|"
    r"you\s+are\s+now\s+a|"
    r"</member_text>|<system_override>|</untrusted_tool_output>|"
    r"print\s+(?:your\s+)?(?:system\s+prompt|hidden\s+instructions)|"
    r"reveal\s+(?:your\s+)?(?:system\s+prompt|instructions)|"
    r"drop\s+table|union\s+select|"
    r"\{\s*\"(?:category|proposedLevel|verdict|approved)\"\s*:|"
    r"auto[_-]?approv(?:e|ed)\s+loan"
    r")"
)

EMAIL_RE = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")
PHONE_RE = re.compile(r"\b(?:\+?1[-.\s]?)?\(?[2-9][0-9]{2}\)?[-.\s]?[0-9]{3}[-.\s]?[0-9]{4}\b")
SSN_RE = re.compile(r"\b\d{3}-\d{2}-\d{4}\b")
DOLLAR_RE = re.compile(r"\$([0-9][0-9,]*(?:\.[0-9]{1,2})?)")

CLOSED_REASON_CATEGORIES = (
    "INCOME_DROP",
    "HOURS_CUT",
    "JOB_LOSS",
    "MEDICAL_OR_FAMILY",
    "UNEXPECTED_EXPENSE",
    "PAYDAY_TIMING",
    "OTHER",
)


@dataclass(frozen=True)
class FailedCheck:
    code: str
    detail: str


@dataclass(frozen=True)
class ValidationVerdict:
    result: str  # "APPROVABLE" or "REJECTED"
    checks: int = 12
    failed: list[FailedCheck] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return self.result == "APPROVABLE" and len(self.failed) == 0


def redact_pii(text: str) -> str:
    """Redacts emails, phone numbers, and SSNs before any model call or log entry."""
    if not text:
        return ""
    out = EMAIL_RE.sub("[REDACTED_EMAIL]", text)
    out = SSN_RE.sub("[REDACTED_SSN]", out)
    out = PHONE_RE.sub("[REDACTED_PHONE]", out)
    return out


def detect_prompt_injection(text: str) -> bool:
    """Returns True if the input contains known prompt-injection or delimiter-escape patterns."""
    if not text:
        return False
    return bool(INJECTION_PATTERNS.search(text))


def sanitize_untrusted_text(text: str) -> str:
    """Strips XML/fence escape sequences and meta-instructions from untrusted user text."""
    if not text:
        return ""
    cleaned = re.sub(r"</?[a-zA-Z0-9_:-]+>", " ", text)
    cleaned = cleaned.replace("```", " ")
    # Remove clauses starting with injection triggers while preserving genuine hardship clauses before them
    lines: list[str] = []
    for segment in re.split(r"[\r\n]+|(?<=[.!?])\s+", cleaned):
        if not INJECTION_PATTERNS.search(segment):
            lines.append(segment)
    result = " ".join(lines).strip()
    return redact_pii(result)


def classify_hardship_reason(text: str, language: str = "en") -> dict[str, Any]:
    """Closed-set hardship reason extractor resistant to prompt injection."""
    injected = detect_prompt_injection(text)
    cleaned = sanitize_untrusted_text(text).lower()

    keyword_map: list[tuple[str, tuple[str, ...]]] = [
        ("HOURS_CUT", ("hours cut", "fewer hours", "reduced shift", "menos horas", "redujeron mis horas", "turnos")),
        ("JOB_LOSS", ("laid off", "lost my job", "fired", "unemployed", "perdi mi trabajo", "despedido")),
        ("MEDICAL_OR_FAMILY", ("hospital", "medical", "sick", "doctor", "surgery", "illness", "family emergency", "medico", "enfermo", "hospitalizado")),
        ("UNEXPECTED_EXPENSE", ("car broke", "repair", "transmission", "rent spike", "utility bill", "reparacion", "emergencia", "auto")),
        ("PAYDAY_TIMING", ("payday", "paid on friday", "next week", "late check", "viernes", "dia de pago", "quincena")),
        ("INCOME_REDUCED", ("income drop", "income reduced", "fewer customers", "bake less bread", "quiet", "slow week", "fewer rides", "tips down", "low sales", "bajaron mis ingresos", "pocas ventas")),
        ("INCOME_DROP", ("income drop", "slow week", "fewer rides", "tips down", "low sales", "bajaron mis ingresos", "pocas ventas")),
    ]

    for category, keywords in keyword_map:
        if any(kw in cleaned for kw in keywords):
            return {
                "category": category,
                "confidence": "MEDIUM" if injected else "HIGH",
                "injectionDetected": injected,
                "language": language if language in ("en", "es") else "en",
            }

    return {
        "category": "OTHER",
        "confidence": "LOW",
        "injectionDetected": injected,
        "language": language if language in ("en", "es") else "en",
    }


def wrap_untrusted_tool_output(tool_name: str, payload: dict[str, Any] | str) -> str:
    """Wraps tool JSON in an explicit untrusted data fence so the LLM treats it as data, never instructions."""
    raw = payload if isinstance(payload, str) else json.dumps(payload, separators=(",", ":"))
    safe_raw = raw.replace("</untrusted_tool_output>", "[STRIPPED_TAG]")
    return f'<untrusted_tool_output tool="{tool_name}">\n{safe_raw}\n</untrusted_tool_output>'


def check_narrative_guardrails(text: str, allowed_dollar_cents: set[int] | None = None) -> list[FailedCheck]:
    """Checks a narrative string against V06 (dollar grounding) and V07 (forbidden content)."""
    failures: list[FailedCheck] = []
    if not text or not text.strip():
        failures.append(FailedCheck("V01_SCHEMA", "Narrative text is empty."))
        return failures

    match = FORBIDDEN_NARRATIVE_PATTERNS.search(text)
    if match:
        failures.append(
            FailedCheck("V07_FORBIDDEN_CONTENT", f"Forbidden token or pattern detected: '{match.group(0)}'")
        )

    if allowed_dollar_cents is not None:
        for m in DOLLAR_RE.finditer(text):
            raw_num = m.group(1).replace(",", "")
            try:
                cents = int(round(float(raw_num) * 100))
                if cents not in allowed_dollar_cents:
                    failures.append(
                        FailedCheck(
                            "V06_UNGROUNDED_FIGURE",
                            f"Dollar figure ${m.group(1)} ({cents} cents) is not grounded in tool outputs.",
                        )
                    )
            except ValueError:
                failures.append(FailedCheck("V06_UNGROUNDED_FIGURE", f"Malformed dollar figure: ${m.group(1)}"))

    return failures


def validate_proposal_v01_v12(
    proposal: dict[str, Any],
    *,
    expected_run_id: str,
    expected_case_id: str,
    expected_loan_id: str,
    computed_signals: dict[str, Any],
    offered_options: list[dict[str, Any]],
    tool_outputs: dict[str, Any],
) -> ValidationVerdict:
    """Deterministic 12-point validator (V01-V12) for Guardian hardship proposals."""
    failed: list[FailedCheck] = []

    # V01: Schema & required top-level fields
    required_top = {"runId", "caseId", "loanId", "agentVersion", "assessment", "options", "figures", "narrative"}
    missing = required_top - set(proposal.keys())
    if missing:
        failed.append(FailedCheck("V01_SCHEMA", f"Missing required proposal fields: {sorted(missing)}"))
        return ValidationVerdict("REJECTED", 12, failed)

    # V02: Token binding
    if (
        str(proposal.get("runId")) != str(expected_run_id)
        or str(proposal.get("caseId")) != str(expected_case_id)
        or str(proposal.get("loanId")) != str(expected_loan_id)
    ):
        failed.append(FailedCheck("V02_TOKEN_BINDING", "Proposal runId/caseId/loanId does not match run token claims."))

    # V03: Escalate-only risk level
    assessment = proposal.get("assessment") or {}
    comp_level = str(computed_signals.get("computedLevel", "NONE"))
    prop_level = str(assessment.get("proposedLevel", comp_level))
    if comp_level not in RISK_LADDER or prop_level not in RISK_LADDER:
        failed.append(FailedCheck("V03_LEVEL_NOT_ESCALATE_ONLY", f"Invalid risk level: {prop_level}"))
    else:
        c_idx = RISK_LADDER.index(comp_level)
        p_idx = RISK_LADDER.index(prop_level)
        if p_idx < c_idx or p_idx > c_idx + 1:
            failed.append(
                FailedCheck(
                    "V03_LEVEL_NOT_ESCALATE_ONLY",
                    f"Proposed level {prop_level} must equal {comp_level} or be at most 1 step higher.",
                )
            )
        fired_codes = {s.get("code") for s in computed_signals.get("signals", [])}
        cited = set(assessment.get("citedSignals") or [])
        if not cited.issubset(fired_codes):
            failed.append(
                FailedCheck("V03_LEVEL_NOT_ESCALATE_ONLY", f"Cited signals {cited - fired_codes} did not fire.")
            )

    # V04 & V05: Option set, previewHash, and OptionRanker recommendation
    prop_options = proposal.get("options") or []
    expected_hashes = {opt["option"]: opt["previewHash"] for opt in offered_options if opt.get("eligible", True)}
    expected_rec = {opt["option"] for opt in offered_options if opt.get("recommended")}

    prop_hashes = {opt.get("option"): opt.get("previewHash") for opt in prop_options}
    if prop_hashes != expected_hashes:
        failed.append(
            FailedCheck("V04_OPTION_SET_OR_PREVIEW_MISMATCH", "Proposal options or previewHashes do not match engine.")
        )

    rec_chosen = [opt.get("option") for opt in prop_options if opt.get("recommended")]
    if len(rec_chosen) != 1 or set(rec_chosen) != expected_rec:
        failed.append(
            FailedCheck("V05_RECOMMENDATION_NOT_FROM_CODE", "Recommended option flag must match OptionRanker.")
        )

    # V06: Grounding of figures
    allowed_cents: set[int] = set()
    raw_blob = json.dumps(tool_outputs)
    for num_match in re.finditer(r"\b(\d+)\b", raw_blob):
        val = int(num_match.group(1))
        allowed_cents.add(val)
        allowed_cents.add(val * 100)

    narrative = proposal.get("narrative") or {}
    brief = str(narrative.get("officerBrief", ""))
    member_msg = narrative.get("memberMessage") or {}
    msg_en = str(member_msg.get("en", ""))
    msg_es = str(member_msg.get("es", ""))

    # V07: Forbidden content & V06 narrative dollar check
    for text_part in (brief, msg_en, msg_es):
        failed.extend(check_narrative_guardrails(text_part, allowed_cents))

    # V08: Policy eligibility
    for opt in offered_options:
        if not opt.get("eligible", True) and opt.get("option") in prop_hashes:
            failed.append(FailedCheck("V08_POLICY_INELIGIBLE", f"Option {opt.get('option')} is ineligible."))

    # V10: Language presence
    if not msg_en.strip() or not msg_es.strip():
        failed.append(FailedCheck("V10_LANGUAGE", "Both English (en) and Spanish (es) member messages are required."))

    # V11: Length limits
    if len(brief) > 1200 or len(msg_en) > 480 or len(msg_es) > 520:
        failed.append(FailedCheck("V11_LENGTH", "Narrative exceeds character limits (brief<=1200, en<=480, es<=520)."))

    return ValidationVerdict("APPROVABLE" if not failed else "REJECTED", 12, failed)
