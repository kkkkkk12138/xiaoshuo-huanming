import { createTextEngine } from '../content/textEngine';
import type { ReplaceRule } from '../shared/types';
import type { NameReplacerRuntime, OrderedReplaceRule } from './types';

export type { ApplyResult, NameReplacerRuntime, OrderedReplaceRule } from './types';

function isValidRules(rules: unknown): rules is OrderedReplaceRule[] {
  return (
    Array.isArray(rules) &&
    rules.every(
      (rule) =>
        typeof rule === 'object' &&
        rule !== null &&
        typeof rule.id === 'string' &&
        typeof rule.source === 'string' &&
        rule.source.length > 0 &&
        typeof rule.target === 'string' &&
        typeof rule.order === 'number' &&
        Number.isFinite(rule.order)
    )
  );
}

function orderRules(rules: OrderedReplaceRule[]): ReplaceRule[] {
  return [...rules]
    .sort((left, right) => left.order - right.order)
    .map(({ id, source, target }) => ({ id, source, target }));
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export function installNameReplacerRuntime(doc: Document): NameReplacerRuntime {
  const engine = createTextEngine();
  let installed = false;

  const runtime: NameReplacerRuntime = {
    install() {
      installed = true;
    },

    applyRules(rules) {
      if (!installed) {
        return {
          ok: false,
          code: 'NOT_INSTALLED',
          message: 'Name replacer runtime is not installed'
        };
      }

      if (!isValidRules(rules)) {
        return {
          ok: false,
          code: 'INVALID_RULES',
          message: 'Rules must contain a non-empty source and a finite order'
        };
      }

      try {
        const summary = engine.applyToDocument(doc, orderRules(rules));

        return {
          ok: true,
          activeRuleCount: rules.length,
          ...summary
        };
      } catch (error) {
        return {
          ok: false,
          code: 'RUNTIME_ERROR',
          message: errorMessage(error)
        };
      }
    },

    restoreOriginalText() {
      if (!installed) {
        return {
          ok: false,
          code: 'NOT_INSTALLED',
          message: 'Name replacer runtime is not installed'
        };
      }

      const summary = engine.applyToDocument(doc, []);
      return {
        ok: true,
        activeRuleCount: 0,
        ...summary
      };
    },

    renderedOffsetToSource(node, renderedOffset) {
      return engine.renderedOffsetToSource(node, renderedOffset);
    },

    sourceOffsetToRendered(node, sourceOffset) {
      return engine.sourceOffsetToRendered(node, sourceOffset);
    },

    dispose() {
      runtime.restoreOriginalText();
      installed = false;
    }
  };

  runtime.install();
  return runtime;
}
