// ============================================================
// layout.js — Cytoscape layout persistence (localStorage + file)
// ============================================================

function autoSaveLayoutToLocalStorage(cy, graphId) {
    if (!cy || !graphId || typeof localStorage === 'undefined') return;
    console.log("save");

    const layoutData = { nodes: {}, edges: {} };

    cy.nodes().forEach(node => {
        if (node.children().length === 0) {
            layoutData.nodes[node.id()] = node.position();
        }
    });

    cy.edges().forEach(edge => {
        const dists   = edge.data('cyedgecontroleditingDistances') || edge.data('edgeDistances');
        const weights = edge.data('cyedgecontroleditingWeights')   || edge.data('edgeWeights');
        if (dists && dists.length > 0) {
            layoutData.edges[edge.id()] = { distances: dists, weights: weights };
        }
    });

    localStorage.setItem(`cyLayout_${graphId}`, JSON.stringify(layoutData));
}

function loadLayoutFromLocalStorage(cy, graphId) {
    if (!cy || !graphId || typeof localStorage === 'undefined') return false;

    const savedLayout = localStorage.getItem(`cyLayout_${graphId}`);
    if (!savedLayout) return false;

    try {
        const savedData = JSON.parse(savedLayout);
        cy.batch(() => {
            if (savedData.nodes) {
                for (const nodeId in savedData.nodes) {
                    const node = cy.getElementById(nodeId);
                    if (node.length > 0) node.position(savedData.nodes[nodeId]);
                }
            }
            if (savedData.edges) {
                for (const edgeId in savedData.edges) {
                    const edge = cy.getElementById(edgeId);
                    if (edge.length > 0) {
                        edge.data('cyedgecontroleditingDistances', savedData.edges[edgeId].distances);
                        edge.data('cyedgecontroleditingWeights',   savedData.edges[edgeId].weights);
                    }
                }
            }
        });
        cy.fit(null, 50);
        return true;
    } catch (e) {
        console.error("Erro ao carregar layout:", e);
        return false;
    }
}

function applySavedPositions(graphElements, sourceCode) {
    const graphId = getLayoutKey(sourceCode);
    try {
        var savedJson = localStorage.getItem(`cyLayout_${graphId}`);
        if (!savedJson) return false;

        var savedData = JSON.parse(savedJson);
        var foundSomething = false;

        graphElements.forEach(el => {
            if (el.group === 'nodes' || (el.data && !el.data.source)) {
                if (savedData.nodes && savedData.nodes[el.data.id]) {
                    el.position = savedData.nodes[el.data.id];
                    foundSomething = true;
                } else if (el.classes && el.classes.includes('deadlock-node')) {
                    var parts = el.data.id.split('_');
                    if (parts.length >= 2) {
                        var srcId = parts[1];
                        if (savedData.nodes && savedData.nodes[srcId]) {
                            el.position = { x: savedData.nodes[srcId].x, y: savedData.nodes[srcId].y - 70 };
                            foundSomething = true;
                        }
                    }
                }
            } else if (el.data && el.data.source) {
                if (savedData.edges && savedData.edges[el.data.id]) {
                    var d = savedData.edges[el.data.id].distances;
                    var w = savedData.edges[el.data.id].weights;
                    el.data.edgeDistances                   = d;
                    el.data.edgeWeights                     = w;
                    el.data.cyedgecontroleditingDistances   = d;
                    el.data.cyedgecontroleditingWeights     = w;
                    foundSomething = true;
                }
            }
        });
        return foundSomething;
    } catch (e) {
        console.warn("Erro ao aplicar layout salvo:", e);
        return false;
    }
}

// ── File import / export ─────────────────────────────────────

function exportAllLayoutsToFile() {
    if (typeof localStorage === 'undefined') {
        alert("O LocalStorage não é suportado neste navegador.");
        return;
    }

    const allLayouts = {};
    let layoutsFound = 0;

    for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (key && key.startsWith('cyLayout_')) {
            allLayouts[key] = JSON.parse(localStorage.getItem(key));
            layoutsFound++;
        }
    }

    if (layoutsFound === 0) {
        alert("Nenhum layout salvo foi encontrado para exportar.");
        return;
    }

    const blob = new Blob([JSON.stringify(allLayouts, null, 2)], { type: 'application/json' });
    const a    = document.createElement('a');
    a.href     = URL.createObjectURL(blob);
    a.download = 'all-cytoscape-layouts-backup.json';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(a.href);

    console.log(`${layoutsFound} layouts foram exportados com sucesso.`);
}

function importAllLayoutsFromFile(cy, graphId, jsonString) {
    if (typeof localStorage === 'undefined') {
        alert("O LocalStorage não é suportado neste navegador.");
        return;
    }

    try {
        const allLayouts = JSON.parse(jsonString);
        let layoutsImported = 0;

        for (const key in allLayouts) {
            if (key && key.startsWith('cyLayout_')) {
                localStorage.setItem(key, JSON.stringify(allLayouts[key]));
                layoutsImported++;
            }
        }

        if (layoutsImported > 0) {
            alert(`${layoutsImported} layouts foram importados com sucesso para o seu navegador!`);
            console.log("Tentando aplicar o layout para o grafo atual...");
            loadLayoutFromLocalStorage(cy, graphId);
        } else {
            alert("Nenhum layout válido encontrado no arquivo selecionado.");
        }
    } catch (e) {
        console.error("Falha ao importar layouts do arquivo.", e);
        alert("Erro ao ler o arquivo. Verifique se é um backup de layout válido.");
    }
}

function hasExistingLayoutsInLocalStorage() {
    if (typeof localStorage === 'undefined') return false;
    for (let i = 0; i < localStorage.length; i++) {
        if (localStorage.key(i).startsWith('cyLayout_')) return true;
    }
    return false;
}

async function loadDefaultLayoutsFromSeedFile() {
    try {
        if (window.RTA_DEFAULT_LAYOUTS) {
            const layouts = window.RTA_DEFAULT_LAYOUTS;
            for (const k in layouts) {
                if (k.startsWith('cyLayout_') && !localStorage.getItem(k)) {
                    localStorage.setItem(k, JSON.stringify(layouts[k]));
                }
            }
            console.log("✅ Layouts de semente carregados com sucesso via Script.");
        }
    } catch (e) {
        console.warn("⚠️ Não foi possível carregar os layouts padrão:", e);
    }
}
