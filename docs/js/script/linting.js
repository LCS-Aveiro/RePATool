// ============================================================
// LINTING EM TEMPO REAL
// ============================================================
// Requer o patch RTAAPI_checkSyntax_patch.scala (RTA.checkSyntax)
// recompilado. Sem ele, este ficheiro degrada graciosamente:
// mostra apenas um aviso na consola e não faz nada.
//
// Adicionar ao index.html:
//   <script src="js/script/linting.js"></script>
// (depois do <script> que cria `editor`)
//
// CSS sugerido (podes pôr em css/style.css):
//   .cm-lint-error-line { background: rgba(220, 38, 38, 0.08); }
//   .cm-lint-error-mark { border-bottom: 2px wavy #dc2626; }
//   .cm-lint-gutter-marker { color: #dc2626; font-weight: bold; cursor: help; }
//   #lint-status { font-size: 11px; padding: 2px 8px; }
//   #lint-status.ok { color: #16a34a; }
//   #lint-status.error { color: #dc2626; }

(function () {
  var DEBOUNCE_MS = 500;
  var lastMarker = null;
  var lastLineClass = null;
  var timer = null;

  function clearMarks(cm) {
    if (lastMarker) { lastMarker.clear(); lastMarker = null; }
    if (lastLineClass !== null) {
      cm.removeLineClass(lastLineClass, "background", "cm-lint-error-line");
      lastLineClass = null;
    }
  }

  function parseErrorLocation(msg) {
    // Mensagens no formato: "Erro na Linha 4, Coluna 2:\n..."
    var m = /Linha\s+(\d+)(?:,\s*Coluna\s+(\d+))?/i.exec(msg || "");
    if (!m) return null;
    return { line: parseInt(m[1], 10) - 1, col: m[2] ? parseInt(m[2], 10) - 1 : 0 };
  }

  function setStatus(text, ok) {
    var el = document.getElementById("lint-status");
    if (!el) return;
    el.textContent = text;
    el.className = ok ? "ok" : "error";
  }

  function runLint(cm) {
    if (typeof RTA === "undefined" || typeof RTA.checkSyntax !== "function") {
      return; // patch do Scala ainda não aplicado/recompilado
    }
    var source = cm.getValue();
    clearMarks(cm);
    if (!source.trim()) { setStatus("", true); return; }

    var resultJson;
    try {
      resultJson = RTA.checkSyntax(source);
    } catch (e) {
      return;
    }

    var result;
    try { result = JSON.parse(resultJson); } catch (e) { return; }

    if (result.ok) {
      setStatus("✓ Sintaxe OK", true);
      return;
    }

    setStatus("✗ " + (result.error || "Erro de sintaxe").split("\n")[0], false);

    var loc = parseErrorLocation(result.error);
    if (!loc) return;

    var lineNo = Math.max(0, Math.min(loc.line, cm.lineCount() - 1));
    var lineLen = cm.getLine(lineNo).length;

    lastLineClass = cm.addLineClass(lineNo, "background", "cm-lint-error-line");
    lastMarker = cm.markText(
      { line: lineNo, ch: 0 },
      { line: lineNo, ch: Math.max(1, lineLen) },
      {
        className: "cm-lint-error-mark",
        title: result.error,
        attributes: { title: result.error }
      }
    );
  }

  function attachLinting(cm) {
    // barra de estado simples por baixo do editor, se ainda não existir
    if (!document.getElementById("lint-status")) {
      var bar = document.createElement("div");
      bar.id = "lint-status";
      var wrapper = cm.getWrapperElement();
      wrapper.parentNode.insertBefore(bar, wrapper.nextSibling);
    }

    cm.on("changes", function (instance) {
      clearTimeout(timer);
      timer = setTimeout(function () { runLint(instance); }, DEBOUNCE_MS);
    });

    // primeira verificação
    setTimeout(function () { runLint(cm); }, 300);
  }

  document.addEventListener("DOMContentLoaded", function () {
    var tries = 0;
    var iv = setInterval(function () {
      tries++;
      if (typeof editor !== "undefined") {
        attachLinting(editor);
        clearInterval(iv);
      } else if (tries > 50) {
        clearInterval(iv);
      }
    }, 100);
  });
})();