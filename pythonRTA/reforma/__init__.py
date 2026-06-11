"""
reforma - Python bindings for the RePA/reforma tool.

The JAR is bundled inside the package. Usage:

    from reforma import ReForma

    model = ReForma()

    state = model.load_file("my_model.r")
    state = model.step("go_work")
    result = model.check_pdl("Home", "{P=?[F Office]}")
"""

from .client import ReForma
from .model import ReFormaModel, Transition, SimulationState

__all__ = ["ReForma", "ReFormaModel", "Transition", "SimulationState"]
__version__ = "0.1.0"