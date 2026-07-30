// Drag & drop + client-side size check for the one-time-secret file upload.
// Server-side validation remains authoritative; this is UX only.
(function () {
  var input = document.getElementById("ots-file");
  var zone = document.getElementById("ots-dropzone");
  if (!input || !zone) return;
  var nameEl = document.getElementById("ots-file-name");
  var errorEl = document.getElementById("ots-file-error");
  var maxBytes = parseInt(zone.dataset.maxBytes, 10);

  function humanSize(n) {
    if (n < 1024) return n + " B";
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KiB";
    return (n / (1024 * 1024)).toFixed(1) + " MiB";
  }

  function onChange() {
    errorEl.hidden = true;
    nameEl.textContent = "";
    var file = input.files[0];
    if (!file) return;
    if (file.size > maxBytes) {
      errorEl.textContent = errorEl.dataset.message;
      errorEl.hidden = false;
      input.value = "";
      return;
    }
    nameEl.textContent = file.name + " (" + humanSize(file.size) + ")";
  }

  input.addEventListener("change", onChange);

  ["dragover", "dragenter"].forEach(function (evt) {
    zone.addEventListener(evt, function (e) {
      e.preventDefault();
      zone.style.borderColor = "currentColor";
    });
  });
  ["dragleave", "drop"].forEach(function (evt) {
    zone.addEventListener(evt, function () {
      zone.style.borderColor = "";
    });
  });
  zone.addEventListener("drop", function (e) {
    e.preventDefault();
    if (e.dataTransfer && e.dataTransfer.files.length) {
      input.files = e.dataTransfer.files;
      onChange();
    }
  });
})();
