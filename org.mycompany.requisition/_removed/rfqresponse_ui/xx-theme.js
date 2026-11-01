/* Button ke tar lekha dekhe rong dei. Kono click/logic chhuy na, shudhu CSS class boshay. */
(function () {
  var RULES = [
    ['no',     /(reject|cancel|delete|remove|void|discard|decline|^x$|\u2716|\u2715|\u00d7)/],
    ['gray',   /(clear|reset|back|close|undo|skip)/],
    ['accent', /(recommend|winner|forward|delegate|escalate)/],
    ['info',   /(view|detail|print|history|attachment|preview|report|comparative|pdf|download|show)/],
    ['teal',   /(reload|refresh|search|load|find|filter|select|add|new)/],
    ['warn',   /(edit|modify|change|hold|return|revise|re-?activate)/],
    ['ok',     /(approve|save|submit|^ok$|confirm|generate|create|complete|process|send|accept|apply|post|run|finish|update|yes|\u2714|\u2713)/]
  ];
  var ALL = ['xx-b-no', 'xx-b-gray', 'xx-b-accent', 'xx-b-info', 'xx-b-teal', 'xx-b-warn', 'xx-b-ok'];
  function paint() {
    var list = document.querySelectorAll('.xx-form button.z-button, .xx-win button.z-button');
    for (var i = 0; i < list.length; i++) {
      var b = list[i], t = (b.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase(), cls = null;
      if (t) for (var r = 0; r < RULES.length; r++) if (RULES[r][1].test(t)) { cls = 'xx-b-' + RULES[r][0]; break; }
      for (var k = 0; k < ALL.length; k++) if (ALL[k] !== cls) b.classList.remove(ALL[k]);
      if (cls) b.classList.add(cls);
    }
  }
  var timer = null;
  if (window.__xxObserver) window.__xxObserver.disconnect();
  window.__xxObserver = new MutationObserver(function () {
    clearTimeout(timer); timer = setTimeout(paint, 60);
  });
  window.__xxObserver.observe(document.body, { childList: true, subtree: true, characterData: true });
  paint();
})();
