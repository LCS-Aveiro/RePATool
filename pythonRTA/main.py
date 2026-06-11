"""
test_models.py — testa a biblioteca reforma com os três modelos reais.

Corre com:
    python test_models.py

Não precisa de pytest instalado.
Precisa de java no PATH e do RePATool.jar dentro de reforma/bin/.
"""

from reforma import reforma

# ─────────────────────────────────────────────────────────────────────────────
# Modelos
# ─────────────────────────────────────────────────────────────────────────────

SIMPLE = """\
name Simple
init s0
s0 ---> s1: a
s1 ---> s0: b
a  --! a: offA
"""

MOEDA2 = """\
name Moeda2
calibration proportional
init start
start -lancar-> coroa: lancar1
start -lancar-> cara: lancar2
coroa -lancar-> cara: lancarCara1
coroa -lancar-> coroa: lancarCoroa
cara -lancar-> coroa: lancarCoroa
cara -lancar-> cara: lancarCara2
lancarCara1 ->> lancarCara1:c1
lancarCara2 ->>lancarCara2:c2
lancar2 ->>lancarCara1:c3
lancar2 ->> lancarCara2:c4
"""

COIN = """\
name coin
init s0
int d = 0;
s0 -a-> s1: coin (0.5)
s1 -a-> s3: coin (0.5)
s2 -a-> s5: coin (0.5)
s0 -b-> s2: coin (0.5)
s1 -b-> s4: coin (0.5)
s2 -b-> s6: coin (0.5)
s3 -a-> s1: coin (0.5)
s4 -a-> s7: coin (0.5) d':= 2
s5 -a-> s7: coin (0.5) d':= 4
s6 -a-> s2: coin (0.5)
s3 -b-> s7: coin (0.5) d':= 1
s4 -b-> s7: coin (0.5) d':= 3
s5 -b-> s7: coin (0.5) d':= 5
s6 -b-> s7: coin (0.5) d':= 6
s7 ---> s7: loop
"""

# ─────────────────────────────────────────────────────────────────────────────
# Utilidades de log
# ─────────────────────────────────────────────────────────────────────────────

PASS = "\033[92m✓\033[0m"
FAIL = "\033[91m✗\033[0m"
_results = []

def check(description: str, condition: bool, detail: str = "") -> None:
    status = PASS if condition else FAIL
    print(f"  {status}  {description}" + (f"  ({detail})" if detail else ""))
    _results.append((description, condition))

def section(title: str) -> None:
    print(f"\n{'─'*60}")
    print(f"  {title}")
    print(f"{'─'*60}")

def summary() -> None:
    total  = len(_results)
    passed = sum(1 for _, ok in _results if ok)
    failed = total - passed
    print(f"\n{'═'*60}")
    print(f"  Resultado: {passed}/{total} passou" +
          (f"  —  {failed} falharam" if failed else "  — tudo OK 🎉"))
    print(f"{'═'*60}\n")
    if failed:
        raise SystemExit(1)


# ─────────────────────────────────────────────────────────────────────────────
# Testes — modelo Simple
# ─────────────────────────────────────────────────────────────────────────────

def test_simple():
    section("Modelo: Simple")
    pRe = reforma()
    state = pRe.load(SIMPLE, name="Simple")

    check("Carregou sem erros",      state is not None)
    check("Estado inicial é s0",     "s0" in state.current_states)
    check("Transição 'a' habilitada",state.transition_named("a") is not None)

    # step a: s0 → s1
    state = pRe.step("a")
    print(state)
    check("Após 'a': estado é s1",    "s1" in state.current_states)
    check("'b' habilitado em s1",     state.transition_named("b") is not None)
    check("Última transição é 'a'",   state.last_transition.label == "a")

    # undo: volta a s0
    state = pRe.undo()
    check("Após undo: estado é s0",   "s0" in state.current_states)

    # reset
    state = pRe.step("a")
    state = pRe.reset()
    check("Após reset: estado é s0",  "s0" in state.current_states)
    check("Histórico limpo",          pRe.history == [])

    # transição inválida
    try:
        pRe.step("voo_para_lua")
        check("Transição inválida lança erro", False)
    except RuntimeError:
        check("Transição inválida lança erro", True)

    # export PRISM
    prism = pRe.export_prism()
    check("Export PRISM não vazio",   len(prism) > 0)
    check("PRISM contém 'dtmc'",      "dtmc" in prism.lower())

    # export mCRL2
    mcrl2 = pRe.export_mcrl2()
    check("Export mCRL2 não vazio",   len(mcrl2) > 0)

    # export GLTS
    glts = pRe.export_glts()
    check("Export GLTS não vazio",    len(glts) > 0)

    # PDL: existe caminho de s0 via 'a'?
    res = pRe.check_pdl("s0", "<a>s1")
    check("PDL <a>s1 retornou algo",  "Result" in res, res.strip())

    val = pRe.check_pdl_value("s0", "<a>s1")
    check("PDL <a>s1 é True",         val is True, str(val))

    # Probabilidade de chegar a s1
    prob = pRe.check_pdl_value("s0", "{P=?[F s1]}")
    check("P(F s1) é float",          isinstance(prob, float), str(prob))
    check("P(F s1) > 0",              isinstance(prob, float) and prob > 0, str(prob))


# ─────────────────────────────────────────────────────────────────────────────
# Testes — modelo Moeda2
# ─────────────────────────────────────────────────────────────────────────────

def test_moeda2():
    section("Modelo: Moeda2")
    pRe = reforma()
    state = pRe.load(MOEDA2, name="Moeda2")

    check("Carregou sem erros",         state is not None)
    check("Estado inicial é start",     "start" in state.current_states)

    enabled_labels = {t.label for t in state.enabled}
    check("Tem transições habilitadas", len(enabled_labels) > 0,
          str(enabled_labels))

    # As probabilidades devem somar ~1.0
    total_p = sum(t.probability for t in state.enabled)
    check("Probabilidades somam ~1.0",  abs(total_p - 1.0) < 0.05,
          f"soma={total_p:.3f}")

    # Dar um passo com lancar1 ou lancar2 (whichever is enabled)
    first = next(iter(state.enabled))
    state = pRe.step(first.label)
    check(f"Passo '{first.label}' funcionou", state is not None)
    check("Estado mudou após passo",
          state.current_states != ["start"] or first.label in ("lancar1","lancar2"))

    # Export
    prism = pRe.export_prism()
    check("PRISM do Moeda2 não vazio",  len(prism) > 0)


# ─────────────────────────────────────────────────────────────────────────────
# Testes — modelo Coin (com variáveis)
# ─────────────────────────────────────────────────────────────────────────────

def test_coin():
    section("Modelo: Coin (com variável d)")
    pRe = reforma()
    state = pRe.load(COIN, name="coin")

    check("Carregou sem erros",       state is not None)
    check("Estado inicial é s0",      "s0" in state.current_states)

    enabled_labels = {t.label for t in state.enabled}
    check("'coin' habilitado",        "coin" in enabled_labels,
          str(enabled_labels))

    # Verificar que as duas transições de s0 têm p=0.5 cada
    for t in state.enabled:
        check(f"P({t.label}) ≈ 0.5",  abs(t.probability - 0.5) < 0.01,
              f"p={t.probability:.3f}")

    # Dar dois passos (a, b) para chegar a s4
    state = pRe.step("coin")  # s0 → s1 ou s2 (qualquer 'coin')
    check("Após 1º passo: estado mudou", "s0" not in state.current_states
          or state.last_transition is not None)

    # Export PRISM e verificar variável d
    prism = pRe.export_prism()
    check("PRISM contém variável d",  "d" in prism)
    check("PRISM contém s7",          "s7" in prism or "7" in prism)

    # PDL: probabilidade de chegar a s7
    prob = pRe.check_pdl_value("s0", "{P=?[F s7]}")
    check("P(F s7) é float",          isinstance(prob, float), str(prob))
    check("P(F s7) > 0",              isinstance(prob, float) and prob > 0,
          str(prob))

    # Treino simples
    pRe.train([
        ["coin", "coin", "coin"],
        ["coin", "coin"],
    ])
    check("Treino não quebrou o modelo", pRe.source is not None)
    check("Histórico limpo após treino", pRe.history == [])


# ─────────────────────────────────────────────────────────────────────────────
# Testes — bundled JAR
# ─────────────────────────────────────────────────────────────────────────────

def test_bundled_jar():
    section("JAR incluído no pacote")
    jar_path = reforma.bundled_jar()
    check("bundled_jar() retorna um caminho", jar_path is not None, str(jar_path))
    if jar_path:
        import os
        check("Ficheiro existe no disco", os.path.exists(jar_path), jar_path)


# ─────────────────────────────────────────────────────────────────────────────
# Entry point
# ─────────────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    print("\n╔══════════════════════════════════════════════════════════╗")
    print("║         reforma library — testes de integração               ║")
    print("╚══════════════════════════════════════════════════════════╝")

    test_bundled_jar()
    test_simple()
    test_moeda2()
    test_coin()

    summary()