// ============================================================
// cy/views.js — Text view + Mermaid view
// ============================================================

var currentMermaidMode = 'full';

// ── Text view ────────────────────────────────────────────────

function renderTextView() {
    var container = document.getElementById("textContainer");
    if (!container) return;

    if (jsTextHistory.length === 0) {
        container.innerHTML = "<p class='text-muted' style='padding:20px'>Modelo vazio.</p>";
        return;
    }

    var fullHtml = "";
    jsTextHistory.forEach(function (item) {
        var formattedState = parseStateText(item.text);
        fullHtml += `
            <div class="history-row">
                <div class="history-label">${item.label}</div>
                <div class="history-content">${formattedState}</div>
            </div>
        `;
    });

    container.innerHTML = fullHtml;
    setTimeout(function () { container.scrollTop = container.scrollHeight; }, 50);
}

function parseStateText(rawText) {
    if (!rawText) return "";
    var lines = rawText.split('\n');
    var html  = "";

    lines.forEach(function (line) {
        line = line.trim();
        if (!line) return;

        var match = line.match(/^\[(.*?)\]\s*(.*)/);
        if (match) {
            var key     = match[1].toLowerCase();
            var content = match[2];
            var icon    = "🔹";
            var inner   = content;

            if (key === 'init') {
                icon  = "🚩";
                inner = `<span class="tv-tag highlight">${content}</span>`;
            } else if (key === 'act') {
                icon  = "⚡";
                inner = content.split(',').map(s => `<span class="tv-tag active">${s.trim()}</span>`).join(" ");
            } else if (key === 'clocks' || key === 'vars') {
                icon = "#️⃣";
            } else if (key === 'on') {
                icon = "🟢";
            } else if (key === 'off') {
                icon  = "🔴";
                inner = `<span class="tv-tag disabled">${content}</span>`;
            }

            html += `<div class="tv-section"><span class="tv-header">${icon} ${key}: </span><span class="tv-content">${inner}</span></div>`;
        } else {
            html += `<div style="padding-left:20px; font-size:11px; color:#777;">${line}</div>`;
        }
    });
    return html;
}

// ── Mermaid view ─────────────────────────────────────────────

function setMermaidMode(mode) {
    currentMermaidMode = mode;
    renderMermaidView();
}

function showLTS() {
    setMermaidMode('lts');
    $('.nav-tabs a[href="#mermaidTab"]').tab('show');
}

function renderMermaidView() {
    var container = document.getElementById('mermaidContainer');
    if (!container) return;
    if (container.offsetParent === null) return;   // tab not visible

    if (!currentMermaidMode) currentMermaidMode = 'full';

    var mermaidCode = "";
    if      (currentMermaidMode === 'lts')    mermaidCode = RTA.getAllStepsMermaid();
    else if (currentMermaidMode === 'simple') mermaidCode = RTA.getCurrentStateMermaidSimple();
    else                                      mermaidCode = RTA.getCurrentStateMermaid();

    if (!mermaidCode || mermaidCode.trim() === "") {
        container.innerHTML = "<p class='text-muted'>Nenhum gráfico para exibir.</p>";
        return;
    }

    container.innerHTML = mermaidCode;
    container.removeAttribute('data-processed');

    try {
        mermaid.init(undefined, container);
    } catch (e) {
        console.error("Mermaid Error:", e);
    }
}
