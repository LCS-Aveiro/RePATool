


function updateBPValueDropdown() {
    const type         = document.getElementById('bpType').value;
    const valueSelect  = document.getElementById('bpValue');
    const intContainer = document.getElementById('bpIntContainer');
    const suggestions  = getModelSuggestions();

    valueSelect.innerHTML = '';

    if (type === 'state') {
        intContainer.style.display = 'none';
        suggestions.states.forEach(st => {
            let opt = document.createElement('option');
            opt.value   = st;
            opt.innerText = st;
            valueSelect.appendChild(opt);
        });
    } else if (type === 'variable') {
        intContainer.style.display = 'block';
        const vars = Object.keys(lastModelData?.panelData?.variables || {});
        vars.forEach(v => {
            if (!v.startsWith("__")) {
                let opt = document.createElement('option');
                opt.value   = v;
                opt.innerText = v;
                valueSelect.appendChild(opt);
            }
        });
    }
}

function runBestPath() {
    const type       = document.getElementById('bpType').value;
    const val        = document.getElementById('bpValue').value;
    const targetInt  = parseInt(document.getElementById('bpInt').value);
    const criterion  = document.getElementById('bpCriterion').value;
    const resBox     = document.getElementById('bpResultBox');
    const resSummary = document.getElementById('bpResultSummary');
    const resPath    = document.getElementById('bpResultPath');

    if (!val) {
        alert("Selecione um alvo primeiro!");
        return;
    }

    const resultRaw = RTA.findBestPath({
        targetType:  type,
        targetValue: val,
        targetInt:   targetInt,
        criterion:   criterion
    });

    resBox.style.display = 'block';

    if (resultRaw.startsWith("Caminho")) {
        const parts = resultRaw.split('\n');
        resSummary.innerHTML         = `<span class="glyphicon glyphicon-ok"></span> ${parts[0]}<br><small>${parts[1]}</small>`;
        resPath.innerText            = parts[2].replace("Caminho: ", "");
        resBox.style.borderLeftColor = "#28a745";
        resBox.style.background      = "#f4fff4";
    } else {
        resSummary.innerHTML         = `<span class="glyphicon glyphicon-exclamation-sign"></span> Falha`;
        resPath.innerText            = resultRaw;
        resBox.style.borderLeftColor = "#dc3545";
        resBox.style.background      = "#fff5f5";
    }
}
