import type { Risk, ToolAssessment } from "./tools.ts";

export type Effect = "allow" | "ask" | "deny";

export interface PolicyRule {
  /** Tool name, "*", or a prefix pattern like "fs.*". */
  tool: string;
  /** Optional: restrict the rule to these risks. */
  risks?: Risk[];
  effect: Effect;
  reason?: string;
}

export interface PolicyDecisionInput {
  tool: string;
  assessment: ToolAssessment;
  /**
   * True once the task has read workspace content. After that, even "read-only" network requests can
   * leak what was read (to an attacker-chosen host, e.g. after a prompt injection), so they need approval.
   */
  tainted?: boolean;
}

export interface PolicyDecision {
  effect: Effect;
  reason: string;
}

/**
 * Policy Engine (decision D7: deny-by-default).
 * Rules are evaluated in order; first match wins; no match => deny.
 * Scope violations reported by the tool itself are always denied, regardless of rules.
 */
export class PolicyEngine {
  private rules: PolicyRule[];
  constructor(rules: PolicyRule[]) {
    this.rules = rules;
  }

  evaluate(input: PolicyDecisionInput): PolicyDecision {
    if (input.assessment.violation) {
      return { effect: "deny", reason: `scope violation: ${input.assessment.violation}` };
    }
    // Consent given in advance and recorded by the tool itself (never a deny rule's business: a violation was refused above).
    if (input.assessment.preApproved) return { effect: "allow", reason: `approved in advance: ${input.assessment.preApproved}` };
    for (const r of this.rules) {
      if (!matchesTool(r.tool, input.tool)) continue;
      if (r.risks && !r.risks.includes(input.assessment.risk)) continue;
      if (r.effect === "allow" && input.assessment.risk === "network_read") {
        if (input.assessment.exfilRisk) return { effect: "ask", reason: "request carries data in its URL (query/fragment)" };
        if (input.tainted) return { effect: "ask", reason: "network request after reading workspace content could leak it" };
      }
      return { effect: r.effect, reason: r.reason ?? `rule for ${r.tool}` };
    }
    return { effect: "deny", reason: "no matching allow rule (deny-by-default)" };
  }
}

function matchesTool(pattern: string, tool: string): boolean {
  if (pattern === "*") return true;
  if (pattern.endsWith(".*")) return tool.startsWith(pattern.slice(0, -1));
  return pattern === tool;
}

/**
 * Conservative defaults for a new install:
 *  - read inside the workspace and public GETs run automatically,
 *  - writes inside the workspace run automatically (scope is enforced by the tool),
 *  - delete, non-GET network calls, and any command execution need approval,
 *  - anything unclassified is denied.
 */
export const DEFAULT_RULES: PolicyRule[] = [
  { tool: "*", risks: ["read"], effect: "allow", reason: "read-only inside workspace" },
  { tool: "*", risks: ["network_read"], effect: "allow", reason: "read-only public network request" },
  { tool: "*", risks: ["write"], effect: "allow", reason: "write inside workspace" },
  { tool: "*", risks: ["delete"], effect: "ask", reason: "deletion requires approval" },
  { tool: "*", risks: ["network_write"], effect: "ask", reason: "network write requires approval" },
  { tool: "*", risks: ["exec"], effect: "ask", reason: "command execution requires approval" },
  { tool: "*", risks: ["external_effect"], effect: "ask", reason: "external side effect requires approval" },
];
