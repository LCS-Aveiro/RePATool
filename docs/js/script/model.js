let lastModelData = null;

const stripNameCommand = s =>
    s.replace(/^\s*name\s+[a-zA-Z0-9_]+[;\s]*/gm, '')
     .replace(/^\s*(possibilisticView|possibilistic)\b[;\s]*/gmi, '')
     .replace(/^\s*hideTau\b[;\s]*/gmi, '');


function loadExample() {
    var select   = document.getElementById("examplesSelect");
    var code     = select.value;
    var descDiv  = document.getElementById("exampleDesc");
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
    var fullCode  = editor.getValue();
    
    var isPoss = /^\s*(possibilisticView|possibilistic)\b/mi.test(fullCode);
    var isHideTau = /^\s*hideTau\b/mi.test(fullCode);

    var possCheckbox = document.getElementById('possibilisticToggle');
    if (possCheckbox) {
        possCheckbox.checked = isPoss;
        window.isPossibilisticView = isPoss;
    }

    var hideCheckbox = document.getElementById('hideTauToggle');
    if (hideCheckbox) {
        hideCheckbox.checked = isHideTau;
    }

    var cleanCode = stripNameCommand(fullCode);
    console.log(fullCode);
    console.log(cleanCode);

    var jsonString = RTA.loadModel(cleanCode);
    var data       = JSON.parse(jsonString);

    if (data.error) {
        alert(data.error);
    } else {
        textTraceHistory = [];
        jsTextHistory    = [];
        var initialStateText = RTA.getCurrentStateText();
        jsTextHistory.push({ label: "Start ->", text: initialStateText });

        renderCytoscapeGraph("cytoscapeMainContainer", data, true);
        updateAllViews(jsonString);
        console.log(data);
        renderPdlHelpers(data);
    }

    updateBPValueDropdown();
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
