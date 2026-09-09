import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs';

mermaid.initialize({
    startOnLoad: false,
    securityLevel: 'strict',
    theme: window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'default'
});

const sqlInput = document.getElementById('sqlInput');
const generateButton = document.getElementById('generateButton');
const sampleButton = document.getElementById('sampleButton');
const mermaidText = document.getElementById('mermaidText');
const preview = document.getElementById('preview');
const copyButton = document.getElementById('copyButton');
const errorBox = document.getElementById('errorBox');
const warningBox = document.getElementById('warningBox');
const tableBox = document.getElementById('tableBox');


sampleButton.addEventListener('click', () => {
    sqlInput.value = `SELECT
    u.id,
    u.name,
    o.id AS order_id,
    oi.product_id
FROM users u
JOIN orders o
  ON o.user_id = u.id
JOIN order_items oi
  ON oi.order_id = o.id
WHERE u.status = 'ACTIVE';`;
});

generateButton.addEventListener('click', async () => {
    resetMessages();
    setBusy(true);
    try {
        const response = await fetch('/api/erd/generate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sql: sqlInput.value })
        });
        const data = await response.json();
        if (!response.ok) {
            throw new Error(data.message || 'ER図の生成に失敗しました。');
        }

        mermaidText.value = data.mermaid;
        tableBox.textContent = `対象テーブル (${data.tables.length}): ${data.tables.join(', ')}`;
        tableBox.classList.remove('hidden');

        if (data.warnings?.length) {
            warningBox.textContent = data.warnings.join('\n');
            warningBox.classList.remove('hidden');
        }
        await renderMermaid(data.mermaid);
    } catch (e) {
        errorBox.textContent = e.message;
        errorBox.classList.remove('hidden');
        clearPreview();
    } finally {
        setBusy(false);
    }
});

copyButton.addEventListener('click', async () => {
    await navigator.clipboard.writeText(mermaidText.value);
    const original = copyButton.textContent;
    copyButton.textContent = 'コピーしました';
    setTimeout(() => copyButton.textContent = original, 1200);
});

async function renderMermaid(text) {
    const id = `erd-${Date.now()}`;
    const mermaidSource = extractMermaidSource(text);
    const { svg } = await mermaid.render(id, mermaidSource);
    preview.innerHTML = svg;
    preview.classList.remove('empty');
    copyButton.disabled = false;
}

function extractMermaidSource(text) {
    const normalized = text.replace(/\r\n/g, '\n').trim();
    const match = normalized.match(/^```mermaid\s*\n([\s\S]*?)\n```$/);
    return match ? match[1] : normalized;
}

function clearPreview() {
    preview.textContent = 'ER図を生成するとここに表示されます。';
    preview.classList.add('empty');
    copyButton.disabled = true;
}

function resetMessages() {
    for (const el of [errorBox, warningBox, tableBox]) {
        el.textContent = '';
        el.classList.add('hidden');
    }
}

function setBusy(busy) {
    generateButton.disabled = busy;
    generateButton.textContent = busy ? '生成中...' : 'ER図を生成';
}
