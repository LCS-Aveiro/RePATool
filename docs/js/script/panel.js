// ============================================================
// cy/panel.js — Simulation side panel + layout controls UI
// ============================================================

var currentEdgeStyle = 'straight';

function renderGlobalPanel(data, targetId) {
    var containerId = targetId || 'sidePanel';
    var panelDiv    = document.getElementById(containerId);
    if (!panelDiv) return;

    panelDiv.innerHTML = '';
    var panelData = data.panelData;
    lastModelData = data;

    var t      = i18n[currentLang];
    var suffix = containerId === 'sidePanel' ? '' : ('-' + containerId);

    // ── Undo button ──────────────────────────────────────────
    var undoBtn       = document.createElement('button');
    undoBtn.className = 'btn btn-warning btn-block btn-sm undo-btn';
    undoBtn.innerHTML = '<span class="glyphicon glyphicon-step-backward"></span> ' + (t.btn_undo || 'Desfazer (Undo)');
    undoBtn.disabled  = !panelData.canUndo;
    undoBtn.onclick   = function () {
        var json = RTA.undo();
        if (jsTextHistory.length > 1) jsTextHistory.pop();
        updateAllViews(json);
    };
    panelDiv.appendChild(undoBtn);

    // ── Variables / clocks ───────────────────────────────────
    if (
        (panelData.clocks   && Object.keys(panelData.clocks).length   > 0) ||
        (panelData.variables && Object.keys(panelData.variables).length > 0)
    ) {
        var varHeader       = document.createElement('div');
        varHeader.className = 'sec-label';
        varHeader.innerText = t.stat_header || 'Estado Atual:';
        panelDiv.appendChild(varHeader);

        var infoList       = document.createElement('ul');
        infoList.className = "list-unstyled";
        infoList.style.cssText = "font-size:12px; background:#fff; padding:10px; border:1px solid var(--border); border-radius:2px;";

        for (let [k, v] of Object.entries(panelData.variables || {})) {
            if (k.startsWith("__")) continue;
            let li       = document.createElement('li');
            li.innerHTML = `<span class="text-success"># ${k}</span>: <b>${v}</b>`;
            infoList.appendChild(li);
        }
        panelDiv.appendChild(infoList);
    }

    // ── Enabled transitions header ───────────────────────────
    var transHeader       = document.createElement('div');
    transHeader.className = 'sec-label';
    transHeader.innerText = t.enabled_trans || 'Transições:';
    panelDiv.appendChild(transHeader);

    // ── Deadlock indicator ───────────────────────────────────
    var isDeadlock = panelData.enabled.length === 0 ||
        (panelData.enabled.length === 1 && panelData.enabled[0].label === "deadlock");

    if (isDeadlock) {
        var dead       = document.createElement('div');
        dead.className = "alert alert-danger text-center";
        dead.style.cssText = "padding:5px; font-size:12px; font-weight:bold;";
        dead.innerText = (t.deadlock || "DEADLOCK") + " (LOOP)";
        panelDiv.appendChild(dead);
    }

    // ── Transition buttons ───────────────────────────────────
    if (panelData.enabled.length > 0) {
        panelData.enabled.forEach(function (edge) {
            if (edge.p !== undefined && edge.p < window.currentDeltaCut && !edge.isDelay && edge.label !== 'deadlock') return;

            if (edge.isDelay) {
                _renderDelayControl(panelDiv, edge, suffix, containerId);
            } else {
                _renderTransitionButton(panelDiv, edge);
            }
        });
    }

    // ── Layout settings collapsible (left panel only) ────────
    if (containerId === 'sidePanel') {
        _renderLayoutPanel(panelDiv, t, suffix);
    }
}

// ── Private helpers ──────────────────────────────────────────

function _renderDelayControl(panelDiv, edge, suffix, containerId) {
    var btnGroup       = document.createElement('div');
    btnGroup.className = 'input-group input-group-sm';
    btnGroup.style.marginBottom = '5px';

    var input    = document.createElement('input');
    input.type   = 'number';
    input.className = 'form-control';
    input.value  = storedDelayValue;
    input.step   = '0.001';
    input.min    = '0.000001';
    input.id     = 'delayInputVal' + suffix;
    input.onchange = function () {
        var val = parseFloat(this.value);
        if (!isNaN(val)) {
            storedDelayValue = val;
            var otherId = containerId === 'sidePanel' ? 'delayInputVal-sidePanel-bottom' : 'delayInputVal';
            var otherInp = document.getElementById(otherId);
            if (otherInp) otherInp.value = val;
        }
    };

    var spanBtn    = document.createElement('span');
    spanBtn.className = 'input-group-btn';

    var btn        = document.createElement('button');
    btn.className  = 'btn btn-default';
    btn.innerHTML  = '⏱ Delay';
    btn.onclick    = function () {
        storedDelayValue = parseFloat(input.value);
        updateAllViews(RTA.advanceTime(storedDelayValue));
    };

    spanBtn.appendChild(btn);
    btnGroup.appendChild(input);
    btnGroup.appendChild(spanBtn);
    panelDiv.appendChild(btnGroup);
}

function _renderTransitionButton(panelDiv, edge) {
    var btn         = document.createElement('button');
    var displayName = (edge.tId === edge.label) ? edge.label : edge.tId + " (" + edge.label + ")";
    btn.className   = 'sim-btn';

    if (edge.label === 'deadlock') {
        btn.style.backgroundColor = '#FECACA';
        btn.style.borderColor     = '#EF4444';
        btn.style.color           = '#7F1D1D';
    }

    var icon       = document.createElement('span');
    icon.className = 'glyphicon glyphicon-play-circle';
    icon.style.color = 'var(--gray-500)';
    if (edge.label === 'deadlock') icon.style.color = '#B91C1C';
    btn.appendChild(icon);

    var txt       = document.createElement('span');
    txt.innerText = displayName;
    btn.appendChild(txt);

    if (edge.p !== undefined && !window.isPossibilisticView) {
        var pSpan       = document.createElement('span');
        pSpan.className = 'sim-prob';
        pSpan.innerText = 'P=' + edge.p.toFixed(3);
        btn.appendChild(pSpan);
    }

    btn.onclick = function () {
        stopAutoDelay();
        var json         = RTA.takeStep(JSON.stringify(edge));
        var newStateText = RTA.getCurrentStateText();
        jsTextHistory.push({ label: edge.label + " ->", text: newStateText });
        updateAllViews(json);
    };
    panelDiv.appendChild(btn);
}

function _renderLayoutPanel(panelDiv, t, suffix) {
    var panelGroup       = document.createElement('div');
    panelGroup.className = 'panel-group';
    panelGroup.id        = 'layoutSettingsGroup' + suffix;
    panelGroup.style.marginTop = '15px';

    var layoutPanel       = document.createElement('div');
    layoutPanel.className = 'panel panel-default';

    var panelHeading       = document.createElement('div');
    panelHeading.className = 'panel-heading';
    panelHeading.style.padding = '0';
    panelHeading.innerHTML = `
        <h4 class="panel-title" style="font-size:11px;">
            <a data-toggle="collapse" href="#collapseLayout${suffix}" style="text-decoration:none; display:block;">
                <span class="glyphicon glyphicon-cog"></span> ${t.layout_conf_title} <span class="caret"></span>
            </a>
        </h4>`;

    var collapseBody       = document.createElement('div');
    collapseBody.id        = 'collapseLayout' + suffix;
    collapseBody.className = 'panel-collapse collapse';

    var panelBody       = document.createElement('div');
    panelBody.className = 'panel-body';
    renderLayoutControls(panelBody);

    collapseBody.appendChild(panelBody);
    layoutPanel.appendChild(panelHeading);
    layoutPanel.appendChild(collapseBody);
    panelGroup.appendChild(layoutPanel);
    panelDiv.appendChild(panelGroup);
}

// ── Layout controls ──────────────────────────────────────────

function renderLayoutControls(container) {
    var t = i18n[currentLang];

    // Layout algorithm select
    var layoutGroup   = document.createElement('div');
    layoutGroup.className = 'form-group';
    var layoutLabel   = document.createElement('label');
    layoutLabel.innerText  = t.layout_label;
    layoutLabel.style.fontSize = '12px';

    var layoutSelect  = document.createElement('select');
    layoutSelect.className = 'form-control input-sm';
    layoutSelect.innerHTML = `
        <option value="preset">${t.opt_preset}</option>
        <option value="dagre" selected>${t.opt_dagre}</option>
        <option value="cose">${t.opt_cose}</option>
        <option value="circle">${t.opt_circle}</option>
        <option value="grid">${t.opt_grid}</option>
        <option value="random">${t.opt_random}</option>
    `;
    layoutSelect.onchange = function (e) {
        if (!currentCytoscapeInstance) return;
        var name    = e.target.value;
        var options = { name: name, fit: true, padding: 50, animate: true };
        if (name === 'dagre') options.rankDir = 'LR';
        if (name === 'cose')  { options.componentSpacing = 40; options.nodeRepulsion = 8000; }
        currentCytoscapeInstance.layout(options).run();
    };
    layoutGroup.appendChild(layoutLabel);
    layoutGroup.appendChild(layoutSelect);
    container.appendChild(layoutGroup);

    // Edge style select
    var styleGroup   = document.createElement('div');
    styleGroup.className = 'form-group';
    var styleLabel   = document.createElement('label');
    styleLabel.innerText  = t.edge_style_label;
    styleLabel.style.fontSize = '12px';

    var styleSelect  = document.createElement('select');
    styleSelect.className = 'form-control input-sm';
    styleSelect.innerHTML = `
        <option value="straight">${t.opt_straight}</option>
        <option value="taxi">${t.opt_taxi}</option>
        <option value="bezier">${t.opt_bezier}</option>
    `;
    styleSelect.value    = currentEdgeStyle || 'straight';
    styleSelect.onchange = function (e) { changeEdgeStyle(e.target.value); };
    styleGroup.appendChild(styleLabel);
    styleGroup.appendChild(styleSelect);
    container.appendChild(styleGroup);

    container.appendChild(document.createElement('hr'));

    // Save / load layout buttons
    var btnGroup       = document.createElement('div');
    btnGroup.className = 'btn-group-vertical btn-block';

    var saveBtn       = document.createElement('button');
    saveBtn.className = 'btn btn-default btn-sm';
    saveBtn.innerText = t.btn_save_layout;
    saveBtn.onclick   = exportAllLayoutsToFile;

    var loadBtn       = document.createElement('button');
    loadBtn.className = 'btn btn-default btn-sm';
    loadBtn.innerText = t.btn_load_layout;
    loadBtn.onclick   = function () { document.getElementById('hiddenFileInput').click(); };

    if (!document.getElementById('hiddenFileInput')) {
        var fileInput    = document.createElement('input');
        fileInput.type   = 'file';
        fileInput.id     = 'hiddenFileInput';
        fileInput.style.display = 'none';
        fileInput.accept = '.json,application/json';
        fileInput.onchange = function (e) {
            var file = e.target.files[0];
            if (!file) return;
            var reader = new FileReader();
            reader.onload = function (evt) {
                importAllLayoutsFromFile(currentCytoscapeInstance, null, evt.target.result);
            };
            reader.readAsText(file);
            e.target.value = '';
        };
        document.body.appendChild(fileInput);
    }

    btnGroup.appendChild(saveBtn);
    btnGroup.appendChild(loadBtn);
    container.appendChild(btnGroup);
}
