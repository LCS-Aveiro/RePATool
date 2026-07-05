// ============================================================
// cy/filters.js — Delta-cut filter, possibilistic view, export cut model
// ============================================================

window.isPossibilisticView = false;
window.currentDeltaCut     = 0.0;

window.applyDeltaCutBtn = function () {
    var input = document.getElementById('deltaCutInput');
    if (!input) return;
    var val = parseFloat(input.value);
    if (isNaN(val)) val = 0.0;
    window.currentDeltaCut = val;
    if (window.applyFilters) window.applyFilters();
};

window.exportCutModel = function () {
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

window.applyFilters = function () {
    if (!currentCytoscapeInstance) return;
    currentCytoscapeInstance.style().update();

    const delta = window.currentDeltaCut;
    currentCytoscapeInstance.batch(() => {
        currentCytoscapeInstance.nodes('.event-node').forEach(node => {
            const p   = node.data('p');
            const lbl = node.data('label') || "";
            if (p !== undefined && p < delta && !lbl.includes('deadlock')) {
                node.addClass('filtered-out');
            } else {
                node.removeClass('filtered-out');
            }
        });
    });

    if (typeof lastModelData !== 'undefined' && lastModelData) {
        if (typeof renderGlobalPanel === 'function') {
            renderGlobalPanel(lastModelData, 'sidePanel');
            renderGlobalPanel(lastModelData, 'sidePanel-bottom');
        }
    }
};
