export type OrderedReplaceRule = {
  id: string;
  source: string;
  target: string;
  order: number;
};

export type ApplyResult =
  | {
      ok: true;
      activeRuleCount: number;
      changedTextNodeCount: number;
      replacementCount: number;
      perRule: Array<{
        ruleId: string;
        replacementCount: number;
      }>;
    }
  | {
      ok: false;
      code: 'NOT_INSTALLED' | 'INVALID_RULES' | 'RUNTIME_ERROR';
      message: string;
    };

export interface NameReplacerRuntime {
  install(): void;
  applyRules(rules: OrderedReplaceRule[]): ApplyResult;
  restoreOriginalText(): ApplyResult;
  renderedOffsetToSource(node: Text, renderedOffset: number): number;
  sourceOffsetToRendered(node: Text, sourceOffset: number): number;
  dispose(): void;
}
