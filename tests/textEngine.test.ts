import { describe, expect, it } from 'vitest';
import { installNameReplacerRuntime } from '../src/android-runtime/runtime';
import { shouldProcessTextNode } from '../src/content/domFilter';
import { createTextEngine } from '../src/content/textEngine';

describe('domFilter', () => {
  it('skips blocked and editable regions', () => {
    document.body.innerHTML = `
      <p id="plain">沈清辞来了</p>
      <input value="沈清辞" />
      <pre>沈清辞</pre>
      <code>沈清辞</code>
      <div id="editable" contenteditable="true">沈清辞</div>
      <div data-name-replacement-ignore="true"><span id="ignored">沈清辞</span></div>
    `;

    expect(shouldProcessTextNode(document.getElementById('plain')!.firstChild as Text)).toBe(true);
    expect(shouldProcessTextNode(document.querySelector('pre')!.firstChild as Text)).toBe(false);
    expect(shouldProcessTextNode(document.querySelector('code')!.firstChild as Text)).toBe(false);
    expect(shouldProcessTextNode(document.getElementById('editable')!.firstChild as Text)).toBe(
      false
    );
    expect(shouldProcessTextNode(document.getElementById('ignored')!.firstChild as Text)).toBe(
      false
    );
  });
});

describe('text engine', () => {
  it('replaces readable text nodes without injecting wrapper elements', () => {
    document.body.innerHTML = `
      <article>
        <p id="line">沈清辞看着清辞笑了。</p>
      </article>
      <input value="沈清辞" />
      <pre>沈清辞</pre>
      <code>沈清辞</code>
    `;

    const engine = createTextEngine();
    engine.applyToDocument(document, [
      { id: '1', source: '沈清辞', target: '林惊鹤' },
      { id: '2', source: '清辞', target: '惊鹤' }
    ]);

    expect(document.getElementById('line')!.textContent).toBe('林惊鹤看着惊鹤笑了。');
    expect(document.querySelector('#line span')).toBeNull();
    expect((document.querySelector('input') as HTMLInputElement).value).toBe('沈清辞');
    expect(document.querySelector('pre')!.textContent).toBe('沈清辞');
    expect(document.querySelector('code')!.textContent).toBe('沈清辞');
  });

  it('reapplies from original text when rules change or shrink', () => {
    document.body.innerHTML = `<p id="line">沈清辞看着清辞笑了。</p>`;

    const engine = createTextEngine();

    engine.applyToDocument(document, [
      { id: '1', source: '沈清辞', target: '林惊鹤' },
      { id: '2', source: '清辞', target: '惊鹤' }
    ]);
    engine.applyToDocument(document, [{ id: '1', source: '沈清辞', target: '顾云深' }]);

    expect(document.getElementById('line')!.textContent).toBe('顾云深看着清辞笑了。');
  });

  it('can refresh the original baseline for newly changed text nodes', () => {
    document.body.innerHTML = `<div id="root"><p id="line">沈清辞来了。</p></div>`;

    const engine = createTextEngine();
    const textNode = document.getElementById('line')!.firstChild as Text;

    engine.applyToDocument(document, [{ id: '1', source: '沈清辞', target: '林惊鹤' }]);

    textNode.nodeValue = '沈清辞又来了。';
    engine.refreshOriginal(textNode);
    engine.applyToNode(textNode, [{ id: '1', source: '沈清辞', target: '顾云深' }]);

    expect(document.getElementById('line')!.textContent).toBe('顾云深又来了。');
  });

  it('uses one left-to-right pass without cascading and reports real matches', () => {
    document.body.innerHTML = '<p id="line">AB A aaaa</p>';
    const engine = createTextEngine();

    const result = engine.applyToDocument(document, [
      { id: 'ab', source: 'AB', target: 'A' },
      { id: 'a', source: 'A', target: 'B' },
      { id: 'pair', source: 'aa', target: 'X' }
    ]);

    expect(document.getElementById('line')!.textContent).toBe('A B XX');
    expect(result).toEqual({
      changedTextNodeCount: 1,
      replacementCount: 4,
      perRule: [
        { ruleId: 'ab', replacementCount: 1 },
        { ruleId: 'a', replacementCount: 1 },
        { ruleId: 'pair', replacementCount: 2 }
      ]
    });
  });

  it('prefers the longest source and uses input order as a deterministic tie break', () => {
    document.body.innerHTML = '<p id="line">沈清辞 沈清</p>';
    const engine = createTextEngine();

    const result = engine.applyToDocument(document, [
      { id: 'short', source: '沈清', target: 'S' },
      { id: 'first', source: '沈清辞', target: 'L1' },
      { id: 'second', source: '沈清辞', target: 'L2' }
    ]);

    expect(document.getElementById('line')!.textContent).toBe('L1 S');
    expect(result.perRule).toEqual([
      { ruleId: 'short', replacementCount: 1 },
      { ruleId: 'first', replacementCount: 1 },
      { ruleId: 'second', replacementCount: 0 }
    ]);
  });

  it('matches UTF-16 literals without Unicode normalization or crossing text nodes', () => {
    document.body.innerHTML = '<p id="line">😀 é e\u0301 <span>沈</span><span>清</span></p>';
    const engine = createTextEngine();

    engine.applyToDocument(document, [
      { id: 'emoji', source: '😀', target: '表情' },
      { id: 'nfc', source: 'é', target: 'NFC' },
      { id: 'cross', source: '沈清', target: '不应出现' }
    ]);

    expect(document.getElementById('line')!.textContent).toBe('表情 NFC e\u0301 沈清');
  });

  it('maps rendered offsets back to stable original UTF-16 offsets', () => {
    document.body.innerHTML = '<p id="line">前宝宝后</p>';
    const engine = createTextEngine();
    const textNode = document.getElementById('line')!.firstChild as Text;

    engine.applyToDocument(document, [
      { id: 'longer', source: '宝宝', target: '超级长名字' }
    ]);

    expect(textNode.nodeValue).toBe('前超级长名字后');
    expect(engine.renderedOffsetToSource(textNode, 1)).toBe(1);
    expect(engine.renderedOffsetToSource(textNode, 6)).toBe(3);
    expect(engine.sourceOffsetToRendered(textNode, 1)).toBe(1);
    expect(engine.sourceOffsetToRendered(textNode, 3)).toBe(6);

    engine.applyToDocument(document, [
      { id: 'shorter', source: '宝宝', target: '宝' }
    ]);

    expect(textNode.nodeValue).toBe('前宝后');
    expect(engine.sourceOffsetToRendered(textNode, 3)).toBe(2);
    expect(engine.renderedOffsetToSource(textNode, 2)).toBe(3);
  });

  it('keeps replacement totals correct even when rule ids are duplicated', () => {
    document.body.innerHTML = '<p id="line">甲乙</p>';
    const engine = createTextEngine();

    const result = engine.applyToDocument(document, [
      { id: 'same', source: '甲', target: 'A' },
      { id: 'same', source: '乙', target: 'B' }
    ]);

    expect(result.replacementCount).toBe(2);
    expect(result.perRule).toEqual([
      { ruleId: 'same', replacementCount: 1 },
      { ruleId: 'same', replacementCount: 1 }
    ]);
  });

  it('uses UTF-16 offsets around surrogate pairs and restores identity mapping', () => {
    document.body.innerHTML = '<p id="line">A😀B</p>';
    const engine = createTextEngine();
    const textNode = document.getElementById('line')!.firstChild as Text;

    engine.applyToDocument(document, [{ id: 'emoji', source: '😀', target: '表情符号' }]);

    expect(engine.sourceOffsetToRendered(textNode, 1)).toBe(1);
    expect(engine.sourceOffsetToRendered(textNode, 2)).toBe(1);
    expect(engine.sourceOffsetToRendered(textNode, 3)).toBe(5);
    expect(engine.renderedOffsetToSource(textNode, 1)).toBe(1);
    expect(engine.renderedOffsetToSource(textNode, 5)).toBe(3);
    expect(engine.renderedOffsetToSource(textNode, -10)).toBe(0);
    expect(engine.sourceOffsetToRendered(textNode, 999)).toBe(6);

    const cleared = engine.applyToDocument(document, []);

    expect(textNode.nodeValue).toBe('A😀B');
    expect(cleared.replacementCount).toBe(0);
    expect(cleared.perRule).toEqual([]);
    expect(engine.sourceOffsetToRendered(textNode, 2)).toBe(2);
    expect(engine.renderedOffsetToSource(textNode, 2)).toBe(2);
  });

  it('exposes source/rendered UTF-16 mapping through the Android runtime', () => {
    document.body.innerHTML = '<section data-source-start="20">宝宝A😀</section>';
    const node = document.querySelector('section')!.firstChild as Text;
    const runtime = installNameReplacerRuntime(document);

    runtime.applyRules([
      { id: 'long', source: '宝宝', target: '一位很长的名字', order: 0 },
      { id: 'emoji', source: '😀', target: 'X', order: 1 }
    ]);

    expect(runtime.renderedOffsetToSource(node, 7)).toBe(2);
    expect(runtime.sourceOffsetToRendered(node, 2)).toBe(7);
    expect(runtime.sourceOffsetToRendered(node, 3)).toBe(8);
    expect(runtime.sourceOffsetToRendered(node, 4)).toBe(8);
    expect(runtime.sourceOffsetToRendered(node, 5)).toBe(9);

    runtime.applyRules([{ id: 'short', source: '宝宝', target: '宝', order: 0 }]);
    expect(runtime.sourceOffsetToRendered(node, 2)).toBe(1);

    runtime.restoreOriginalText();
    expect(runtime.sourceOffsetToRendered(node, 4)).toBe(4);
    expect(runtime.renderedOffsetToSource(node, 4)).toBe(4);
  });
});
