

function getCytoscapeStyles() {
    return [
        // ── Base ──────────────────────────────────────────────
        {
            selector: 'node',
            style: {
                'label': 'data(label)',
                'text-valign': 'center',
                'color': '#000000',
                'font-family': 'sans-serif',
                'font-weight': 'bold',
                'text-outline-width': 2,
                'text-outline-color': '#FFFFFF'
            }
        },
        {
            selector: 'edge',
            style: {
                'width': 2,
                'curve-style': 'unbundled-bezier',
                'line-color': '#9CA3AF',
                'target-arrow-shape': 'none',
                'label': 'data(label)',
                'color': '#000000',
                'text-outline-color': '#FFFFFF',
                'text-outline-width': 2,
                'font-size': '14px'
            }
        },
        {
            selector: 'edge[edgeDistances]',
            style: {
                'curve-style': 'unbundled-bezier',
                'control-point-distances': 'data(edgeDistances)',
                'control-point-weights': 'data(edgeWeights)',
                'edge-distances': 'node-position'
            }
        },
        { selector: 'edge.from-action-node', style: { 'target-arrow-shape': 'triangle' } },
        { selector: 'edge.simple-conn',        style: { 'text-opacity': 0 } },
        { selector: 'edge.simple-conn.hovered', style: { 'text-opacity': 1, 'z-index': 9999 } },

        // ── State nodes ───────────────────────────────────────
        {
            selector: 'node.state-node',
            style: {
                'background-color': '#BFDBFE',
                'shape': 'ellipse',
                'width': 50,
                'height': 50,
                'border-width': 3,
                'border-color': '#3B82F6',
                'text-wrap': 'wrap',
                'text-valign': 'center'
            }
        },
        {
            selector: 'node.has-invariant',
            style: {
                'label': function (ele) {
                    return ele.data('label') + '\n[' + ele.data('invariant') + ']';
                }
            }
        },
        { selector: '.current-state', style: { 'background-color': '#86EFAC', 'border-color': '#166534', 'border-width': 4 } },

        {
            selector: 'node.event-node',
            style: {
                'label': function (ele) {
                    const data = ele.data();
                    const p    = data.p;
                    let baseName = data.action_name || (data.label ? data.label.split('\n')[0] : "");

                    if (window.isPossibilisticView) return baseName;

                    if (p !== undefined) {
                        const isRule = ele.hasClass('rule-node');
                        if (isRule) return `${baseName}\n(${p.toFixed(3)})`;
                        const nm = (data.transID == data.lbl ? "" : data.transID);
                        return `${nm}\n(${p.toFixed(3)})`;
                    }
                    return data.label || "";
                },
                'background-color': '#ffffff',
                'shape': 'rectangle',
                'width': 'label',
                'height': 'label',
                'padding': 10,
                'border-width': 2,
                'border-color': '#9CA3AF',
                'text-wrap': 'wrap',
                'text-valign': 'center',
                'text-halign': 'center'
            }
        },
        {
            selector: 'node.event-node.hovered',
            style: {
                'label': function (ele) {
                    let hlbl = ele.data('hover_label') || "";
                    if (window.isPossibilisticView) {
                        return hlbl.replace(/\s*\([PΔ]=[\d.]+\)/, '');
                    }
                    return hlbl;
                },
                'z-index': 9999
            }
        },

        // ── Rule nodes ────────────────────────────────────────
        { selector: 'node.rule-node', style: { 'background-color': '#E5E7EB' } },
        { selector: '.enable-rule',   style: { 'line-color': '#2563EB', 'target-arrow-color': '#2563EB' } },
        { selector: '.disable-rule',  style: { 'line-color': '#DC2626', 'target-arrow-color': '#DC2626' } },
        { selector: 'edge.enable-rule.to-target',  style: { 'target-arrow-shape': 'triangle-tee' } },
        { selector: 'edge.disable-rule.to-target', style: { 'target-label': 'X', 'target-text-offset': 5, 'color': '#DC2626', 'font-size': '12px' } },

        // ── State flags ───────────────────────────────────────
        { selector: '.disabled',      style: { 'line-style': 'dashed', 'background-opacity': 0.6, 'border-style': 'dashed', 'opacity': 0.7 } },

        // ── Deadlock ──────────────────────────────────────────
        { selector: '.deadlock-node', style: { 'background-color': '#FECACA', 'border-color': '#EF4444', 'color': '#7F1D1D' } },
        { selector: '.deadlock-edge', style: { 'line-color': '#EF4444', 'target-arrow-color': '#EF4444', 'line-style': 'dashed' } },
        { selector: '.deadlock-edge.enabled', style: { 'line-style': 'solid', 'width': 3 } },

        // ── Flash / filter ────────────────────────────────────
        { selector: '.transition-flash', style: { 'background-color': '#F97316', 'line-color': '#F97316', 'target-arrow-color': '#F97316' } },
        { selector: '.filtered-out',     style: { 'display': 'none' } },

        // ── Compound parent ───────────────────────────────────
        {
            selector: '.compound-parent',
            style: {
                'background-color': '#F3F4F6',
                'background-opacity': 1,
                'border-color': '#D1D5DB',
                'border-width': 2,
                'content': 'data(label)',
                'text-valign': 'top',
                'text-halign': 'center',
                'color': '#374151',
                'font-weight': 'bold',
                'font-size': '16px'
            }
        },

        // ── Animation: domino trace ───────────────────────────
        { selector: '.anim-visiting', style: { 'background-color': '#FDE047', 'line-color': '#FDE047', 'target-arrow-color': '#FDE047', 'border-color': '#EAB308', 'transition-property': 'background-color, line-color, target-arrow-color', 'transition-duration': '0.2s' } },
        { selector: '.anim-visited',  style: { 'background-color': '#FEF08A', 'line-color': '#FEF08A', 'target-arrow-color': '#FEF08A', 'transition-property': 'background-color, line-color', 'transition-duration': '0.5s' } },
        { selector: '.anim-target',   style: { 'background-color': '#4ADE80', 'line-color': '#4ADE80', 'target-arrow-color': '#4ADE80', 'border-color': '#166534', 'border-width': 4, 'transition-property': 'background-color, line-color, border-color', 'transition-duration': '0.3s' } },

        // ── Animation: value iteration ────────────────────────
        { selector: '.anim-vi-node', style: { 'background-color': 'mapData(vi_val, 0, 1, #FFFFFF, #22C55E)', 'transition-property': 'background-color', 'transition-duration': '0.3s', 'label': 'data(vi_label)' } },
        { selector: '.vi-updated',   style: { 'border-color': '#F97316', 'border-width': 5, 'transition-property': 'border-color, border-width', 'transition-duration': '0.1s' } },
        { selector: '.vi-edge-flow', style: { 'line-color': '#F97316', 'target-arrow-color': '#F97316', 'width': 4, 'transition-property': 'line-color, target-arrow-color, width', 'transition-duration': '0.1s' } },
    ];
}
