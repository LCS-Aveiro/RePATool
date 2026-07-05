// ============================================================
// AUTOCOMPLETE — sugestões de estados/labels já usados no editor
// ============================================================
// Requer: CodeMirror já criado como `editor` (ver index.html),
// e os addons show-hint.js / show-hint.css do CodeMirror.
//
// Adicionar ao <head>/antes de </body> do index.html:
//   <link rel="stylesheet" href="js/static/codemirror/addon/hint/show-hint.css">
//   <script src="js/static/codemirror/addon/hint/show-hint.js"></script>
//   <script src="js/script/autocomplete.js"></script>
//
// (os ficheiros show-hint.js/css vêm do addon oficial "hint" do
// CodeMirror 5, mesma família dos addons "edit/matchbrackets" que
// já usas — basta copiar para js/static/codemirror/addon/hint/)

(function () {
  // Palavras-chave da DSL (extraídas do modo "rta" e do parser)
  var KEYWORDS = [
    "name", "init", "aut", "int", "float", "bool", "dyn",
    "training", "laplace", "aggregation",
    "calibration", "proportional", "normalize", "equal",
    "paradigm", "fuzzy", "probabilistic",
    "if", "then", "else", "disabled",
    "arith", "prod", "max", "min", "geom",
    "AND", "OR", "true", "false", "return", "print", "foreach", "in",
    "def"
  ];

  // Regex para identificadores usados como estado/label ao longo do texto:
  //   from ---> to : label
  //   from -id-> to
  //   trigger ->> target : rule
  //   trigger --! target : rule
  var IDENT = "[A-Za-z_][A-Za-z0-9_.]*";
  var lineRe = new RegExp(
    "(" + IDENT + ")\\s*(?:-\\s*(" + IDENT + ")\\s*)?(?:--->|-->|->>|--!|--x|->)\\s*(" + IDENT + ")(?:\\s*:\\s*(" + IDENT + "))?",
    "g"
  );

  function collectSymbols(text) {
    var set = new Set(KEYWORDS);
    var m;
    lineRe.lastIndex = 0;
    while ((m = lineRe.exec(text)) !== null) {
      for (var i = 1; i <= 4; i++) {
        if (m[i]) set.add(m[i]);
      }
    }
    // também variáveis declaradas: "int x = 0", "float y [..] = 0"
    var varRe = /\b(?:int|float|bool|dyn\s+int\[\])\s+([A-Za-z_][A-Za-z0-9_.]*)/g;
    while ((m = varRe.exec(text)) !== null) set.add(m[1]);
    return Array.from(set);
  }

  function rtaHint(cm) {
    var cursor = cm.getCursor();
    var line = cm.getLine(cursor.line);
    var start = cursor.ch, end = cursor.ch;
    while (start > 0 && /[\w.]/.test(line.charAt(start - 1))) start--;
    while (end < line.length && /[\w.]/.test(line.charAt(end))) end++;
    var word = line.slice(start, end);

    var symbols = collectSymbols(cm.getValue());
    var list = symbols
      .filter(function (s) { return s.toLowerCase().indexOf(word.toLowerCase()) === 0 && s !== word; })
      .sort();

    if (!list.length) return null;

    return {
      list: list,
      from: CodeMirror.Pos(cursor.line, start),
      to: CodeMirror.Pos(cursor.line, end)
    };
  }

  CodeMirror.registerHelper("hint", "rta", rtaHint);

  function attachAutocomplete(cmInstance) {
    cmInstance.setOption("extraKeys", Object.assign({}, cmInstance.getOption("extraKeys"), {
      "Ctrl-Space": function (cm) {
        cm.showHint({ hint: rtaHint, completeSingle: false });
      }
    }));

    // Autocomplete "enquanto escreve" (só depois de 2+ caracteres, com debounce)
    var timer = null;
    cmInstance.on("inputRead", function (cm, change) {
      if (change.text.join("") === "") return;
      clearTimeout(timer);
      timer = setTimeout(function () {
        var cursor = cm.getCursor();
        var line = cm.getLine(cursor.line);
        var beforeCursor = line.slice(0, cursor.ch);
        var match = /[A-Za-z_][A-Za-z0-9_.]*$/.exec(beforeCursor);
        if (match && match[0].length >= 2 && !cm.state.completionActive) {
          cm.showHint({ hint: rtaHint, completeSingle: false });
        }
      }, 200);
    });
  }

  // Espera que `editor` (definido em index.html) exista.
  document.addEventListener("DOMContentLoaded", function () {
    var tries = 0;
    var iv = setInterval(function () {
      tries++;
      if (typeof editor !== "undefined") {
        attachAutocomplete(editor);
        clearInterval(iv);
      } else if (tries > 50) {
        clearInterval(iv);
      }
    }, 100);
  });
})();