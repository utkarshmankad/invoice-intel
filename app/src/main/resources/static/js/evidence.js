// Document page (REV-2, architecture.md §6.8): hovering or focusing a field highlights its
// evidence box on the page overlay, and hovering a box highlights its field.
(function () {
  "use strict";

  function toggle(path, on) {
    document.querySelectorAll("[data-field]").forEach(function (el) {
      if (el.getAttribute("data-field") === path) {
        el.classList.toggle("active", on);
      }
    });
  }

  document.querySelectorAll("[data-field]").forEach(function (el) {
    var path = el.getAttribute("data-field");
    ["mouseenter", "focusin"].forEach(function (type) {
      el.addEventListener(type, function () { toggle(path, true); });
    });
    ["mouseleave", "focusout"].forEach(function (type) {
      el.addEventListener(type, function () { toggle(path, false); });
    });
  });
})();
