"""
Data classes representing reforma simulation state and transitions.
"""

from dataclasses import dataclass, field
from typing import Optional


@dataclass
class Transition:
    """A single enabled transition in the current state."""
    from_state: str
    to_state: str
    trans_id: str
    label: str
    probability: float
    is_delay: bool = False

    def __repr__(self) -> str:
        return (
            f"Transition({self.label!r}: {self.from_state!r} → {self.to_state!r}, "
            f"p={self.probability:.3f})"
        )


@dataclass
class SimulationState:
    """
    The full state returned after each simulation step.

    Attributes:
        current_states:  Set of active state names (inits).
        enabled:         List of currently enabled transitions.
        variables:       Dict of variable name → integer value.
        can_undo:        Whether the undo stack has entries.
        last_transition: The transition that was just taken (None on load/undo).
        raw:             The original parsed JSON dict, for advanced use.
    """
    current_states: list[str]
    enabled: list[Transition]
    variables: dict[str, int]
    can_undo: bool
    last_transition: Optional[Transition]
    raw: dict = field(repr=False, default_factory=dict)

    def transition_named(self, label: str) -> Optional[Transition]:
        """Return the enabled transition with the given label, or None."""
        for t in self.enabled:
            if t.label == label:
                return t
        return None

    def summary(self) -> str:
        """Retorna uma string formatada com o estado atual e probabilidades."""
        linhas = [f"📍 Estado Atual: {', '.join(self.current_states)}"]
        
        if self.variables:
            vars_str = ", ".join(f"{k}={v}" for k, v in self.variables.items())
            linhas.append(f"🔢 Variáveis: {vars_str}")
            
        linhas.append("⚡ Transições Habilitadas:")
        
        if not self.enabled:
            linhas.append("  (Nenhuma - Deadlock)")
        else:
            for t in self.enabled:
                linhas.append(f"  - [{t.label}] para {t.to_state} (P = {t.probability:.3f})")
                
        return "\n".join(linhas)

    def __repr__(self) -> str:
        enabled_labels = [t.label for t in self.enabled]
        return (
            f"SimulationState(states={self.current_states}, "
            f"enabled={enabled_labels}, vars={self.variables})"
        )


@dataclass
class ReFormaModel:
    """
    Holds the source code of a loaded reforma model and metadata.
    """
    source: str
    name: str = ""

    @classmethod
    def from_file(cls, path: str) -> "ReFormaModel":
        with open(path, "r", encoding="utf-8") as f:
            source = f.read()
        name = path.split("/")[-1].replace(".r", "")
        return cls(source=source, name=name)

    @classmethod
    def from_string(cls, source: str, name: str = "inline") -> "ReFormaModel":
        return cls(source=source, name=name)