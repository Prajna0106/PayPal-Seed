import "server-only";

import { ApiProblem, type Schemas, unwrap, unwrapOrNull } from "@paypal-seed/api-client";
import { notFound, unstable_rethrow } from "next/navigation";
import { memberApi } from "@/server/api";
import { requireSession } from "@/server/session";
import { type Goal, toGoal, toWithdrawal, type Wallet, type Withdrawal } from "../model";

type HomeView = Schemas["HomeView"];

/** The member's home summary, or `null` when it cannot be read: savings pages still work without it. */
export async function readHome(): Promise<HomeView | null> {
  try {
    const api = await memberApi();
    return await unwrap(api.GET("/api/v1/members/me/home"));
  } catch (error) {
    unstable_rethrow(error);
    console.error("[savings] home summary unavailable", error);
    return null;
  }
}

export interface SavingsOverview {
  goals: Goal[];
  /** The member's saving streak (one streak across all goals), when the summary could be read. */
  streakMonths: number | null;
  /** Saved across all goals, as the home summary reports it. */
  totalSavedCents: number | null;
}

/** Every goal, oldest first, with the member's streak, read in parallel. */
export async function getSavingsOverview(): Promise<SavingsOverview> {
  const [session, api] = await Promise.all([requireSession(), memberApi()]);
  const [goals, home] = await Promise.all([
    unwrap(api.GET("/api/v1/savings/members/{memberId}/goals", { params: { path: { memberId: session.memberId } } })),
    readHome(),
  ]);
  return {
    goals: goals.map(toGoal),
    streakMonths: home?.savings?.currentStreakMonths ?? null,
    totalSavedCents: home?.savings?.totalSaved.cents ?? null,
  };
}

export interface GoalDetail {
  goal: Goal;
  withdrawals: Withdrawal[];
  streakMonths: number | null;
}

/** One goal with its withdrawals. Another member's goal and a missing goal both answer 404. */
export async function getGoalDetail(goalId: string): Promise<GoalDetail> {
  const api = await memberApi();
  const path = { params: { path: { goalId } } };
  try {
    const [goal, withdrawals, home] = await Promise.all([
      unwrap(api.GET("/api/v1/savings/goals/{goalId}", path)),
      unwrap(api.GET("/api/v1/savings/goals/{goalId}/withdrawals", path)),
      readHome(),
    ]);
    return {
      goal: toGoal(goal),
      withdrawals: withdrawals.map(toWithdrawal),
      streakMonths: home?.savings?.currentStreakMonths ?? null,
    };
  } catch (error) {
    unstable_rethrow(error);
    if (error instanceof ApiProblem && error.status === 404) {
      notFound();
    }
    throw error;
  }
}

/** A goal for the return page: `null` when the id is unknown or not the member's. */
export async function findGoal(goalId: string): Promise<Goal | null> {
  const api = await memberApi();
  const goal = await unwrapOrNull(api.GET("/api/v1/savings/goals/{goalId}", { params: { path: { goalId } } }));
  return goal ? toGoal(goal) : null;
}

export interface NewGoalDefaults {
  wallet: Wallet;
  language: "en" | "es";
}

/** The wallet the member usually uses and their language, so the form starts in the right place. */
export async function getNewGoalDefaults(): Promise<NewGoalDefaults> {
  const home = await readHome();
  return { wallet: home?.profile?.walletPreference ?? "PAYPAL", language: home?.profile?.language ?? "en" };
}
