"""
client.py — High-level Python API for the reforma tool.
"""

from __future__ import annotations

import re
import os

from typing import Optional
import json
from .jar_bridge import JarBridge, JarError
from .model import ReFormaModel, SimulationState, Transition


class ReForma:
    """
    High-level client for the reforma / RePA tool.

    Parameters
    ----------
    jar_path : str | None
        Path to ``reformaTool.jar``.  Omit (or pass ``None``) to use the
        JAR bundled inside the package.
    java_bin : str
        Path to the ``java`` executable (default: ``"java"``).
    """

    def __init__(self, jar_path: Optional[str] = None, java_bin: str = "java"):
        self._bridge = JarBridge(jar_path, java_bin)
        self._model: Optional[ReFormaModel] = None
        self._state: Optional[SimulationState] = None
        self._history: list[str] = []
        self._current_source: Optional[str] = None

    def help(self) -> None:
        """Prints a comprehensive list of features and methods available in the ReForma Python wrapper."""
        msg = """
        ===================================================================
                                ReForma Python API
        ===================================================================

        # --- Loading and Core ---
        load(source, name)     : Loads a model from a source string.
        load_file(path)        : Loads a model from a .Re file.
        reset()                : Resets the simulation to the initial state.

        # --- Simulation ---
        step(label)            : Takes a transition given its label.
        undo()                 : Undoes the last transition.

        # --- Visualizations & State ---
        show_interactive()     : Renders a Jupyter interactive graph.
        show_all_steps_interactive() : Renders a Jupyter interactive graph of the full LTS tree.
        save_image_plt(path)   : Saves a static image of the graph.
        get_all_steps()        : Returns a Mermaid.js diagram with the full LTS (all reachable states).
        get_current_state_text(): Textual summary of the automaton.
        summary() / print(state): High-level view of current state variables and probabilities.

        # --- Analysis & Stats ---
        check_problems()       : Runs a random walk to find deadlocks, unreachable states, or rule inconsistencies.
        get_stats()            : Explores the state space and counts total reachable states and transitions.
        find_best_path(...)    : Finds the most/least probable path to a state or variable condition.

        # --- Training & Manipulation ---
        train(sessions)        : Updates transition weights based on a batch of sessions.
        train_from_file(path)  : Trains weights using a log file.
        delta_cut(delta)       : Prunes transitions and rules with weights below the given delta, updating the model.
        merge_models(...)      : Merges the current model with another (union or intersection) using an aggregation function.

        # --- Verification (PDL / PCTL) ---
        check_pdl(state, form) : Evaluates a formula and returns the raw string.
        check_pdl_value(...)   : Evaluates a formula and returns a float or bool.

        # --- Exports ---
        export_prism()         : Exports to PRISM DTMC.
        export_glts()          : Translates to GLTS.
        ===================================================================
        """
        print(msg)

    
    def create_from_dataframe(
        self,
        df,
        session_col: str = 'user_session',
        event_col: str = 'event_type',
        sub_event_col: Optional[str] = None,
        time_col: str = 'event_time',
        model_name: str = "MinedModel",
        save_model_path: Optional[str] = None,
        save_traces_path: Optional[str] = None,
        initial_weights: str = "frequency",
        session_order: str = "time"
    ) -> list[list[str]]:
        """
        Discovers the RePA model directly from a Pandas DataFrame.

        :param df: Pandas DataFrame containing the dataset.
        :param session_col: Column name identifying user sessions.
        :param event_col: Column name containing the action/event.
        :param sub_event_col: Optional column name for sub-events (e.g., categories).
        :param time_col: Column name containing the timestamp.
        :param model_name: Name to give the automaton.
        :param save_model_path: If set, saves the generated .rta code to this file path.
        :param save_traces_path: If set, saves the session logs (.txt) to this file path.
        :param initial_weights: "frequency" (default) writes the observed transition frequencies (the maximum
            likelihood estimate) as weights; "uniform" leaves the weights out, so every state starts uniform.
        :param session_order: "time" (default) returns the sessions in the order they started, as they happened;
            "id" returns them sorted by session identifier. The order matters when the sessions are replayed with
            an aggregation of constant weight (``training aggregation ema(g)``).
        :return: A list of sessions (each session is a list of transition IDs).
        """
        try:
            import pandas as pd
            import numpy as np
            import re
        except ImportError:
            raise RuntimeError("To use process mining you need to install: pip install pandas")
        if initial_weights not in ("frequency", "uniform"):
            raise ValueError("initial_weights must be 'frequency' or 'uniform'")
        if session_order not in ("time", "id"):
            raise ValueError("session_order must be 'time' or 'id'")

        has_sub = bool(sub_event_col) and sub_event_col in df.columns
        cols = [session_col, time_col, event_col] + ([sub_event_col] if has_sub else [])
        df_work = df.loc[df[session_col].notna(), cols]
        # stable sort: events with the same timestamp keep the order of the log
        df_work = df_work.sort_values(by=[session_col, time_col], kind="mergesort")

        # Every row gets the integer code of its (composite) event; all the work below is done on these codes.
        ev = df_work[event_col].astype("category")
        ev_codes = ev.cat.codes.to_numpy().astype(np.int64)
        ev_names = [str(c) for c in ev.cat.categories]
        if has_sub:
            sub = df_work[sub_event_col].astype("category")
            sub_names = [str(c) for c in sub.cat.categories] + ["other"]          # missing sub-events -> "other"
            sub_codes = sub.cat.codes.to_numpy().astype(np.int64)
            sub_codes[sub_codes < 0] = len(sub_names) - 1
            # the last component of a category ("electronics.smartphone" -> "smartphone") is used, unless two
            # different categories share it: those keep their full path
            by_leaf: dict = {}
            for c in sub_names:
                by_leaf.setdefault(c.split('.')[-1], set()).add(c)
            short = [c.split('.')[-1] if len(by_leaf[c.split('.')[-1]]) == 1 else c for c in sub_names]
            combo = ev_codes * len(sub_names) + sub_codes
            uniq, raw_codes = np.unique(combo, return_inverse=True)
            raw_names = [f"{ev_names[u // len(sub_names)]}_{short[u % len(sub_names)]}" for u in uniq]
        else:
            raw_codes, raw_names = ev_codes, ev_names
        raw_codes = np.asarray(raw_codes, dtype=np.int64)

        # One Re-Lang name per distinct event, distinct from each other, from the artificial states and from keywords.
        reserved = {"Start", "exit", "init", "training", "calibration", "paradigm", "name", "int", "float", "bool",
                    "dyn", "def", "aut", "if", "then", "disabled", "true", "false", "return", "print", "foreach",
                    "in", "AND", "OR", "laplace", "aggregation", "arith", "prod", "max", "min", "geom"}
        clean = [""] * len(raw_names)
        used = set(reserved)
        for i in sorted(range(len(raw_names)), key=lambda i: raw_names[i]):
            base = re.sub(r'[^a-zA-Z0-9_]', '_', raw_names[i]).strip('_') or "event"
            if base[0].isdigit() or base in reserved:
                base = "ev_" + base
            candidate, k = base, 2
            while candidate in used:
                candidate, k = f"{base}_{k}", k + 1
            used.add(candidate)
            clean[i] = candidate
        K = len(clean)
        names = clean + ["exit"]                                                # index K is the artificial exit

        # sessions are contiguous after the sort
        sess_codes = pd.factorize(df_work[session_col], sort=False)[0]
        new_sess = np.empty(len(sess_codes), dtype=bool)
        new_sess[:1] = True
        new_sess[1:] = sess_codes[1:] != sess_codes[:-1]
        is_last = np.empty_like(new_sess)
        is_last[:-1] = new_sess[1:]
        is_last[-1:] = True
        nxt = np.empty_like(raw_codes)
        nxt[:-1] = raw_codes[1:]
        nxt[is_last] = K

        firsts = raw_codes[new_sess]
        first_counts = np.bincount(firsts, minlength=K)
        pair = raw_codes * (K + 1) + nxt
        pair_counts = np.bincount(pair, minlength=K * (K + 1))
        out_totals = pair_counts.reshape(K, K + 1).sum(axis=1)

        def weight(count, total) -> str:
            return f" ({count / total:.6f})" if initial_weights == "frequency" else ""

        rta_output = [
            f"name {model_name};",
            "\n// =====================================================",
            "// Model From Dataset",
            "// =====================================================\n",
            "init Start;\n"
        ]

        edge_counter = 1
        n_sessions = int(first_counts.sum())
        start_label = np.empty(K, dtype=object)
        for i in sorted((i for i in range(K) if first_counts[i] > 0), key=lambda i: (-first_counts[i], names[i])):
            label_id = f"e{edge_counter}"
            rta_output.append(f"Start -enter_{names[i]}-> {names[i]} : {label_id}{weight(first_counts[i], n_sessions)}")
            start_label[i] = label_id
            edge_counter += 1

        rta_output.append("")

        pair_label = np.empty(K * (K + 1), dtype=object)
        observed = [p for p in np.flatnonzero(pair_counts)]
        for p in sorted(observed, key=lambda p: (names[p // (K + 1)], names[p % (K + 1)])):
            src, tgt = names[p // (K + 1)], names[p % (K + 1)]
            label_id = f"e{edge_counter}"
            rta_output.append(f"{src} -{tgt}-> {tgt} : {label_id}{weight(pair_counts[p], out_totals[p // (K + 1)])}")
            pair_label[p] = label_id
            edge_counter += 1

        loop_edge_id = f"e{edge_counter}"
        rta_output.append("\n// Loop at the final state to avoid deadlock in PRISM")
        rta_output.append(f"exit -loop-> exit : {loop_edge_id} (1.0000)")

        row_labels = pair_label[pair]
        starts = np.flatnonzero(new_sess)
        traces = [[start_label[f]] + chunk.tolist() for f, chunk in zip(firsts, np.split(row_labels, starts[1:]))]
        if session_order == "time" and len(traces) > 1:
            t = df_work[time_col]
            if isinstance(t.dtype, pd.CategoricalDtype) and t.cat.categories.is_monotonic_increasing:
                first_t = t.cat.codes.to_numpy()[starts]
            else:
                first_t = t.to_numpy()[starts]
            traces = [traces[i] for i in np.argsort(first_t, kind="stable")]

        full_source = "\n".join(rta_output)
        self.load(full_source, name=model_name)

        if save_model_path:
            with open(save_model_path, "w", encoding="utf-8") as f:
                f.write(full_source)

        if save_traces_path:
            with open(save_traces_path, "w", encoding="utf-8") as f:
                for t in traces:
                    f.write(",".join(t))
                    f.write("\n")

        return traces


    def check_problems(self) -> str:
        """Runs a random walk to find deadlocks, unreachable states, or inconsistencies."""
        self._require_model()
        return self._bridge.check_problems(self._model.source)
        
    def get_stats(self) -> str:
        """Explores the state space and counts total reachable states and transitions."""
        self._require_model()
        return self._bridge.get_stats(self._model.source)

    def find_best_path(self, target_type: str, target_value: str, target_int: int = 0, criterion: str = "max") -> str:
        """
        Finds the most or least probable path to a specific target.
        target_type: "state" or "variable"
        target_value: Name of the state or variable.
        target_int: Target integer value (if target_type is "variable").
        criterion: "max" (most probable) or "min" (least probable).
        """
        self._require_model()
        return self._bridge.find_best_path(self._model.source, target_type, target_value, target_int, criterion)


    def load(self, source: str, name: str = "model") -> SimulationState:
        """Load a model from a source-code string."""
        self._model = ReFormaModel.from_string(source, name)
        self._current_source = source
        self._history = []
        self._state = self._parse_simulation(
            self._bridge.list_transitions(source)
        )
        return self._state

    def load_file(self, path: str) -> SimulationState:
        """Load a model from a ``.r`` file."""
        self._model = ReFormaModel.from_file(path)
        self._current_source = self._model.source
        self._history = []
        self._state = self._parse_simulation(
            self._bridge.list_transitions(self._model.source)
        )
        return self._state
    

    def delta_cut(self, delta: float) -> "ReForma":
        """
        Prunes transitions and rules with probabilities below the given delta.
        Updates the current model in-place and resets history.
        """
        self._require_model()
        updated_source = self._bridge.delta_cut(self._model.source, delta)
        self._model = ReFormaModel.from_string(updated_source, self._model.name)
        self._current_source = updated_source
        self._history = []
        self._state = self._parse_simulation(self._bridge.list_transitions(updated_source))
        return self

    def merge_models(self, other_source: str, op_type: str = "union", agg: str = "arith") -> "ReForma":
        """
        Merges the current model with another model source code.
        op_type: "union" or "intersect"
        agg: aggregation strategy ("arith", "prod", "max", "min", "geom")
        Updates the current model in-place.
        """
        self._require_model()

        if isinstance(other_source, ReForma):
            if other_source.source is None:
                raise RuntimeError("The passed ReForma model is empty.")
            other_source = other_source.source
            
        updated_source = self._bridge.merge_models(self._model.source, other_source, op_type, agg)
        self._model = ReFormaModel.from_string(updated_source, self._model.name)
        self._current_source = updated_source
        self._history = []
        self._state = self._parse_simulation(self._bridge.list_transitions(updated_source))
        return self

    def save_image_plt(self, path: str) -> None:
        """Saves a static image of the graph using Matplotlib."""
        self._require_model()
        
        try:
            import networkx as nx
            import matplotlib.pyplot as plt
        except ImportError:
            raise RuntimeError("To use this method you need to install: pip install networkx matplotlib")

        cy_json_str = self._bridge.get_cytoscape(self._model.source, self._history)
        elements = json.loads(cy_json_str)

        G = nx.DiGraph()

        state_nodes, current_state_nodes, event_nodes, deadlock_nodes = [], [], [], []
        simple_edges, enable_edges, disable_edges, disabled_edges = [], [], [], []
        node_labels = {}

        for el in elements:
            data = el.get("data", {})
            classes = el.get("classes", "")
            
            if "source" not in data and "target" not in data:
                nid = data["id"]
                label = data.get("label", nid).replace("\\n", "\n")
                G.add_node(nid)
                node_labels[nid] = label
                
                if "state-node" in classes:
                    if "current-state" in classes:
                        current_state_nodes.append(nid)
                    else:
                        state_nodes.append(nid)
                elif "deadlock" in classes:
                    deadlock_nodes.append(nid)
                else:
                    event_nodes.append(nid)
                    
            else:
                src, tgt = data["source"], data["target"]
                G.add_edge(src, tgt)
                
                if "disabled" in classes:
                    disabled_edges.append((src, tgt))
                elif "enable-rule" in classes:
                    enable_edges.append((src, tgt))
                elif "disable-rule" in classes:
                    disable_edges.append((src, tgt))
                else:
                    simple_edges.append((src, tgt))

        plt.figure(figsize=(12, 8))

        if not hasattr(self, "_layout_pos") or self._layout_pos is None or set(G.nodes) != set(self._layout_pos.keys()):
            self._layout_pos = nx.kamada_kawai_layout(G)
        
        pos = self._layout_pos

        nx.draw_networkx_nodes(G, pos, nodelist=state_nodes, node_color='#BFDBFE', edgecolors='#3B82F6', node_size=1500, node_shape='o')
        if current_state_nodes:
            nx.draw_networkx_nodes(G, pos, nodelist=current_state_nodes, node_color='#86EFAC', edgecolors='#166534', node_size=1500, node_shape='o', linewidths=3)
        nx.draw_networkx_nodes(G, pos, nodelist=event_nodes, node_color='#E5E7EB', edgecolors='#9CA3AF', node_size=1200, node_shape='s')
        if deadlock_nodes:
            nx.draw_networkx_nodes(G, pos, nodelist=deadlock_nodes, node_color='#FECACA', edgecolors='#EF4444', node_size=1200, node_shape='h')

        nx.draw_networkx_edges(G, pos, edgelist=simple_edges, arrows=True, arrowstyle='-|>', arrowsize=20, 
                               edge_color='#6B7280', width=1.5, node_size=1500, connectionstyle='arc3,rad=0.1')
        nx.draw_networkx_edges(G, pos, edgelist=enable_edges, arrows=True, arrowstyle='-|>', arrowsize=20, 
                               edge_color='#2563EB', style='solid', width=1.5, node_size=1500, connectionstyle='arc3,rad=0.1')
        nx.draw_networkx_edges(G, pos, edgelist=disable_edges, arrows=True, arrowstyle='-|>', arrowsize=20, 
                               edge_color='#DC2626', style='solid', width=1.5, node_size=1500, connectionstyle='arc3,rad=0.1')
        if disabled_edges:
            nx.draw_networkx_edges(G, pos, edgelist=disabled_edges, arrows=True, arrowstyle='-|>', arrowsize=15, 
                                   edge_color='#D1D5DB', style='dashed', width=1.0, alpha=0.5, node_size=1500, connectionstyle='arc3,rad=0.1')

        nx.draw_networkx_labels(G, pos, labels=node_labels, font_size=8, font_weight='bold')

        plt.axis('off')
        plt.tight_layout()
        plt.savefig(path, dpi=300, bbox_inches='tight')
        plt.close()
    
    
    def step(self, label: str) -> SimulationState:
        """Takes a transition given its label."""
        self._require_model()
        if self._state and not self._state.transition_named(label):
            enabled = [t.label for t in self._state.enabled]
            raise RuntimeError(f"Transition {label!r} is not enabled. Enabled: {enabled}")

        transition = self._state.transition_named(label)
        dest_state = transition.to_state if transition and transition.to_state else None

        self._history.append(label)

        raw = self._bridge.list_transitions(self._model.source, self._history)
        next_state = self._parse_simulation(raw)

        resolved_states = next_state.current_states if next_state.current_states else self._state.current_states

        self._state = SimulationState(
            current_states=resolved_states,
            enabled=next_state.enabled,
            variables=next_state.variables,
            can_undo=len(self._history) > 0,
            last_transition=Transition(
                transition.from_state if transition else "", dest_state or "",
                label, label, transition.probability if transition else 0.0,
            ),
            raw=next_state.raw,
        )
        return self._state

    def set_layout(self, pos_dict: dict) -> None:
        self._layout_pos = pos_dict
    
    def show_interactive(self, height: int = 500) -> None:
        """Renders an interactive Jupyter graph."""
        self._require_model()
        
        try:
            from IPython.display import display, HTML
            import html
            import json
            import networkx as nx
        except ImportError:
            raise RuntimeError("To use this method you need to be running in Jupyter with networkx installed.")

        cy_json_str = self._bridge.get_cytoscape(self._current_source, self._history)
        elements = json.loads(cy_json_str)
        
        G = nx.DiGraph()
        for el in elements:
            data = el.get("data", {})
            if "source" not in data and "target" not in data:
                G.add_node(data["id"])
            else:
                G.add_edge(data["source"], data["target"])

        if not hasattr(self, "_layout_pos") or self._layout_pos is None or set(G.nodes) != set(self._layout_pos.keys()):
            pos = nx.spring_layout(G, k=2.0, seed=42, iterations=150)
            self._layout_pos = {k: [float(v[0]), float(v[1])] for k, v in pos.items()}
            
        for el in elements:
            data = el.get("data", {})
            if "source" not in data and "target" not in data:
                nid = data["id"]
                if nid in self._layout_pos:
                    x, y = self._layout_pos[nid]
                    el["position"] = {"x": float(x) * 400, "y": float(y) * 400}
                    
        cy_json_fixed = json.dumps(elements)
        
        import uuid
        div_id = f"cy_{uuid.uuid4().hex}"
        cyto_js = _get_local_js("cytoscape.min.js")
        
        inner_html = f"""
        <!DOCTYPE html>
        <html>
        <head>
            <script>{cyto_js}</script>
            <style>
                body {{ margin: 0; padding: 0; background: #fafafa; font-family: sans-serif; overflow: hidden; }}
                #cy {{ width: 100vw; height: 100vh; display: block; }}
                .btn-export {{ position: absolute; top: 10px; right: 10px; z-index: 10; padding: 8px 12px; background: #3B82F6; color: white; border: none; border-radius: 4px; font-weight: bold; cursor: pointer; }}
                .btn-export:hover {{ background: #2563EB; }}
                #overlay {{ display: none; position: absolute; top: 50px; right: 10px; width: 350px; background: white; padding: 15px; border: 1px solid #ccc; border-radius: 8px; box-shadow: 0 4px 12px rgba(0,0,0,0.15); z-index: 20; }}
                textarea {{ width: 100%; height: 100px; font-family: monospace; font-size: 11px; margin-bottom: 10px; resize: none; }}
            </style>
        </head>
        <body>
            <button class="btn-export" onclick="exportLayout()">Save Positions to Python</button>
            
            <div id="overlay">
                <p style="margin-top:0; font-size: 13px; color: #333;"><b>1.</b> Copy the code below.<br><b>2.</b> Paste it into a new cell in your Jupyter notebook and run it.</p>
                <textarea id="pycode" readonly></textarea>
                <button onclick="document.getElementById('overlay').style.display='none'" style="width: 100%; padding: 6px; cursor: pointer;">Close</button>
            </div>

            <div id="cy"></div>
            
            <script>
                var elementsData = {cy_json_fixed};
                
                var cy = cytoscape({{
                    container: document.getElementById('cy'),
                    elements: elementsData,
                    layout: {{ name: 'preset' }},
                    style: [
                            {{ selector: 'node', style: {{ 'label': 'data(label)', 'text-valign': 'center', 'color': '#000000', 'font-family': 'sans-serif', 'font-weight': 'bold', 'text-outline-width': 2, 'text-outline-color': '#FFFFFF' }} }},
                            
                            {{ selector: 'edge', style: {{ 'width': 2, 'curve-style': 'unbundled-bezier', 'line-color': '#9CA3AF','target-arrow-shape': 'none', 'label': 'data(label)','color': '#000000', 'text-outline-color': '#FFFFFF','text-outline-width': 2,'font-size': '14px'}} }}, 
                            {{ selector: 'edge[edgeDistances]', style: {{'curve-style': 'unbundled-bezier','control-point-distances': 'data(edgeDistances)','control-point-weights': 'data(edgeWeights)','edge-distances': 'node-position'}}}},
                            {{ selector: 'edge.from-action-node', style: {{ 'target-arrow-shape': 'triangle' }} }},
                            
                            {{ selector: 'edge.simple-conn', style: {{ 'text-opacity': 0 }} }},
                            {{ selector: 'edge.simple-conn.hovered', style: {{ 'text-opacity': 1, 'z-index': 9999 }} }},
                            
                            {{ selector: 'node.state-node', style: {{ 'background-color': '#BFDBFE', 'shape': 'ellipse', 'width': 50, 'height': 50, 'border-width': 3, 'border-color': '#3B82F6', 'text-wrap': 'wrap', 'text-valign': 'center' }} }},
                            {{ selector: 'node.has-invariant', style: {{ 'label': function(ele) {{ return ele.data('label') + '\\n[' + ele.data('invariant') + ']'; }} }} }},
                            
                            {{ selector: '.current-state', style: {{ 'background-color': '#86EFAC', 'border-color': '#166534', 'border-width': 4 }} }},
                            
                            {{ selector: 'node.event-node', style: {{ 
                                'label': function(ele) {{
                                    const data = ele.data();
                                    const p = data.p;
                                    let baseName = data.action_name || (data.label ? data.label.split('\\n')[0] : "");

                                    if (window.isPossibilisticView) {{
                                        return baseName;
                                    }}

                                    if (p !== undefined) {{
                                        const isRule = ele.hasClass('rule-node'); 
                                        const symbol = isRule ? "Δ" : "P";
                                        if (isRule) return `${{baseName}}\\n(${{symbol}}=${{p.toFixed(3)}})`;
                                        const nm = (data.transID == data.lbl ? "":data.transID);
                                        return `${{nm}}\\n(${{symbol}}=${{p.toFixed(3)}})`
                                    }}
                                    return data.label || "";
                                }},
                                'background-color': '#ffffff', 'shape': 'rectangle', 'width': 'label', 'height': 'label', 'padding': 10, 'border-width': 2, 'border-color': '#9CA3AF', 'text-wrap': 'wrap', 'text-valign': 'center', 'text-halign': 'center' 
                            }} }},
                            
                            {{ selector: 'node.event-node.hovered', style: {{ 
                                'label': function(ele) {{
                                    let hlbl = ele.data('hover_label') || "";
                                    if (window.isPossibilisticView) {{
                                        return hlbl.replace(/\\s*\\([PΔ]=[\\d.]+\\)/, '');
                                    }}
                                    return hlbl;
                                }},
                                'z-index': 9999
                            }} }},
                            {{ selector: 'node.rule-node', style: {{ 'background-color': '#E5E7EB' }} }},
                            {{ selector: '.enable-rule', style: {{ 'line-color': '#2563EB', 'target-arrow-color': '#2563EB' }} }},
                            
                            {{ selector: '.disable-rule', style: {{ 'line-color': '#DC2626', 'target-arrow-color': '#DC2626' }} }},
                            {{ selector: 'edge.enable-rule.to-target', style: {{ 'target-arrow-shape': 'triangle-tee' }} }},
                            {{ selector: 'edge.disable-rule.to-target', style: {{ 'target-label': 'X', 'target-text-offset': 5, 'color': '#DC2626', 'font-size': '12px' }} }},
                            
                            {{ selector: '.disabled', style: {{ 'line-style': 'dashed', 'background-opacity': 0.6, 'border-style': 'dashed', 'opacity': 0.7 }} }},
                            
                            {{ selector: '.deadlock-node', style: {{ 'background-color': '#FECACA', 'border-color': '#EF4444', 'color': '#7F1D1D' }} }},
                            {{ selector: '.deadlock-edge', style: {{ 'line-color': '#EF4444', 'target-arrow-color': '#EF4444', 'line-style': 'dashed' }} }},
                            {{ selector: '.deadlock-edge.enabled', style: {{ 'line-style': 'solid', 'width': 3 }} }},

                            {{ selector: '.transition-flash', style: {{ 'background-color': '#F97316', 'line-color': '#F97316', 'target-arrow-color': '#F97316' }} }},
                            
                            {{ selector: '.filtered-out', style: {{ 'display': 'none' }} }},

                            {{ selector: '.compound-parent', style: {{ 'background-color': '#F3F4F6', 'background-opacity': 1, 'border-color': '#D1D5DB', 'border-width': 2, 'content': 'data(label)', 'text-valign': 'top', 'text-halign': 'center', 'color': '#374151', 'font-weight': 'bold', 'font-size': '16px' }} }}
                        ]
                }});
                
                cy.nodes().style('opacity', 0);
                cy.edges().style('opacity', 0);
                cy.elements().animate({{ style: {{ opacity: 1 }} }}, {{ duration: 400 }});

                function exportLayout() {{
                    var posDict = {{}};
                    cy.nodes().forEach(function(n) {{
                        var pos = n.position();
                        // Divided by 400 to return to the original Python scale
                        posDict[n.id()] = [pos.x / 400.0, pos.y / 400.0];
                    }});
                    
                    var jsonStr = JSON.stringify(posDict).replace(/"/g, "'");
                    var pyCode = "repa.set_layout(" + jsonStr + ")";
                    
                    document.getElementById('pycode').value = pyCode;
                    document.getElementById('overlay').style.display = 'block';
                    document.getElementById('pycode').select();
                }}
            </script>
        </body>
        </html>
        """
        
        safe_html = html.escape(inner_html)
        iframe_wrapper = f'''
        <iframe srcdoc="{safe_html}" 
                style="width: 100%; height: {height}px; border: 1px solid #ddd; border-radius: 8px; box-shadow: inset 0 0 10px rgba(0,0,0,0.05);" 
                frameborder="0" 
                scrolling="no">
        </iframe>
        '''
        display(HTML(iframe_wrapper))
    

    def show_all_steps_interactive(self, height: int = 600) -> None:
        """
        Renders an interactive Jupyter view of the full LTS (all reachable states).
        """
        self._require_model()
        
        try:
            from IPython.display import display, HTML
            import html
            import json
            import re
        except ImportError:
            raise RuntimeError("To use this method you need to be running in Jupyter.")
            
        mermaid_str = self.export_mermaid(full_lts=True)
        
        elements = []
        edge_pattern = re.compile(r'^(\d+)\s*-->\|"(.*?)"\|\s*(\d+)$')
        node_pattern = re.compile(r'^(\d+)\("(.*?)"\)$')
        style_pattern = re.compile(r'^style\s+(\d+)\s+fill:#bbf')
        
        root_node = None
        
        for line in mermaid_str.splitlines():
            line = line.strip()
            
            m_edge = edge_pattern.match(line)
            if m_edge:
                src, lbl, tgt = m_edge.groups()
                elements.append({
                    "data": {"id": f"e_{src}_{tgt}_{lbl}", "source": src, "target": tgt, "label": lbl}
                })
                continue
                
            m_node = node_pattern.match(line)
            if m_node:
                nid, lbl = m_node.groups()
                elements.append({
                    "data": {"id": nid, "label": lbl},
                    "classes": "lts-node"
                })
                continue
                
            m_style = style_pattern.search(line)
            if m_style:
                root_node = m_style.group(1)
                
        for el in elements:
            if "source" not in el.get("data", {}) and el["data"]["id"] == root_node:
                el["classes"] = el.get("classes", "") + " root-node"
                
        cy_json_str = json.dumps(elements)

        cyto_js = _get_local_js("cytoscape.min.js")
        dagre_js = _get_local_js("dagre.js")
        cyto_dagre_js = _get_local_js("cytoscape-dagre.js")
        
        inner_html = f"""
        <!DOCTYPE html>
        <html>
        <head>
            <script>{cyto_js}</script>
            <script>{dagre_js}</script>
            <script>{cyto_dagre_js}</script>
            <style>
                body {{ margin: 0; padding: 0; background: #fafafa; font-family: sans-serif; overflow: hidden; }}
                #cy {{ width: 100vw; height: 100vh; display: block; }}
            </style>
        </head>
        <body>
            <div id="cy"></div>
            <script>
                var elementsData = {cy_json_str};
                var cy = cytoscape({{
                    container: document.getElementById('cy'),
                    elements: elementsData,
                    layout: {{ name: 'dagre', rankDir: 'LR', spacingFactor: 1.2 }},
                    style: [
                            {{ selector: 'node', style: {{ 'label': 'data(label)', 'text-valign': 'center', 'color': '#000000', 'font-family': 'sans-serif', 'font-weight': 'bold', 'text-outline-width': 2, 'text-outline-color': '#FFFFFF' }} }},
                            
                            {{ selector: 'edge', style: {{ 'width': 2, 'curve-style': 'unbundled-bezier', 'line-color': '#9CA3AF','target-arrow-shape': 'none', 'label': 'data(label)','color': '#000000', 'text-outline-color': '#FFFFFF','text-outline-width': 2,'font-size': '14px'}} }}, 
                            {{ selector: 'edge[edgeDistances]', style: {{'curve-style': 'unbundled-bezier','control-point-distances': 'data(edgeDistances)','control-point-weights': 'data(edgeWeights)','edge-distances': 'node-position'}}}},
                            {{ selector: 'edge.from-action-node', style: {{ 'target-arrow-shape': 'triangle' }} }},
                            
                            {{ selector: 'edge.simple-conn', style: {{ 'text-opacity': 0 }} }},
                            {{ selector: 'edge.simple-conn.hovered', style: {{ 'text-opacity': 1, 'z-index': 9999 }} }},
                            
                            {{ selector: 'node.state-node', style: {{ 'background-color': '#BFDBFE', 'shape': 'ellipse', 'width': 50, 'height': 50, 'border-width': 3, 'border-color': '#3B82F6', 'text-wrap': 'wrap', 'text-valign': 'center' }} }},
                            {{ selector: 'node.has-invariant', style: {{ 'label': function(ele) {{ return ele.data('label') + '\\n[' + ele.data('invariant') + ']'; }} }} }},
                            
                            {{ selector: '.current-state', style: {{ 'background-color': '#86EFAC', 'border-color': '#166534', 'border-width': 4 }} }},
                            
                            {{ selector: 'node.event-node', style: {{ 
                                'label': function(ele) {{
                                    const data = ele.data();
                                    const p = data.p;
                                    let baseName = data.action_name || (data.label ? data.label.split('\\n')[0] : "");

                                    if (window.isPossibilisticView) {{
                                        return baseName;
                                    }}

                                    if (p !== undefined) {{
                                        const isRule = ele.hasClass('rule-node'); 
                                        const symbol = isRule ? "Δ" : "P";
                                        if (isRule) return `${{baseName}}\\n(${{symbol}}=${{p.toFixed(3)}})`;
                                        const nm = (data.transID == data.lbl ? "":data.transID);
                                        return `${{nm}}\\n(${{symbol}}=${{p.toFixed(3)}})`
                                    }}
                                    return data.label || "";
                                }},
                                'background-color': '#ffffff', 'shape': 'rectangle', 'width': 'label', 'height': 'label', 'padding': 10, 'border-width': 2, 'border-color': '#9CA3AF', 'text-wrap': 'wrap', 'text-valign': 'center', 'text-halign': 'center' 
                            }} }},
                            
                            {{ selector: 'node.event-node.hovered', style: {{ 
                                'label': function(ele) {{
                                    let hlbl = ele.data('hover_label') || "";
                                    if (window.isPossibilisticView) {{
                                        return hlbl.replace(/\\s*\\([PΔ]=[\\d.]+\\)/, '');
                                    }}
                                    return hlbl;
                                }},
                                'z-index': 9999
                            }} }},
                            {{ selector: 'node.rule-node', style: {{ 'background-color': '#E5E7EB' }} }},
                            {{ selector: '.enable-rule', style: {{ 'line-color': '#2563EB', 'target-arrow-color': '#2563EB' }} }},
                            
                            {{ selector: '.disable-rule', style: {{ 'line-color': '#DC2626', 'target-arrow-color': '#DC2626' }} }},
                            {{ selector: 'edge.enable-rule.to-target', style: {{ 'target-arrow-shape': 'triangle-tee' }} }},
                            {{ selector: 'edge.disable-rule.to-target', style: {{ 'target-label': 'X', 'target-text-offset': 5, 'color': '#DC2626', 'font-size': '12px' }} }},
                            
                            {{ selector: '.disabled', style: {{ 'line-style': 'dashed', 'background-opacity': 0.6, 'border-style': 'dashed', 'opacity': 0.7 }} }},
                            
                            {{ selector: '.deadlock-node', style: {{ 'background-color': '#FECACA', 'border-color': '#EF4444', 'color': '#7F1D1D' }} }},
                            {{ selector: '.deadlock-edge', style: {{ 'line-color': '#EF4444', 'target-arrow-color': '#EF4444', 'line-style': 'dashed' }} }},
                            {{ selector: '.deadlock-edge.enabled', style: {{ 'line-style': 'solid', 'width': 3 }} }},

                            {{ selector: '.transition-flash', style: {{ 'background-color': '#F97316', 'line-color': '#F97316', 'target-arrow-color': '#F97316' }} }},
                            
                            {{ selector: '.filtered-out', style: {{ 'display': 'none' }} }},

                            {{ selector: '.compound-parent', style: {{ 'background-color': '#F3F4F6', 'background-opacity': 1, 'border-color': '#D1D5DB', 'border-width': 2, 'content': 'data(label)', 'text-valign': 'top', 'text-halign': 'center', 'color': '#374151', 'font-weight': 'bold', 'font-size': '16px' }} }}
                        ]
                }});
            </script>
        </body>
        </html>
        """
        
        safe_html = html.escape(inner_html)
        iframe_wrapper = f'''
        <iframe srcdoc="{safe_html}" 
                style="width: 100%; height: {height}px; border: 1px solid #ddd; border-radius: 8px; box-shadow: inset 0 0 10px rgba(0,0,0,0.05);" 
                frameborder="0" 
                scrolling="no">
        </iframe>
        '''
        display(HTML(iframe_wrapper))

    def undo(self) -> SimulationState:
        """Undoes the last transition."""
        self._require_model()
        if not self._history: raise RuntimeError("Nothing to undo.")
        self._history.pop()
        
        raw = self._bridge.list_transitions(self._model.source, self._history)
        self._state = self._parse_simulation(raw)
        return self._state

    def reset(self) -> SimulationState:
        """Resets the simulation to the initial state."""
        self._require_model()
        self._history = []
        self._state = self._parse_simulation(self._bridge.list_transitions(self._model.source))
        return self._state

    def check_pdl(self, state: str, formula: str) -> str:
        """Evaluates a formula and returns the raw string."""
        self._require_model()
        return self._bridge.check_pdl(self._model.source, state, formula, self._history)

    @property
    def state(self) -> Optional[SimulationState]:
        """The most recent SimulationState (None if no model loaded)."""
        return self._state

    @property
    def history(self) -> list[str]:
        """Labels taken since last load/reset."""
        return list(self._history)


    def train(self, sessions: list[list[str]]) -> "ReForma":
        """
        Train the model with a batch of sessions.

        Each session is a list of event labels::

            ReForma.train([
                ["go_work", "easy_task", "go_home"],
                ["battery_low", "go_charge", "finish_charge"],
            ])

        The model source is updated in-place with the new weights.
        Returns ``self`` for chaining.
        """
        self._require_model()
        log_lines = [",".join(s) for s in sessions]
        updated_source = self._bridge.train(self._model.source, log_lines)
        # the engine reports events that could not be replayed (unknown label, or not enabled at that point)
        report = next((l for l in getattr(self._bridge, "last_stdout", "").splitlines() if "Warning:" in l), None)
        if report:
            import warnings
            warnings.warn(report.split("Training completed")[-1].strip(), RuntimeWarning, stacklevel=2)
        self._model = ReFormaModel.from_string(updated_source, self._model.name)
        self._current_source = updated_source
        self._history = []
        self._state = self._parse_simulation(
            self._bridge.list_transitions(updated_source)
        )
        return self

    def train_from_file(self, log_path: str) -> "ReForma":
        """
        Train from a log file.  Each line is a comma-separated session::

            go_work,easy_task,go_home
            battery_low,go_charge,finish_charge
        """
        with open(log_path, "r", encoding="utf-8") as f:
            lines = [l.strip() for l in f if l.strip()]
        sessions = [line.split(",") for line in lines]
        return self.train(sessions)


    def check_pdl_value(self, state: str, formula: str) -> "float | bool":
        """
        Like :meth:`check_pdl` but parses the result into a Python value.
        Returns ``float`` for quantitative results, ``bool`` for qualitative.
        """
        return _parse_pdl_result(self.check_pdl(state, formula))



    def export_prism(self) -> str:
        """Export to PRISM DTMC format."""
        self._require_model()
        return self._bridge.get_prism(self._model.source)


    def export_mcrl2(self) -> str:
        """Export to mCRL2."""
        self._require_model()
        return self._bridge.get_mcrl2(self._model.source)

    def export_glts(self) -> str:
        """Translate to GLTS (imperative) format."""
        self._require_model()
        return self._bridge.get_glts(self._model.source)

    def export_mermaid(self, full_lts: bool = False) -> str:
        """
        Export to Mermaid diagram.
        Set ``full_lts=True`` to generate all reachable states.
        """
        self._require_model()
        return (
            self._bridge.get_lts_mermaid(self._model.source)
            if full_lts
            else self._bridge.get_mermaid(self._model.source)
        )
    
    def get_current_state_text(self) -> str:
        """Returns a textual summary of the current automaton state."""
        self._require_model()
        return self.text_summary()
    
    def get_all_steps(self) -> str:
        """Returns a Mermaid.js diagram with the full LTS (all reachable states)."""
        self._require_model()
        return self.export_mermaid(full_lts=True)

    def save_prism(self, path: str) -> None:
        """Export to PRISM and write to *path*."""
        with open(path, "w", encoding="utf-8") as f:
            f.write(self.export_prism())

    def save_source(self, path: str) -> None:
        """Write the current (possibly trained) model source to *path*."""
        self._require_model()
        with open(path, "w", encoding="utf-8") as f:
            f.write(self._model.source)


    @property
    def source(self) -> Optional[str]:
        """The current model source code."""
        return self._model.source if self._model else None

    def text_summary(self) -> str:
        """Return the JAR's textual summary of the current model."""
        self._require_model()
        return self._bridge.get_text(self._model.source)

    @staticmethod
    def bundled_jar() -> Optional[str]:
        """Return the path to the bundled JAR, or None if not present."""
        from .jar_bridge import _bundled_jar_path
        p = _bundled_jar_path()
        return str(p) if p else None



    def _require_model(self) -> None:
        if self._model is None:
            raise RuntimeError(
                "No model loaded. Call load() or load_file() first."
            )

    def _parse_simulation(self, jar_output: str) -> SimulationState:
        # Note: Do NOT translate the string matchers below ("Estado Atual:", "Variaveis:") 
        # as they parse the explicit console output format coming from the Java JAR.
        lines = jar_output.strip().splitlines()
        current_states: list[str] = []
        enabled: list[Transition] = []
        variables: dict[str, int] = {}

        import re
        
        for line in lines:
            if line.startswith("Estado Atual:") or line.startswith("Current State:"):
                st_parts = line.split(":", 1)[1].strip()
                current_states = [s.strip() for s in st_parts.split(",")]
            
            elif line.startswith("Variaveis:") or line.startswith("Variables:"):
                var_parts = line.split(":", 1)[1].strip()
                matches = re.finditer(r"([a-zA-Z0-9_]+)\s*=\s*(?:VInt\(\s*([^,)]+)|VFloat\(\s*([^,)]+)|VBool\(\s*([^,)]+)|([^,]+))", var_parts)
                
                for m in matches:
                    k = m.group(1)
                    val_str = m.group(2) or m.group(3) or m.group(4) or m.group(5)
                    if val_str:
                        val_str = val_str.strip()
                        try:
                            variables[k] = int(val_str)
                        except ValueError:
                            try:
                                variables[k] = float(val_str)
                            except ValueError:
                                variables[k] = val_str

            m = re.match(
                r"\s*-\s*\[(.+?)\]\s+(?:de|from)\s+(\S+)\s+(?:para|to)\s+(\S+)\s+\(P=([\d.,]+)\)",
                line,
            )
            if m:
                label, from_s, to_s, prob_raw = m.groups()
                prob = float(prob_raw.replace(",", "."))
                enabled.append(
                    Transition(from_s, to_s, label, label, prob)
                )

        return SimulationState(
            current_states=current_states,
            enabled=enabled,
            variables=variables,
            can_undo=len(self._history) > 0,
            last_transition=None,
            raw={},
        )

def _get_local_js(filename: str) -> str:
    """Reads the local JS file to be injected into HTML."""
    base_dir = os.path.dirname(__file__)
    js_path = os.path.join(base_dir, "bin", "js", filename)
    try:
        with open(js_path, "r", encoding="utf-8") as f:
            return f.read()
    except FileNotFoundError:
        raise RuntimeError(f"JS file not found: {js_path}. Make sure you have downloaded it.")



def _update_init(source: str, new_state: str) -> str:
    """
    Replace the ``init <state>`` line in *source* with *new_state*.
    Handles both ``init s0`` and ``init s0;`` forms, on any line.
    If no init line is found, prepends one.
    """
    new_line = f"init {new_state}"
    updated, count = re.subn(
        r"^(\s*)init\s+\S+",
        lambda m: f"{m.group(1)}init {new_state}",
        source,
        flags=re.MULTILINE,
    )
    if count == 0:
        updated = new_line + "\n" + source
    return updated


def _parse_pdl_result(raw: str) -> "float | bool | str":

    m = re.search(r"(?:Result(?:ado)?)\s*:\s*(.+)$", raw, re.IGNORECASE | re.MULTILINE)
    if not m:
        return raw

    val = m.group(1).strip()

    if re.match(r"Result(?:ado)?\s*:", val, re.IGNORECASE):
        return _parse_pdl_result(val)

    if val.lower() in ("true", "verdadeiro"):
        return True
    if val.lower() in ("false", "falso"):
        return False
    try:
        return float(val.replace(",", "."))
    except ValueError:
        return val