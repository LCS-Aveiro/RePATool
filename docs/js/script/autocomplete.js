
(function () {
  var KEYWORDS = [
    "name", "init", "aut", "int", "float", "bool", "dyn",
    "training", "laplace", "aggregation",
    "calibration", "proportional", "normalize", "equal","revise_equal",
    "paradigm", "fuzzy", "probabilistic",
    "if", "then", "else", "disabled",
    "arith", "prod", "max", "min", "geom",
    "AND", "OR", "true", "false", "return", "print", "foreach", "in",
    "def"
  ];

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
    // "int x = 0", "float y [..] = 0"
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