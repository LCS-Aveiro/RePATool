import re
from collections import defaultdict, deque

def find_all_effects_with_depth(start_actor, all_rules):
    """
    BFS que só encadeia em regras de enable (->>).
    Retorna lista de (rule, depth), sem duplicar gatilhos.
    """
    queue = deque([(start_actor, 0)])
    visited_triggers = {start_actor}
    triggered_rules_with_depth = []

    while queue:
        current_trigger, current_depth = queue.popleft()

        for rule in all_rules.get(current_trigger, []):
            triggered_rules_with_depth.append((rule, current_depth))

            if rule['type'] == 'enable':
                next_trigger = rule['name']
                if next_trigger not in visited_triggers:
                    visited_triggers.add(next_trigger)
                    queue.append((next_trigger, current_depth + 1))

    return triggered_rules_with_depth


def translate_syntax(input_script):
    """
    Converte a sintaxe declarativa para imperativa, respeitando a ordem
    causal dos efeitos em cascata. (Versão Definitiva)
    """
    initial_state = None
    actors = {}
    edges = []
    rules = defaultdict(list)
    all_lines = input_script.strip().split('\n')

    for line in all_lines:
        line = line.strip()
        if not line or line.startswith("init"): continue

        match_full = re.match(r"([\w_]+)\s*(-->|->>|--!)\s*([\w_]+):\s*([\w_]+)(\s+disabled)?", line)
        match_short = re.match(r"([\w_]+)\s*(-->|->>|--!)\s*([\w_]+)(\s+disabled)?$", line)
        
        actor_name = None
        is_disabled = False
        
        if match_full:
            _, op, _, name, disabled_flag = match_full.groups()
            actor_name = name
            is_disabled = (disabled_flag is not None)
            if op == '-->':
                 if actor_name not in actors: actors[actor_name] = 1
        elif match_short:
            _, op, target, disabled_flag = match_short.groups()
            actor_name = target
            is_disabled = (disabled_flag is not None)

        if actor_name:
            if actor_name not in actors:
                 actors[actor_name] = 0 if is_disabled else 1
            elif is_disabled:
                 actors[actor_name] = 0

    for line in all_lines:
        line = line.strip()
        if not line: continue

        if line.startswith("init"):
            initial_state = line.split()[1]
            continue

        match_full = re.match(r"([\w_]+)\s*(-->|->>|--!)\s*([\w_]+):\s*([\w_]+)(\s+disabled)?", line)
        match_short = re.match(r"([\w_]+)\s*(-->|->>|--!)\s*([\w_]+)(\s+disabled)?$", line)

        source, op, target, name = (None,) * 4
        if match_full:
            source, op, target, name, _ = match_full.groups()
        elif match_short:
            source, op, target, _ = match_short.groups()
            name = target
        else: continue
        
        if op == '-->':
            edges.append({'source': source, 'target': target, 'action': name})
            if name not in actors: actors[name] = 1
        else:
            rule_type = 'enable' if op == '->>' else 'disable'
            trigger = source
            rules[trigger].append({'type': rule_type, 'target': target, 'name': name})

    output = []
    for actor_name in sorted(actors.keys()):
        output.append(f"int {actor_name}_active = {actors[actor_name]}")
    output.append("")
    if initial_state:
        output.append(f"init {initial_state}")
        output.append("")

    for edge in edges:
        action = edge['action']
        line_out = f"{edge['source']} --> {edge['target']}: {action} if ({action}_active == 1)"

        effects_with_depth = find_all_effects_with_depth(action, rules)
        
        if not effects_with_depth:
            output.append(line_out)
            continue
            
        sorted_effects = sorted(effects_with_depth, key=lambda item: (item[1], item[0]['name']))
        line_out += " then {"
        output.append(line_out)

        for rule, depth in sorted_effects:
            target_val = 1 if rule['type'] == 'enable' else 0
            if rule['name'] == rule['target']:
                output.append(f"    {rule['target']}_active' := {target_val}")
            else:
                output.append(f"    if ({rule['name']}_active == 1) then {{")
                output.append(f"        {rule['target']}_active' := {target_val}")
                output.append(f"    }}")
        output.append("}")

    return "\n".join(output)

input_script = """
init s0
s0 --> s0: act
act --! act: offAct disabled
act ->> offAct: on1 disabled
act ->> on1
"""

output_script = translate_syntax(input_script)
print(output_script)