import type { ReplaceRule } from './types';

const SHARED_BOUNDARY_WHITESPACE = new Set([
  '\u0009',
  '\u000A',
  '\u000B',
  '\u000C',
  '\u000D',
  '\u0020',
  '\u00A0',
  '\u1680',
  '\u2000',
  '\u2001',
  '\u2002',
  '\u2003',
  '\u2004',
  '\u2005',
  '\u2006',
  '\u2007',
  '\u2008',
  '\u2009',
  '\u200A',
  '\u2028',
  '\u2029',
  '\u202F',
  '\u205F',
  '\u3000',
  '\uFEFF'
]);

export function sharedTrim(value: string): string {
  let start = 0;
  let end = value.length;

  while (start < end && SHARED_BOUNDARY_WHITESPACE.has(value[start])) {
    start += 1;
  }
  while (end > start && SHARED_BOUNDARY_WHITESPACE.has(value[end - 1])) {
    end -= 1;
  }

  return value.slice(start, end);
}

export function createEmptyRule(): ReplaceRule {
  return {
    id: crypto.randomUUID(),
    source: '',
    target: ''
  };
}

export function normalizeRules(input: ReplaceRule[]): ReplaceRule[] {
  const seen = new Set<string>();

  return input
    .map((rule) => ({
      ...rule,
      source: sharedTrim(rule.source),
      target: sharedTrim(rule.target)
    }))
    .filter((rule) => rule.source && rule.target && rule.source !== rule.target)
    .filter((rule) => {
      const key = `${rule.source}::${rule.target}`;

      if (seen.has(key)) {
        return false;
      }

      seen.add(key);
      return true;
    })
    .sort((a, b) => b.source.length - a.source.length);
}
