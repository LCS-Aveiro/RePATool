// ============================================================
// cy/animation.js — Graph animation: value iteration + domino trace + counterexample
// ============================================================

window.runGraphAnimationTrace = function (traceData, startNodeId) {
    if (!currentCytoscapeInstance || !traceData) return;

    var cy = currentCytoscapeInstance;
    cy.elements().removeClass('anim-visiting anim-visited anim-target anim-vi-node vi-updated vi-edge-flow');

    let startNode = cy.getElementById(startNodeId);
    if (!startNode || startNode.length === 0) startNode = cy.nodes('.current-state');
    if (startNode.length === 0) return;

    // ── A) Value Iteration ────────────────────────────────────
    if (traceData.valueIterationTrace && traceData.valueIterationTrace.length > 0) {
        _animateValueIteration(cy, traceData.valueIterationTrace, startNode);
        return;
    }

    // ── B) Domino trace (PDL / linear) ───────────────────────
    _animateDominoTrace(cy, traceData, startNode);
};

// ── Value iteration (backwards propagation) ──────────────────

function _animateValueIteration(cy, viTrace, startNode) {
    const frameRate = 700; // ms per iteration

    // Create floating overlay
    let overlay = document.getElementById('vi-overlay');
    if (!overlay) {
        overlay = document.createElement('div');
        overlay.id = 'vi-overlay';
        Object.assign(overlay.style, {
            position: 'fixed', top: '120px', left: '50%', transform: 'translateX(-50%)',
            backgroundColor: '#1E293B', color: '#fff', padding: '10px 24px',
            borderRadius: '30px', fontFamily: 'sans-serif', fontSize: '16px',
            fontWeight: 'bold', zIndex: '2147483647', pointerEvents: 'none',
            boxShadow: '0px 10px 25px rgba(0,0,0,0.6)'
        });
        document.body.appendChild(overlay);
    }

    cy.animate({}, {
        duration: 500,
        complete: function () {
            // Initialise all state nodes with V=0
            cy.nodes('.state-node').addClass('anim-vi-node').forEach(n => {
                n.data('vi_val', 0);
                let baseName = n.data('label') ? n.data('label').split('\n')[0] : n.id();
                n.data('vi_label', baseName + '\n[ V=0.000 ]');
            });

            let step         = 0;
            let prevSnapshot = {};

            let interval = setInterval(() => {
                if (step >= viTrace.length) {
                    clearInterval(interval);
                    overlay.innerText = "✨ Convergência Alcançada!";
                    overlay.style.backgroundColor = '#16A34A';
                    if (startNode && startNode.length > 0) startNode.addClass('anim-target');

                    setTimeout(() => {
                        cy.nodes().removeClass('anim-vi-node anim-target vi-updated');
                        cy.edges().removeClass('vi-edge-flow');
                        cy.nodes('.state-node').forEach(n => {
                            n.removeData('vi_label');
                            n.removeData('vi_val');
                        });
                        if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
                    }, 6000);
                    return;
                }

                let snapshot = viTrace[step];
                overlay.innerText = `🔄 Value Iteration: T${step}`;

                cy.batch(() => {
                    cy.nodes('.state-node').forEach(n => {
                        let baseName = n.data('label') ? n.data('label').split('\n')[0] : n.id();
                        let val      = snapshot[n.id()];
                        if (val === undefined) val = snapshot[baseName];
                        if (val === undefined) return;

                        let prevVal = prevSnapshot[baseName] || 0;
                        n.data('vi_val',   val);
                        n.data('vi_label', baseName + '\n[ V=' + val.toFixed(3) + ' ]');

                        if (Math.abs(val - prevVal) > 0.0001) {
                            n.addClass('vi-updated');
                            setTimeout(() => n.removeClass('vi-updated'), frameRate - 100);

                            // Light up contributing paths
                            n.outgoers('edge').forEach(e1 => {
                                let eventNode = e1.target();
                                if (!eventNode.hasClass('event-node')) return;

                                let e2s         = eventNode.outgoers('edge');
                                let contributes = false;
                                e2s.forEach(e2 => {
                                    let tgt      = e2.target();
                                    let tBase    = tgt.data('label') ? tgt.data('label').split('\n')[0] : tgt.id();
                                    let tVal     = snapshot[tgt.id()] !== undefined ? snapshot[tgt.id()] : snapshot[tBase];
                                    if (tVal > 0) contributes = true;
                                });

                                if (contributes) {
                                    e1.addClass('vi-edge-flow');
                                    eventNode.addClass('vi-updated');
                                    e2s.forEach(e2 => e2.addClass('vi-edge-flow'));
                                    setTimeout(() => {
                                        e1.removeClass('vi-edge-flow');
                                        eventNode.removeClass('vi-updated');
                                        e2s.forEach(e2 => e2.removeClass('vi-edge-flow'));
                                    }, frameRate - 100);
                                }
                            });
                        }
                    });
                });

                prevSnapshot = {};
                for (let k in snapshot) prevSnapshot[k] = snapshot[k];
                step++;
            }, frameRate);
        }
    });
}

// ── Domino trace ─────────────────────────────────────────────

function _animateDominoTrace(cy, traceData, startNode) {
    const visitedNodes      = new Set(traceData.visitedNodes);
    const visitedEventNodes = new Set(traceData.visitedEdges);
    const targetNodes       = new Set(traceData.targetNodes);
    const targetEventNodes  = new Set(traceData.targetEdges);
    const stepDelay         = 300;

    cy.animate(
        { center: { eles: startNode }, zoom: Math.min(cy.zoom(), 1.5) },
        {
            duration: 500,
            complete: function () {
                let queue              = [startNode];
                let localVisitedStates = new Set([startNode.id()]);
                let localVisitedEvents = new Set();

                function processLevel() {
                    if (queue.length === 0) {
                        cy.nodes().forEach(n => {
                            if (targetNodes.has(n.id()))      n.addClass('anim-target');
                            if (targetEventNodes.has(n.id())) n.addClass('anim-target');
                        });
                        setTimeout(() => cy.elements().removeClass('anim-visiting anim-visited anim-target'), 5000);
                        return;
                    }

                    let nextQueue = [];
                    queue.forEach(stateNode => {
                        if (targetNodes.has(stateNode.id())) {
                            stateNode.addClass('anim-target');
                        } else {
                            stateNode.addClass('anim-visiting');
                            setTimeout(() => stateNode.removeClass('anim-visiting').addClass('anim-visited'), stepDelay);
                        }

                        stateNode.outgoers('edge').forEach(edgeToEvent => {
                            let eventNode = edgeToEvent.target();
                            if (!visitedEventNodes.has(eventNode.id())) return;
                            if (localVisitedEvents.has(eventNode.id())) return;
                            localVisitedEvents.add(eventNode.id());

                            let isTarget    = targetEventNodes.has(eventNode.id());
                            let edgeFromEvent = eventNode.outgoers('edge');

                            setTimeout(() => {
                                edgeToEvent.addClass(isTarget ? 'anim-target' : 'anim-visiting');
                                if (!isTarget) setTimeout(() => edgeToEvent.removeClass('anim-visiting').addClass('anim-visited'), stepDelay);
                            }, stepDelay / 3);

                            setTimeout(() => {
                                eventNode.addClass(isTarget ? 'anim-target' : 'anim-visiting');
                                if (!isTarget) setTimeout(() => eventNode.removeClass('anim-visiting').addClass('anim-visited'), stepDelay);
                            }, (stepDelay / 3) * 2);

                            setTimeout(() => {
                                edgeFromEvent.forEach(e => {
                                    e.addClass(isTarget ? 'anim-target' : 'anim-visiting');
                                    if (!isTarget) setTimeout(() => e.removeClass('anim-visiting').addClass('anim-visited'), stepDelay);
                                });
                            }, stepDelay);

                            edgeFromEvent.forEach(e => {
                                let tgt = e.target();
                                if (!localVisitedStates.has(tgt.id()) && visitedNodes.has(tgt.id())) {
                                    localVisitedStates.add(tgt.id());
                                    nextQueue.push(tgt);
                                }
                            });
                        });
                    });

                    queue = nextQueue;
                    setTimeout(processLevel, stepDelay * 1.5);
                }

                processLevel();
            }
        }
    );
}

// ── Counterexample playback ───────────────────────────────────

window.playCounterexample = function () {
    if (!window.lastCounterexample || !currentCytoscapeInstance) return;

    var cy   = currentCytoscapeInstance;
    var path = window.lastCounterexample;

    cy.elements().removeClass('anim-visiting anim-visited anim-target cx-node cx-path');

    cy.style()
        .selector('.cx-path').style({ 'line-color': '#DC2626', 'target-arrow-color': '#DC2626', 'width': 5, 'line-style': 'solid', 'transition-property': 'line-color, target-arrow-color, width', 'transition-duration': '0.3s' })
        .selector('.cx-node').style({ 'background-color': '#FECACA', 'border-color': '#B91C1C', 'border-width': 4, 'transition-property': 'background-color, border-color', 'transition-duration': '0.3s' })
        .update();

    let step = 0;
    function nextStep() {
        if (step >= path.length) return;

        let eventNode = cy.getElementById(path[step]);
        if (eventNode.length > 0) {
            eventNode.addClass('cx-node');
            eventNode.incomers('edge').addClass('cx-path').sources().addClass('cx-node');
            eventNode.outgoers('edge').addClass('cx-path').targets().addClass('cx-node');
            cy.animate({ center: { eles: eventNode }, zoom: 1.2 }, { duration: 400 });
        }

        step++;
        setTimeout(nextStep, 900);
    }
    nextStep();
};
