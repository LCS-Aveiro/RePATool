import re
import xml.etree.ElementTree as ET
from xml.dom import minidom

def convert_to_uppaal_xml_functions_always(input_text):
    """
    Converte uma descrição textual de um autômato para o formato XML do Uppaal,
    SEMPRE criando uma função para qualquer bloco de atualização não vazio.

    Args:
        input_text: Uma string contendo a descrição do autômato.

    Returns:
        Uma string contendo o XML formatado e válido para o Uppaal.
    """
    parts = re.split(r'\n\s*init\s+\w+\s*\n', input_text, 1)
    declarations_part = parts[0]
    transitions_part = parts[1] if len(parts) > 1 else ""

    variable_declarations = "\n".join([
        f"{decl};" for decl in re.findall(r'int\s+\w+\s*=\s*\d+', declarations_part)
    ])

    init_state_match = re.search(r'init\s+(\w+)', input_text)
    init_state_name = init_state_match.group(1) if init_state_match else None

    
    transition_strings = re.split(r'\n(?=\w+\s*-->)', transitions_part.strip())
    transition_pattern = r'(\w+)\s*-->\s*(\w+):\s*(\w*)\s*if\s*\((.*?)\)\s*then\s*\{(.*)\}'
    
    generated_functions = []
    function_counter = 0
    processed_transitions = []

    def format_code_block(block_text):
        """Formata um bloco de código de múltiplas linhas para a sintaxe do Uppaal."""
        lines = [line.strip() for line in block_text.split('\n') if line.strip()]
        formatted_lines = []
        for line in lines:
            formatted_line = re.sub(r"(\w+)'\s*:=\s*(.*)", r"\1 = \2;", line)
            formatted_line = formatted_line.replace("then", "")
            formatted_lines.append(formatted_line)
        return "\n\t".join(formatted_lines)

    for trans_str in transition_strings:
        if not trans_str.strip():
            continue

        match = re.search(transition_pattern, trans_str, re.DOTALL)
        if not match:
            continue
        
        source, target, event, guard, assignment_block = match.groups()
        assignment_block = assignment_block.strip()
        final_assignment = ""

        if assignment_block:
            function_name = f"update_{source}_to_{target}_{function_counter}"
            function_counter += 1
            
            function_body = format_code_block(assignment_block)
            
            function_definition = f"void {function_name}() {{\n\t{function_body}\n}}"
            generated_functions.append(function_definition)
            
            final_assignment = f"{function_name}()"

        processed_transitions.append((source, target, event, guard, final_assignment))

    nta = ET.Element('nta')
    declaration = ET.SubElement(nta, 'declaration')
    
    declaration_text = f"// Place global declarations here.\n\n{variable_declarations}"
    if generated_functions:
        declaration_text += "\n\n// Auto-generated functions for transition logic\n"
        declaration_text += "\n\n".join(generated_functions)
    declaration.text = declaration_text

    template = ET.SubElement(nta, 'template')
    ET.SubElement(template, 'name', x="5", y="5").text = "Template"
    ET.SubElement(template, 'declaration').text = "// Place local declarations here.\n"

    states = set()
    if init_state_name: states.add(init_state_name)
    for s, t, _, _, _ in processed_transitions:
        states.add(s)
        states.add(t)

    state_map = {}
    x_coord = -600
    for i, state_name in enumerate(sorted(list(states))):
        state_id = f"id{i}"
        state_map[state_name] = state_id
        location = ET.SubElement(template, 'location', id=state_id, x=str(x_coord), y="-150")
        ET.SubElement(location, 'name', x=str(x_coord - 20), y="-180").text = state_name
        x_coord += 250

    if init_state_name:
        ET.SubElement(template, 'init', ref=state_map[init_state_name])

    for i, (source, target, event, guard, assignment) in enumerate(processed_transitions):
        transition = ET.SubElement(template, 'transition', id=f"id{len(states) + i}")
        ET.SubElement(transition, 'source', ref=state_map[source])
        ET.SubElement(transition, 'target', ref=state_map[target])
        
        y_offset = -120
        
        if guard:
            ET.SubElement(transition, 'label', kind="guard", x="-550", y=str(y_offset)).text = guard.strip()
            y_offset += 25

        if assignment:
            ET.SubElement(transition, 'label', kind="assignment", x="-550", y=str(y_offset)).text = assignment
            
        ET.SubElement(transition, 'nail', x="-550", y="-100")

    system = ET.SubElement(nta, 'system')
    system.text = "// Place template instantiations here.\nProcess = Template();\n// List one or more processes to be composed into a system.\nsystem Process;\n"

    queries = ET.SubElement(nta, 'queries')
    query = ET.SubElement(queries, 'query')
    ET.SubElement(query, 'formula')
    ET.SubElement(query, 'comment')

    xml_string = ET.tostring(nta, 'utf-8')
    reparsed = minidom.parseString(xml_string)
    
    doctype = minidom.DOMImplementation().createDocumentType(
        "nta",
        "-//Uppaal Team//DTD Flat System 1.6//EN",
        "http://www.it.uu.se/research/group/darts/uppaal/flat-1_6.dtd"
    )
    reparsed.insertBefore(doctype, reparsed.documentElement)

    return reparsed.toprettyxml(indent="\t")


input_graph = """
int a_active = 1
int b_active = 1
int offA_active = 1

init s0

s0 --> s1: a if (a_active == 1) then {
    if (offA_active == 1) then {
        a_active' := 0
    }
}
s1 --> s0: b if (b_active == 1) then {}
"""

uppaal_xml = convert_to_uppaal_xml_functions_always(input_graph)
print(uppaal_xml)

with open("maquina_cafe_final.xml", "w", encoding="utf-8") as f:
    f.write(uppaal_xml)

print("\nArquivo 'maquina_cafe_final.xml' gerado com sucesso!")