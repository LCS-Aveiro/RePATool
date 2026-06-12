function MermaidSVG() {
    const container = document.getElementById('mermaidContainer');
    const svgElement = container.querySelector('svg');

    if (!svgElement) {
        alert("Please open the Mermaid tab first.");
        return;
    }

    const clonedSvg = svgElement.cloneNode(true);
    const fontSize = "14px";
    const textColor = "#000000";

    const style = document.createElementNS("http://www.w3.org/2000/svg", "style");
    style.textContent = `

    foreignObject {
            overflow: visible !important;
        }


        .edgeLabel rect, .label rect {
            display: none !important;
        }


        .edgeLabel span {
            font-family: Arial, sans-serif !important;
            font-size: ${fontSize} !important;
            color: ${textColor} !important;
            font-weight: bold !important;
            background-color: white !important;
            padding: 1px 4px !important;
            border: 1px solid #999 !important; 
            border-radius: 3px !important;
            display: inline-block !important;
            
            transform: translate(-50%, -50%) !important;
            position: absolute !important;
            white-space: nowrap !important;
        }

        .edgeLabel span:empty {
            display: none !important;
        }

        .nodeLabel, .node span {
            font-family: Arial, sans-serif !important;
            font-size: ${fontSize} !important;
            color: ${textColor} !important;
            font-weight: bold !important;
            background: none !important;
            border: none !important;
        }

        .marker.cross path {
            stroke: red !important;
            stroke-width: 2px !important;
        }

        text {
            font-family: Arial, sans-serif !important;
            font-size: ${fontSize} !important;
            fill: ${textColor} !important;
        }
    `;
    clonedSvg.insertBefore(style, clonedSvg.firstChild);


    const serializer = new XMLSerializer();
    let svgData = serializer.serializeToString(clonedSvg);

    if (!svgData.match(/xmlns="http\:\/\/www\.w3\.org\/2000\/svg"/)) {
        svgData = svgData.replace(/^<svg/, '<svg xmlns="http://www.w3.org/2000/svg"');
    }

    

   return svgData;
}

function downloadMermaidSVG() {

    const svgBlob = new Blob(['<?xml version="1.0" encoding="UTF-8" standalone="no"?>\r\n' + MermaidSVG()], {
        type: "image/svg+xml;charset=utf-8"
    });
    
    const url = URL.createObjectURL(svgBlob);
    const downloadLink = document.createElement("a");
    downloadLink.href = url;
    downloadLink.download = "rta.svg";
    document.body.appendChild(downloadLink);
    downloadLink.click();
    document.body.removeChild(downloadLink);
}


 function svgToTikz(svgElement) {
    const viewBox = svgElement.viewBox.baseVal;
    const offsetX = viewBox ? viewBox.x : 0;
    const offsetY = viewBox ? viewBox.y : 0;
    const scale = 0.018; 
    
    let tikz = "% In LaTeX: \\usepackage{tikz} \\usetikzlibrary{arrows.meta}\n";
    tikz += "\\begin{tikzpicture}[yscale=-1, x=1cm, y=1cm, >=Stealth]\n";

    function parseColor(color) {
        if (!color || color === 'none' || color === 'transparent') return null;
        if (color.startsWith('#')) {
            const hex = color.replace('#', '');
            const r = (parseInt(hex.length === 3 ? hex[0]+hex[0] : hex.slice(0, 2), 16) / 255).toFixed(2);
            const g = (parseInt(hex.length === 3 ? hex[1]+hex[1] : hex.slice(2, 4), 16) / 255).toFixed(2);
            const b = (parseInt(hex.length === 3 ? hex[2]+hex[2] : hex.slice(4, 6), 16) / 255).toFixed(2);
            return `{rgb,1:red,${r};green,${g};blue,${b}}`;
        }
        return color; 
    }

    function getStyles(el) {
        const styleStr = el.getAttribute('style') || "";
        const inline = {};
        styleStr.split(';').forEach(s => {
            const [k, v] = s.split(':');
            if (k && v) inline[k.trim()] = v.trim();
        });

        const fill = parseColor(inline['fill'] || el.getAttribute('fill'));
        const stroke = parseColor(inline['stroke'] || el.getAttribute('stroke'));
        const sw = parseFloat(inline['stroke-width'] || el.getAttribute('stroke-width') || 1);
        const dash = inline['stroke-dasharray'] || el.getAttribute('stroke-dasharray');

        let res = [];
        if (fill && fill !== 'none') res.push(`fill=${fill}`);
        if (stroke && stroke !== 'none') res.push(`draw=${stroke}`);
        res.push(`line width=${(isNaN(sw) ? 0.5 : sw * 0.5).toFixed(1)}pt`);

        if (dash && dash !== '0' && dash !== 'none') {
            const dashVal = parseFloat(dash.split(/[\s,]+/)[0]);
            if (dashVal <= 3) res.push('dotted');
            else res.push('dashed');
        }

        const markerEnd = el.getAttribute('marker-end') || "";
        if (markerEnd.includes('pointEnd')) res.push(`->`);
      
        return res.length ? `[${res.join(', ')}] ` : "";
    }

    function process(el, ax, ay) {
        let x = ax, y = ay;
        const trans = el.getAttribute('transform');
        if (trans && trans.includes('translate')) {
            const m = trans.match(/translate\(([^,)]+)[, ]?([^)]+)?\)/);
            if (m) { x += parseFloat(m[1]) || 0; y += parseFloat(m[2]) || 0; }
        }

        const fX = (v) => ((parseFloat(v) + x - offsetX) * scale).toFixed(3);
        const fY = (v) => ((parseFloat(v) + y - offsetY) * scale).toFixed(3);

        if (el.tagName === 'rect') {
            const styleStr = el.getAttribute('style') || "";
            const wAttr = parseFloat(el.getAttribute('width') || 0);
            if (wAttr > 0 && !styleStr.includes('width: 0')) {
                const h = parseFloat(el.getAttribute('height') || 0);
                const rx = parseFloat(el.getAttribute('x') || 0);
                const ry = parseFloat(el.getAttribute('y') || 0);
                tikz += `  \\draw${getStyles(el)} (${fX(rx)},${fY(ry)}) rectangle (${fX(rx + wAttr)},${fY(ry + h)});\n`;
            }
        }

        if (el.tagName === 'path' && !el.classList.contains('arrowMarkerPath')) {
            const d = el.getAttribute('d');
            const markerEnd = el.getAttribute('marker-end') || "";
            if (d) {
                let p = d.replace(/([MLCQZ])([^MLCQZ]*)/gi, (m, c, a) => {
                    const pts = a.trim().split(/[\s,]+/).map(parseFloat);
                    if (pts.some(isNaN) && c.toUpperCase() !== 'Z') return "";
                    if (c.toUpperCase() === 'M') return `(${fX(pts[0])},${fY(pts[1])}) `;
                    if (c.toUpperCase() === 'L') return `-- (${fX(pts[0])},${fY(pts[1])}) `;
                    if (c.toUpperCase() === 'C') return `.. controls (${fX(pts[0])},${fY(pts[1])}) and (${fX(pts[2])},${fY(pts[3])}) .. (${fX(pts[4])},${fY(pts[5])}) `;
                    if (c.toUpperCase() === 'Z') return `-- cycle`;
                    return "";
                });
                if (p.trim()) {
                    let suffix = ";";
                    if (markerEnd.includes('crossEnd')) {
                        suffix = " node[at end, sloped, anchor=center, inner sep=0pt, text=red, font=\\bfseries\\small] {X};";
                    }
                    tikz += `  \\draw${getStyles(el)} ${p.trim()}${suffix}\n`;
                }
            }
        }

        if (el.tagName === 'span' || (el.tagName === 'text' && !el.closest('marker'))) {
            const txt = el.textContent.trim();
            if (txt && txt.length < 300) {
                const fo = el.closest('foreignObject');
                let tx, ty;
                if (fo) {
                    const w = parseFloat(fo.getAttribute('width') || 0);
                    const h = parseFloat(fo.getAttribute('height') || 0);
                    tx = parseFloat(fo.getAttribute('x') || 0) + w/2;
                    ty = parseFloat(fo.getAttribute('y') || 0) + h/2;
                } else {
                    tx = parseFloat(el.getAttribute('x') || 0);
                    ty = parseFloat(el.getAttribute('y') || 0);
                }
                const safeTxt = txt.replace(/([_#&$%])/g, '\\$1');
                tikz += `  \\node at (${fX(tx)},${fY(ty)}) {\\small\\textbf{${safeTxt}}};\n`;
            }
        }
        Array.from(el.children).forEach(c => process(c, x, y));
    }

    process(svgElement, 0, 0);
    tikz += "\\end{tikzpicture}";
    return tikz;
}


function downloadLatex() {
    const svgContent = MermaidSVG();
    let element;

    if (typeof svgContent === "string") {
        const parser = new DOMParser();
        const doc = parser.parseFromString(svgContent, "image/svg+xml");
        element = doc.querySelector("svg");
    } else {
        element = svgContent;
    }

    const tikzCode = svgToTikz(element);

    const blob = new Blob([tikzCode], { type: "text/plain;charset=utf-8" });
    
    const url = URL.createObjectURL(blob);
    const downloadLink = document.createElement("a");
    downloadLink.href = url;
    downloadLink.download = "rta.tex"; 
    document.body.appendChild(downloadLink);
    downloadLink.click();
    
    document.body.removeChild(downloadLink);
    URL.revokeObjectURL(url); 
}


function downloadPNG() {
    if (!currentCytoscapeInstance) {
        alert("Carregue o modelo primeiro.");
        return;
    }

    const pngData = currentCytoscapeInstance.png({
        full: true,
        bg: '#ffffff',
        scale: 2
    });

    const link = document.createElement("a");
    link.href = pngData;
    link.download = "rta-graph.png";
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
}



function translateToGLTS() {
    var newCode = RTA.translateToGLTS();
    if (newCode && !newCode.startsWith("Erro")) {
        editor.setValue(newCode);
        loadAndRender();
        alert(i18n[currentLang].alert_trans_ok);
    } else {
        alert(newCode);
    }
}



function autoSaveLayoutToLocalStorage(cy, graphId) {
    if (!cy || !graphId || typeof localStorage === 'undefined') return;
    console.log("save");
    const layoutData = {
        nodes: {},
        edges: {}
    };

    cy.nodes().forEach(node => {
        if (node.children().length === 0) {
            layoutData.nodes[node.id()] = node.position();
        }
    });


    cy.edges().forEach(edge => {
        const dists = edge.data('cyedgecontroleditingDistances') || edge.data('edgeDistances');
        const weights = edge.data('cyedgecontroleditingWeights') || edge.data('edgeWeights');

        if (dists && dists.length > 0) {
            layoutData.edges[edge.id()] = {
                distances: dists,
                weights: weights
            };
        }
    });

    localStorage.setItem(`cyLayout_${graphId}`, JSON.stringify(layoutData));
}

function loadLayoutFromLocalStorage(cy, graphId) {
    if (!cy || !graphId || typeof localStorage === 'undefined') return false;

    const storageKey = `cyLayout_${graphId}`;
    const savedLayout = localStorage.getItem(storageKey);

    if (savedLayout) {
        try {
            const savedData = JSON.parse(savedLayout);
            cy.batch(() => {
                if (savedData.nodes) {
                    for (const nodeId in savedData.nodes) {
                        const node = cy.getElementById(nodeId);
                        if (node.length > 0) node.position(savedData.nodes[nodeId]);
                    }
                }
                if (savedData.edges) {
                    for (const edgeId in savedData.edges) {
                        const edge = cy.getElementById(edgeId);
                        if (edge.length > 0) {
                            edge.data('cyedgecontroleditingDistances', savedData.edges[edgeId].distances);
                            edge.data('cyedgecontroleditingWeights', savedData.edges[edgeId].weights);
                        }
                    }
                }
            });
            cy.fit(null, 50);
            return true;
        } catch (e) {
            console.error("Erro ao carregar layout:", e);
            return false;
        }
    }
    return false;
}


function exportAllLayoutsToFile() {
    if (typeof localStorage === 'undefined') {
        alert("O LocalStorage não é suportado neste navegador.");
        return;
    }

    const allLayouts = {};
    let layoutsFound = 0;

    for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);

        if (key && key.startsWith('cyLayout_')) {

            allLayouts[key] = JSON.parse(localStorage.getItem(key));
            layoutsFound++;
        }
    }

    if (layoutsFound === 0) {
        alert("Nenhum layout salvo foi encontrado para exportar.");
        return;
    }

    const jsonString = JSON.stringify(allLayouts, null, 2);
    const blob = new Blob([jsonString], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'all-cytoscape-layouts-backup.json';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(a.href);

    console.log(`${layoutsFound} layouts foram exportados com sucesso.`);
}


function importAllLayoutsFromFile(cy, graphId, jsonString) {
    if (typeof localStorage === 'undefined') {
        alert("O LocalStorage não é suportado neste navegador.");
        return;
    }

    try {
        const allLayouts = JSON.parse(jsonString);
        let layoutsImported = 0;


        for (const key in allLayouts) {
            if (key && key.startsWith('cyLayout_')) {
                const value = JSON.stringify(allLayouts[key]);
                localStorage.setItem(key, value);
                layoutsImported++;
            }
        }

        if (layoutsImported > 0) {
            alert(`${layoutsImported} layouts foram importados com sucesso para o seu navegador!`);

            console.log("Tentando aplicar o layout para o grafo atual...");
            loadLayoutFromLocalStorage(cy, graphId);

        } else {
            alert("Nenhum layout válido encontrado no arquivo selecionado.");
        }

    } catch (e) {
        console.error("Falha ao importar layouts do arquivo.", e);
        alert("Erro ao ler o arquivo. Verifique se é um backup de layout válido.");
    }
}

function hasExistingLayoutsInLocalStorage() {
    if (typeof localStorage === 'undefined') return false;
    for (let i = 0; i < localStorage.length; i++) {
        if (localStorage.key(i).startsWith('cyLayout_')) return true;
    }
    return false;
}

async function loadDefaultLayoutsFromSeedFile() {
    try {
        if (window.RTA_DEFAULT_LAYOUTS) {
            const layouts = window.RTA_DEFAULT_LAYOUTS;
            
            for (const k in layouts) {
                if (k.startsWith('cyLayout_') && !localStorage.getItem(k)) {
                    localStorage.setItem(k, JSON.stringify(layouts[k]));
                }
            }
            console.log("✅ Layouts de semente carregados com sucesso via Script.");
        }
    } catch (e) { 
        console.warn("⚠️ Não foi possível carregar os layouts padrão:", e); 
    }
}


function downloadPrism() {
    var content = RTA.getPrismModel();
    downloadString("model.pm", content);
}

function downloadPrism2() {
    var content = RTA.getPrismModel2();
    downloadString("model.pm", content);
}


function updatePctlDropdowns(data) {
    var stateSelect = document.getElementById('pctlStateSelect');
    var actionSelect = document.getElementById('pctlActionSelect');
    var actionOffSelect = document.getElementById('pctlActionOffSelect');
    
    if(!stateSelect || !actionSelect || !actionOffSelect || !data || !data.graphElements) return;

    stateSelect.innerHTML = '';
    actionSelect.innerHTML = '';
    actionOffSelect.innerHTML = '';
    
    var uniqueStates = new Set();
    var uniqueActions = new Set();

    data.graphElements.forEach(function (el) {
        var cls = el.classes || "";
        var d = el.data;

        if (cls.indexOf('state-node') !== -1 && d && d.label) {
            uniqueStates.add(d.label);
        }

        if (cls.indexOf('event-node') !== -1 && d && d.action_name) {
            uniqueActions.add(d.action_name);
        }
    });

    Array.from(uniqueStates).sort().forEach(function(st) {
        var opt = document.createElement('option');
        opt.value = st; 
        opt.innerText = st;
        stateSelect.appendChild(opt);
    });
    
    Array.from(uniqueActions).sort().forEach(function(act) {
        var sanitized = act.replace(/[^a-zA-Z0-9_]/g, "_");
        
        var opt1 = document.createElement('option');
        opt1.value = sanitized;
        opt1.innerText = act;
        actionSelect.appendChild(opt1);
        
        var opt2 = document.createElement('option');
        opt2.value = sanitized;
        opt2.innerText = act;
        actionOffSelect.appendChild(opt2);
    });
}

function openPctlGenerator() {
    if (typeof showRightTab === 'function') {
        showRightTab('pctl');
    }
    var textArea = document.getElementById('pctlTextArea');
    if (textArea.value.trim() === "") {
        textArea.value = 
            "// PRISM Properties File (.pctl)\n" +
            "// P=?   : Qual a probabilidade?\n" +
            "// F     : Eventualmente (Future)\n" +
            "// G     : Sempre (Globally)\n" +
            "// X     : No exato próximo passo (Next)\n" +
            "// A U B : 'A' mantém-se verdade até que 'B' aconteça (Until)\n\n";
    }
}

function insertPctlText(text) {
    var input = document.getElementById('pctlTextArea');
    if (input.selectionStart || input.selectionStart === 0) {
        var startPos = input.selectionStart;
        var endPos = input.selectionEnd;
        input.value = input.value.substring(0, startPos) + text + input.value.substring(endPos, input.value.length);
        input.selectionStart = startPos + text.length;
        input.selectionEnd = startPos + text.length;
    } else {
        input.value += text;
    }
    input.focus();
}

function addPctlTemplate(type) {
    var prop = "";
    if (type === 'F_state') {
        var sel = document.getElementById('pctlStateSelect');
        var stName = sel.value;
        prop = `\n// Qual a probabilidade de alcançar '${stName}'?\nP=? [ F ${stName} ]\n`;
    } 
    else if (type === 'F_act1') {
        var sel = document.getElementById('pctlActionSelect');
        prop = `\n// Qual a probabilidade da ação '${sel.value}' ficar ATIVA?\nP=? [ F ${sel.value}_act=1 ]\n`;
    } 
    else if (type === 'F_act0') {
        var sel = document.getElementById('pctlActionOffSelect');
        prop = `\n// Qual a probabilidade da ação '${sel.value}' ficar INATIVA?\nP=? [ F ${sel.value}_act=0 ]\n`;
    }
    insertPctlText(prop);
}

function downloadPctl() {
    var content = document.getElementById('pctlTextArea').value;
    if (content.trim() === "") {
        alert("O arquivo de propriedades está vazio!");
        return;
    }
    downloadString("propriedades.pctl", content); 
}

function runPctl() {
    var textArea = document.getElementById('pctlTextArea');
    var rawText = textArea.value;
    var lines = rawText.split('\n');
    var formulas = [];
    
    var selectedText = textArea.value.substring(textArea.selectionStart, textArea.selectionEnd).trim();
    var linesToProcess = (selectedText.length > 0) ? selectedText.split('\n') : lines;

    for (var i = 0; i < linesToProcess.length; i++) {
        var l = linesToProcess[i].trim();
        if (l !== "" && !l.startsWith("//")) {
            formulas.push(l);
        }
    }
    
    if (formulas.length === 0) {
        alert("Nenhuma fórmula PCTL encontrada para verificar.");
        return;
    }

    var evalState = "";
    var evalStateInput = document.getElementById('pctlEvalState');
    if (evalStateInput) {
        evalState = evalStateInput.value.trim();
    }
    
    if (evalState === "") {
        if (currentCytoscapeInstance) {
            var currentNodes = currentCytoscapeInstance.nodes('.current-state');
            if (currentNodes.length > 0) {
                evalState = currentNodes[0].data('label');
                if (evalStateInput) evalStateInput.value = evalState;
            }
        }
    }
    
    if (evalState === "") {
        alert("Não foi possível determinar o estado de partida. Por favor, digite-o.");
        return;
    }

    var resDiv = document.getElementById("pctlResult");
    if (!resDiv) return;
    
    var resultHtml = "<ul style='padding-left: 10px; margin-top: 5px; list-style-type: none;'>";

    for (var j = 0; j < formulas.length; j++) {
        var originalFormula = formulas[j];
        var formulaToRun = originalFormula;
        
        if (!formulaToRun.startsWith("{")) {
            formulaToRun = "{" + formulaToRun + "}";
        }

        var res = RTA.runPdl(evalState, formulaToRun);
        
        var color = "#333";
        var icon = "";
        var textRes = "";

        if (res.includes("true") || res.includes("Result: true")) {
            color = "green";
            icon = '<span class="glyphicon glyphicon-ok"></span>';
            textRes = 'Verdadeiro';
        } else if (res.includes("false") || res.includes("Result: false")) {
            color = "red";
            icon = '<span class="glyphicon glyphicon-remove"></span>';
            textRes = 'Falso';
        } else if (res.includes("Result:")) {
            color = "#0056b3";
            icon = '<span class="glyphicon glyphicon-stats"></span>';
            textRes = res.replace("Result: ", "P = ");
        } else {
            color = "#991b1b";
            icon = '<span class="glyphicon glyphicon-exclamation-sign"></span>';
            textRes = res;
        }

        resultHtml += `
            <li style="margin-bottom: 8px; border-bottom: 1px solid #eee; padding-bottom: 4px;">
                <div style="font-family: var(--font-mono); font-size: 10px; color: #666; background: #f9f9f9; padding: 2px 4px; display: inline-block; border-radius: 3px; margin-bottom: 2px;">
                    ${originalFormula}
                </div>
                <div style="color: ${color}; font-size: 12px; font-weight: bold;">
                    ${icon} ${textRes}
                </div>
            </li>`;
    }
    
    resultHtml += "</ul>";
    resDiv.innerHTML = resultHtml;
}