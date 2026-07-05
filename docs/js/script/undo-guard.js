// ============================================================
// SEPARAÇÃO: Undo do EDITOR (texto) vs Undo da SIMULAÇÃO (RTA)
// ============================================================
// Problema que isto resolve: o CodeMirror já tem o seu próprio
// histórico de undo/redo (Ctrl+Z / Ctrl+Y), completamente
// independente do histórico de simulação (`RTA.undo()`, botão
// "Undo" na toolbar). Se noutro sítio do código existir um
// listener global de teclado que chame `doUndo()` em Ctrl+Z,
// isso rouba o atalho ao editor quando estás a escrever texto.
//
// Este ficheiro:
//  1. Garante, com `capture: true`, que Ctrl+Z / Ctrl+Y só disparam
//     undo/redo da SIMULAÇÃO quando o foco NÃO está no editor de
//     texto (nem em nenhum <input>/<textarea>).
//  2. Adiciona dois botões visíveis "Undo texto" / "Redo texto"
//     junto ao botão de guardar no separador Editor, para não
//     haver ambiguidade sobre qual histórico está a ser usado.
//
// Adicionar ao index.html:
//   <script src="js/script/undo-guard.js"></script>
// (depois do <script> que cria `editor` e depois de `RTA` estar
// carregado via scala.js)

(function () {
  function isTypingContext(target) {
    if (!target) return false;
    var tag = (target.tagName || "").toLowerCase();
    if (tag === "input" || tag === "textarea") return true;
    // CodeMirror usa uma <textarea> escondida + um div contenteditable
    // consoante o inputStyle; isto cobre ambos os casos.
    var cmNode = target.closest ? target.closest(".CodeMirror") : null;
    return !!cmNode;
  }

  function guardGlobalUndo(e) {
    var ctrlOrCmd = e.ctrlKey || e.metaKey;
    if (!ctrlOrCmd) return;
    var key = e.key ? e.key.toLowerCase() : "";
    var isUndo = key === "z" && !e.shiftKey;
    var isRedo = (key === "y") || (key === "z" && e.shiftKey);
    if (!isUndo && !isRedo) return;

    if (isTypingContext(e.target)) {
      // Deixa o CodeMirror (ou o campo de texto) tratar disto.
      // Não chamamos doUndo()/RTA aqui; apenas impedimos que um
      // eventual listener global de simulação também dispare.
      e.stopImmediatePropagation();
    }
    // Se não estiver num contexto de escrita, não fazemos nada aqui:
    // o atalho de simulação (se existir) continua livre para atuar.
  }

  // capture:true para correr antes de outros listeners no document
  document.addEventListener("keydown", guardGlobalUndo, true);

  function addEditorUndoButtons(cm) {
    var toolbar = document.querySelector('#editorTab div[style*="padding:8px"]');
    if (!toolbar) return;
    if (document.getElementById("editorUndoBtn")) return; // já adicionado

    var undoBtn = document.createElement("button");
    undoBtn.id = "editorUndoBtn";
    undoBtn.className = "u-btn";
    undoBtn.title = "Desfazer edição de texto (Ctrl+Z)";
    undoBtn.innerHTML = '<span class="glyphicon glyphicon-share-alt" style="transform:scaleX(-1);display:inline-block;"></span> Undo texto';
    undoBtn.onclick = function () { cm.undo(); cm.focus(); };

    var redoBtn = document.createElement("button");
    redoBtn.id = "editorRedoBtn";
    redoBtn.className = "u-btn";
    redoBtn.title = "Refazer edição de texto (Ctrl+Y)";
    redoBtn.innerHTML = '<span class="glyphicon glyphicon-share-alt"></span> Redo texto';
    redoBtn.onclick = function () { cm.redo(); cm.focus(); };

    toolbar.appendChild(undoBtn);
    toolbar.appendChild(redoBtn);

    // Mantém os botões ativos/inativos conforme há ou não histórico
    function refreshState() {
      var hist = cm.historySize();
      undoBtn.disabled = hist.undo === 0;
      redoBtn.disabled = hist.redo === 0;
    }
    cm.on("changes", refreshState);
    refreshState();
  }

  document.addEventListener("DOMContentLoaded", function () {
    var tries = 0;
    var iv = setInterval(function () {
      tries++;
      if (typeof editor !== "undefined") {
        addEditorUndoButtons(editor);
        clearInterval(iv);
      } else if (tries > 50) {
        clearInterval(iv);
      }
    }, 100);
  });
})();