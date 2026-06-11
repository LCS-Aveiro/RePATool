"""
jar_bridge.py — low-level subprocess wrapper around the reforma JAR.

The JAR can be:
  1. Bundled inside the package at  reforma/bin/RePATool.jar   (default)
  2. Supplied explicitly via  JarBridge("/path/to/custom.jar")
"""

from __future__ import annotations

import importlib.resources
import json
import os
import subprocess
import tempfile
from pathlib import Path
from typing import Optional


class JarError(RuntimeError):
    """Raised when the JAR returns an error payload or exits non-zero."""


def _bundled_jar_path() -> Optional[Path]:
    """
    Return the path to the bundled RePATool.jar, or None if not present.

    The JAR lives at:  reforma/bin/RePATool.jar
    """
    try:
        with importlib.resources.as_file(
            importlib.resources.files("reforma").joinpath("bin/RePATool.jar")
        ) as p:
            if p.exists():
                return p
    except (TypeError, FileNotFoundError, ModuleNotFoundError):
        pass

    candidate = Path(__file__).parent / "bin" / "RePATool.jar"
    return candidate if candidate.exists() else None


def _make_tempfile(suffix: str, content: str) -> str:
    """
    Cria um ficheiro temporário com o conteúdo dado, fecha-o e devolve o path.
    Usar delete=False + close explícito garante compatibilidade com Windows,
    onde ficheiros abertos não podem ser relidos por outro processo.
    """
    fd, path = tempfile.mkstemp(suffix=suffix)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(content)
    except Exception:
        os.unlink(path)
        raise
    return path


def _make_empty_tempfile(suffix: str) -> str:
    """Cria um ficheiro temporário vazio e devolve o path (para output do JAR)."""
    fd, path = tempfile.mkstemp(suffix=suffix)
    os.close(fd)
    return path


class JarBridge:
    """
    Wraps the reforma JAR.

    Parameters
    ----------
    jar_path : str | None
        Path to ``RePATool.jar``.  Pass ``None`` (default) to use the
        bundled JAR automatically.
    java_bin : str
        Path to the ``java`` executable (default: ``"java"``).
    """

    def __init__(self, jar_path: Optional[str] = None, java_bin: str = "java"):
        self.java_bin = java_bin

        if jar_path is not None:
            resolved = Path(jar_path).resolve()
            if not resolved.exists():
                raise FileNotFoundError(f"reforma JAR not found: {jar_path}")
            self.jar_path = str(resolved)
        else:
            bundled = _bundled_jar_path()
            if bundled is None:
                raise FileNotFoundError(
                    "No bundled RePATool.jar found inside the reforma package.\n"
                    "Either install a package build that includes the JAR, or\n"
                    "supply the path explicitly:  reforma('path/to/RePATool.jar')"
                )
            self.jar_path = str(bundled)

    def _run(self, *args: str, stdin_data: Optional[str] = None) -> str:
        """
        Run:  java -jar <jar> <args...>
        Returns stdout.  Raises JarError on non-zero exit.
        """
        cmd = [self.java_bin, "-jar", self.jar_path, *args]
        result = subprocess.run(
            cmd,
            input=stdin_data,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        if result.returncode != 0:
            raise JarError(
                f"JAR exited {result.returncode}:\n{result.stderr.strip()}"
            )
        return (result.stdout or "").strip()

    def _run_with_tempfile(
        self, flag: str, source: str, extra_args: Optional[list[str]] = None
    ) -> str:
        """Write *source* to a temp ``.r`` file, invoke the JAR, return stdout."""
        tmp = _make_tempfile(".r", source)
        try:
            return self._run(flag, tmp, *(extra_args or []))
        finally:
            try:
                os.unlink(tmp)
            except FileNotFoundError:
                pass


    def get_text(self, source: str) -> str:
        return self._run_with_tempfile("-text", source)

    def get_mermaid(self, source: str) -> str:
        return self._run_with_tempfile("-mermaid", source)

    def get_lts_mermaid(self, source: str) -> str:
        return self._run_with_tempfile("-lts", source)

    def get_prism(self, source: str) -> str:
        return self._run_with_tempfile("-prism", source)

    def get_cytoscape(self, source: str, history: Optional[list[str]] = None) -> str:
        extra = [",".join(history)] if history else []
        return self._run_with_tempfile("-cytoscape", source, extra_args=extra)

    def check_problems(self, source: str) -> str:
        return self._run_with_tempfile("-check", source)

    def get_stats(self, source: str) -> str:
        return self._run_with_tempfile("-stats", source)

    def delta_cut(self, source: str, delta: float) -> str:
        return self._run_with_tempfile("-deltacut", source, extra_args=[str(delta)])

    def find_best_path(self, source: str, target_type: str, target_value: str, target_int: int, criterion: str) -> str:
        return self._run_with_tempfile(
            "-bestpath", source,
            extra_args=[target_type, target_value, str(target_int), criterion]
        )

    def get_glts(self, source: str) -> str:
        return self._run_with_tempfile("-translate", source)

    def list_transitions(self, source: str, history: Optional[list[str]] = None) -> str:
        extra = [",".join(history)] if history else []
        return self._run_with_tempfile("-step", source, extra_args=extra)

    def check_pdl(self, source: str, state: str, formula: str, history: Optional[list[str]] = None) -> str:
        args = [state, formula]
        if history:
            args.append(",".join(history))
        return self._run_with_tempfile("-pdl", source, extra_args=args)


    def _run_to_file(self, *args: str) -> str:
        """
        Corre o JAR com os argumentos dados.
        O último argumento deve ser o path do ficheiro de saída.
        Lê e devolve o conteúdo desse ficheiro.
        Progresso/logs do JAR vão para stderr — ignorados aqui.
        """
        cmd = [self.java_bin, "-jar", self.jar_path, *args]
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        if result.returncode != 0:
            raise JarError(
                f"JAR exited {result.returncode}:\n{result.stderr.strip()}"
            )
        out_path = args[-1]
        if not os.path.exists(out_path):
            raise JarError(
                f"JAR não gerou o ficheiro de saída esperado: {out_path}\n"
                f"stderr: {result.stderr.strip()}"
            )
        with open(out_path, "r", encoding="utf-8") as f:
            return f.read()

    def merge_models(self, sourceA: str, sourceB: str, op_type: str, agg: str) -> str:
        tmpA    = _make_tempfile(".r", sourceA)
        tmpB    = _make_tempfile(".r", sourceB)
        out_tmp = _make_empty_tempfile(".r")
        try:
            return self._run_to_file("-merge", tmpA, tmpB, op_type, agg, out_tmp)
        finally:
            for p in (tmpA, tmpB, out_tmp):
                try:
                    os.unlink(p)
                except FileNotFoundError:
                    pass

    def train(self, source: str, log_lines: list[str]) -> str:
        """
        Treina o modelo com uma lista de sessões e devolve o source actualizado.

        O comando -train escreve o resultado num ficheiro (não no stdout),
        por isso usamos _run_to_file em vez de _run.
        """
        model_tmp = _make_tempfile(".r",   source)
        log_tmp   = _make_tempfile(".txt", "\n".join(log_lines))
        out_tmp   = _make_empty_tempfile(".r")
        try:
            return self._run_to_file("-train", model_tmp, log_tmp, out_tmp)
        finally:
            for p in (model_tmp, log_tmp, out_tmp):
                try:
                    os.unlink(p)
                except FileNotFoundError:
                    pass