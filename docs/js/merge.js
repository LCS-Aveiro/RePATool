
function doMerge(type) {
    const codeA = stripNameCommand(editor.getValue());
    const targetSelect = document.getElementById("mergeTarget");
    const codeB = stripNameCommand(targetSelect.value);
    const agg = document.getElementById("mergeAgg").value;

    if (!codeB) return;


    let result = RTA.mergeModels(codeA, codeB, agg, type);

    if (result.startsWith("Error")) {
        alert(result);
    } else {
        editor.setValue(result);
        
        loadAndRender(); 
        
        alert("Modelos unidos com sucesso no editor!");
    }
}

function updateMergeTargets() {
    const select = document.getElementById("mergeTarget");
    if (!select) return;

    select.innerHTML = `<option value="">${currentLang === 'pt' ? "Combinar com..." : "Combine with..."}</option>`;

    try {
        const nativeExamples = JSON.parse(RTA.getExamples());
        const groupNative = document.createElement("optgroup");
        groupNative.label = currentLang === 'pt' ? "Exemplos Padrão" : "Built-in Examples";
        
        for (let name in nativeExamples) {
            let opt = document.createElement("option");
            opt.value = nativeExamples[name];
            opt.innerHTML = name;
            groupNative.appendChild(opt);
        }
        select.appendChild(groupNative);
    } catch (e) {
        console.error("Erro ao carregar exemplos nativos para merge", e);
    }

    try {
        const saved = localStorage.getItem('rta_user_custom_models');
        if (saved) {
            const userModels = JSON.parse(saved);
            if (Object.keys(userModels).length > 0) {
                const groupUser = document.createElement("optgroup");
                groupUser.label = currentLang === 'pt' ? "Meus Modelos" : "My Saved Models";
                
                for (let name in userModels) {
                    let opt = document.createElement("option");
                    opt.value = userModels[name];
                    opt.innerHTML = "⭐ " + name;
                    groupUser.appendChild(opt);
                }
                select.appendChild(groupUser);
            }
        }
    } catch (e) {
        console.error("Erro ao carregar modelos do usuário para merge", e);
    }
}

document.addEventListener("DOMContentLoaded", function() {
    setTimeout(updateMergeTargets, 500);
});