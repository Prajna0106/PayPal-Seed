import type { StaffRole } from "@paypal-seed/api-client/types";

/**
 * Demo staff. Fixed ids so the maker and the checker are always different people; the backend enforces separation
 * of duties on these ids. Real staff sign-in (passkeys) belongs to the identity feature.
 */
export interface Persona {
  id: string;
  name: string;
  title: string;
  roles: readonly StaffRole[];
}

export const PERSONAS = [
  {
    id: "0b5e1f4a-6d2c-4c7e-9a1b-3f6e2d8c4a01",
    name: "Aira Jena",
    title: "Loan officer (maker)",
    roles: ["OFFICER"],
  },
  {
    id: "0b5e1f4a-6d2c-4c7e-9a1b-3f6e2d8c4a02",
    name: "Priyam Priyanshu Pradhan",
    title: "Senior officer (checker)",
    roles: ["CHECKER"],
  },
  {
    id: "0b5e1f4a-6d2c-4c7e-9a1b-3f6e2d8c4a03",
    name: "Prajna Jeet Ojha",
    title: "Operations admin",
    roles: ["OFFICER", "CHECKER", "ADMIN"],
  },
] as const satisfies readonly Persona[];

export function personaById(id: string): Persona | undefined {
  return PERSONAS.find((persona) => persona.id === id);
}
