"""
Script de teste para as novas funcionalidades da ReForma (RTA)
"""

from reforma import ReForma

# ==========================================
# 1. Definição de Modelos de Teste
# ==========================================

MODELO_A = """
name ModelA
init s0
int tasks = 0

s0 ---> s1: work_hard (0.8)
s0 ---> s2: slack_off (0.2)

s1 ---> s3: finish_early (1.0) tasks':= tasks + 1
s2 ---> s3: finish_late (1.0)

s3 ---> s3: loop (1.0)
"""

MODELO_B = """
name ModelB
init s0

s0 ---> s1: work_hard (0.5)
s0 ---> s2: slack_off (0.5)

s3 ---> s4: go_home (1.0)
s4 ---> s4: sleep (1.0)
"""

def section(title: str):
    print(f"\n{'-'*60}")
    print(f" {title.upper()}")
    print(f"{'-'*60}")

if __name__ == "__main__":
    
    # Iniciar a biblioteca
    modelo = ReForma()
    
    section("1. Testar o Help (Documentação)")
    modelo.help()
    
    section("2. Carregar o Modelo A")
    modelo.load(MODELO_A, name="ModelA")
    print("Modelo carregado com sucesso! Estado atual:")
    print(modelo.state.summary())
    
    section("3. Estatísticas e Verificação de Problemas")
    print("--- Estatísticas do LTS ---")
    print(modelo.get_stats())
    print("\n--- Verificação de Inconsistências / Problemas ---")
    print(modelo.check_problems())
    
    section("4. Procura do Melhor Caminho (Best Path)")
    # Objetivo: Chegar ao estado 's3' com a máxima probabilidade
    print("Procurar caminho mais provável para o estado 's3':")
    caminho_estado = modelo.find_best_path(target_type="state", target_value="s3", criterion="max")
    print(caminho_estado)
    
    print("\nProcurar caminho mais provável para cumprir a variável 'tasks == 1':")
    caminho_var = modelo.find_best_path(target_type="variable", target_value="tasks", target_int=1, criterion="max")
    print(caminho_var)

    section("5. Visualização em Texto e Mermaid (LTS Completo)")
    print("--- Texto Resumo do Estado Atual ---")
    print(modelo.get_current_state_text())
    
    print("\n--- Grafo Mermaid Completo (get_all_steps) ---")
    # Imprime apenas as primeiras 5 linhas para não encher o ecrã
    mermaid_lines = modelo.get_all_steps().splitlines()
    print("\n".join(mermaid_lines[:5]) + "\n...")
    
    section("6. Merge (Unir Modelo A com Modelo B)")
    # O Modelo B tem uma probabilidade diferente no início e transições novas a partir de s3
    print("Vamos fazer a união ('union') usando 'max' para resolver os pesos conflitantes.")
    modelo.merge_models(MODELO_B, op_type="union", agg="max")
    
    print("\nNovo código fonte gerado após a união:")
    print(modelo.source.strip())
    
    print("\nEstatísticas após a união (deve ter mais estados agora):")
    print(modelo.get_stats())
    
    section("7. Delta Cut (Poda)")
    print("Vamos cortar todas as arestas com probabilidade ABAIXO de 0.3")
    print("O 'slack_off' que originalment tinha p=0.2 (ou 0.5 após o max) deve sofrer o corte, dependendo do merge.")
    
    modelo.delta_cut(delta=0.6) # Cortar agressivamente abaixo de 0.6 para testar
    
    print("\nNovo código fonte após a poda agressiva (0.6):")
    print(modelo.source.strip())
    
    print("\nEstatísticas finais após o Delta Cut:")
    print(modelo.get_stats())
    
    print("\n✅ Testes concluídos com sucesso!")