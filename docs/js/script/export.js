

function MermaidSVG() {
    const container  = document.getElementById('mermaidContainer');
    const svgElement = container.querySelector('svg');

    if (!svgElement) {
        alert("Please open the Mermaid tab first.");
        return;
    }

    const clonedSvg = svgElement.cloneNode(true);
    const fontSize  = "14px";
    const textColor = "#000000";

    const style = document.createElementNS("http://www.w3.org/2000/svg", "style");
    style.textContent = `
        foreignObject { overflow: visible !important; }
        .edgeLabel rect, .label rect { display: none !important; }
        .edgeLabel span {
            font-family: Arial, sans-serif !important; font-size: ${fontSize} !important;
            color: ${textColor} !important; font-weight: bold !important;
            background-color: white !important; padding: 1px 4px !important;
            border: 1px solid #999 !important; border-radius: 3px !important;
            display: inline-block !important; transform: translate(-50%, -50%) !important;
            position: absolute !important; white-space: nowrap !important;
        }
        .edgeLabel span:empty { display: none !important; }
        .nodeLabel, .node span {
            font-family: Arial, sans-serif !important; font-size: ${fontSize} !important;
            color: ${textColor} !important; font-weight: bold !important;
            background: none !important; border: none !important;
        }
        .marker.cross path { stroke: red !important; stroke-width: 2px !important; }
        text { font-family: Arial, sans-serif !important; font-size: ${fontSize} !important; fill: ${textColor} !important; }
    `;
    clonedSvg.insertBefore(style, clonedSvg.firstChild);

    const serializer = new XMLSerializer();
    let svgData = serializer.serializeToString(clonedSvg);
    if (!svgData.match(/xmlns="http:\/\/www\.w3\.org\/2000\/svg"/)) {
        svgData = svgData.replace(/^<svg/, '<svg xmlns="http://www.w3.org/2000/svg"');
    }
    return svgData;
}

function downloadMermaidSVG() {
    const svgBlob = new Blob(
        ['<?xml version="1.0" encoding="UTF-8" standalone="no"?>\r\n' + MermaidSVG()],
        { type: "image/svg+xml;charset=utf-8" }
    );
    const url          = URL.createObjectURL(svgBlob);
    const downloadLink = document.createElement("a");
    downloadLink.href  = url;
    downloadLink.download = "rta.svg";
    document.body.appendChild(downloadLink);
    downloadLink.click();
    document.body.removeChild(downloadLink);
}


function downloadPNG() {
    if (!currentCytoscapeInstance) {
        alert("Carregue o modelo primeiro.");
        return;
    }
    const pngData  = currentCytoscapeInstance.png({ full: true, bg: '#ffffff', scale: 2 });
    const link     = document.createElement("a");
    link.href      = pngData;
    link.download  = "rta-graph.png";
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
}


function svgToTikz(svgElement) {
    const viewBox = svgElement.viewBox.baseVal;
    const offsetX = viewBox ? viewBox.x : 0;
    const offsetY = viewBox ? viewBox.y : 0;
    const scale   = 0.018;

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
        const inline   = {};
        styleStr.split(';').forEach(s => {
            const [k, v] = s.split(':');
            if (k && v) inline[k.trim()] = v.trim();
        });
        const fill  = parseColor(inline['fill']   || el.getAttribute('fill'));
        const stroke = parseColor(inline['stroke'] || el.getAttribute('stroke'));
        const sw    = parseFloat(inline['stroke-width'] || el.getAttribute('stroke-width') || 1);
        const dash  = inline['stroke-dasharray'] || el.getAttribute('stroke-dasharray');

        let res = [];
        if (fill   && fill   !== 'none') res.push(`fill=${fill}`);
        if (stroke && stroke !== 'none') res.push(`draw=${stroke}`);
        res.push(`line width=${(isNaN(sw) ? 0.5 : sw * 0.5).toFixed(1)}pt`);
        if (dash && dash !== '0' && dash !== 'none') {
            const dashVal = parseFloat(dash.split(/[\s,]+/)[0]);
            res.push(dashVal <= 3 ? 'dotted' : 'dashed');
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

        const fX = v => ((parseFloat(v) + x - offsetX) * scale).toFixed(3);
        const fY = v => ((parseFloat(v) + y - offsetY) * scale).toFixed(3);

        if (el.tagName === 'rect') {
            const w = parseFloat(el.getAttribute('width') || 0);
            if (w > 0 && !(el.getAttribute('style') || "").includes('width: 0')) {
                const h  = parseFloat(el.getAttribute('height') || 0);
                const rx = parseFloat(el.getAttribute('x') || 0);
                const ry = parseFloat(el.getAttribute('y') || 0);
                tikz += `  \\draw${getStyles(el)} (${fX(rx)},${fY(ry)}) rectangle (${fX(rx + w)},${fY(ry + h)});\n`;
            }
        }

        if (el.tagName === 'path' && !el.classList.contains('arrowMarkerPath')) {
            const d         = el.getAttribute('d');
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
                    tx = parseFloat(fo.getAttribute('x') || 0) + parseFloat(fo.getAttribute('width')  || 0) / 2;
                    ty = parseFloat(fo.getAttribute('y') || 0) + parseFloat(fo.getAttribute('height') || 0) / 2;
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
        element = parser.parseFromString(svgContent, "image/svg+xml").querySelector("svg");
    } else {
        element = svgContent;
    }

    const tikzCode = svgToTikz(element);
    const blob     = new Blob([tikzCode], { type: "text/plain;charset=utf-8" });
    const url      = URL.createObjectURL(blob);
    const a        = document.createElement("a");
    a.href         = url;
    a.download     = "rta.tex";
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}


function downloadPrism()  { downloadString("model.pm", RTA.getPrismModel());  }
function downloadPrism2() { downloadString("model.pm", RTA.getPrismModel2()); }


function updatePctlDropdowns(data) {
    var stateSelect = document.getElementById('pctlStateSelect');
    if (!stateSelect || !data || !data.graphElements) return;

    stateSelect.innerHTML = '';
    var uniqueStates = new Set();
    data.graphElements.forEach(function (el) {
        if ((el.classes || "").indexOf('state-node') !== -1 && el.data && el.data.label) {
            uniqueStates.add(el.data.label);
        }
    });
    Array.from(uniqueStates).sort().forEach(function (st) {
        var opt      = document.createElement('option');
        opt.value    = st;
        opt.innerText = st;
        stateSelect.appendChild(opt);
    });
}

function openPctlGenerator() {
    if (typeof showRightTab === 'function') showRightTab('pctl');
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
        var s = input.selectionStart;
        var e = input.selectionEnd;
        input.value = input.value.substring(0, s) + text + input.value.substring(e);
        input.selectionStart = input.selectionEnd = s + text.length;
    } else {
        input.value += text;
    }
    input.focus();
}

function addPctlTemplate(type) {
    var prop = "";
    if (type === 'F_state') {
        var stName = document.getElementById('pctlStateSelect').value;
        prop = `\n// Qual a probabilidade de alcançar '${stName}'?\nP=? [ F ${stName} ]\n`;
    } else if (type === 'F_act1') {
        var sel = document.getElementById('pctlActionSelect').value;
        prop = `\n// Qual a probabilidade da ação '${sel}' ficar ATIVA?\nP=? [ F ${sel}_act=1 ]\n`;
    } else if (type === 'F_act0') {
        var sel = document.getElementById('pctlActionOffSelect').value;
        prop = `\n// Qual a probabilidade da ação '${sel}' ficar INATIVA?\nP=? [ F ${sel}_act=0 ]\n`;
    }
    insertPctlText(prop);
}

function downloadPctl() {
    var content = document.getElementById('pctlTextArea').value;
    if (content.trim() === "") { alert("O arquivo de propriedades está vazio!"); return; }
    downloadString("propriedades.pctl", content);
}

function runPctl() {
    var textArea     = document.getElementById('pctlTextArea');
    var selectedText = textArea.value.substring(textArea.selectionStart, textArea.selectionEnd).trim();
    var linesToProcess = (selectedText.length > 0) ? selectedText.split('\n') : textArea.value.split('\n');

    var formulas = linesToProcess.map(l => l.trim()).filter(l => l !== "" && !l.startsWith("//"));
    if (formulas.length === 0) { alert("Nenhuma fórmula PCTL encontrada para verificar."); return; }

    var evalState = _getPctlEvalState();
    if (evalState === "") { alert("Não foi possível determinar o estado de partida. Por favor, digite-o."); return; }

    var resDiv    = document.getElementById("pctlResult");
    if (!resDiv) return;

    var resultHtml = "<ul style='padding-left:10px; margin-top:5px; list-style-type:none;'>";
    window.lastPctlTraces = [];

    formulas.forEach(function (originalFormula) {
        var formulaToRun = originalFormula.startsWith("{") ? originalFormula : "{" + originalFormula + "}";
        var rawRes = RTA.runPdl(evalState, formulaToRun, window.appSettings.maxStates, window.appSettings.maxIter, window.appSettings.epsilon);

        var resObj, res = rawRes;
        try {
            resObj = JSON.parse(rawRes);
            res    = resObj.error ? resObj.error : resObj.result;
            if (!resObj.error) window.lastPctlTraces.push(resObj);
        } catch(e) {}

        var color = "#333", icon = "", textRes = "";
        if      (res.includes("true"))    { color = "green";   icon = '<span class="glyphicon glyphicon-ok"></span>';              textRes = 'Verdadeiro'; }
        else if (res.includes("false"))   { color = "red";     icon = '<span class="glyphicon glyphicon-remove"></span>';          textRes = 'Falso'; }
        else if (res.includes("Result:")) { color = "#0056b3"; icon = '<span class="glyphicon glyphicon-stats"></span>';           textRes = res.replace("Result: ", "P = "); }
        else                              { color = "#991b1b"; icon = '<span class="glyphicon glyphicon-exclamation-sign"></span>'; textRes = res; }

        resultHtml += `
            <li style="margin-bottom:8px; border-bottom:1px solid #eee; padding-bottom:4px;">
                <div style="font-family:var(--font-mono); font-size:10px; color:#666; background:#f9f9f9; padding:2px 4px; display:inline-block; border-radius:3px; margin-bottom:2px;">
                    ${originalFormula}
                </div>
                <div style="color:${color}; font-size:12px; font-weight:bold;">
                    ${icon} ${textRes}
                </div>
            </li>`;
    });

    resultHtml += "</ul>";
    resDiv.innerHTML = resultHtml;
}

function animatePctl() {
    var evalState = _getPctlEvalState();
    var textArea  = document.getElementById('pctlTextArea');
    var raw       = textArea.value;
    var selected  = raw.substring(textArea.selectionStart, textArea.selectionEnd).trim();
    var formulaText = selected.length > 0 ? selected : raw;
    if (formulaText.trim() === "" || formulaText.startsWith("//")) return;

    runPctl();

    if (typeof runGraphAnimationTrace === 'function' && window.lastPctlTraces && window.lastPctlTraces.length > 0) {
        runGraphAnimationTrace(window.lastPctlTraces[0], evalState);
    } else if (evalState === "") {
        alert("Estado de partida não definido.");
    }
}

function _getPctlEvalState() {
    var evalStateInput = document.getElementById('pctlEvalState');
    var evalState      = evalStateInput ? evalStateInput.value.trim() : "";
    if (evalState === "" && currentCytoscapeInstance) {
        var currentNodes = currentCytoscapeInstance.nodes('.current-state');
        if (currentNodes.length > 0) {
            evalState = currentNodes[0].data('label');
            if (evalStateInput) evalStateInput.value = evalState;
        }
    }
    return evalState;
}
