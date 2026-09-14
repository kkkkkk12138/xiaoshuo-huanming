import { readFile } from 'node:fs/promises';
import { describe, expect, it } from 'vitest';
import { installNameReplacerRuntime } from '../src/android-runtime/runtime';

describe('Android page runtime', () => {
  it('applies longest sources first and restores from original text', () => {
    document.body.innerHTML = '<p>沈清辞和沈清</p>';
    const runtime = installNameReplacerRuntime(document);
    const applied = runtime.applyRules([
      { id: 'short', source: '沈清', target: 'A', order: 0 },
      { id: 'long', source: '沈清辞', target: 'B', order: 1 }
    ]);
    expect(applied).toEqual({
      ok: true,
      activeRuleCount: 2,
      changedTextNodeCount: 1,
      replacementCount: 2,
      perRule: [
        { ruleId: 'short', replacementCount: 1 },
        { ruleId: 'long', replacementCount: 1 }
      ]
    });
    expect(document.body.textContent).toBe('B和A');
    const restored = runtime.restoreOriginalText();
    expect(restored).toEqual({
      ok: true,
      activeRuleCount: 0,
      changedTextNodeCount: 1,
      replacementCount: 0,
      perRule: []
    });
    expect(document.body.textContent).toBe('沈清辞和沈清');
  });

  it('does not expose browser or native capabilities', async () => {
    await import('../src/android-runtime/index');
    const source = await Promise.all(
      ['index.ts', 'runtime.ts', 'types.ts'].map((file) =>
        readFile(`src/android-runtime/${file}`, 'utf8')
      )
    );

    expect(window.__NAME_REPLACER__).toBeDefined();
    expect(source.join('\n')).not.toMatch(
      /chrome\.|browser\.|addJavascriptInterface|document\.cookie|fetch\(/
    );
  });
});
