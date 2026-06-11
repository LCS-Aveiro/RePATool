"""
tests/test_reforma.py — full test suite (mocked, no real JAR needed).
"""

import pytest
from unittest.mock import MagicMock, patch

from reforma import reforma

from reforma.model import reformaModel, Transition, SimulationState
from reforma.jar_bridge import JarBridge, JarError
from reforma.client import _parse_pdl_result

# ---------------------------------------------------------------------------
SIMPLE_SOURCE = """\
name Simple
init Home
Home ---> Office: go_work (0.5)
Home ---> Station: go_charge (0.5)
Office ---> Home: go_home (1.0)
"""
STEP_OUTPUT = """\
Estado Atual: Home
Transicoes Habilitadas:
  - [go_work] de Home para Office (P=0.500)
  - [go_charge] de Home para Station (P=0.500)
"""
STEP_OUTPUT_OFFICE = """\
Estado Atual: Office
Transicoes Habilitadas:
  - [go_home] de Office para Home (P=1.000)
"""


def make_reforma(step_output: str = STEP_OUTPUT) -> reforma:
    """reforma with a fully mocked JarBridge — no JAR or Java needed."""
    with patch("reforma.client.JarBridge") as MockBridge:
        instance = MockBridge.return_value
        instance.list_transitions.return_value = step_output
        modelo = reforma()
        modelo._bridge = instance
        modelo.load(SIMPLE_SOURCE)
    return modelo  


# ---------------------------------------------------------------------------
# reformaModel
# ---------------------------------------------------------------------------
class TestreformaModel:
    def test_from_string(self):
        m = reformaModel.from_string("init s0\ns0 ---> s1: a", name="test")
        assert m.name == "test"

    def test_from_file(self, tmp_path):
        p = tmp_path / "model.r"
        p.write_text(SIMPLE_SOURCE)
        m = reformaModel.from_file(str(p))
        assert m.name == "model"
        assert "Home" in m.source


# ---------------------------------------------------------------------------
# SimulationState
# ---------------------------------------------------------------------------
class TestSimulationState:
    def test_transition_named_found(self):
        t = Transition("Home", "Office", "go_work", "go_work", 0.5)
        s = SimulationState(["Home"], [t], {}, False, None)
        assert s.transition_named("go_work") is t

    def test_transition_named_not_found(self):
        s = SimulationState(["Home"], [], {}, False, None)
        assert s.transition_named("missing") is None


# ---------------------------------------------------------------------------
# JarBridge — bundled JAR discovery
# ---------------------------------------------------------------------------
class TestJarBridge:
    def test_explicit_missing_raises(self):
        with pytest.raises(FileNotFoundError):
            JarBridge("/nonexistent/RePATool.jar")

    def test_no_bundled_jar_raises(self):
        with patch("reforma.jar_bridge._bundled_jar_path", return_value=None):
            with pytest.raises(FileNotFoundError, match="bundled"):
                JarBridge()      # no explicit path, no bundled jar

    def test_bundled_jar_used_when_present(self, tmp_path):
        fake_jar = tmp_path / "RePATool.jar"
        fake_jar.write_bytes(b"PK")   # minimal fake
        with patch("reforma.jar_bridge._bundled_jar_path", return_value=fake_jar):
            bridge = JarBridge()
        assert bridge.jar_path == str(fake_jar)

    def test_explicit_jar_overrides_bundled(self, tmp_path):
        custom = tmp_path / "custom.jar"
        custom.write_bytes(b"PK")
        bridge = JarBridge(str(custom))
        assert "custom" in bridge.jar_path

    def test_run_nonzero_raises(self, tmp_path):
        fake = tmp_path / "f.jar"
        fake.write_bytes(b"")
        bridge = JarBridge.__new__(JarBridge)
        bridge.jar_path = str(fake)
        bridge.java_bin = "java"
        import subprocess
        with patch("reforma.jar_bridge.subprocess.run") as mock_run:
            mock_run.return_value = MagicMock(returncode=1, stdout="", stderr="err")
            with pytest.raises(JarError):
                bridge._run("-step", "x.r")


# ---------------------------------------------------------------------------
# reforma.bundled_jar()
# ---------------------------------------------------------------------------
class TestBundledJar:
    def test_returns_none_when_absent(self):
        with patch("reforma.jar_bridge._bundled_jar_path", return_value=None):
            assert reforma.bundled_jar() is None

    def test_returns_path_when_present(self, tmp_path):
        fake = tmp_path / "RePATool.jar"
        fake.write_bytes(b"PK")
        with patch("reforma.jar_bridge._bundled_jar_path", return_value=fake):
            assert reforma.bundled_jar() == str(fake)


# ---------------------------------------------------------------------------
# reforma high-level
# ---------------------------------------------------------------------------
class TestreformaLoad:
    def test_load_parses_state(self):
        reforma = make_reforma()
        assert reforma.state.current_states == ["Home"]

    def test_enabled_transitions(self):
        reforma = make_reforma()
        labels = {t.label for t in reforma.state.enabled}
        assert {"go_work", "go_charge"} == labels

    def test_probabilities(self):
        reforma = make_reforma()
        t = reforma.state.transition_named("go_work")
        assert abs(t.probability - 0.5) < 1e-6

    def test_no_model_raises(self):
        with patch("reforma.client.JarBridge"):
            modelo = reforma()
        with pytest.raises(RuntimeError, match="No model loaded"):
            modelo.step("x")


class TestreformaSimulation:
    def test_step_moves_state(self):
        reforma = make_reforma()
        reforma._bridge.list_transitions.return_value = STEP_OUTPUT_OFFICE
        state = reforma.step("go_work")
        assert state.current_states == ["Office"]

    def test_step_invalid_raises(self):
        reforma = make_reforma()
        with pytest.raises(RuntimeError, match="not enabled"):
            reforma.step("fly_to_moon")

    def test_history_tracks_steps(self):
        reforma = make_reforma()
        reforma._bridge.list_transitions.return_value = STEP_OUTPUT_OFFICE
        reforma.step("go_work")
        assert reforma.history == ["go_work"]

    def test_undo_pops_history(self):
        reforma = make_reforma()
        reforma._bridge.list_transitions.return_value = STEP_OUTPUT_OFFICE
        reforma.step("go_work")
        reforma._bridge.list_transitions.return_value = STEP_OUTPUT
        reforma.undo()
        assert reforma.history == []

    def test_undo_empty_raises(self):
        reforma = make_reforma()
        with pytest.raises(RuntimeError, match="Nothing to undo"):
            reforma.undo()

    def test_reset_clears_history(self):
        reforma = make_reforma()
        reforma._bridge.list_transitions.return_value = STEP_OUTPUT_OFFICE
        reforma.step("go_work")
        reforma.reset()
        assert reforma.history == []


class TestreformaTraining:
    def test_train_updates_source(self):
        reforma = make_reforma()
        updated = SIMPLE_SOURCE.replace("(0.5)", "(0.7)")
        reforma._bridge.train.return_value = updated
        reforma._bridge.list_transitions.return_value = STEP_OUTPUT
        reforma.train([["go_work", "go_home"]])
        assert "(0.7)" in reforma.source

    def test_train_resets_history(self):
        reforma = make_reforma()
        reforma._bridge.list_transitions.return_value = STEP_OUTPUT_OFFICE
        reforma.step("go_work")
        reforma._bridge.train.return_value = SIMPLE_SOURCE
        reforma._bridge.list_transitions.return_value = STEP_OUTPUT
        reforma.train([["go_work"]])
        assert reforma.history == []

    def test_train_from_file(self, tmp_path):
        log = tmp_path / "log.txt"
        log.write_text("go_work,go_home\ngo_charge\n")
        reforma = make_reforma()
        reforma._bridge.train.return_value = SIMPLE_SOURCE
        reforma._bridge.list_transitions.return_value = STEP_OUTPUT
        reforma.train_from_file(str(log))
        assert reforma._bridge.train.call_args[0][1] == ["go_work,go_home", "go_charge"]


class TestreformaVerification:
    def test_check_pdl_string(self):
        reforma = make_reforma()
        reforma._bridge.check_pdl.return_value = "Result: 0.50000"
        assert "0.50000" in reforma.check_pdl("Home", "{P=?[F Office]}")

    def test_check_pdl_value_float(self):
        reforma = make_reforma()
        reforma._bridge.check_pdl.return_value = "Result: 0.75000"
        assert abs(reforma.check_pdl_value("Home", "{P=?[F Office]}") - 0.75) < 1e-6

    def test_check_pdl_true(self):
        reforma = make_reforma()
        reforma._bridge.check_pdl.return_value = "Result: true"
        assert reforma.check_pdl_value("Home", "<go_work>Office") is True

    def test_check_pdl_false(self):
        reforma = make_reforma()
        reforma._bridge.check_pdl.return_value = "Result: false"
        assert reforma.check_pdl_value("Home", "[]false") is False


class TestreformaExport:
    def test_export_prism(self):
        reforma = make_reforma()
        reforma._bridge.get_prism.return_value = "dtmc\n..."
        assert reforma.export_prism().startswith("dtmc")

    def test_export_mcrl2(self):
        reforma = make_reforma()
        reforma._bridge.get_mcrl2.return_value = "act\n..."
        assert reforma.export_mcrl2().startswith("act")

    def test_export_glts(self):
        reforma = make_reforma()
        reforma._bridge.get_glts.return_value = "int go_work_active = 1"
        assert "go_work_active" in reforma.export_glts()

    def test_save_prism(self, tmp_path):
        reforma = make_reforma()
        reforma._bridge.get_prism.return_value = "dtmc\n..."
        out = tmp_path / "model.pm"
        reforma.save_prism(str(out))
        assert out.read_text().startswith("dtmc")

    def test_save_source(self, tmp_path):
        reforma = make_reforma()
        out = tmp_path / "out.r"
        reforma.save_source(str(out))
        assert "Home" in out.read_text()


class TestParsePdlResult:
    def test_float(self):
        assert abs(_parse_pdl_result("Result: 0.33333") - 0.33333) < 1e-4

    def test_true(self):
        assert _parse_pdl_result("Result: true") is True

    def test_false(self):
        assert _parse_pdl_result("Result: false") is False

    def test_unparseable(self):
        assert isinstance(_parse_pdl_result("Deadlock found"), str)