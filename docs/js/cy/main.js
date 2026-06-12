window.isPossibilisticView = false;
window.currentDeltaCut = 0.0;


window.applyDeltaCutBtn = function() {
    var input = document.getElementById('deltaCutInput');
    if (!input) return;
    var val = parseFloat(input.value);
    if (isNaN(val)) val = 0.0;
    window.currentDeltaCut = val;
    if (window.applyFilters) window.applyFilters();
};

window.exportCutModel = function() {
    var input = document.getElementById('deltaCutInput');
    if (!input) return;
    var val = parseFloat(input.value);
    if (isNaN(val)) val = 0.0;
    
    if (typeof RTA === 'undefined') {
        alert("O motor do backend ainda não está carregado.");
        return;
    }
    
    var cutCode = RTA.exportDeltaCutModel(val);
    if (cutCode.startsWith("Erro")) {
        alert(cutCode);
    } else {
        downloadString("modelo_corte_" + val + ".Re", cutCode);
    }
};

window.applyFilters = function() {
    if (!currentCytoscapeInstance) return;

    currentCytoscapeInstance.style().update();

    const delta = window.currentDeltaCut;
    currentCytoscapeInstance.batch(() => {
        currentCytoscapeInstance.nodes('.event-node').forEach(node => {
            const p = node.data('p');
            const lbl = node.data('label') || "";
            if (p !== undefined && p < delta && !lbl.includes('deadlock')) {
                node.addClass('filtered-out');
            } else {
                node.removeClass('filtered-out');
            }
        });
    });

    if (typeof lastModelData !== 'undefined' && lastModelData) {
        renderGlobalPanel(lastModelData, 'sidePanel');
        renderGlobalPanel(lastModelData, 'sidePanel-bottom');
    }
};

var currentCytoscapeInstance = null;
var textTraceHistory = [];
var autoDelayTimer = null;
var currentMermaidMode = 'full';
var currentEdgeStyle = 'straight';
var storedDelayValue = 1.0;
var jsTextHistory = [];

const simpleHash = s => {
    let h = 0;
    for (let i = 0; i < s.length; i++) h = Math.imul(31, h) + s.charCodeAt(i) | 0;
    return String(h);
};

const getLayoutKey = s => {
    const match = s.match(/\bname\s+([a-zA-Z0-9_]+)/);
    if (match && match[1]) {
        return "name_" + match[1]; 
    }
    return simpleHash(s.replace(/\s+/g, '')); 
};

window.stopAutoDelay = stopAutoDelay;

const originalUpdateAllViews = window.updateAllViews;
window.updateAllViews = function(json) {
    if (typeof originalUpdateAllViews === 'function' && originalUpdateAllViews !== window.updateAllViews) {
        originalUpdateAllViews(json);
    }
    
    try {
        var data = JSON.parse(json);
        if (data.error) {
            console.error("Erro no modelo:", data.error);
            return;
        }

        if (typeof renderGlobalPanel === 'function') {
            renderGlobalPanel(data, 'sidePanel');        
            renderGlobalPanel(data, 'sidePanel-bottom'); 
        }

        if (typeof renderCytoscapeGraph === 'function') {
            renderCytoscapeGraph("cytoscapeMainContainer", data, false);
        }
        
        if (typeof renderTextView === 'function') renderTextView();
        if (typeof renderMermaidView === 'function') renderMermaidView();
        
        if (typeof renderPdlHelpers === 'function') renderPdlHelpers(data);
        if (typeof updatePctlDropdowns === 'function') updatePctlDropdowns(data);
        
        if (data && data.panelData) {
            var sbStates = document.getElementById('sb-states');
            var trans = data.panelData.enabled || [];
            if (sbStates) {
                sbStates.textContent = trans.length + ' transition' + (trans.length !== 1 ? 's' : '') + ' enabled';
            }
            
            var sbModel = document.getElementById('sb-model');
            if (sbModel) {
                sbModel.textContent = 'Model active';
                sbModel.style.color = '#86EFAC';
            }
        }

        lastModelData = data;

    } catch (e) {
        console.error("Erro ao processar updateAllViews:", e);
    }
};

function renderCytoscapeGraph(mainContainerId, dataOrJson, isFirstRender) {
    var mainContainer = document.getElementById(mainContainerId);
    if (!mainContainer) return;

    var data = (typeof dataOrJson === 'string') ? JSON.parse(dataOrJson) : dataOrJson;
    var sourceCode = (typeof editor !== 'undefined') ? editor.getValue() : "";
    applySavedPositions(data.graphElements, sourceCode);
    if (isFirstRender || !currentCytoscapeInstance) {
        setupInitialCytoscape(mainContainerId, data);
        return;
    }

    try {
        if (currentCytoscapeInstance) {
            var existingNodes = currentCytoscapeInstance.nodes().map(n => n.id());
            
            var newIds = new Set();
            data.graphElements.forEach(function(el) {
                newIds.add(el.data.id);
            });
            var toRemove = currentCytoscapeInstance.elements().filter(function(el) {
                return !newIds.has(el.id());
            });
            currentCytoscapeInstance.remove(toRemove);

            currentCytoscapeInstance.json({ elements: data.graphElements });
            
            var newNodes = currentCytoscapeInstance.nodes().filter(n => !existingNodes.includes(n.id()));
            newNodes.forEach(function(node) {
                if (node.hasClass('event-node') || node.hasClass('deadlock-node')) {
                    var parts = node.id().split('_');
                    if (parts.length >= 2) {
                        var sourceNode = currentCytoscapeInstance.getElementById(parts[1]);
                        if (sourceNode.length > 0) {
                            var pos = sourceNode.position();
                            node.position({ x: pos.x, y: pos.y - 70 });
                        }
                    }
                }
            });

            if (data.lastTransition) {
                var trans = data.lastTransition;
                
                var actionNodeId = `event_${trans.from}_${trans.to}_${trans.tId}_${trans.label}`;
                
                var edgeTo = `s_to_a_${trans.from}_${actionNodeId}`;
                var edgeFrom = `a_to_s_${actionNodeId}_${trans.to}`;

                var elementsToFlash = currentCytoscapeInstance.elements(`#${actionNodeId}, #${edgeTo}, #${edgeFrom}`);
                
                if (elementsToFlash.length > 0) {
                    elementsToFlash.addClass('transition-flash');
                    setTimeout(() => elementsToFlash.removeClass('transition-flash'), 1000);
                }
            }
            if (window.applyFilters) window.applyFilters();
        }
    } catch (e) {
        console.error("Erro ao atualizar grafo, recriando...", e);
        setupInitialCytoscape(mainContainerId, data);
    }
}

function formatCode(code) {
    if (!code) return "";

    let formatted = code
        .replace(/;/g, ";\n")
        .replace(/(\d)if/g, "$1\nif")
        .replace(/\sif\s/g, "\nif ")
        .replace(/\sif\(/g, "\nif (")
        .replace(/\{/g, " {\n    ")
        .replace(/\}/g, "\n}")
        .replace(/then/g, " then ")
        .replace(/AND/g, " AND\n    ")
        .replace(/  +/g, ' ')
        .replace(/\n\s*/g, "\n    ")
        .replace(/\n    \}/g, "\n}");

    return formatted.trim();
}

async function setupInitialCytoscape(mainContainerId, data) {
    var mainContainer = document.getElementById(mainContainerId);
    if (currentCytoscapeInstance) {
        currentCytoscapeInstance.destroy();
        currentCytoscapeInstance = null;
    }
    mainContainer.innerHTML = '';
    mainContainer.style.display = 'block';
    mainContainer.style.width = '100%';
    mainContainer.style.height = '100%';
    mainContainer.style.backgroundColor = '#ffffff';

    var sourceCode = (typeof editor !== 'undefined') ? editor.getValue() : JSON.stringify(data.graphElements);

    const graphId = getLayoutKey(sourceCode);

    if (!hasExistingLayoutsInLocalStorage()) {
        await loadDefaultLayoutsFromSeedFile();
    }

    var hasSavedLayout = applySavedPositions(data.graphElements, sourceCode);

    let layoutOptions = {
        name: hasSavedLayout ? 'preset' : 'dagre',
        rankDir: 'LR',
        fit: true,
        padding: 50,
        spacingFactor: 1.2,
        animate: false
    };

    var cy = cytoscape({
        container: mainContainer,
        elements: data.graphElements,
        style: getCytoscapeStyles(),
        layout: layoutOptions,
        wheelSensitivity: 0.2,
        textureOnViewport: true,
        pixelRatio: 1
    });

    if (cy.edgeEditing) {
        cy.edgeEditing({
            undoable: false,
            bendPositionsFunction: function(ele, val) {
                if (val) ele.data('cyedgecontroleditingDistances', val);
                return ele.data('cyedgecontroleditingDistances');
            },
            bendWeightsFunction: function(ele, val) {
                if (val) ele.data('cyedgecontroleditingWeights', val);
                return ele.data('cyedgecontroleditingWeights');
            },
            anchorSize: 10,
            anchorColor: '#ff9e64',
            enableDoubleTapToCreateBendPoint: true,
            initBendPointsAutomated: false
        });
    }

    let saveTimeout;
    const triggerAutoSave = () => {
        clearTimeout(saveTimeout);
        saveTimeout = setTimeout(() => {
            const stableId = getLayoutKey(editor.getValue());
            autoSaveLayoutToLocalStorage(cy, stableId);
            console.log("Layout salvo automaticamente!");
        }, 100);
    };

    cy.on('dragfree', 'node', triggerAutoSave);
    cy.on('cedragfree', 'edge', triggerAutoSave);

    cy.on('tap', 'node.event-node.enabled', function (evt) {
        var node = evt.target;
        var parts = node.id().split('_');
        if (parts.length >= 5) {
            var from = parts[1]; 
            var to = parts[2]; 
            var tId = parts[3]; 
            var lbl = parts[4];
            
            var edgeJson = JSON.stringify({ 
                "from": from, 
                "to": to, 
                "tId": tId, 
                "label": lbl 
            });

            var responseJson = RTA.takeStep(edgeJson);
            var newStateText = RTA.getCurrentStateText();
            jsTextHistory.push({ label: lbl + " ->", text: newStateText });

            updateAllViews(responseJson);
        }
    });

    cy.on('mouseover', 'node.event-node, edge', function (evt) {
        var ele = evt.target;
        ele.addClass('hovered');
        
        if (ele.isNode() && ele.hasClass('event-node')) {
            if (ele.hasClass('enabled')) {
                ele.cy().container().style.cursor = 'pointer';
            }
        }
    });

    cy.on('mouseout', 'node.event-node, edge', function (evt) {
        var ele = evt.target;
        ele.removeClass('hovered');
        
        if (ele.isNode() && ele.hasClass('event-node')) {
            ele.cy().container().style.cursor = 'default';
        }
    });

    cy.on('dbltap', 'edge.has-details', function (evt) {
        var edge = evt.target;
        var rawText = edge.data('full_label');
        var formattedText = formatCode(rawText);
        var contentPre = document.getElementById('edgeDetailContent');
        contentPre.textContent = formattedText;

        if (typeof Prism !== 'undefined') {
            contentPre.className = "language-clike";
            Prism.highlightElement(contentPre);
        }
        $('#edgeDetailModal').modal('show');
    });

    currentCytoscapeInstance = cy;
    setupContextMenu(cy);
    
    if (window.applyFilters) window.applyFilters();
}

function changeEdgeStyle(styleName) {
    if (!currentCytoscapeInstance) return;
    currentEdgeStyle = styleName;

    var edges = currentCytoscapeInstance.edges();
    edges.removeClass('taxi bezier straight');

    if (styleName === 'taxi') {
        edges.style({ 'curve-style': 'taxi', 'taxi-direction': 'vertical' });
    } else if (styleName === 'bezier') {
        edges.style({ 'curve-style': 'bezier', 'control-point-step-size': 40 });
    } else {
        edges.style({ 'curve-style': 'straight' });
    }
}

function renderGlobalPanel(data, targetId) {
    var containerId = targetId || 'sidePanel';
    var panelDiv = document.getElementById(containerId);
    if (!panelDiv) return;

    panelDiv.innerHTML = '';
    var panelData = data.panelData;
    lastModelData = data;

    var t = i18n[currentLang];
    var suffix = containerId === 'sidePanel' ? '' : ('-' + containerId);

    var undoBtn = document.createElement('button');
    undoBtn.className = 'btn btn-warning btn-block btn-sm undo-btn';
    undoBtn.innerHTML = '<span class="glyphicon glyphicon-step-backward"></span> ' + (t.btn_undo || 'Desfazer (Undo)');
    undoBtn.disabled = !panelData.canUndo;
    undoBtn.onclick = function () {
        var json = RTA.undo();
        if (jsTextHistory.length > 1) {
            jsTextHistory.pop();
        }
        updateAllViews(json);
    };
    panelDiv.appendChild(undoBtn);

    if ((panelData.clocks && Object.keys(panelData.clocks).length > 0) ||
        (panelData.variables && Object.keys(panelData.variables).length > 0)) {

        var varHeader = document.createElement('div');
        varHeader.className = 'sec-label';
        varHeader.innerText = t.stat_header || 'Estado Atual:';
        panelDiv.appendChild(varHeader);

        var infoList = document.createElement('ul');
        infoList.className = "list-unstyled";
        infoList.style.fontSize = "12px";
        infoList.style.background = "#fff";
        infoList.style.padding = "10px";
        infoList.style.border = "1px solid var(--border)";
        infoList.style.borderRadius = "2px";

        for (let [k, v] of Object.entries(panelData.variables || {})) {
            if (k.startsWith("__")) continue;
            let li = document.createElement('li');
            li.innerHTML = `<span class="text-success"># ${k}</span>: <b>${v}</b>`;
            infoList.appendChild(li);
        }
        panelDiv.appendChild(infoList);
    }

    var transHeader = document.createElement('div');
    transHeader.className = 'sec-label';
    transHeader.innerText = t.enabled_trans || 'Transições:';
    panelDiv.appendChild(transHeader);

    var isDeadlock = panelData.enabled.length === 0 || (panelData.enabled.length === 1 && panelData.enabled[0].label === "deadlock");
    if (isDeadlock) {
        var dead = document.createElement('div');
        dead.className = "alert alert-danger text-center";
        dead.style.padding = "5px";
        dead.style.fontSize = "12px";
        dead.style.fontWeight = "bold";
        dead.innerText = (t.deadlock || "DEADLOCK") + " (LOOP)";
        panelDiv.appendChild(dead);
    } 
    
    if (panelData.enabled.length > 0) {
        panelData.enabled.forEach(function (edge) {
            if (edge.p !== undefined && edge.p < window.currentDeltaCut && !edge.isDelay && edge.label !== 'deadlock') return;

            var btnGroup = document.createElement('div');

            if (edge.isDelay) {
                btnGroup.className = 'input-group input-group-sm';
                btnGroup.style.marginBottom = '5px';

                var input = document.createElement('input');
                input.type = 'number';
                input.className = 'form-control';
                input.value = storedDelayValue;
                input.step = '0.001';
                input.min = '0.000001';
                input.id = 'delayInputVal' + suffix;

                input.onchange = function () {
                    var val = parseFloat(this.value);
                    if (!isNaN(val)) {
                        storedDelayValue = val;
                        var otherInp = document.getElementById(containerId === 'sidePanel' ? 'delayInputVal-sidePanel-bottom' : 'delayInputVal');
                        if (otherInp) otherInp.value = val;
                    }
                };

                var spanBtn = document.createElement('span');
                spanBtn.className = 'input-group-btn';

                var btn = document.createElement('button');
                btn.className = 'btn btn-default';
                btn.innerHTML = '⏱ Delay';
                btn.onclick = function () {
                    var val = parseFloat(input.value);
                    storedDelayValue = val;
                    var json = RTA.advanceTime(val);
                    updateAllViews(json);
                };

                spanBtn.appendChild(btn);
                btnGroup.appendChild(input);
                btnGroup.appendChild(spanBtn);
                panelDiv.appendChild(btnGroup);

            } else {
                var btn = document.createElement('button');
                var displayName = (edge.tId === edge.label) ? edge.label : edge.tId + " (" + edge.label + ")";

                btn.className = 'sim-btn';
                if (edge.label === 'deadlock') {
                    btn.style.backgroundColor = '#FECACA';
                    btn.style.borderColor = '#EF4444';
                    btn.style.color = '#7F1D1D';
                }
                
                var icon = document.createElement('span');
                icon.className = 'glyphicon glyphicon-play-circle';
                icon.style.color = 'var(--gray-500)';
                if (edge.label === 'deadlock') icon.style.color = '#B91C1C';
                btn.appendChild(icon);
                
                var txt = document.createElement('span');
                txt.innerText = displayName;
                btn.appendChild(txt);
                
                if (edge.p !== undefined && !window.isPossibilisticView) {
                    var pSpan = document.createElement('span');
                    pSpan.className = 'sim-prob';
                    pSpan.innerText = 'P=' + edge.p.toFixed(3);
                    btn.appendChild(pSpan);
                }

                btn.onclick = function () {
                    stopAutoDelay();
                    var json = RTA.takeStep(JSON.stringify(edge));
                    var newStateText = RTA.getCurrentStateText();
                    jsTextHistory.push({ label: edge.label + " ->", text: newStateText });
                    updateAllViews(json);
                };
                panelDiv.appendChild(btn);
            }
        });
    }

    if (containerId === 'sidePanel') {
        var panelGroup = document.createElement('div');
        panelGroup.className = 'panel-group';
        panelGroup.id = 'layoutSettingsGroup' + suffix;
        panelGroup.style.marginTop = '15px';

        var layoutPanel = document.createElement('div');
        layoutPanel.className = 'panel panel-default';

        var panelHeading = document.createElement('div');
        panelHeading.className = 'panel-heading';
        panelHeading.style.padding = '0';

        var titleHtml = `
            <h4 class="panel-title" style="font-size: 11px;">
                <a data-toggle="collapse" href="#collapseLayout${suffix}" style="text-decoration: none; display: block;">
                    <span class="glyphicon glyphicon-cog"></span> ${t.layout_conf_title} <span class="caret"></span>
                </a>
            </h4>`;
        panelHeading.innerHTML = titleHtml;

        var collapseBody = document.createElement('div');
        collapseBody.id = 'collapseLayout' + suffix;
        collapseBody.className = 'panel-collapse collapse';

        var panelBody = document.createElement('div');
        panelBody.className = 'panel-body';

        renderLayoutControls(panelBody);

        collapseBody.appendChild(panelBody);
        layoutPanel.appendChild(panelHeading);
        layoutPanel.appendChild(collapseBody);
        panelGroup.appendChild(layoutPanel);

        panelDiv.appendChild(panelGroup);
    }
}

function renderLayoutControls(container) {
    var t = i18n[currentLang];
    var layoutGroup = document.createElement('div');
    layoutGroup.className = 'form-group';

    var layoutLabel = document.createElement('label');
    layoutLabel.innerText = t.layout_label;
    layoutLabel.style.fontSize = '12px';

    var layoutSelect = document.createElement('select');
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
        var layoutName = e.target.value;
        var options = { name: layoutName, fit: true, padding: 50, animate: true };

        if (layoutName === 'dagre') options.rankDir = 'LR';
        if (layoutName === 'cose') { options.componentSpacing = 40; options.nodeRepulsion = 8000; }

        currentCytoscapeInstance.layout(options).run();
    };

    layoutGroup.appendChild(layoutLabel);
    layoutGroup.appendChild(layoutSelect);
    container.appendChild(layoutGroup);

    var styleGroup = document.createElement('div');
    styleGroup.className = 'form-group';

    var styleLabel = document.createElement('label');
    styleLabel.innerText = t.edge_style_label;
    styleLabel.style.fontSize = '12px';

    var styleSelect = document.createElement('select');
    styleSelect.className = 'form-control input-sm';
    styleSelect.innerHTML = `
        <option value="straight">${t.opt_straight}</option>
        <option value="taxi">${t.opt_taxi}</option>
        <option value="bezier">${t.opt_bezier}</option>
    `;

    styleSelect.value = currentEdgeStyle || 'straight';

    styleSelect.onchange = function (e) {
        changeEdgeStyle(e.target.value); 
    };

    styleGroup.appendChild(styleLabel);
    styleGroup.appendChild(styleSelect);
    container.appendChild(styleGroup);

    container.appendChild(document.createElement('hr'));

    var btnGroup = document.createElement('div');
    btnGroup.className = 'btn-group-vertical btn-block';

    var saveBtn = document.createElement('button');
    saveBtn.className = 'btn btn-default btn-sm';
    saveBtn.innerText = t.btn_save_layout;
    saveBtn.onclick = exportAllLayoutsToFile;

    var loadBtn = document.createElement('button');
    loadBtn.className = 'btn btn-default btn-sm';
    loadBtn.innerText = t.btn_load_layout;
    loadBtn.onclick = function () {
        document.getElementById('hiddenFileInput').click();
    };

    if (!document.getElementById('hiddenFileInput')) {
        var fileInput = document.createElement('input');
        fileInput.type = 'file';
        fileInput.id = 'hiddenFileInput';
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

function renderTextView() {
    var container = document.getElementById("textContainer");

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

    setTimeout(function () {
        container.scrollTop = container.scrollHeight;
    }, 50);
}

function setMermaidMode(mode) {
    currentMermaidMode = mode;
    renderMermaidView();
}

function showLTS() {
    setMermaidMode('lts');
    $('.nav-tabs a[href="#mermaidTab"]').tab('show');
}

function parseStateText(rawText) {
    if (!rawText) return "";
    var lines = rawText.split('\n');
    var html = "";

    lines.forEach(function (line) {
        line = line.trim();
        if (!line) return;

        var match = line.match(/^\[(.*?)\]\s*(.*)/);
        if (match) {
            var key = match[1].toLowerCase();
            var content = match[2];
            var icon = "🔹";
            var inner = content;

            if (key === 'init') { icon = "🚩"; inner = `<span class="tv-tag highlight">${content}</span>`; }
            else if (key === 'act') {
                icon = "⚡";
                inner = content.split(',').map(s => `<span class="tv-tag active">${s.trim()}</span>`).join(" ");
            }
            else if (key === 'clocks' || key === 'vars') { icon = "#️⃣"; }
            else if (key === 'on') { icon = "🟢"; }
            else if (key === 'off') { icon = "🔴"; inner = `<span class="tv-tag disabled">${content}</span>`; }

            html += `<div class="tv-section"><span class="tv-header">${icon} ${key}: </span><span class="tv-content">${inner}</span></div>`;
        } else {
            html += `<div style="padding-left:20px; font-size:11px; color:#777;">${line}</div>`;
        }
    });
    return html;
}

function renderMermaidView() {
    var container = document.getElementById('mermaidContainer');
    if (!container) return;

    if (container.offsetParent === null) {
        return;
    }

    var mermaidCode = "";

    if (!currentMermaidMode) currentMermaidMode = 'full';

    if (currentMermaidMode === 'lts') {
        mermaidCode = RTA.getAllStepsMermaid();
    } else if (currentMermaidMode === 'simple') {
        mermaidCode = RTA.getCurrentStateMermaidSimple();
    } else {
        mermaidCode = RTA.getCurrentStateMermaid();
    }

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


function downloadString(filename, content) {
    var blob = new Blob([content], { type: 'text/plain' });
    var a = document.createElement('a');
    a.href = window.URL.createObjectURL(blob);
    a.download = filename;
    a.click();
}

function downloadMcrl2() { downloadString("model.mcrl2", RTA.getMcrl2()); }



function showStats() {
    document.getElementById("analysisResult").innerText = RTA.getStats();
}

function checkProblems() {
    document.getElementById("analysisResult").innerText = RTA.checkProblems();
}

function stopAutoDelay() {
    if (autoDelayTimer) {
        clearInterval(autoDelayTimer);
        autoDelayTimer = null;
    }
}

function toggleAutoDelay(isChecked) {
    if (isChecked) {
        if (autoDelayTimer) return;
        const runStep = () => {
            var inp = document.getElementById('delayInputVal');
            var delay = inp ? parseFloat(inp.value) : 1.0;
            var json = RTA.advanceTime(delay);
            updateAllViews(json);
        };
        runStep();
        autoDelayTimer = setInterval(runStep, 1000);
    } else {
        stopAutoDelay();
    }
}

function applySavedPositions(graphElements, sourceCode) {
    const graphId = getLayoutKey(sourceCode);

    try {
        var savedJson = localStorage.getItem(`cyLayout_${graphId}`);
        if (savedJson) {
            var savedData = JSON.parse(savedJson);
            var foundSomething = false;

            graphElements.forEach(el => {
                if (el.group === 'nodes' || (el.data && !el.data.source)) {
                    if (savedData.nodes && savedData.nodes[el.data.id]) {
                        el.position = savedData.nodes[el.data.id];
                        foundSomething = true;
                    } else if (el.classes && el.classes.includes('deadlock-node')) {
                        var parts = el.data.id.split('_');
                        if (parts.length >= 2) {
                            var srcId = parts[1];
                            if (savedData.nodes && savedData.nodes[srcId]) {
                                el.position = { x: savedData.nodes[srcId].x, y: savedData.nodes[srcId].y - 70 };
                                foundSomething = true;
                            }
                        }
                    }
                }
                else if (el.data && el.data.source) {
                    if (savedData.edges && savedData.edges[el.data.id]) {
                        var d = savedData.edges[el.data.id].distances;
                        var w = savedData.edges[el.data.id].weights;
                        el.data.edgeDistances = d;
                        el.data.edgeWeights = w;
                        el.data.cyedgecontroleditingDistances = d;
                        el.data.cyedgecontroleditingWeights = w;
                        foundSomething = true;
                    }
                }
            });
            return foundSomething;
        }
    } catch (e) { console.warn("Erro ao aplicar layout salvo:", e); }
    return false;
}

function getCytoscapeStyles() {
    return [
        { selector: 'node', style: { 'label': 'data(label)', 'text-valign': 'center', 'color': '#000000', 'font-family': 'sans-serif', 'font-weight': 'bold', 'text-outline-width': 2, 'text-outline-color': '#FFFFFF' } },
        
        { selector: 'edge', style: { 'width': 2, 'curve-style': 'unbundled-bezier', 'line-color': '#9CA3AF','target-arrow-shape': 'none', 'label': 'data(label)','color': '#000000', 'text-outline-color': '#FFFFFF','text-outline-width': 2,'font-size': '14px'} }, 
        { selector: 'edge[edgeDistances]', style: {'curve-style': 'unbundled-bezier','control-point-distances': 'data(edgeDistances)','control-point-weights': 'data(edgeWeights)','edge-distances': 'node-position'}},
        { selector: 'edge.from-action-node', style: { 'target-arrow-shape': 'triangle' } },
        
        { selector: 'edge.simple-conn', style: { 'text-opacity': 0 } },
        { selector: 'edge.simple-conn.hovered', style: { 'text-opacity': 1, 'z-index': 9999 } },
        
        { selector: 'node.state-node', style: { 'background-color': '#BFDBFE', 'shape': 'ellipse', 'width': 50, 'height': 50, 'border-width': 3, 'border-color': '#3B82F6', 'text-wrap': 'wrap', 'text-valign': 'center' } },
        { selector: 'node.has-invariant', style: { 'label': function(ele) { return ele.data('label') + '\n[' + ele.data('invariant') + ']'; } } },
        
        { selector: '.current-state', style: { 'background-color': '#86EFAC', 'border-color': '#166534', 'border-width': 4 } },
        
        { selector: 'node.event-node', style: { 
            'label': function(ele) {
                const data = ele.data();
                const p = data.p;
                let baseName = data.action_name || (data.label ? data.label.split('\n')[0] : "");

                if (window.isPossibilisticView) {
                    return baseName;
                }

                if (p !== undefined) {
                    const isRule = ele.hasClass('rule-node'); 
                    const symbol = isRule ? "Δ" : "P";
                    if (isRule) return `${baseName}\n(${symbol}=${p.toFixed(3)})`;
                    const nm = (data.transID == data.lbl ? "":data.transID);
                    return `${nm}\n(${symbol}=${p.toFixed(3)})`
                }
                return data.label || "";
            },
            'background-color': '#E5E7EB', 'shape': 'rectangle', 'width': 'label', 'height': 'label', 'padding': 10, 'border-width': 2, 'border-color': '#9CA3AF', 'text-wrap': 'wrap', 'text-valign': 'center', 'text-halign': 'center' 
        } },
        
        { selector: 'node.event-node.hovered', style: { 
            'label': function(ele) {
                let hlbl = ele.data('hover_label') || "";
                if (window.isPossibilisticView) {
                    return hlbl.replace(/\s*\([PΔ]=[\d.]+\)/, '');
                }
                return hlbl;
            },
            'z-index': 9999
        } },
        
        { selector: '.enable-rule', style: { 'line-color': '#2563EB', 'target-arrow-color': '#2563EB' } },
        { selector: '.disable-rule', style: { 'line-color': '#DC2626', 'target-arrow-color': '#DC2626' } },
        { selector: 'edge.enable-rule.to-target', style: { 'target-arrow-shape': 'triangle-tee' } },
        { selector: 'edge.disable-rule.to-target', style: { 'target-label': 'X', 'target-text-offset': 5, 'color': '#DC2626', 'font-size': '12px' } },
        
        { selector: '.disabled', style: { 'line-style': 'dashed', 'background-opacity': 0.6, 'border-style': 'dashed', 'opacity': 0.7 } },
        
        { selector: '.deadlock-node', style: { 'background-color': '#FECACA', 'border-color': '#EF4444', 'color': '#7F1D1D' } },
        { selector: '.deadlock-edge', style: { 'line-color': '#EF4444', 'target-arrow-color': '#EF4444', 'line-style': 'dashed' } },
        { selector: '.deadlock-edge.enabled', style: { 'line-style': 'solid', 'width': 3 } },

        { selector: '.transition-flash', style: { 'background-color': '#F97316', 'line-color': '#F97316', 'target-arrow-color': '#F97316' } },
        
        { selector: '.filtered-out', style: { 'display': 'none' } },

        { selector: '.compound-parent', style: { 'background-color': '#F3F4F6', 'background-opacity': 1, 'border-color': '#D1D5DB', 'border-width': 2, 'content': 'data(label)', 'text-valign': 'top', 'text-halign': 'center', 'color': '#374151', 'font-weight': 'bold', 'font-size': '16px' } }
    ];
}