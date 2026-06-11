document.addEventListener('click', function(e) {
    var menu = document.getElementById('cy-context-menu');
    if (menu) menu.style.display = 'none';
});


function findTransitionLine(from, to, tId, lbl) {
    var doc = editor.getDoc();
    var lineCount = doc.lineCount();

    var fromEsc = from.replace(/\./g, '\\.');
    var toEsc = to.replace(/\./g, '\\.');
    var tIdEsc = tId.replace(/\./g, '\\.');
    var lblEsc = lbl.replace(/\./g, '\\.');

    var regex = new RegExp(`^\\s*${fromEsc}\\s*(?:--->|-${tIdEsc}->)\\s*${toEsc}\\s*:\\s*${lblEsc}`);

    for (var i = 0; i < lineCount; i++) {
        if (regex.test(doc.getLine(i))) return i;
    }
    return -1;
}

function setupContextMenu(cy) {
    var container = document.getElementById('cytoscapeMainContainer');
    container.oncontextmenu = function(e) {
        e.preventDefault();
        return false;
    };

    cy.on('cxttap', function(event) {
        var menu = document.getElementById('cy-context-menu');
        if (menu) {
            menu.oncontextmenu = function(e) {
                e.preventDefault(); 
                e.stopPropagation(); 
                return false;
            };
        }
        var list = menu.querySelector('ul');
        list.innerHTML = ''; 

        var target = event.target;
        var isNode = target.isNode && target.isNode();
        var isEdge = target.isEdge && target.isEdge();
        var isBackground = target === cy;

        var nativeEvent = event.originalEvent; 
        
        menu.style.left = nativeEvent.clientX + 'px';
        menu.style.top = nativeEvent.clientY + 'px';
        menu.style.display = 'block';

        if (isBackground) {
            addMenuItem(list, '➕ Create Variable (int)', createVariable);
            addMenuItem(list, '➜ New Transition (-->)', function() { createTransition(); }); 
            addMenuItem(list, '🚩 New Initial State (init)', createInitState);
        } 
        else if (isNode) {
            var cls = target.classes() || [];
            var data = target.data();


            if (cls.includes('event-node')) {
                var parts = data.id.split('_'); 
                if (parts.length >= 5) {
                    var from = parts[1];
                    var to = parts[2];
                    var tId = parts[3];
                    var lbl = parts[4];
                    
                    addMenuItem(list, '⚡ Create Activation (->>)', () => createInteraction(lbl, '->>'));
                    addMenuItem(list, '❌ Create Negation (--!)', () => createInteraction(lbl, '--!'));
                    
                    addMenuItem(list, '❓ Add Condition (if)', () => addConditionToEdge(from, to, tId, lbl));
                    addMenuItem(list, '📝 Add Update (if ... then)', () => addUpdateToEdge(from, to, tId, lbl));
                }
            }
        }
    });
}

function addMenuItem(list, text, onClick) {
    var li = document.createElement('li');
    var a = document.createElement('a');
    a.href = "#";
    a.innerText = text;
    a.onclick = function(e) {
        e.preventDefault();
        document.getElementById('cy-context-menu').style.display = 'none';
        onClick();
    };
    li.appendChild(a);
    list.appendChild(li);
}

function addUpdateToEdge(from, to, tId, lbl) {
    openSmartModal('Add Update (Effect)', [
        { label: 'Execute IF (Condition):', placeholder: 'e.g.: true', required: true, value: 'true' },
        { label: 'THEN do (Updates):', placeholder: "e.g.: counter' := 0", required: true }
    ], function(cond, updateCode) {
        var lineIndex = findTransitionLine(from, to, tId, lbl);
        if (lineIndex !== -1) {
            var doc = editor.getDoc();
            var lineText = doc.getLine(lineIndex);
            var textToAdd = ` if (${cond}) then { ${updateCode} }`;
            doc.replaceRange(lineText + textToAdd, {line: lineIndex, ch: 0}, {line: lineIndex, ch: lineText.length});
            loadAndRender();
        } else {
            alert("Could not find the transition line.");
        }
    });
}

function appendToCode(text) {
    var doc = editor.getDoc();
    var lastLine = doc.lineCount();
    doc.replaceRange("\n" + text, {line: lastLine, ch: 0});
    loadAndRender();
}

function prependToCode(text) {
    var doc = editor.getDoc();
    doc.replaceRange(text + "\n", {line: 0, ch: 0});
    loadAndRender();
}

function getModelSuggestions() {
    var states = new Set();
    var actions = new Set();
    
    if (currentCytoscapeInstance) {
        currentCytoscapeInstance.nodes().forEach(function(ele) {
            var data = ele.data();
            var cls = ele.classes() || [];
            
            if (cls.includes('state-node') && data.label) {
                states.add(data.label);
            }
            if (cls.includes('event-node') && data.label) {
                var cleanName = data.label.split('\n')[0].split('(')[0].trim();
                actions.add(cleanName);
            }
        });
    }
    return {
        states: Array.from(states).sort(),
        actions: Array.from(actions).sort()
    };
}

function openSmartModal(title, fields, callback) {
    var modalTitle = document.getElementById('quickModalTitle');
    var container = document.getElementById('quickModalInputs');
    var saveBtn = document.getElementById('quickModalSaveBtn');
    
    modalTitle.innerText = title;
    container.innerHTML = '';

    fields.forEach(function(field, index) {
        var group = document.createElement('div');
        group.className = 'form-group';
        
        if (field.label) {
            var lbl = document.createElement('label');
            lbl.style.fontSize = '12px';
            lbl.innerText = field.label;
            group.appendChild(lbl);
        }

        var input = document.createElement('input');
        input.className = 'form-control input-sm';
        input.id = 'modal_input_' + index;
        input.type = field.type || 'text';
        if (field.value) input.value = field.value;
        if (field.placeholder) input.placeholder = field.placeholder;

        if (field.suggestions && field.suggestions.length > 0) {
            var listId = 'list_' + Math.random().toString(36).substr(2, 9);
            input.setAttribute('list', listId);
            
            var datalist = document.createElement('datalist');
            datalist.id = listId;
            
            field.suggestions.forEach(function(opt) {
                var option = document.createElement('option');
                option.value = opt;
                datalist.appendChild(option);
            });
            group.appendChild(datalist);
        }

        group.appendChild(input);
        container.appendChild(group);
    });

    var newBtn = saveBtn.cloneNode(true);
    saveBtn.parentNode.replaceChild(newBtn, saveBtn);
    
    newBtn.addEventListener('click', function() {
        var results = [];
        var isValid = true;
        
        fields.forEach(function(f, i) {
            var val = document.getElementById('modal_input_' + i).value;
            if (!val && f.required) isValid = false;
            results.push(val);
        });

        if (isValid) {
            callback.apply(null, results); 
            $('#quickModal').modal('hide');
        } else {
            alert("Please fill in all required fields.");
        }
    });

    $('#quickModal').one('shown.bs.modal', function () {
        document.getElementById('modal_input_0').focus();
    });

    $('#quickModal').appendTo("body").modal('show'); 
}

function createVariable() {
    openSmartModal('New Variable', [
        { label: 'Variable Name:', placeholder: 'e.g.: counter', required: true },
        { label: 'Initial Value:', type: 'number', value: '0', required: true }
    ], function(name, val) {
        prependToCode(`int ${name} = ${val}`);
    });
}



function createInitState() {
    openSmartModal('Initial State', [
        { label: 'State Name:', value: 's0', required: true }
    ], function(name) {
        appendToCode(`init ${name}`);
    });
}

function createTransition() {
    var data = getModelSuggestions();
    
    openSmartModal('New Transition', [
        { 
            label: 'From (Source):', 
            required: true,
            suggestions: data.states 
        },
        { 
            label: 'To (Target):', 
            required: true,
            suggestions: data.states
        },
        { 
            label: 'Act:', 
            placeholder: 'e.g.: t1 (leave empty for default)', 
            required: false 
        },
        { 
            label: 'Label:', 
            placeholder: 'e.g.: insertCoin', 
            required: true,
            suggestions: data.actions 
        },
        { 
            label: 'Weight (P):', 
            type: 'number', 
            value: '1.0', 
            required: true 
        },
        { 
            label: 'Aggregation:', 
            value: 'arith', 
            required: false,
            suggestions: ['arith', 'prod', 'max', 'min', 'geom']
        }
    ], function(source, target, tId, label, weight, agg) {
        let weightPart = `(${weight})`;
        let aggPart = (agg && agg !== 'arith') ? ` ${agg}` : "";
        
        if (!tId || tId.trim() === "") {
            appendToCode(`${source} ---> ${target}: ${label} ${weightPart}${aggPart}`);
        } else {
            appendToCode(`${source} -${tId}-> ${target}: ${label} ${weightPart}${aggPart}`);
        }
    });
}



function createInteraction(sourceLabel, symbol) {
    var data = getModelSuggestions();
    var typeText = symbol === '->>' ? 'Activate' : 'Deactivate (Negate)';
    
    openSmartModal(`${typeText} action...`, [
        { 
            label: `'${sourceLabel}' will ${typeText.toLowerCase()}:`, 
            placeholder: 'Select target action...', 
            required: true,
            suggestions: data.actions 
        },
        { 
            label: 'Rule Weight (Δ):', 
            type: 'number', 
            value: '0.1', 
            required: true 
        },
        { 
            label: 'Aggregation:', 
            value: 'arith', 
            required: false,
            suggestions: ['arith', 'prod', 'max', 'min', 'geom']
        }
    ], function(targetLabel, weight, agg) {
        let weightPart = `(${weight})`;
        let aggPart = (agg && agg !== 'arith') ? ` ${agg}` : "";
        appendToCode(`${sourceLabel} ${symbol} ${targetLabel} ${weightPart}${aggPart}`);
    });
}

function addConditionToEdge(from, to, tId, lbl) {
    openSmartModal('Add Condition (Guard)', [
        { label: 'Expression:', placeholder: 'e.g.: counter > 0', required: true }
    ], function(cond) {
        var lineIndex = findTransitionLine(from, to, tId, lbl);
        if (lineIndex !== -1) {
            var doc = editor.getDoc();
            var lineText = doc.getLine(lineIndex);
            var newLine = lineText.includes(" if ") ? lineText + ` AND (${cond})` : lineText + ` if (${cond})`;
            doc.replaceRange(newLine, {line: lineIndex, ch: 0}, {line: lineIndex, ch: lineText.length});
            loadAndRender();
        } else {
            alert("Could not find the transition line in the editor.");
        }
    });
}
