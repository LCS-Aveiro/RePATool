
# ReForma — Reconfigurable Formal Automata in Python

**ReForma** is a powerful Python library that bridges the gap between Formal Methods (Model Checking, MDPs) and Data Science. It wraps the RePA/ReForma Java backend, providing a clean, Pythonic interface for simulating, training, visualizing, and verifying Reconfigurable Formal Automata.

With ReForma, you can define probabilistic systems where **rules dynamically change the transition weights** during execution, train these systems with real-world event logs, and verify complex properties using PDL/PCTL.

## Acknowledgements

This project relies on and interacts with several excellent open-source projects and academic tools. We would like to thank their creators and maintainers:

**Graph Rendering & Visualization:**
* [Cytoscape.js](https://js.cytoscape.org/) (MIT License) - Used for interactive graph rendering.
* [dagre](https://github.com/dagrejs/dagre) & [cytoscape-dagre](https://github.com/cytoscape/cytoscape.js-dagre) (MIT License) - Used for directed graph layout.
* [Mermaid.js](https://mermaid.js.org/) - Used as an export format for LTS diagrams.

**Python Dependencies:**
* [NetworkX](https://networkx.org/) (BSD License) - Used for internal graph processing and layout calculation.
* [Matplotlib](https://matplotlib.org/) (Matplotlib License) - Used for offline high-resolution graph exports.

**Formal Verification Ecosystem:**
* [PRISM Model Checker](https://www.prismmodelchecker.org/) - The ReForma DSL heavily supports exporting DTMC models for verification in PRISM.

---

## Features

* **Interactive Visualizations:** Render drag-and-drop graphs (Base Model or Full LTS) directly inside Jupyter Notebooks.
* **Model Manipulation:** Merge models (union/intersection) and prune low-probability paths (`delta_cut`).
* **Advanced Analysis:** Find the most/least probable paths to states or variable conditions.
* **Formal Verification:** Evaluate PCTL and PDL formulas (e.g., *What is the probability of eventually reaching a Deadlock?*).
* **Training from Logs:** Automatically update transition weights by feeding real user sessions/traces.
* **Exports:** Export your models to PRISM, mCRL2, GLTS, or Mermaid.js.

---

## Installation & Requirements

**Requirements:**
1. **Python 3.10+**
2. **Java (JRE or JDK)**: ReForma relies on a high-performance Scala/Java backend. You *must* have `java` accessible in your system's `PATH`.

**Installation:**
```bash
pip install reforma
```

For **visualization features** (offline PNGs and interactive Jupyter graphs), install the required extras:
```bash
pip install networkx matplotlib
```

---

## Quick Start

```python
from reforma import ReForma

modelo = ReForma()

# 1. Load a model
modelo.load_file("recommender.r")

# 2. Get a beautiful printout of the current state and probabilities
print(modelo.state.summary())

# 3. Simulate steps
modelo.step("go_work")
modelo.step("easy_task")

# 4. Find the most probable path to a specific state
path = modelo.find_best_path(target_type="state", target_value="Home", criterion="max")
print(path)
```

---

## Visualizations (Jupyter & Offline)

ReForma provides powerful graphing tools directly integrated into your workflow. Active rules are rendered as solid lines, while disabled elements are rendered as dashed/transparent lines.

```python
# 1. Interactive Jupyter View (Base Model)
# Renders a drag-and-drop Cytoscape.js graph right inside your Notebook!
modelo.show_interactive()

# 2. Interactive Jupyter View (Full LTS)
# Renders the full Labelled Transition System tree (all reachable states)
modelo.show_all_steps_interactive()

# 3. Offline Static Images
# Renders a high-res graph using Matplotlib/NetworkX (no browser needed)
modelo.save_image_plt("output/my_graph.png")
```

---

## Model Manipulation & Analysis

ReForma isn't just for reading models; you can actively manipulate and analyze them:

```python
# --- Delta Cut (Pruning) ---
# Prunes all transitions and rules with a probability below 0.15
modelo.delta_cut(delta=0.15)

# --- Merge Models ---
# Merges the current model with another one, resolving conflicting weights 
# by taking the maximum value ("max"), or average ("arith").
modelo.merge_models(other_model_source, op_type="union", agg="max")

# --- Stats & Sanity Checks ---
print(modelo.get_stats())       # Counts total reachable states and transitions
print(modelo.check_problems())  # Hunts for deadlocks, unreachability, and rule inconsistencies
```

---

## Training with Event Logs

Train the model on a batch of sessions (lists of event labels) to automatically update the transition weights. Useful for Process Mining.

```python
# Train directly from a Python list
modelo.train([
    ["go_work", "easy_task", "easy_task", "go_home"],
    ["battery_low", "go_charge", "finish_charge", "socialize"],
])

# Or train from a log file (one session per line, comma-separated)
modelo.train_from_file("logs/sessions.txt")

# Save the updated model with the new calculated weights
modelo.save_source("model_trained.r")
```

---

## PDL / PCTL Verification

Evaluate probabilistic and dynamic logic formulas natively.

```python
# Quantitative: Probability of eventually reaching the Office
prob = modelo.check_pdl_value("Home", "{P=?[F Office]}")
print(f"P(reach Office from Home) = {prob:.4f}")

# Qualitative: Is it probable? (Returns True/False)
holds = modelo.check_pdl_value("Home", "{P>=0.4[F Office]}")

# PDL: Is there a path via go_work to Office?
holds = modelo.check_pdl_value("Home", "<go_work>Office")
```

### Formula Syntax Reference

| Formula                     | Meaning                                        |
|-----------------------------|------------------------------------------------|
| `{P=?[F target]}`           | Probability of eventually reaching `target`    |
| `{P=?[G safe]}`             | Probability of staying in `safe` forever       |
| `{P=?[X next]}`             | Probability of reaching `next` in exactly 1 step|
| `{P=?[a U b]}`              | Probability of `a` holding until `b` occurs    |
| `{P>=0.5[F target]}`        | Is the probability of reaching target ≥ 0.5?   |
| `<action>state`             | There exists a path via `action` to `state`    |
| `[action]state`             | All paths via `action` lead to `state`         |

---

## Full API Reference

You can access the full API reference at any time in your Python console by running:

```python
from reforma import ReForma
modelo = ReForma()
modelo.help()
```

### Main Methods:
* **Loading**: `load(source, name)`, `load_file(path)`, `reset()`
* **Simulation**: `step(label)`, `undo()`
* **Visualizations**: `show_interactive()`, `show_all_steps_interactive()`, `save_image_plt(path)`, `get_all_steps()`
* **Analysis**: `check_problems()`, `get_stats()`, `find_best_path(...)`
* **Manipulation**: `train(sessions)`, `train_from_file(path)`, `delta_cut(delta)`, `merge_models(...)`
* **Verification**: `check_pdl(state, form)`, `check_pdl_value(...)`
* **Exports**: `export_prism()`, `export_glts()`, `export_mcrl2()`, `save_source(path)`




# Re_lang — Reconfigurable Language Guide

**Re_lang** is the Domain-Specific Language (DSL) designed to define models for the ReForma library. 
Its main innovation lies in its ability to define **Reconfigurable Probabilistic Automata**, where transitions are not static: executing an action can dynamically enable, disable, or alter the probability of other actions in the future.

---

## 1. Basic Structure and Global Definitions

Every model starts with a name, variables (optional), and the definition of the initial state. Optionally, you can configure the mathematical mode for weight distribution.

```text
name MyModel

// (Optional) Defines how residual weights are redistributed after a rule modifies a probability.
// Options: normalize (default), equal, proportional
calibration proportional

// (Optional) Ignores hyper-rules math and calculates empirical frequencies instead
// training 

// Integer variables declaration (Optional)
int counter = 0
int flag = 1

// Initial State (Required)
init s0
```

---

## 2. Base Transitions (Simple Actions)

Base transitions define the static behavioral graph of the system. You can define them using a shorthand syntax or a full syntax if you need to separate the transition's unique ID from its label.

**Shorthand Syntax:** `Source ---> Target: label (probability) [aggregation] [disabled]`  
**Full Syntax (with ID):** `Source -act-> Target: label (probability) [aggregation] [disabled]`

```text
// Shorthand: Transition ID and Label are both 'work'
s0 ---> s1: work (0.8)

// Full Syntax: Explicitly separating the act from the Label
a -b-> c: d (0.5)

// In the example above:
// 'a' = Source state
// 'b' = act 
// 'c' = Target state
// 'd' = Label (Used by hyper-edges/dynamic rules to buff or debuff this action)

// Transition with implicit probability (assumes 1.0 or divides evenly among available options)
s1 ---> s2: rest

// Transition inactive by default (will need a dynamic rule to enable it later)
s2 ---> s3: emergency disabled
```
*Why use full syntax?* It is extremely useful when you have multiple paths between the same (or different) states that should respond to the exact same dynamic rule (sharing the same `label`), but you need to uniquely identify each path (using the act).

---

---

## 3. Hyper-Edges (Dynamic Rules)

The core of Re_lang. Rules define how the occurrence of an event alters the structure of the model itself. A hyper-edge links a **trigger label** to a **target label**.

**Syntax (Enable / Buff):** `Trigger ->> Target: RuleName (Strength)`  
**Syntax (Disable / Debuff):** `Trigger --! Target: RuleName (Strength)`

```text
// When 'battery_low' occurs, it enables and buffs the probability of 'go_charge' (strength 0.6)
battery_low ->> go_charge: chargeRule (0.6)

// When 'battery_low' occurs, it disables/debuffs the probability of 'go_work'
battery_low --! go_work: fatigueRule (0.4)
```


---

## 4. Aggregations (Rule Mathematics)

When a rule acts upon a transition, the final probability is calculated using an aggregation function that combines 3 values: the original weight of the target, the strength of the rule, and the weight of the trigger.

By default, it uses the arithmetic mean (`arith`), but you can specify others:
* `arith` : Arithmetic mean (Default)
* `max` : Chooses the maximum value.
* `min` : Chooses the minimum value.
* `prod` : Product of probabilities.
* `geom` : Geometric mean.

**Example:**
```text
// Uses 'max' to ensure that if the rule is very strong, it overrides the base probability.
stress ->> slack_off: ruleStress (0.9) max
```

---

## 5. Guards and Updates

Any transition (simple or rule) can have conditions to occur and can alter global variables.

**Supported Operators:** `==`, `!=`, `<=`, `>=`, `<`, `>`, `AND`, `OR`.

```text
int tasks = 0

// Transition with Guard and Update
office ---> home: go_home if (tasks >= 5 AND stress_level > 2) then {
    tasks' := 0
    stress_level' := stress_level - 1
}

// A dynamic rule can also be conditionally fired
finish_task ->> rest: reward if (tasks == 10)
```
*(Note: The variable being modified must have a prime `'` before the assignment operator `:=`, e.g., `tasks'`)*

---

## 6. Modules (Sub-Automata)

For larger models, you can encapsulate logic inside `aut` (module) blocks, creating namespaces. This prevents state name collisions in complex systems.

```text
name SecuritySystem

aut Sensor {
    init idle
    idle ---> active: detect_motion
}

aut Alarm {
    init off
    off ---> on: sound_alarm disabled
}

// Inter-modular rule: Motion in the Sensor enables the Alarm
Sensor.detect_motion ->> Alarm.sound_alarm: trigger_alert
```

---

## 7. Full Example: The Modern Worker

A model that brings all the concepts together:

```text
name AdvancedBot
calibration proportional

int energy = 10
int tasks_done = 0

init Home

// --- Base Behavior ---
Home ---> Office: go_work (0.5)
Home ---> Station: go_charge (0.5)

Office ---> Office: easy_task (0.7) if (energy > 0) then {
    tasks_done' := tasks_done + 1
    energy' := energy - 1
}
Office ---> Home: go_home (0.3)

// Maintenance states (Only available at Home)
Home ---> Home: battery_low
Station ---> Home: finish_charge (1.0) then {
    energy' := 10
}

// --- Dynamic Rules (Adaptation) ---

// If battery is low, force going to the station and block going to work
battery_low ->> go_charge: buff_charge (0.9) max
battery_low --! go_work: block_work (0.8)

// Upon finishing the charge, reset/enable going back to work
finish_charge ->> go_work: reset_work (0.5)
```
```

