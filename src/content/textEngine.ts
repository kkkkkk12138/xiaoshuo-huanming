import type { ReplaceRule } from '../shared/types';
import { shouldProcessTextNode } from './domFilter';

export type RuleMatchCount = {
  ruleId: string;
  replacementCount: number;
};

export type TextApplySummary = {
  changedTextNodeCount: number;
  replacementCount: number;
  perRule: RuleMatchCount[];
};

type MappingSegment = {
  sourceStart: number;
  sourceEnd: number;
  renderedStart: number;
  renderedEnd: number;
  replaced: boolean;
};

type NodeTransform = {
  text: string;
  mapping: MappingSegment[];
  counts: number[];
};

type PreparedRule = {
  rule: ReplaceRule;
  inputOrder: number;
};

export type TextEngine = {
  applyToDocument(doc: Document, rules: ReplaceRule[]): TextApplySummary;
  applyToNode(root: Node, rules: ReplaceRule[]): TextApplySummary;
  refreshOriginal(node: Text): void;
  renderedOffsetToSource(node: Text, renderedOffset: number): number;
  sourceOffsetToRendered(node: Text, sourceOffset: number): number;
};

function orderedRules(rules: ReplaceRule[]): PreparedRule[] {
  return rules
    .map((rule, inputOrder) => ({ rule, inputOrder }))
    .filter(({ rule }) => rule.source.length > 0)
    .sort(
      (left, right) =>
        right.rule.source.length - left.rule.source.length || left.inputOrder - right.inputOrder
    );
}

function appendMapping(segments: MappingSegment[], next: MappingSegment): void {
  const previous = segments.at(-1);

  if (
    previous &&
    !previous.replaced &&
    !next.replaced &&
    previous.sourceEnd === next.sourceStart &&
    previous.renderedEnd === next.renderedStart
  ) {
    previous.sourceEnd = next.sourceEnd;
    previous.renderedEnd = next.renderedEnd;
    return;
  }

  segments.push(next);
}

function transformText(baseText: string, rules: ReplaceRule[]): NodeTransform {
  const candidates = orderedRules(rules);
  const counts = rules.map(() => 0);
  const mapping: MappingSegment[] = [];
  const output: string[] = [];
  let sourceOffset = 0;
  let renderedOffset = 0;

  while (sourceOffset < baseText.length) {
    const match = candidates.find(({ rule }) => baseText.startsWith(rule.source, sourceOffset));

    if (match) {
      output.push(match.rule.target);
      appendMapping(mapping, {
        sourceStart: sourceOffset,
        sourceEnd: sourceOffset + match.rule.source.length,
        renderedStart: renderedOffset,
        renderedEnd: renderedOffset + match.rule.target.length,
        replaced: true
      });
      sourceOffset += match.rule.source.length;
      renderedOffset += match.rule.target.length;
      counts[match.inputOrder] += 1;
      continue;
    }

    output.push(baseText[sourceOffset]);
    appendMapping(mapping, {
      sourceStart: sourceOffset,
      sourceEnd: sourceOffset + 1,
      renderedStart: renderedOffset,
      renderedEnd: renderedOffset + 1,
      replaced: false
    });
    sourceOffset += 1;
    renderedOffset += 1;
  }

  return { text: output.join(''), mapping, counts };
}

function emptySummary(rules: ReplaceRule[]): TextApplySummary {
  return {
    changedTextNodeCount: 0,
    replacementCount: 0,
    perRule: rules.map((rule) => ({ ruleId: rule.id, replacementCount: 0 }))
  };
}

function mergeCounts(target: number[], source: number[]): void {
  source.forEach((count, index) => {
    target[index] += count;
  });
}

function clampOffset(offset: number, length: number): number {
  if (!Number.isFinite(offset)) {
    return 0;
  }
  return Math.min(Math.max(Math.trunc(offset), 0), length);
}

function mapRenderedToSource(
  segments: MappingSegment[],
  renderedOffset: number,
  sourceLength: number,
  renderedLength: number
): number {
  const offset = clampOffset(renderedOffset, renderedLength);
  if (offset === renderedLength) {
    return sourceLength;
  }

  const segment = segments.find(
    (candidate) =>
      offset >= candidate.renderedStart &&
      (offset < candidate.renderedEnd ||
        (candidate.renderedStart === candidate.renderedEnd &&
          offset === candidate.renderedStart))
  );
  if (!segment) {
    return Math.min(offset, sourceLength);
  }
  if (!segment.replaced) {
    return segment.sourceStart + (offset - segment.renderedStart);
  }

  const distanceFromStart = offset - segment.renderedStart;
  const distanceFromEnd = segment.renderedEnd - offset;
  return distanceFromStart <= distanceFromEnd ? segment.sourceStart : segment.sourceEnd;
}

function mapSourceToRendered(
  segments: MappingSegment[],
  sourceOffset: number,
  sourceLength: number,
  renderedLength: number
): number {
  const offset = clampOffset(sourceOffset, sourceLength);
  if (offset === sourceLength) {
    return renderedLength;
  }

  const segment = segments.find(
    (candidate) => offset >= candidate.sourceStart && offset < candidate.sourceEnd
  );
  if (!segment) {
    return Math.min(offset, renderedLength);
  }
  if (!segment.replaced) {
    return segment.renderedStart + (offset - segment.sourceStart);
  }

  const distanceFromStart = offset - segment.sourceStart;
  const distanceFromEnd = segment.sourceEnd - offset;
  return distanceFromStart <= distanceFromEnd ? segment.renderedStart : segment.renderedEnd;
}

function getWalkerDocument(root: Node): Document | null {
  if (root.nodeType === Node.DOCUMENT_NODE) {
    return root as Document;
  }

  return root.ownerDocument;
}

export function createTextEngine(): TextEngine {
  const originals = new WeakMap<Text, string>();
  const mappings = new WeakMap<Text, MappingSegment[]>();

  function applyToTextNode(node: Text, rules: ReplaceRule[]): NodeTransform | null {
    if (!shouldProcessTextNode(node)) {
      return null;
    }

    const baseText = originals.get(node) ?? (node.nodeValue ?? '');

    if (!originals.has(node)) {
      originals.set(node, baseText);
    }

    const transformed = transformText(baseText, rules);
    mappings.set(node, transformed.mapping);

    if (transformed.text !== node.nodeValue) {
      node.nodeValue = transformed.text;
    }
    return transformed;
  }

  function visitNode(root: Node, rules: ReplaceRule[]): TextApplySummary {
    const summary = emptySummary(rules);
    const counts = rules.map(() => 0);

    const record = (node: Text) => {
      const previousValue = node.nodeValue;
      const transformed = applyToTextNode(node, rules);
      if (!transformed) {
        return;
      }
      if (transformed.text !== previousValue) {
        summary.changedTextNodeCount += 1;
      }
      mergeCounts(counts, transformed.counts);
    };

    if (root.nodeType === Node.TEXT_NODE) {
      record(root as Text);
      summary.perRule = rules.map((rule, index) => ({
        ruleId: rule.id,
        replacementCount: counts[index]
      }));
      summary.replacementCount = summary.perRule.reduce(
        (total, item) => total + item.replacementCount,
        0
      );
      return summary;
    }

    const doc = getWalkerDocument(root);

    if (!doc) {
      return summary;
    }

    const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    let current = walker.nextNode();

    while (current) {
      record(current as Text);
      current = walker.nextNode();
    }

    summary.perRule = rules.map((rule, index) => ({
      ruleId: rule.id,
      replacementCount: counts[index]
    }));
    summary.replacementCount = summary.perRule.reduce(
      (total, item) => total + item.replacementCount,
      0
    );
    return summary;
  }

  return {
    applyToDocument(doc, rules) {
      if (!doc.body) {
        return emptySummary(rules);
      }

      return visitNode(doc.body, rules);
    },
    applyToNode(root, rules) {
      return visitNode(root, rules);
    },
    refreshOriginal(node) {
      const value = node.nodeValue ?? '';
      originals.set(node, value);
      mappings.set(node, [
        {
          sourceStart: 0,
          sourceEnd: value.length,
          renderedStart: 0,
          renderedEnd: value.length,
          replaced: false
        }
      ]);
    },
    renderedOffsetToSource(node, renderedOffset) {
      const source = originals.get(node) ?? (node.nodeValue ?? '');
      const rendered = node.nodeValue ?? '';
      return mapRenderedToSource(
        mappings.get(node) ?? [],
        renderedOffset,
        source.length,
        rendered.length
      );
    },
    sourceOffsetToRendered(node, sourceOffset) {
      const source = originals.get(node) ?? (node.nodeValue ?? '');
      const rendered = node.nodeValue ?? '';
      return mapSourceToRendered(
        mappings.get(node) ?? [],
        sourceOffset,
        source.length,
        rendered.length
      );
    }
  };
}
