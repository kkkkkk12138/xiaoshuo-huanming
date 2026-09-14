import { describe, expect, it } from 'vitest';
import { createEmptyRule, normalizeRules, sharedTrim } from '../src/shared/rules';
import { loadRules, saveRules } from '../src/shared/storage';

describe('normalizeRules', () => {
  it('trims, removes blanks, removes duplicates, and sorts by source length desc', () => {
    expect(
      normalizeRules([
        { id: '1', source: ' 清辞 ', target: '惊鹤' },
        { id: '2', source: '', target: '空' },
        { id: '3', source: '沈清辞', target: '林惊鹤' },
        { id: '4', source: '清辞', target: '惊鹤' },
        { id: '5', source: '沈清辞', target: '林惊鹤' },
        { id: '6', source: '阿辞', target: '阿辞' }
      ])
    ).toEqual([
      { id: '3', source: '沈清辞', target: '林惊鹤' },
      { id: '1', source: '清辞', target: '惊鹤' }
    ]);
  });

  it('uses the fixed shared boundary whitespace table', () => {
    expect(sharedTrim('\u0009\u00A0\uFEFF 宝\u3000宝 \u2007')).toBe('宝\u3000宝');
  });
});

describe('createEmptyRule', () => {
  it('creates an empty rule with a generated id', () => {
    expect(createEmptyRule()).toEqual({
      id: 'test-uuid',
      source: '',
      target: ''
    });
  });
});

describe('storage', () => {
  it('returns empty rules by default and persists normalized rules', async () => {
    expect(await loadRules()).toEqual([]);

    await saveRules([
      { id: '1', source: ' 沈清辞 ', target: '林惊鹤' },
      { id: '2', source: '', target: '' },
      { id: '3', source: '清辞', target: ' 惊鹤 ' },
      { id: '4', source: '清辞', target: '惊鹤' }
    ]);

    expect(await loadRules()).toEqual([
      { id: '1', source: '沈清辞', target: '林惊鹤' },
      { id: '3', source: '清辞', target: '惊鹤' }
    ]);
  });
});
