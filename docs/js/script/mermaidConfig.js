let mZoom = 1.0;
let mPan = { x: 20, y: 80 };
let isDraggingMermaid = false;
let startPan = { x: 0, y: 0 };

function initMermaidInteractivity() {
    const tab = document.getElementById('mermaidTab');
    const container = document.getElementById('mermaidContainer');

    tab.onwheel = function(e) {
        e.preventDefault();
        const delta = e.deltaY > 0 ? 0.9 : 1.1;
        adjustMermaidZoom(delta);
    };

    tab.onmousedown = function(e) {
        if (e.button !== 0) return; 
        isDraggingMermaid = true;
        startPan = { x: e.clientX - mPan.x, y: e.clientY - mPan.y };
    };

    window.addEventListener('mousemove', function(e) {
        if (!isDraggingMermaid) return;
        mPan.x = e.clientX - startPan.x;
        mPan.y = e.clientY - startPan.y;
        applyMermaidTransform();
    });

    window.addEventListener('mouseup', function() {
        isDraggingMermaid = false;
    });
}

function adjustMermaidZoom(factor) {
    mZoom *= factor;
    if (mZoom < 0.1) mZoom = 0.1;
    if (mZoom > 10) mZoom = 5;
    applyMermaidTransform();
}

function resetMermaidZoom() {
    mZoom = 1.0;
    mPan = { x: 20, y: 80 };
    applyMermaidTransform();
}

function applyMermaidTransform() {
    const container = document.getElementById('mermaidContainer');
    if (container) {
        container.style.transform = `translate(${mPan.x}px, ${mPan.y}px) scale(${mZoom})`;
    }
}

const originalRenderMermaidView = renderMermaidView;
renderMermaidView = function() {
    originalRenderMermaidView();
    setTimeout(applyMermaidTransform, 50);
};

document.addEventListener('DOMContentLoaded', initMermaidInteractivity);