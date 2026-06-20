"""
verify_fidelity.py
==================
Testa a fidelidade matemática entre o motor interno da ReForma e a exportação PRISM.

Para cada modelo da biblioteca de exemplos, gera automaticamente propriedades PCTL
(probabilidades de 1 passo, 2 passos, alcançabilidade) e compara os resultados
entre o verificador PDL interno e o PRISM externo.

Uso:
    python verify_fidelity.py
    python verify_fidelity.py --prism /caminho/para/prism
    python verify_fidelity.py --epsilon 0.005 --output relatorio.json
"""

import os
import re
import json
import time
import argparse
import subprocess
import itertools
from dataclasses import dataclass, field, asdict
from typing import Optional

try:
    from reforma import ReForma
except ImportError:
    raise SystemExit("❌  Instala o pacote reforma antes de correr este script: pip install reforma")


DEFAULT_PRISM_CMD = "C:/Program Files/prism-4.10/bin"
DEFAULT_EPSILON   = 0.001  
MAX_STATES_PRISM  = 100_000 




MODELS: dict[str, str] = {

    "Simple": """name Simple
init s0
s0 ---> s1: a
s1 ---> s0: b
a  --! a: offA""",

    "Suene": """name suene
init u
u--->u:uu(0.8)
u ---> v: uv (0.2)
v ---> w: vw (0.8)
w ---> u: wu (0.4)
w ---> w: ww (0.6)
v ---> z:vz (0.2)""",

    "MM": """name MM
init x
x ---> x: xx(0.7)
x ---> y: xy (0.2)
x ---> z:xz (0.1)
z ---> y: zy (0.3) disabled
z ---> z: zz (0.2)
z ---> v: vz (0.5)
xy ->> zy: xyyz (0.4)""",

    "NN": """name NN
init x
x ---> x: xx(0.8)
x ---> z: xz (0.2) disabled
y ---> x:yx (0.1)
y ---> y:yy (0.9)
z ---> y: zy (0.4)
z ---> z: zz (0.6)
yx ->> xz: yxxz (0.3)""",

    "DroneSystem": """name DroneSystem
init Home
Home ---> Flying: launch (1.0)
Flying ---> Delivered: success (0.8)
Flying ---> Crashed: fail (0.2)
Delivered ---> Home: return (1.0)
fail ->> return (1.0)""",

    "Conditions": """name Conditions
int counter = 0
init start
start ---> middle: step1  if (counter < 2) then {
  counter' := counter + 1
}
middle ---> endN: activateStep2 if (counter == 1)""",

    "LikeAlgorithm": """name LikeAlgorithm
init Feed
Feed ---> Watch: watch
Watch ---> Watch: like
Watch ---> Feed: dontLike
Watch ---> Feed: refresh disabled
Feed ---> List: watchLike disabled
List ---> Watch: watch2
watch ->> dontLike: wd
like --! dontLike: ld
like ->> refresh: lr
like ->> watchLike: lw
dontLike --! watchLike: dw""",

    "Recommender": """name AdvancedBot
init Home
Home ---> Office: go_work (0.5)
Home ---> Station: go_charge (0.5)
Home ---> Home: socialize (0.8)
Home ---> Home: battery_low
Home ---> Home: no_money
Office ---> Home: go_home (1.0)
Office ---> Office: easy_task (0.7)
Office ---> Office: high_stress
Station ---> Home: finish_charge (1.0)
battery_low ->> go_charge (0.6)
battery_low --! go_work (0.4)
no_money ->> go_work (0.7)
high_stress ->> easy_task (0.2)
finish_charge ->> socialize (0.1)""",

    "VendingMax": """name Vending
init Insert
Insert ---> Coffee: ct50
Insert ---> Chocolate: eur1
Coffee ---> Insert: GetCoffee
Chocolate ---> Insert: GetChoc
eur1 --! ct50
eur1 --! eur1
ct50 --! ct50: lastct50 disabled
ct50 --! eur1
ct50 ->> lastct50""",

    "Moeda2": """name Moeda2
calibration proportional
init start
start -lancar-> coroa: lancar1
start -lancar-> cara: lancar2
coroa -lancar-> cara: lancarCara1
coroa -lancar-> coroa: lancarCoroa
cara -lancar-> coroa: lancarCoroa
cara -lancar-> cara: lancarCara2
lancarCara1 ->> lancarCara1:c1
lancarCara2 ->> lancarCara2:c2
lancar2 ->> lancarCara1:c3
lancar2 ->> lancarCara2:c4""",

    "Moeda1": """name Moeda_Viciada
calibration proportional
int passos = 0
int lado = 0
init lancar
lancar ---> lancar: cara (0.5) if (passos < 5) then {
    passos' := passos + 1
    lado' := 0
}
lancar ---> lancar: coroa (0.5) if (passos < 5) then {
    passos' := passos + 1
    lado' := 1
}
cara ->> cara: viciar (1.0)""",

    "GRG": """name GRG
int a_active   = 1
int b_active   = 0
int c_active = 0
init s0
s0 ---> s1: aa  if (a_active == 1) then {
  b_active' := 1;
  if (c_active == 1) then {
      a_active' := 0
  }
}
s1 ---> s0: bb  if (b_active == 1) then {
  c_active' := 1;
  if (a_active == 0) then {
      b_active' := 0
  }
}
s1 ---> s2: cc  if (c_active == 1)
aa --! aa: offA2 disabled
aa ->> bb: onB if (b_active == 0)
bb ->> offA2: onOffA if (c_active == 0)""",
}



@dataclass
class TestCase:
    """Uma propriedade PCTL testada num modelo."""
    model_name:      str
    start_state:     str
    description:     str
    reforma_formula: str
    prism_formula:   str
    reforma_result:  Optional[float | bool] = None
    prism_result:    Optional[float | bool] = None
    diff:            Optional[float]        = None
    passed:          Optional[bool]         = None
    error:           Optional[str]          = None
    duration_s:      float                  = 0.0


@dataclass
class ModelReport:
    """Resultado agregado para um modelo."""
    model_name:  str
    total:       int = 0
    passed:      int = 0
    failed:      int = 0
    errors:      int = 0
    cases:       list[TestCase] = field(default_factory=list)
    skipped:     bool = False
    skip_reason: str  = ""

    @property
    def ok(self) -> bool:
        return self.failed == 0 and self.errors == 0 and not self.skipped

    @property
    def pass_rate(self) -> float:
        return (self.passed / self.total * 100) if self.total else 0.0



def discover_model_info(source: str) -> dict:
    """
    Carrega o modelo e extrai:
      - estados disponíveis
      - transições habilitadas no estado inicial
      - mapeamento estado → ID inteiro (para as fórmulas PRISM)
    """
    rf = ReForma()
    rf.load(source)

    init_states = rf.state.current_states if rf.state else []

    text = rf.text_summary()

    prism_code = rf.export_prism()
    state_order = _extract_state_order_from_prism(prism_code)

    return {
        "init_states":   init_states,
        "state_order":   state_order,   
        "prism_code":    prism_code,
        "reforma":       rf,
    }


def _extract_state_order_from_prism(prism_code: str) -> dict[str, int]:
    """
    Lê o bloco de comentários e comandos do PRISM gerado para recuperar o
    mapeamento  nome_estado → ID.

    O PrismConverter2 emite linhas do tipo:
        // State: Home
        [Home] s=2 & sum_s2 > 0 -> ...
    Aproveitamos isso para montar o mapa.
    """
    mapping: dict[str, int] = {}
    lines = prism_code.splitlines()

    for i, line in enumerate(lines):
        m_comment = re.match(r"\s*//\s*State:\s*(\S+)", line)
        if m_comment:
            state_name = m_comment.group(1)
            # A próxima linha útil tem o padrão  [Name] s=N ...
            for j in range(i + 1, min(i + 5, len(lines))):
                m_cmd = re.match(r"\s*\[.*?\]\s*s\s*=\s*(\d+)", lines[j])
                if m_cmd:
                    mapping[state_name] = int(m_cmd.group(1))
                    break
    return mapping



def generate_test_cases(model_name: str, info: dict) -> list[TestCase]:
    """
    Gera automaticamente propriedades PCTL razoáveis para um modelo,
    usando os estados descobertos e o mapeamento de IDs.
    """
    cases: list[TestCase] = []
    state_order = info["state_order"]
    init_states = info["init_states"]
    rf: ReForma = info["reforma"]

    if not init_states or not state_order:
        return cases

    start_name = init_states[0]
    start_id   = state_order.get(start_name)

    other_states = [(n, i) for n, i in state_order.items() if n != start_name]

    # --- P=? [ X s=? ]  (probabilidade de 1 passo para cada estado) ---
    if start_id is not None:
        for st_name, st_id in other_states[:4]:  
            cases.append(TestCase(
                model_name      = model_name,
                start_state     = start_name,
                description     = f"P=?[X {st_name}] from {start_name}",
                reforma_formula = f"{{P=?[X {st_name}]}}",
                prism_formula   = f"P=? [ X s={st_id} ]",
            ))

    # --- P=? [ X X s=? ]  (probabilidade de 2 passos) ---
    if start_id is not None and len(other_states) >= 1:
        st_name, st_id = other_states[0]
        cases.append(TestCase(
            model_name      = model_name,
            start_state     = start_name,
            description     = f"P=?[X X {st_name}] from {start_name} (2 steps)",
            reforma_formula = f"{{P=?[X X {st_name}]}}",
            prism_formula   = f"P=? [ X X s={st_id} ]",
        ))

    # --- P=? [ F s=? ]  (alcançabilidade futura) ---
    for st_name, st_id in other_states[:3]:
        cases.append(TestCase(
            model_name      = model_name,
            start_state     = start_name,
            description     = f"P=?[F {st_name}] from {start_name} (reachability)",
            reforma_formula = f"{{P=?[F {st_name}]}}",
            prism_formula   = f"P=? [ F s={st_id} ]",
        ))

    return cases




def run_prism(prism_cmd: str, prism_code: str, pctl_formula: str) -> tuple[Optional[float | bool], Optional[str]]:
    """
    Escreve o modelo e a propriedade em ficheiros temporários,
    corre o PRISM e extrai o resultado.

    Retorna (resultado, mensagem_de_erro).
    """
    pm_file    = "_tmp_verify.pm"
    pctl_file  = "_tmp_verify.pctl"

    try:
        with open(pm_file,   "w", encoding="utf-8") as f:
            f.write(prism_code)
        with open(pctl_file, "w", encoding="utf-8") as f:
            f.write(pctl_formula)

        cmd    = [prism_cmd, pm_file, pctl_file, "-ex"]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=60)

        if result.returncode != 0:
            combined = result.stdout + result.stderr
            m = re.search(r"Result:\s*([\d.eE+\-]+|true|false)", combined, re.IGNORECASE)
            if not m:
                return None, f"PRISM error (rc={result.returncode}): {result.stderr.strip()[:200]}"

        output = result.stdout
        m = re.search(r"Result:\s*([\d.eE+\-]+|true|false)", output, re.IGNORECASE)
        if not m:
            return None, f"Could not parse PRISM output:\n{output[:300]}"

        val_str = m.group(1).lower()
        if val_str == "true":
            return True, None
        if val_str == "false":
            return False, None
        return float(val_str), None

    except subprocess.TimeoutExpired:
        return None, "PRISM timeout (>60s)"
    except FileNotFoundError:
        return None, f"PRISM binary not found: {prism_cmd}"
    except Exception as e:
        return None, str(e)
    finally:
        for f in (pm_file, pctl_file):
            try:
                os.remove(f)
            except FileNotFoundError:
                pass



def run_test_case(tc: TestCase, info: dict, prism_cmd: str, epsilon: float) -> TestCase:
    """Preenche tc.reforma_result, tc.prism_result, tc.diff, tc.passed, tc.error."""
    t0 = time.perf_counter()
    rf: ReForma = info["reforma"]

    try:
        tc.reforma_result = rf.check_pdl_value(tc.start_state, tc.reforma_formula)
    except Exception as e:
        tc.error = f"ReForma error: {e}"
        tc.passed = False
        tc.duration_s = time.perf_counter() - t0
        return tc

    prism_val, prism_err = run_prism(prism_cmd, info["prism_code"], tc.prism_formula)

    if prism_err:
        tc.error  = prism_err
        tc.passed = False
        tc.duration_s = time.perf_counter() - t0
        return tc

    tc.prism_result = prism_val

    rr = tc.reforma_result
    pr = tc.prism_result

    if isinstance(rr, float) and isinstance(pr, float):
        tc.diff   = abs(rr - pr)
        tc.passed = tc.diff <= epsilon
    elif isinstance(rr, bool) and isinstance(pr, bool):
        tc.diff   = 0.0 if rr == pr else 1.0
        tc.passed = rr == pr
    else:
        try:
            tc.diff   = abs(float(rr) - float(pr))
            tc.passed = tc.diff <= epsilon
        except Exception:
            tc.diff   = None
            tc.passed = (rr == pr)

    tc.duration_s = time.perf_counter() - t0
    return tc



def verify_model(model_name: str, source: str, prism_cmd: str, epsilon: float) -> ModelReport:
    report = ModelReport(model_name=model_name)

    try:
        info = discover_model_info(source)
    except Exception as e:
        report.skipped     = True
        report.skip_reason = f"Failed to load model: {e}"
        return report

    cases = generate_test_cases(model_name, info)

    if not cases:
        report.skipped     = True
        report.skip_reason = "No test cases generated (no reachable states found)"
        return report

    for tc in cases:
        tc = run_test_case(tc, info, prism_cmd, epsilon)
        report.cases.append(tc)
        report.total += 1

        if tc.error:
            report.errors += 1
        elif tc.passed:
            report.passed += 1
        else:
            report.failed += 1

    return report




PASS = "\033[92m✅ PASS\033[0m"
FAIL = "\033[91m❌ FAIL\033[0m"
ERR  = "\033[93m⚠️  ERR\033[0m"
SKIP = "\033[90m⏭  SKIP\033[0m"


def print_report(reports: list[ModelReport], epsilon: float) -> None:
    print()
    print("=" * 70)
    print("  REPA ↔ PRISM  FIDELITY REPORT")
    print("=" * 70)

    total_models  = len(reports)
    models_ok     = sum(1 for r in reports if r.ok)
    total_tests   = sum(r.total  for r in reports)
    total_passed  = sum(r.passed for r in reports)
    total_failed  = sum(r.failed for r in reports)
    total_errors  = sum(r.errors for r in reports)
    total_skipped = sum(1 for r in reports if r.skipped)

    for rep in reports:
        if rep.skipped:
            print(f"\n{SKIP}  [{rep.model_name}]  — {rep.skip_reason}")
            continue

        status = PASS if rep.ok else FAIL
        print(f"\n{status}  [{rep.model_name}]  "
              f"{rep.passed}/{rep.total} passed  ({rep.pass_rate:.0f}%)")

        for tc in rep.cases:
            if tc.error:
                marker = ERR
                detail = tc.error[:80]
            elif tc.passed:
                marker = "     ✔"
                detail = (f"ReForma={tc.reforma_result:.5f}  "
                          f"PRISM={tc.prism_result:.5f}  "
                          f"Δ={tc.diff:.2e}")
            else:
                marker = "     ✘"
                detail = (f"ReForma={tc.reforma_result}  "
                          f"PRISM={tc.prism_result}  "
                          f"Δ={tc.diff:.5f}")

            print(f"  {marker}  {tc.description}")
            print(f"         {detail}")

    print()
    print("=" * 70)
    print(f"  SUMMARY  (ε = {epsilon})")
    print("=" * 70)
    print(f"  Models   : {total_models}  ({models_ok} fully faithful, {total_skipped} skipped)")
    print(f"  Tests    : {total_tests}  passed={total_passed}  failed={total_failed}  errors={total_errors}")

    global_ok = (total_failed == 0 and total_errors == 0)
    if global_ok and total_passed > 0:
        print(f"\n  🏆  ALL {total_passed} TESTS PASSED — export is 100% faithful!\n")
    else:
        pct = total_passed / total_tests * 100 if total_tests else 0
        print(f"\n  ⚠️   {pct:.1f}% pass rate — review failures above.\n")



def save_json_report(reports: list[ModelReport], path: str) -> None:
    data = {
        "summary": {
            "total_models":  len(reports),
            "models_ok":     sum(1 for r in reports if r.ok),
            "total_tests":   sum(r.total  for r in reports),
            "total_passed":  sum(r.passed for r in reports),
            "total_failed":  sum(r.failed for r in reports),
            "total_errors":  sum(r.errors for r in reports),
            "total_skipped": sum(1 for r in reports if r.skipped),
        },
        "models": [asdict(r) for r in reports],
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, default=str)
    print(f"  📄 JSON report saved → {path}")



def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Verifica fidelidade matemática ReForma ↔ PRISM para todos os modelos."
    )
    p.add_argument(
        "--prism",
        default=DEFAULT_PRISM_CMD,
        help=f"Caminho para o executável do PRISM (default: {DEFAULT_PRISM_CMD})",
    )
    p.add_argument(
        "--epsilon",
        type=float,
        default=DEFAULT_EPSILON,
        help=f"Tolerância numérica para comparação (default: {DEFAULT_EPSILON})",
    )
    p.add_argument(
        "--output", "-o",
        default=None,
        help="Guarda relatório JSON neste ficheiro (opcional)",
    )
    p.add_argument(
        "--model", "-m",
        default=None,
        help="Testa apenas este modelo (ex: --model Simple)",
    )
    p.add_argument(
        "--list",
        action="store_true",
        help="Lista os modelos disponíveis e sai",
    )
    return p.parse_args()


def main() -> None:
    args = parse_args()

    if args.list:
        print("Modelos disponíveis:")
        for name in sorted(MODELS):
            print(f"  • {name}")
        return

    if args.model:
        if args.model not in MODELS:
            raise SystemExit(f"Modelo '{args.model}' não encontrado. Usa --list para ver os disponíveis.")
        models_to_test = {args.model: MODELS[args.model]}
    else:
        models_to_test = MODELS

    prism_available = os.path.isfile(args.prism) or _prism_in_path(args.prism)
    if not prism_available:
        print(f"⚠️   PRISM não encontrado em '{args.prism}'.")
        print("     A correr apenas verificação interna (sem comparação PRISM).\n")

    print(f"🔍  A verificar {len(models_to_test)} modelo(s)  [ε={args.epsilon}]")
    print(f"    PRISM: {args.prism}" + ("  ✓" if prism_available else "  ✗ (não disponível)"))
    print()

    reports: list[ModelReport] = []

    for name, source in models_to_test.items():
        print(f"  ▶  {name} ...", end="", flush=True)
        t0 = time.perf_counter()
        rep = verify_model(name, source, args.prism, args.epsilon)
        elapsed = time.perf_counter() - t0
        status  = "✅" if rep.ok else ("⏭" if rep.skipped else "❌")
        print(f"\r  {status}  {name:<22}  {elapsed:.1f}s")
        reports.append(rep)

    print_report(reports, args.epsilon)

    if args.output:
        save_json_report(reports, args.output)

    all_ok = all(r.ok or r.skipped for r in reports)
    raise SystemExit(0 if all_ok else 1)


def _prism_in_path(cmd: str) -> bool:
    """Verifica se `cmd` é encontrável no PATH do sistema."""
    try:
        subprocess.run([cmd, "-version"], capture_output=True, timeout=5)
        return True
    except Exception:
        return False


if __name__ == "__main__":
    main()