import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs';

mermaid.initialize({
    startOnLoad: false,
    securityLevel: 'strict',
    theme: window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'default'
});

const sourcePath = document.getElementById('sourcePath');
const scanButton = document.getElementById('scanButton');
const controllerSelect = document.getElementById('controllerSelect');
const endpointSelect = document.getElementById('endpointSelect');
const sequenceButton = document.getElementById('sequenceButton');
const endpointBox = document.getElementById('endpointBox');
const scanErrorBox = document.getElementById('scanErrorBox');
const scanWarningBox = document.getElementById('scanWarningBox');
const generateErrorBox = document.getElementById('generateErrorBox');
const generateWarningBox = document.getElementById('generateWarningBox');
const mermaidText = document.getElementById('mermaidText');
const preview = document.getElementById('preview');
const copyButton = document.getElementById('copyButton');

let controllers = [];

scanButton.addEventListener('click', async () => {
    clearBox(scanErrorBox);
    clearBox(scanWarningBox);
    clearBox(generateErrorBox);
    clearBox(generateWarningBox);
    setScanBusy(true);
    try {
        const response = await fetch('/api/sequence/controllers', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sourcePath: sourcePath.value })
        });
        const data = await response.json();
        if (!response.ok) throw new Error(data.message || 'Controller一覧の取得に失敗しました。');

        controllers = data.controllers || [];
        renderControllers();
        resetDiagram();
        showWarnings(scanWarningBox, data.warnings);
    } catch (e) {
        scanErrorBox.textContent = e.message;
        scanErrorBox.classList.remove('hidden');
        controllers = [];
        renderControllers();
        resetDiagram();
    } finally {
        setScanBusy(false);
    }
});

controllerSelect.addEventListener('change', () => {
    const controller = controllers.find(item => item.qualifiedName === controllerSelect.value);
    renderEndpoints(controller);
    resetDiagram();
});

endpointSelect.addEventListener('change', () => {
    sequenceButton.disabled = !controllerSelect.value || !endpointSelect.value;
    updateEndpointInfo();
    resetDiagram();
});

sequenceButton.addEventListener('click', async () => {
    clearBox(generateErrorBox);
    clearBox(generateWarningBox);
    setGenerateBusy(true);
    try {
        const response = await fetch('/api/sequence/generate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                sourcePath: sourcePath.value,
                controllerClass: controllerSelect.value,
                endpointMethod: endpointSelect.value
            })
        });
        const data = await response.json();
        if (!response.ok) throw new Error(data.message || 'シーケンス図の生成に失敗しました。');

        mermaidText.value = data.mermaid;
        showWarnings(generateWarningBox, data.warnings);
        await renderMermaid(data.mermaid);
    } catch (e) {
        generateErrorBox.textContent = e.message;
        generateErrorBox.classList.remove('hidden');
        clearPreview();
    } finally {
        setGenerateBusy(false);
    }
});

copyButton.addEventListener('click', async () => {
    await navigator.clipboard.writeText(mermaidText.value);
    const original = copyButton.textContent;
    copyButton.textContent = 'コピーしました';
    setTimeout(() => copyButton.textContent = original, 1200);
});

function renderControllers() {
    controllerSelect.innerHTML = '';
    if (!controllers.length) {
        controllerSelect.append(new Option('Controllerが見つかりません', ''));
        controllerSelect.disabled = true;
        renderEndpoints(null);
        return;
    }
    controllerSelect.append(new Option('Controllerを選択してください', ''));
    for (const controller of controllers) {
        const endpointCount = controller.endpoints?.length || 0;
        controllerSelect.append(new Option(`${controller.qualifiedName} (${endpointCount} endpoints)`, controller.qualifiedName));
    }
    controllerSelect.disabled = false;
    renderEndpoints(null);
}

function renderEndpoints(controller) {
    endpointSelect.innerHTML = '';
    sequenceButton.disabled = true;

    if (!controller) {
        endpointSelect.append(new Option('先にControllerを選択してください', ''));
        endpointSelect.disabled = true;
        clearBox(endpointBox);
        return;
    }

    const endpoints = controller.endpoints || [];
    if (!endpoints.length) {
        endpointSelect.append(new Option('Endpointが見つかりません', ''));
        endpointSelect.disabled = true;
        endpointBox.textContent = `ファイル: ${controller.filePath}\n\nRequestMapping系アノテーション付きメソッドは見つかりませんでした。`;
        endpointBox.classList.remove('hidden');
        return;
    }

    endpointSelect.append(new Option('Endpointを選択してください', ''));
    for (const endpoint of endpoints) {
        const methods = endpoint.httpMethods?.length ? endpoint.httpMethods.join('/') : 'REQUEST';
        const paths = endpoint.paths?.length ? endpoint.paths.join(', ') : '(path指定なし)';
        endpointSelect.append(new Option(`${methods} ${paths} → ${endpoint.methodName}()`, endpoint.methodName));
    }
    endpointSelect.disabled = false;
    endpointBox.textContent = `ファイル: ${controller.filePath}`;
    endpointBox.classList.remove('hidden');
}

function updateEndpointInfo() {
    const controller = controllers.find(item => item.qualifiedName === controllerSelect.value);
    if (!controller) {
        clearBox(endpointBox);
        return;
    }

    const endpoint = (controller.endpoints || []).find(item => item.methodName === endpointSelect.value);
    if (!endpoint) {
        endpointBox.textContent = `ファイル: ${controller.filePath}`;
        endpointBox.classList.remove('hidden');
        return;
    }

    const methods = endpoint.httpMethods?.length ? endpoint.httpMethods.join('/') : 'REQUEST';
    const paths = endpoint.paths?.length ? endpoint.paths.join(', ') : '(path指定なし)';
    endpointBox.textContent = `ファイル: ${controller.filePath}\n\n選択中: ${methods} ${paths} → ${endpoint.methodName}()`;
    endpointBox.classList.remove('hidden');
}

async function renderMermaid(text) {
    const id = `sequence-${Date.now()}`;
    const { svg } = await mermaid.render(id, extractMermaidSource(text));
    preview.innerHTML = svg;
    preview.classList.remove('empty');
    copyButton.disabled = false;
}

function extractMermaidSource(text) {
    const normalized = text.replace(/\r\n/g, '\n').trim();
    const match = normalized.match(/^```mermaid\s*\n([\s\S]*?)\n```$/);
    return match ? match[1] : normalized;
}

function resetDiagram() {
    mermaidText.value = '';
    clearBox(generateErrorBox);
    clearBox(generateWarningBox);
    clearPreview();
    sequenceButton.disabled = !controllerSelect.value || !endpointSelect.value;
}

function clearPreview() {
    preview.textContent = 'Endpointを選択してシーケンス図を生成するとここに表示されます。';
    preview.classList.add('empty');
    copyButton.disabled = true;
}

function showWarnings(element, warnings) {
    if (!warnings?.length) return;
    element.textContent = warnings.join('\n');
    element.classList.remove('hidden');
}

function clearBox(element) {
    element.textContent = '';
    element.classList.add('hidden');
}

function setScanBusy(busy) {
    scanButton.disabled = busy;
    scanButton.textContent = busy ? '解析中...' : 'Controller一覧を取得';
}

function setGenerateBusy(busy) {
    sequenceButton.disabled = busy;
    sequenceButton.textContent = busy ? '生成中...' : 'シーケンス図生成';
}
