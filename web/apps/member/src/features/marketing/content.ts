/**
 * Landing page copy and the figures it shows. The offer and officer glimpses use Ana's worked example from
 * docs/features/f5-hardship-safety-net/03-algorithms.md and the live demo: a $1,200 loan over 8 months, $150 due on
 * the 20th, weekly income down from $200 to $96.
 */

export const NAV_LINKS = [
  { href: "#inclusion", label: "Who it is for" },
  { href: "#journey", label: "How it works" },
  { href: "#safety-net", label: "Safety net" },
  { href: "#trust", label: "Trust" },
  { href: "#faq", label: "FAQ" },
] as const;

export const HERO_CHECKS = ["No credit score", "0% interest", "$0 fees", "Starts at $5 a month"] as const;

/** An example, not a quote: three months of $25 saving unlocking a first loan. */
export const HERO_CARD = {
  goal: "Emergency buffer",
  saved: "$75",
  cadence: "$25 a month, three months in a row",
  months: ["Month 1", "Month 2", "Month 3"],
  loan: "$300",
  terms: "6 payments of $50. No fees, and no credit check.",
} as const;

export const PROOF_POINTS = [
  { value: "$5", label: "a month is enough to start saving" },
  { value: "0", label: "credit scores needed to qualify" },
  { value: "0%", label: "interest on every loan" },
  { value: "$0", label: "fees, including for paying early or late" },
  { value: "2", label: "different officers approve a pause or a new plan" },
] as const;

export const AUDIENCES = [
  {
    icon: "gig",
    title: "Gig and freelance workers",
    body: "Income that changes week to week, and lenders who only trust a steady paycheque.",
  },
  {
    icon: "new",
    title: "New to credit",
    body: "No score and no history, so no one will lend. Seed looks at saving and PayPal activity instead.",
  },
  {
    icon: "cash",
    title: "People who save outside banks",
    body: "Cash and wallet savers who never had a reason, or a way, to open an account.",
  },
  {
    icon: "young",
    title: "Young adults and newcomers",
    body: "Real earnings, too little history. A first fair loan that builds a record.",
  },
] as const satisfies ReadonlyArray<{ icon: "gig" | "new" | "cash" | "young"; title: string; body: string }>;

export const COMPARISON = [
  { label: "Credit score", typical: "Usually required", seed: "Never needed" },
  { label: "Cost of borrowing", typical: "Interest, plus fees", seed: "0% interest, $0 fees" },
  {
    label: "When a month goes wrong",
    typical: "Late fees, then collections",
    seed: "We reach out first, and a person approves a fix",
  },
  {
    label: "Where it lives",
    typical: "A branch or a separate app",
    seed: "Inside the PayPal app you already use",
  },
] as const;

export type JourneyTone = "mint" | "peach" | "lavender" | "sky" | "rose";

export const JOURNEY = [
  {
    step: "01",
    title: "Save a little",
    body: "Start from $5 a month toward something you name. PayPal collects it on your schedule, and you can pause or withdraw any time.",
    detail: "PayPal Subscriptions · Payouts",
    tone: "mint",
  },
  {
    step: "02",
    title: "Unlock a fair loan",
    body: "Three months of steady saving unlocks a 0% loan sized to what you can repay, from your own consented PayPal income. No credit score needed.",
    detail: "Transaction Search, with consent",
    tone: "sky",
  },
  {
    step: "03",
    title: "Repay without thinking",
    body: "Equal payments on the day you choose, collected by autopay. Pay early or catch up in one tap. Five days of grace, never a fee.",
    detail: "Autopay · Orders · Webhooks",
    tone: "lavender",
  },
  {
    step: "04",
    title: "Get help early",
    body: "If your income dips before a due date, Seed reaches out first with a few clear ways through the month. You choose; a person approves.",
    detail: "The safety net",
    tone: "peach",
  },
  {
    step: "05",
    title: "Build a record",
    body: "Every on-time payment becomes a verifiable record that opens a bigger loan. An approved pause counts as on plan.",
    detail: "Signed credit record",
    tone: "rose",
  },
] as const satisfies ReadonlyArray<{
  step: string;
  title: string;
  body: string;
  detail: string;
  tone: JourneyTone;
}>;

export const OFFER = {
  member: "Ana",
  dueLabel: "$150 due November 20",
  message:
    "Money looks tighter than usual this month. Your $150 payment is due November 20. You can pick another way to pay it. You choose, and you can say no.",
  options: [
    {
      label: "A",
      title: "Move the date",
      line: "Pay $150 on December 4 instead.",
      note: "Approved straight away",
      suggested: false,
    },
    {
      label: "B",
      title: "Pause one month",
      line: "This payment moves to the end of your plan.",
      note: "Reviewed by our team",
      suggested: false,
    },
    {
      label: "C",
      title: "Smaller, longer plan",
      line: "12 payments of $100 instead of $150.",
      note: "Reviewed by our team",
      suggested: true,
    },
    {
      label: "D",
      title: "Pay what you can",
      line: "Pay $61 now; $89 stays due November 20.",
      note: "No approval needed",
      suggested: false,
    },
  ],
} as const;

export const FLOW_STEPS = [
  { title: "Daily scan", body: "Loans due in the next 10 days or in grace." },
  { title: "Risk scorecard", body: "Points from consented income, failed autopay and late history." },
  { title: "Case and four options", body: "Opens at Likely to miss. Code computes every option." },
  { title: "Guardian writes the message", body: "Grounded in the numbers the code produced." },
  { title: "Twelve checks", body: "Any failure, AI off or late: a fixed template goes out instead." },
  { title: "Member chooses", body: "A, B, C, D or no thanks, in the app or by SMS reply." },
  { title: "New schedule version", body: "Applied after approval. The old plan is kept." },
] as const;

export const BRANCHES = [
  { label: "A", title: "Move date", outcome: "Approved by policy" },
  { label: "B", title: "Pause one month", outcome: "Two officers approve" },
  { label: "C", title: "Smaller, longer plan", outcome: "Two officers approve" },
  { label: "D", title: "Pay what you can", outcome: "Paid with PayPal, case closes" },
] as const;

export const GUARDRAILS = [
  {
    title: "No write access",
    body: "The agent reads one loan through a token that expires in ten minutes. Its only action is to submit a proposal.",
  },
  {
    title: "Twelve checks, all must pass",
    body: "Schema, binding, risk may only go up, options and recommendation from code, grounding, policy, outreach limits, language, length, case state.",
  },
  {
    title: "Every number is grounded",
    body: "Any amount, date or option in the AI's words must match what the code computed. Links, phone numbers and promises are refused.",
  },
  {
    title: "Personal data stays home",
    body: "Names, phone numbers and emails are stripped before any model call. A member's own note is sorted into a fixed category and never reaches the agent.",
  },
  {
    title: "A kill switch that trips itself",
    body: "Live, shadow or templates only. Too many rejected proposals switch the agent off automatically, and a daily spend cap backs it up.",
  },
  {
    title: "Two people, tamper-evident trail",
    body: "A pause or a new plan needs two different officers, enforced by the database. Every step lands in a hash-chained audit log.",
  },
] as const;

export const QUEUE_ROWS = [
  { member: "Ana R.", level: "Likely to miss", points: 7, option: "B", status: "Waiting for checker", sla: "6h left" },
  { member: "Marcus O.", level: "About to miss", points: 10, option: "A", status: "Applied", sla: "Done" },
  { member: "Priya S.", level: "Likely to miss", points: 6, option: "C", status: "Waiting for maker", sla: "9h left" },
  { member: "Daniel K.", level: "About to miss", points: 9, option: "—", status: "Offer sent", sla: "—" },
] as const;

export const EVIDENCE = [
  {
    code: "INCOME_DROP_SEVERE",
    name: "Income dropped sharply",
    points: 5,
    facts: ["down 52%", "last 4 weeks $384", "usual 4 weeks $800"],
  },
  {
    code: "LOW_COVERAGE",
    name: "Income barely covers the payment",
    points: 2,
    facts: ["covers 82.28%", "recent monthly income $411.42", "payment $150"],
  },
] as const;

export const BUILT_WITH = [
  {
    group: "PayPal",
    items: ["Subscriptions", "Payouts", "Orders + JS SDK", "Webhooks", "Transaction Search"],
  },
  {
    group: "Partners",
    items: ["AG Grid", "Bryntum", "Render", "Postman", "APIMatic", "Zapier", "OpenRouter"],
  },
] as const;

export const FAQS = [
  {
    q: "Who is Seed for?",
    a: "People who are underbanked: no bank account, no credit score, or an income that moves around. If you earn and save but lenders keep saying no, Seed starts from what you already do in PayPal.",
  },
  {
    q: "Do I need a credit score or a bank account?",
    a: "No credit score. Seed looks at steady saving and, with your consent, your own PayPal income. In the demo everything runs on a PayPal account.",
  },
  {
    q: "Is the loan really 0%?",
    a: "Yes. Loans carry 0% interest and no fees: not for paying early, not for paying late, and not for any hardship option.",
  },
  {
    q: "Does asking for help hurt my record?",
    a: "No. An approved pause or new plan counts as on plan in your Seed record. Asking early is exactly what the safety net is for.",
  },
  {
    q: "What does the AI actually see?",
    a: "Signals and numbers about one loan: the scorecard result, the schedule and the options the code built. It never sees your name, phone number or email, and it cannot change anything.",
  },
  {
    q: "What happens if the AI is down?",
    a: "Nothing breaks. A fixed, checked template goes out instead, and every option, approval and payment works the same way.",
  },
  {
    q: "Who approves a change to my plan?",
    a: "Moving a date up to 14 days is approved by policy as soon as you choose it. A pause or a smaller, longer plan needs two different officers. Paying what you can needs no approval.",
  },
  {
    q: "Is this real money?",
    a: "The demo runs on the PayPal Sandbox, so no real money moves. In production, lending is offered with a licensed partner.",
  },
] as const;
