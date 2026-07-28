

(function () {
  function isTypingContext(target) {
    if (!target) return false;
    var tag = (target.tagName || "").toLowerCase();
    if (tag === "input" || tag === "textarea") return true;

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

      e.stopImmediatePropagation();
    }

  }


  document.addEventListener("keydown", guardGlobalUndo, true);

  function addEditorUndoButtons(cm) {
    var toolbar = document.querySelector('#editorTab div[style*="padding:8px"]');
    if (!toolbar) return;
    if (document.getElementById("editorUndoBtn")) return; 

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