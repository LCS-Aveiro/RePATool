
let lastModelData = null;



const stripNameCommand = s => {
    return s.replace(/^\s*name\s+[a-zA-Z0-9_]+[;\s]*/gm, '');
};




function loadExample() {
    var select = document.getElementById("examplesSelect");
    var code = select.value;
    var descDiv = document.getElementById("exampleDesc");

    var selectedName = select.options[select.selectedIndex].text;

    if (code) {
        editor.setValue(code);

        const desc = exampleDescriptions[currentLang][selectedName];

        if (desc) {
            descDiv.innerText = desc;
            descDiv.style.display = "block";
        } else {
            descDiv.style.display = "none";
        }
    } else {
        descDiv.style.display = "none";
    }
}


function loadAndRender() {
    var fullCode = editor.getValue();
    var cleanCode = stripNameCommand(fullCode);
    console.log(fullCode);
    console.log(cleanCode);

    var jsonString = RTA.loadModel(cleanCode);
    var data = JSON.parse(jsonString);

    if (data.error) {
        alert(data.error);
    } else {
        textTraceHistory = [];
        jsTextHistory = [];
        var initialStateText = RTA.getCurrentStateText();
        jsTextHistory.push({ label: "Start ->", text: initialStateText });
        renderCytoscapeGraph("cytoscapeMainContainer", data, true);

        updateAllViews(jsonString);
        console.log(data);
        renderPdlHelpers(data);
        
    }
    updateBPValueDropdown();
}


function updateBPValueDropdown() {
    const type = document.getElementById('bpType').value;
    const valueSelect = document.getElementById('bpValue');
    const intContainer = document.getElementById('bpIntContainer');
    
    const suggestions = getModelSuggestions(); 
    
    valueSelect.innerHTML = '';
    
    if (type === 'state') {
        intContainer.style.display = 'none';
        suggestions.states.forEach(st => {
            let opt = document.createElement('option');
            opt.value = st;
            opt.innerText = st;
            valueSelect.appendChild(opt);
        });
    } else if (type === 'variable') {
        intContainer.style.display = 'block';
        const data = currentCytoscapeInstance.nodes('.state-node')[0]?.cy().data(); 
        

        const vars = Object.keys(lastModelData?.panelData?.variables || {});
        
        vars.forEach(v => {
            if(!v.startsWith("__")) { 
                let opt = document.createElement('option');
                opt.value = v;
                opt.innerText = v;
                valueSelect.appendChild(opt);
            }
        });
    }
}



function updateProjectTree(highlightName) {
    const tree = document.getElementById("project-tree");
    if (!tree) return;
    tree.innerHTML = "";

    let nativeExamples = {};
    try {
        nativeExamples = JSON.parse(RTA.getExamples());
    } catch (e) { console.error(e); }

    let userModels = {};
    const saved = localStorage.getItem('rta_user_custom_models');
    if (saved) userModels = JSON.parse(saved);

    renderTreeSection(tree, "Examples", nativeExamples, "📁", false, highlightName);

    if (Object.keys(userModels).length > 0) {
        renderTreeSection(tree, "My Models", userModels, "⭐", true, highlightName);
    }
}

function renderTreeSection(container, title, models, icon, isUser, highlightName) {
    const header = document.createElement("div");
    header.className = "tree-item";
    header.style.fontWeight = "bold";
    header.style.color = "var(--gray-500)";
    header.innerHTML = `<span class="tree-icon">${icon}</span> ${title}`;
    container.appendChild(header);

    for (let name in models) {
        const item = document.createElement("div");
        item.className = "tree-item tree-indent";
        
        if (name === highlightName) {
            item.classList.add('selected');
        }

        item.innerHTML = `<span class="tree-icon">📄</span> ${name}.Re`;
        
        item.onclick = function() {
            selectTreeItem(item);
            loadModelFromTree(models[name], name);
        };
        
        container.appendChild(item);
    }
}

function loadModelFromTree(code, name) {
    editor.setValue(code);
    if (typeof exampleDescriptions !== 'undefined') {
        const descDiv = document.getElementById("exampleDesc");
        const desc = exampleDescriptions[currentLang][name];
        if (desc) {
            descDiv.innerText = desc;
            descDiv.style.display = "block";
        } else {
            descDiv.style.display = "none";
        }
    }
    showCanvasTab('editorTab');
    
    document.getElementById('sb-model').textContent = name + ".Re";
}

function selectTreeItem(element) {
    document.querySelectorAll('.tree-item').forEach(el => el.classList.remove('selected'));
    element.classList.add('selected');
}


function runBestPath() {
    const type = document.getElementById('bpType').value;
    const val = document.getElementById('bpValue').value;
    const targetInt = parseInt(document.getElementById('bpInt').value);
    const criterion = document.getElementById('bpCriterion').value;
    const resBox = document.getElementById('bpResultBox');
    const resSummary = document.getElementById('bpResultSummary');
    const resPath = document.getElementById('bpResultPath');

    if (!val) {
        alert("Selecione um alvo primeiro!");
        return;
    }

    const resultRaw = RTA.findBestPath({
        targetType: type,
        targetValue: val,
        targetInt: targetInt,
        criterion: criterion
    });

    resBox.style.display = 'block';

    if (resultRaw.startsWith("Caminho")) {
        const parts = resultRaw.split('\n');
        resSummary.innerHTML = `<span class="glyphicon glyphicon-ok"></span> ${parts[0]}<br><small>${parts[1]}</small>`;
        resPath.innerText = parts[2].replace("Caminho: ", "");
        resBox.style.borderLeftColor = "#28a745";
        resBox.style.background = "#f4fff4";
    } else {
        resSummary.innerHTML = `<span class="glyphicon glyphicon-exclamation-sign"></span> Falha`;
        resPath.innerText = resultRaw;
        resBox.style.borderLeftColor = "#dc3545"; 
        resBox.style.background = "#fff5f5";
    }
}


$(document).ready(function() {
    $('#simCollapse, #pdlBody').on('shown.bs.collapse hidden.bs.collapse', function () {
        $(this).css('height', '');
    });
});

$(document).on('shown.bs.tab', 'a[data-toggle="tab"]', function (e) {
    var target = $(e.target).attr("href");

    if (target === '#mermaidTab') {
        setTimeout(function () {
            renderMermaidView();
        }, 10);
    }
});
