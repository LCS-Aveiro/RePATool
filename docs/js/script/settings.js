// ============================================================
// settings.js — Engine preferences + panel resize logic
// ============================================================

window.appSettings = {
    maxStates: 1000,
    maxIter: 1000,
    epsilon: 0.00001
};

function loadAppSettings() {
    var saved = localStorage.getItem('rta_engine_settings');
    if (saved) {
        try {
            var parsed = JSON.parse(saved);
            Object.assign(window.appSettings, parsed);
        } catch(e) {}
    }
    var mStates = document.getElementById('cfgMaxStates');
    if (mStates) mStates.value = window.appSettings.maxStates;
    var mIter = document.getElementById('cfgMaxIter');
    if (mIter) mIter.value = window.appSettings.maxIter;
    var mEps = document.getElementById('cfgEpsilon');
    if (mEps) mEps.value = window.appSettings.epsilon;
}

function saveAppSettings() {
    window.appSettings.maxStates = parseInt(document.getElementById('cfgMaxStates').value) || 1000;
    window.appSettings.maxIter   = parseInt(document.getElementById('cfgMaxIter').value)   || 1000;
    window.appSettings.epsilon   = parseFloat(document.getElementById('cfgEpsilon').value) || 0.00001;

    localStorage.setItem('rta_engine_settings', JSON.stringify(window.appSettings));
    $('#settingsModal').modal('hide');
    console.log("Configurações salvas:", window.appSettings);
}

// ── Panel resize (left / right / bottom) ────────────────────
document.addEventListener("DOMContentLoaded", function () {
    var leftPanel   = document.getElementById('left-panel');
    var rightPanel  = document.getElementById('right-panel');
    var bottomPanel = document.getElementById('bottom-panel');
    var hResizes    = document.querySelectorAll('.h-resize');
    var vResizes    = document.querySelectorAll('.v-resize');

    var activeResizer = null;

    if (hResizes.length >= 2) {
        hResizes[0].addEventListener('mousedown', function (e) {
            activeResizer = 'left';
            document.body.style.cursor = 'col-resize';
            e.preventDefault();
        });
        hResizes[1].addEventListener('mousedown', function (e) {
            activeResizer = 'right';
            document.body.style.cursor = 'col-resize';
            e.preventDefault();
        });
    }

    if (vResizes.length >= 1) {
        vResizes[0].addEventListener('mousedown', function (e) {
            activeResizer = 'bottom';
            document.body.style.cursor = 'row-resize';
            e.preventDefault();
        });
    }

    document.addEventListener('mousemove', function (e) {
        if (!activeResizer) return;
        if (activeResizer === 'left')   leftPanel.style.width   = e.clientX + 'px';
        else if (activeResizer === 'right')  rightPanel.style.width  = (window.innerWidth - e.clientX) + 'px';
        else if (activeResizer === 'bottom') bottomPanel.style.height = (window.innerHeight - e.clientY - 22) + 'px';
    });

    document.addEventListener('mouseup', function () {
        if (activeResizer) {
            activeResizer = null;
            document.body.style.cursor = 'default';
        }
    });
});
