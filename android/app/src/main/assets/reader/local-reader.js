(() => {
  'use strict';

  const MAX_DOM_CHUNKS = 5;
  const params = new URLSearchParams(location.search);
  const session = params.get('session') || '';
  const chunkCount = Number(params.get('chunks') || 0);
  const starts = (params.get('starts') || '').split(',').filter(Boolean).map(Number);
  const requestedOffset = Number(params.get('offset') || 0);
  const totalUtf16Units = Number(params.get('totalUtf16Units') || 0);
  const reader = document.getElementById('reader');
  const status = document.getElementById('status');
  const loaded = new Map();
  let loading = false;

  function chunkIndexForOffset(offset) {
    let result = 0;
    for (let index = 0; index < starts.length && starts[index] <= offset; index += 1) {
      result = index;
    }
    return result;
  }

  let currentIndex = chunkIndexForOffset(requestedOffset);

  const viewportHeight = () => window.innerHeight;
  const chunkUrl = index =>
    `/txt/${encodeURIComponent(session)}/${index}`;

  async function fetchChunk(index) {
    if (index < 0 || index >= chunkCount || loaded.has(index)) return;
    const response = await fetch(chunkUrl(index), { credentials: 'omit', cache: 'no-store' });
    if (!response.ok) throw new Error(`TXT chunk ${index} unavailable`);
    const element = document.createElement('section');
    element.className = 'chunk';
    element.dataset.index = String(index);
    element.dataset.sourceStart =
      response.headers.get('X-Character-Start') || String(starts[index] || 0);
    element.dataset.sourceEnd =
      response.headers.get('X-Character-End') || element.dataset.sourceStart;
    element.innerHTML = await response.text();
    loaded.set(index, element);
  }

  function renderWindow(anchorIndex) {
    const anchor = loaded.get(anchorIndex);
    const previousTop = anchor?.getBoundingClientRect().top;
    const ordered = [...loaded.entries()].sort((left, right) => left[0] - right[0]);
    const keep = ordered
      .sort((left, right) =>
        Math.abs(left[0] - currentIndex) - Math.abs(right[0] - currentIndex))
      .slice(0, MAX_DOM_CHUNKS)
      .sort((left, right) => left[0] - right[0]);
    loaded.clear();
    keep.forEach(([index, element]) => loaded.set(index, element));
    reader.replaceChildren(...keep.map(([, element]) => element));
    if (anchor && previousTop !== undefined && loaded.has(anchorIndex)) {
      window.scrollBy(0, anchor.getBoundingClientRect().top - previousTop);
    }
    status.hidden = loaded.size >= chunkCount;
  }

  async function loadWindow(center, anchorIndex = center) {
    if (loading) return;
    loading = true;
    try {
      currentIndex = Math.max(0, Math.min(chunkCount - 1, center));
      const indexes = [];
      for (let index = currentIndex - 2; index <= currentIndex + 2; index += 1) {
        if (index >= 0 && index < chunkCount) indexes.push(index);
      }
      await Promise.all(indexes.map(fetchChunk));
      renderWindow(anchorIndex);
    } finally {
      loading = false;
    }
  }

  function textPointFromViewport() {
    const visible = [...reader.querySelectorAll('.chunk')]
      .find(element => element.getBoundingClientRect().bottom > 0);
    if (!visible) return null;
    const rect = visible.getBoundingClientRect();
    const x = Math.max(1, Math.min(window.innerWidth - 1, rect.left + 8));
    const y = Math.max(1, Math.min(window.innerHeight - 1, rect.top + 1));
    if (typeof document.caretPositionFromPoint === 'function') {
      const position = document.caretPositionFromPoint(x, y);
      if (position) return { node: position.offsetNode, offset: position.offset };
    }
    if (typeof document.caretRangeFromPoint === 'function') {
      const range = document.caretRangeFromPoint(x, y);
      if (range) return { node: range.startContainer, offset: range.startOffset };
    }
    return null;
  }

  function textNodeAt(point) {
    if (point?.node?.nodeType === Node.TEXT_NODE) return point;
    const root = point?.node?.nodeType === Node.ELEMENT_NODE ? point.node : null;
    const node = root && document.createTreeWalker(root, NodeFilter.SHOW_TEXT).nextNode();
    return node ? { node, offset: 0 } : null;
  }

  function renderedOffsetToSource(node, offset) {
    const runtime = window.__NAME_REPLACER__;
    return typeof runtime?.renderedOffsetToSource === 'function'
      ? runtime.renderedOffsetToSource(node, offset)
      : offset;
  }

  function sourceOffsetToRendered(node, offset) {
    const runtime = window.__NAME_REPLACER__;
    return typeof runtime?.sourceOffsetToRendered === 'function'
      ? runtime.sourceOffsetToRendered(node, offset)
      : offset;
  }

  function sourceOffsetWithinChunk(chunk, targetNode, renderedOffset) {
    const walker = document.createTreeWalker(chunk, NodeFilter.SHOW_TEXT);
    let consumed = 0;
    let node = walker.nextNode();
    while (node) {
      if (node === targetNode) {
        return consumed + renderedOffsetToSource(node, renderedOffset);
      }
      const renderedLength = node.nodeValue?.length || 0;
      consumed += renderedOffsetToSource(node, renderedLength);
      node = walker.nextNode();
    }
    return consumed;
  }

  function characterOffset() {
    const point = textNodeAt(textPointFromViewport());
    const chunk = point?.node?.parentElement?.closest('.chunk');
    if (!point || !chunk) return Math.max(0, Math.min(totalUtf16Units, requestedOffset));
    const sourceStart = Number(chunk.dataset.sourceStart || 0);
    const localOffset = sourceOffsetWithinChunk(chunk, point.node, point.offset);
    return Math.max(0, Math.min(totalUtf16Units, sourceStart + localOffset));
  }

  function findRenderedPosition(element, sourceOffset) {
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
    let consumed = 0;
    let node = walker.nextNode();
    while (node) {
      const renderedLength = node.nodeValue?.length || 0;
      const sourceLength = renderedOffsetToSource(node, renderedLength);
      if (sourceOffset <= consumed + sourceLength) {
        return {
          node,
          offset: sourceOffsetToRendered(node, sourceOffset - consumed),
        };
      }
      consumed += sourceLength;
      node = walker.nextNode();
    }
    return null;
  }

  function scrollToPosition(position, fallbackTop) {
    if (!position) {
      window.scrollTo(0, fallbackTop);
      return;
    }
    const range = document.createRange();
    range.setStart(position.node, position.offset);
    range.collapse(true);
    const rect = range.getBoundingClientRect();
    window.scrollTo(0, window.scrollY + rect.top);
  }

  window.__TXT_READER__ = {
    characterOffset,
    restoreCharacterOffset: async offset => {
      const normalized = Math.max(0, Math.min(totalUtf16Units, Number(offset) || 0));
      const target = chunkIndexForOffset(normalized);
      await loadWindow(target);
      const element = loaded.get(target);
      if (!element) return;
      const sourceStart = Number(element.dataset.sourceStart || 0);
      scrollToPosition(findRenderedPosition(element, normalized - sourceStart), element.offsetTop);
    },
    totalUtf16Units,
  };

  window.addEventListener('scroll', () => {
    const viewportHeight = window.innerHeight;
    const visible = [...reader.querySelectorAll('.chunk')]
      .find(element => element.getBoundingClientRect().bottom > 0);
    const anchorIndex = Number(visible?.dataset.index || currentIndex);
    const indexes = [...loaded.keys()];
    const firstIndex = Math.min(...indexes);
    const lastIndex = Math.max(...indexes);
    const distanceToBottom =
      document.documentElement.scrollHeight - window.scrollY - viewportHeight;
    if (distanceToBottom < viewportHeight * 2 && lastIndex + 1 < chunkCount) {
      loadWindow(lastIndex + 1, anchorIndex).catch(() => { status.textContent = '载入失败'; });
    } else if (window.scrollY < viewportHeight * 2 && firstIndex > 0) {
      loadWindow(firstIndex - 1, anchorIndex).catch(() => { status.textContent = '载入失败'; });
    }
  }, { passive: true });

  window.__TXT_READER__.restoreCharacterOffset(requestedOffset)
    .catch(() => { status.textContent = '无法载入 TXT'; });
})();
