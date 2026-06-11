function renderPdlHelpers(data) {
    var statesDiv = document.getElementById('pdl-states-list');
    var actionsDiv = document.getElementById('pdl-actions-list');
    var varsDiv = document.getElementById('pdl-vars-list');

    if (!statesDiv || !actionsDiv || !data || !data.graphElements) return;

    statesDiv.innerHTML = '';
    actionsDiv.innerHTML = '';
    if (varsDiv) varsDiv.innerHTML = '';

    var uniqueStates = new Set();
    var actionMap = new Map();

    data.graphElements.forEach(function (el) {
        var cls = el.classes || "";
        var d = el.data;

        if (cls.indexOf('state-node') !== -1 && d && d.label) {
            uniqueStates.add(d.label);
        }

        if (cls.indexOf('event-node') !== -1 && d && d.action_name) {
            var display = d.hover_label || d.label.replace(/\\n/g, ' ').replace('\n', ' ');
            var technicalName = d.action_name;

            actionMap.set(display, technicalName);
        }
    });

    if (uniqueStates.size === 0) {
        statesDiv.innerHTML = '<span class="text-muted" style="font-size:10px;">Nenhum estado.</span>';
    } else {
        Array.from(uniqueStates).sort().forEach(function (st) {
            var btn = document.createElement('button');
            btn.className = 'btn btn-xs btn-primary';
            btn.style.margin = '2px';
            btn.innerText = st;
            btn.onclick = function () { setState(st); };
            statesDiv.appendChild(btn);
        });
    }

    var hasVars = false;
    if (data.panelData) {
        var allVars = {};
        if (data.panelData.variables) Object.assign(allVars, data.panelData.variables);
        if (data.panelData.clocks) Object.assign(allVars, data.panelData.clocks);

        for (var vName in allVars) {
            hasVars = true;
            var btn = document.createElement('button');
            btn.className = 'btn btn-xs btn-success';
            btn.style.margin = '2px';
            btn.innerText = vName;
            btn.onclick = (function (name) {
                return function () { insertPdl(name); };
            })(vName);
            if (varsDiv) varsDiv.appendChild(btn);
        }
    }
    if (!hasVars && varsDiv) {
        varsDiv.innerHTML = '<span class="text-muted" style="font-size:10px;">No variables.</span>';
    }

    var hasActions = false;

    if (actionMap.size > 0) {
        Array.from(actionMap.keys()).sort().forEach(function (display) {
            var techName = actionMap.get(display);
            var btn = document.createElement('button');
            btn.className = 'btn btn-xs btn-warning';
            btn.style.margin = '2px';
            btn.innerText = display;
            btn.onclick = function () {
                insertPdl(techName);
            };
            actionsDiv.appendChild(btn);
        });
        hasActions = true;
    }

    if (uniqueStates.size > 0) {
        if (hasActions) {
            var divider = document.createElement('span');
            divider.style.display = "inline-block";
            divider.style.width = "2px";
            divider.style.height = "15px";
            divider.style.background = "#ddd";
            divider.style.margin = "0 8px";
            divider.style.verticalAlign = "middle";
            actionsDiv.appendChild(divider);
        }
        Array.from(uniqueStates).sort().forEach(function (st) {
            var btn = document.createElement('button');
            btn.className = 'btn btn-xs btn-primary';
            btn.style.margin = '2px';
            btn.innerText = st;
            btn.onclick = function () { insertPdl(st); };
            actionsDiv.appendChild(btn);
        });
        hasActions = true;
    }

    if (!hasActions) {
        actionsDiv.innerHTML = '<span class="text-muted" style="font-size:10px;">Nenhum elemento disponível.</span>';
    }
}
function setState(val) {
    var input = document.getElementById('pdlState');
    input.value = val;
    input.style.backgroundColor = "#d1e7dd";
    setTimeout(() => input.style.backgroundColor = "#fff", 300);
}

function insertPdl(text, suffix) {
    var input = document.getElementById('pdlFormula');
    var valToInsert = text + (suffix || "");

    if (input.selectionStart || input.selectionStart == '0') {
        var startPos = input.selectionStart;
        var endPos = input.selectionEnd;
        input.value = input.value.substring(0, startPos) + valToInsert + input.value.substring(endPos, input.value.length);

        if (suffix) {
            input.selectionStart = startPos + text.length;
            input.selectionEnd = startPos + text.length;
        } else {
            input.selectionStart = startPos + valToInsert.length;
            input.selectionEnd = startPos + valToInsert.length;
        }
    } else {
        input.value += valToInsert;
    }
    input.focus();
}

function runPdl() {
    var s = document.getElementById("pdlState").value;
    var visualFormula = document.getElementById("pdlFormula").value;

    var finalCode = visualFormula
        .replace(/⟨/g, '<')
        .replace(/⟩/g, '>')
        .replace(/≤/g, '<=')
        .replace(/≥/g, '>=')
        .replace(/≠/g, '!=')
        .replace(/¬/g, '!')
        .replace(/∧/g, '&&')
        .replace(/∨/g, '||')
        .replace(/→/g, '->')
        .replace(/⊤/g, 'true')
        .replace(/⊥/g, 'false');

    console.log("Fórmula Original:", visualFormula);
    console.log("Enviando ao Backend:", finalCode);

    var res = RTA.runPdl(s, finalCode);
    var resDiv = document.getElementById("pdlResult");

    if (res.includes("true") || res.includes("Result: true")) {
        resDiv.style.color = "green";
        resDiv.innerHTML = '<span class="glyphicon glyphicon-ok"></span> Verdadeiro';
    } else if (res.includes("false") || res.includes("Result: false")) {
        resDiv.style.color = "red";
        resDiv.innerHTML = '<span class="glyphicon glyphicon-remove"></span> Falso';
    } else if (res.includes("Result:")) {
        resDiv.style.color = "#0056b3"; // Azul Prism
        resDiv.innerHTML = '<span class="glyphicon glyphicon-stats"></span> ' + res.replace("Result: ", "Probabilidade = ");
    } else {
        resDiv.style.color = "#991b1b";
        resDiv.innerText = res;
    }
}