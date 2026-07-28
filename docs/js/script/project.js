

(function () {
  var STORAGE_KEY = "re_project_v2";
  var currentFileId = null;
  var expanded = new Set(["examples-root", "my-root"]);

  function loadStore() {
    try {
      var raw = localStorage.getItem(STORAGE_KEY);
      if (raw) return JSON.parse(raw);
    } catch (e) { /* ignore */ }
    return { folders: {}, files: {} };
  }

  function saveStore(store) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(store));
  }

  function uid(prefix) {
    return prefix + "_" + Date.now().toString(36) + "_" + Math.random().toString(36).slice(2, 7);
  }

  function childrenOf(store, parentId) {
    var folders = Object.keys(store.folders)
      .filter(function (id) { return store.folders[id].parentId === parentId; })
      .map(function (id) { return { id: id, type: "folder", name: store.folders[id].name }; });
    var files = Object.keys(store.files)
      .filter(function (id) { return store.files[id].parentId === parentId; })
      .map(function (id) { return { id: id, type: "file", name: store.files[id].name }; });
    return folders.concat(files).sort(function (a, b) {
      if (a.type !== b.type) return a.type === "folder" ? -1 : 1;
      return a.name.localeCompare(b.name);
    });
  }

  function openFile(id, name, content) {
    currentFileId = id;
    if (typeof editor !== "undefined") editor.setValue(content);
    if (typeof showCanvasTab === "function") showCanvasTab("editorTab");
    if (typeof loadAndRender === "function") loadAndRender();
    var sb = document.getElementById("sb-model");
    if (sb) sb.textContent = name;
    highlightSelected(id);
  }

  function highlightSelected(id) {
    document.querySelectorAll(".tree-item").forEach(function (el) {
      el.classList.toggle("selected", el.dataset.id === id);
    });
  }


  function renderNode(container, node, store, depth) {
    var row = document.createElement("div");
    row.className = "tree-item";
    row.style.paddingLeft = (12 + depth * 14) + "px";
    row.dataset.id = node.id;
    row.dataset.type = node.type;
    row.draggable = node.type === "file";

    if (node.type === "folder") {
      var isOpen = expanded.has(node.id);
      var caret = document.createElement("span");
      caret.className = "glyphicon " + (isOpen ? "glyphicon-triangle-bottom" : "glyphicon-triangle-right");
      caret.style.marginRight = "4px";
      caret.style.fontSize = "9px";
      row.appendChild(caret);
      var icon = document.createElement("span");
      icon.className = "glyphicon glyphicon-folder-" + (isOpen ? "open" : "close");
      icon.style.marginRight = "5px";
      row.appendChild(icon);
      row.appendChild(document.createTextNode(node.name));

      row.onclick = function () {
        if (expanded.has(node.id)) expanded.delete(node.id); else expanded.add(node.id);
        renderTree();
      };
      row.oncontextmenu = function (e) {
        e.preventDefault();
        showFolderMenu(e, node.id, store);
      };

      container.appendChild(row);

      if (isOpen) {
        var kids = childrenOf(store, node.id);
        kids.forEach(function (k) { renderNode(container, k, store, depth + 1); });
        if (!kids.length) {
          var empty = document.createElement("div");
          empty.className = "tree-item tree-empty";
          empty.style.paddingLeft = (12 + (depth + 1) * 14) + "px";
          empty.style.opacity = "0.5";
          empty.style.fontStyle = "italic";
          empty.textContent = "(vazio)";
          container.appendChild(empty);
        }
      }
    } else {
      var fIcon = document.createElement("span");
      fIcon.className = "glyphicon glyphicon-file";
      fIcon.style.marginRight = "5px";
      row.appendChild(fIcon);
      row.appendChild(document.createTextNode(node.name));

      row.onclick = function () {
        var f = store.files[node.id];
        if (f) openFile(node.id, f.name, f.content);
      };

      row.ondragstart = function (e) {
        e.dataTransfer.setData("text/rta-file-id", node.id);
      };

      container.appendChild(row);
    }
    return row;
  }

  function renderExampleNode(container, key, content, depth) {
    var row = document.createElement("div");
    row.className = "tree-item";
    row.style.paddingLeft = (12 + depth * 14) + "px";
    row.dataset.id = "example:" + key;
    var fIcon = document.createElement("span");
    fIcon.className = "glyphicon glyphicon-file";
    fIcon.style.marginRight = "5px";
    fIcon.style.opacity = "0.6";
    row.appendChild(fIcon);
    row.appendChild(document.createTextNode(key + ".Re"));
    row.title = "Exemplo incorporado (só leitura)";
    row.onclick = function () {
      currentFileId = null;
      if (typeof editor !== "undefined") editor.setValue(content);
      if (typeof showCanvasTab === "function") showCanvasTab("editorTab");
      if (typeof loadAndRender === "function") loadAndRender();
      var sb = document.getElementById("sb-model");
      if (sb) sb.textContent = key + ".Re";
      highlightSelected(row.dataset.id);
    };
    container.appendChild(row);
  }

  function renderTree() {
    var root = document.getElementById("project-tree");
    if (!root) return;
    root.innerHTML = "";
    var store = loadStore();

    var exFolderOpen = expanded.has("examples-root");
    var exHeader = document.createElement("div");
    exHeader.className = "tree-item";
    exHeader.style.paddingLeft = "0px";
    exHeader.style.fontWeight = "bold";
    exHeader.innerHTML =
      '<span class="glyphicon ' + (exFolderOpen ? "glyphicon-triangle-bottom" : "glyphicon-triangle-right") +
      '" style="margin-right:4px;font-size:9px;"></span>' +
      '<span class="glyphicon glyphicon-book" style="margin-right:5px;"></span>Exemplos';
    exHeader.onclick = function () {
      if (expanded.has("examples-root")) expanded.delete("examples-root"); else expanded.add("examples-root");
      renderTree();
    };
    root.appendChild(exHeader);

    if (exFolderOpen && typeof RTA !== "undefined" && typeof RTA.getExamples === "function") {
      try {
        var examples = JSON.parse(RTA.getExamples());
        Object.keys(examples).forEach(function (key) {
          renderExampleNode(root, key, examples[key], 1);
        });
      } catch (e) { /* ignore */ }
    }

    var myOpen = expanded.has("my-root");
    var myHeader = document.createElement("div");
    myHeader.className = "tree-item";
    myHeader.style.paddingLeft = "0px";
    myHeader.style.fontWeight = "bold";
    myHeader.innerHTML =
      '<span class="glyphicon ' + (myOpen ? "glyphicon-triangle-bottom" : "glyphicon-triangle-right") +
      '" style="margin-right:4px;font-size:9px;"></span>' +
      '<span class="glyphicon glyphicon-hdd" style="margin-right:5px;"></span>Meus Ficheiros';
    myHeader.onclick = function () {
      if (expanded.has("my-root")) expanded.delete("my-root"); else expanded.add("my-root");
      renderTree();
    };
    myHeader.ondragover = function (e) { e.preventDefault(); };
    myHeader.ondrop = function (e) {
      e.preventDefault();
      var id = e.dataTransfer.getData("text/rta-file-id");
      if (id) moveFile(id, null);
    };
    root.appendChild(myHeader);

    if (myOpen) {
      childrenOf(store, null).forEach(function (node) {
        var row = renderNode(root, node, store, 1);
        if (node.type === "folder") {
          row.ondragover = function (e) { e.preventDefault(); };
          row.ondrop = function (e) {
            e.preventDefault();
            e.stopPropagation();
            var id = e.dataTransfer.getData("text/rta-file-id");
            if (id) moveFile(id, node.id);
          };
        }
      });
    }
  }

  function moveFile(fileId, newParentId) {
    var store = loadStore();
    if (!store.files[fileId]) return;
    store.files[fileId].parentId = newParentId;
    saveStore(store);
    renderTree();
  }


  function showFolderMenu(e, folderId, store) {
    var old = document.getElementById("folder-context-menu");
    if (old) old.remove();

    var menu = document.createElement("div");
    menu.id = "folder-context-menu";
    menu.className = "custom-context-menu";
    menu.style.display = "block";
    menu.style.left = e.clientX + "px";
    menu.style.top = e.clientY + "px";
    menu.innerHTML =
      '<ul>' +
      '<li data-act="new-file"><span class="glyphicon glyphicon-file"></span> Novo ficheiro aqui</li>' +
      '<li data-act="new-folder"><span class="glyphicon glyphicon-folder-open"></span> Nova subpasta</li>' +
      '<li data-act="rename"><span class="glyphicon glyphicon-pencil"></span> Renomear</li>' +
      '<li data-act="delete"><span class="glyphicon glyphicon-trash"></span> Apagar pasta</li>' +
      '</ul>';
    document.body.appendChild(menu);

    menu.addEventListener("click", function (ev) {
      var li = ev.target.closest("li");
      if (!li) return;
      var act = li.dataset.act;
      var s = loadStore();
      if (act === "new-file") {
        var fname = prompt("Nome do novo ficheiro:", "novo.Re");
        if (fname) {
          var fid = uid("file");
          s.files[fid] = { name: fname, parentId: folderId, content: "name " + fname.replace(/\.Re$/, "") + "\n\ninit start\n" };
          saveStore(s);
          expanded.add(folderId);
          renderTree();
        }
      } else if (act === "new-folder") {
        var fdname = prompt("Nome da subpasta:", "Nova pasta");
        if (fdname) {
          var fdid = uid("folder");
          s.folders[fdid] = { name: fdname, parentId: folderId };
          saveStore(s);
          expanded.add(folderId);
          renderTree();
        }
      } else if (act === "rename") {
        var newName = prompt("Novo nome:", s.folders[folderId].name);
        if (newName) {
          s.folders[folderId].name = newName;
          saveStore(s);
          renderTree();
        }
      } else if (act === "delete") {
        if (confirm('Apagar a pasta "' + s.folders[folderId].name + '" e todo o seu conteúdo?')) {
          deleteFolderRecursive(s, folderId);
          saveStore(s);
          renderTree();
        }
      }
      menu.remove();
    });

    setTimeout(function () {
      document.addEventListener("click", function closeMenu() {
        menu.remove();
        document.removeEventListener("click", closeMenu);
      });
    }, 0);
  }

  function deleteFolderRecursive(store, folderId) {
    Object.keys(store.folders).forEach(function (id) {
      if (store.folders[id].parentId === folderId) deleteFolderRecursive(store, id);
    });
    Object.keys(store.files).forEach(function (id) {
      if (store.files[id].parentId === folderId) delete store.files[id];
    });
    delete store.folders[folderId];
  }


  window.updateProjectTree = renderTree;

  window.createNewModel = function () {
    var name = prompt("Nome do novo ficheiro (.Re):", "novo.Re");
    if (!name) return;
    if (!/\.Re$/.test(name)) name += ".Re";
    var store = loadStore();
    var id = uid("file");
    var template = "name " + name.replace(/\.Re$/, "") + "\n\ninit start\n";
    store.files[id] = { name: name, parentId: null, content: template };
    saveStore(store);
    expanded.add("my-root");
    renderTree();
    openFile(id, name, template);
  };

  window.saveUserModel = function () {
    var content = typeof editor !== "undefined" ? editor.getValue() : "";
    var name = prompt("Guardar como:", currentFileId ? (loadStore().files[currentFileId] || {}).name || "modelo.Re" : "modelo.Re");
    if (!name) return;
    if (!/\.Re$/.test(name)) name += ".Re";
    var store = loadStore();
    var id = uid("file");
    store.files[id] = { name: name, parentId: null, content: content };
    saveStore(store);
    currentFileId = id;
    expanded.add("my-root");
    renderTree();
    var sb = document.getElementById("sb-model");
    if (sb) sb.textContent = name;
    highlightSelected(id);
  };

  window.overwriteUserModel = function () {
    if (!currentFileId) {
      alert("Nenhum ficheiro guardado está aberto — a usar 'Guardar como' em alternativa.");
      window.saveUserModel();
      return;
    }
    var store = loadStore();
    if (!store.files[currentFileId]) {
      alert("O ficheiro original já não existe. A guardar como novo.");
      window.saveUserModel();
      return;
    }
    store.files[currentFileId].content = typeof editor !== "undefined" ? editor.getValue() : "";
    saveStore(store);
    renderTree();
  };

  window.deleteUserModel = function () {
    if (!currentFileId) {
      alert("Nenhum ficheiro guardado está aberto.");
      return;
    }
    var store = loadStore();
    var f = store.files[currentFileId];
    if (!f) return;
    if (!confirm('Apagar "' + f.name + '"? Esta ação não pode ser desfeita.')) return;
    delete store.files[currentFileId];
    saveStore(store);
    currentFileId = null;
    renderTree();
    var sb = document.getElementById("sb-model");
    if (sb) sb.textContent = "Nenhum modelo guardado";
  };


  document.addEventListener("DOMContentLoaded", function () {
    var menu = document.getElementById("project-context-menu");
    if (menu) {
      var ul = menu.querySelector("ul");
      if (ul && !ul.querySelector('[data-act="new-root-folder"]')) {
        var li = document.createElement("li");
        li.dataset.act = "new-root-folder";
        li.innerHTML = '<span class="glyphicon glyphicon-folder-close"></span> Nova pasta';
        li.onclick = function () {
          var name = prompt("Nome da pasta:", "Nova pasta");
          if (name) {
            var store = loadStore();
            var id = uid("folder");
            store.folders[id] = { name: name, parentId: null };
            saveStore(store);
            expanded.add("my-root");
            renderTree();
          }
        };
        ul.appendChild(li);
      }
    }
    renderTree();
  });
})();