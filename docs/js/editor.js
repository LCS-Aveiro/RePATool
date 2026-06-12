const USER_MODELS_KEY = 'rta_user_custom_models';

function saveUserModel() {
    const name = prompt(currentLang === 'pt' ? "Nome do modelo:" : "Model name:");
    if (!name) return;

    let userModels = JSON.parse(localStorage.getItem(USER_MODELS_KEY) || '{}');
    userModels[name] = editor.getValue();
    
    localStorage.setItem(USER_MODELS_KEY, JSON.stringify(userModels));
    updateProjectTree();
    alert(currentLang === 'pt' ? "Salvo com sucesso!" : "Saved successfully!");
}

function getSelectedUserModelName() {
    const selectedItem = document.querySelector('#project-tree .tree-item.selected');
    if (!selectedItem) return null;


    const isUserItem = selectedItem.innerText.includes('.r'); 
    
    const rawName = selectedItem.innerText.replace('📄', '').replace('.r', '').trim();
    
    let userModels = JSON.parse(localStorage.getItem(USER_MODELS_KEY) || '{}');
    return userModels[rawName] !== undefined ? rawName : null;
}


function deleteUserModel() {
    const select = document.getElementById("examplesSelect");
    const selectedOpt = select.options[select.selectedIndex];
    
    if (!selectedOpt || !selectedOpt.hasAttribute('data-user-model')) {
        alert(currentLang === 'pt' ? "Você só pode excluir seus próprios modelos." : "You can only delete your own models.");
        return;
    }

    const name = selectedOpt.getAttribute('data-raw-name');
    if (confirm((currentLang === 'pt' ? "Excluir " : "Delete ") + name + "?")) {
        let userModels = JSON.parse(localStorage.getItem(USER_MODELS_KEY) || '{}');
        delete userModels[name];
        localStorage.setItem(USER_MODELS_KEY, JSON.stringify(userModels));
        updateProjectTree();
    }
}


function overwriteUserModel() {
    const name = getSelectedUserModelName();
    
    if (!name) {
        alert(currentLang === 'pt' ? 
            "Selecione um dos SEUS modelos (em 'My Models') para sobrescrever." : 
            "Select one of YOUR models (in 'My Models') to overwrite.");
        return;
    }

    if (confirm(currentLang === 'pt' ? `Deseja sobrescrever '${name}'?` : `Overwrite '${name}'?`)) {
        const currentCode = editor.getValue();
        let userModels = JSON.parse(localStorage.getItem(USER_MODELS_KEY) || '{}');
        
        userModels[name] = currentCode;
        localStorage.setItem(USER_MODELS_KEY, JSON.stringify(userModels));
        
        if (typeof updateProjectTree === 'function') {
            updateProjectTree(name);
        }
        
        if (typeof loadAndRender === 'function') {
            loadAndRender();
        }

        if (typeof updateMergeTargets === 'function') {
            updateMergeTargets();
        }

        console.log("Modelo sobrescrito e motor reiniciado com sucesso.");
    }
}

function createNewModel() {
    const name = prompt(currentLang === 'pt' ? "Nome do novo modelo:" : "New model name:");
    if (!name) return;

    const fileName = name.replace(".r", "");
    const template = `name ${fileName}\ninit s0\ns0 ---> s1: a`;

    let userModels = JSON.parse(localStorage.getItem(USER_MODELS_KEY) || '{}');
    userModels[fileName] = template;
    localStorage.setItem(USER_MODELS_KEY, JSON.stringify(userModels));

    updateProjectTree(fileName); 
    loadModelFromTree(template, fileName);
    
    document.getElementById('project-context-menu').style.display = 'none';
}


document.addEventListener("DOMContentLoaded", function() {
    const leftPanel = document.getElementById('left-panel');
    const rightPanel = document.getElementById('right-panel');
    const bottomPanel = document.getElementById('bottom-panel');
    const hResizes = document.querySelectorAll('.h-resize');
    const vResizes = document.querySelectorAll('.v-resize');

    let activeResizer = null;

    if (hResizes.length >= 2) {
        hResizes[0].addEventListener('mousedown', function(e) {
            activeResizer = 'left';
            document.body.style.cursor = 'col-resize';
            e.preventDefault();
        });
        hResizes[1].addEventListener('mousedown', function(e) {
            activeResizer = 'right';
            document.body.style.cursor = 'col-resize';
            e.preventDefault();
        });
    }

    if (vResizes.length >= 1) {
        vResizes[0].addEventListener('mousedown', function(e) {
            activeResizer = 'bottom';
            document.body.style.cursor = 'row-resize';
            e.preventDefault();
        });
    }

    document.addEventListener('mousemove', function(e) {
        if (!activeResizer) return;
        
        if (activeResizer === 'left') {
            leftPanel.style.width = e.clientX + 'px';
        } 
        else if (activeResizer === 'right') {
            rightPanel.style.width = (window.innerWidth - e.clientX) + 'px';
        } 
        else if (activeResizer === 'bottom') {
            bottomPanel.style.height = (window.innerHeight - e.clientY - 22) + 'px';
        }
    });

    document.addEventListener('mouseup', function() {
        if (activeResizer) {
            activeResizer = null;
            document.body.style.cursor = 'default';
        }
    });
});