// Family videos player page (euporious.family): chapter jumps, #t= deep links,
// current-chapter highlight, "copy link to this moment", AV1 fallback notice.
// No dependencies, nothing loaded from elsewhere.
(function () {
  "use strict";

  function parseTime(s) {
    // "184", "3:04", "1:02:03" -> seconds
    if (!s) return null;
    var parts = s.split(":").map(Number);
    if (parts.some(isNaN)) return null;
    return parts.reduce(function (acc, n) { return acc * 60 + n; }, 0);
  }

  function hashTime() {
    var m = /(?:^#|&)t=([0-9:.]+)/.exec(location.hash);
    return m ? parseTime(m[1]) : null;
  }

  function fmt(sec) {
    sec = Math.floor(sec);
    var h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
    var mm = h ? String(m).padStart(2, "0") : String(m);
    return (h ? h + ":" : "") + mm + ":" + String(s).padStart(2, "0");
  }

  function toast(text) {
    var el = document.createElement("div");
    el.className = "toast";
    el.setAttribute("role", "status");
    el.textContent = text;
    document.body.appendChild(el);
    setTimeout(function () { el.remove(); }, 2500);
  }

  function init() {
    var video = document.getElementById("video");
    if (!video) return;
    var chapters = Array.prototype.slice.call(document.querySelectorAll(".chapter"));
    var starts = chapters.map(function (a) { return parseFloat(a.dataset.start); });

    function seek(t, play) {
      if (t == null) return;
      var go = function () {
        video.currentTime = t;
        if (play) video.play().catch(function () {});
      };
      if (video.readyState >= 1) go();
      else video.addEventListener("loadedmetadata", go, { once: true });
    }

    // Deep link on load: seek, but don't autoplay (browsers would block it anyway).
    seek(hashTime(), false);

    chapters.forEach(function (a, i) {
      a.addEventListener("click", function (e) {
        e.preventDefault();
        history.replaceState(null, "", "#t=" + Math.floor(starts[i]));
        seek(starts[i], true);
        if (window.matchMedia("(max-width: 62rem)").matches) {
          video.scrollIntoView({ behavior: "smooth", block: "start" });
        }
      });
    });

    window.addEventListener("hashchange", function () { seek(hashTime(), true); });

    var current = -1;
    video.addEventListener("timeupdate", function () {
      var t = video.currentTime, idx = -1;
      for (var i = 0; i < starts.length; i++) if (t + 0.25 >= starts[i]) idx = i;
      if (idx !== current) {
        if (current >= 0) chapters[current].classList.remove("current");
        if (idx >= 0) chapters[idx].classList.add("current");
        current = idx;
      }
    });

    var copy = document.getElementById("copy-link");
    if (copy) {
      copy.addEventListener("click", function () {
        var t = Math.floor(video.currentTime || 0);
        var url = location.href.split("#")[0] + "#t=" + t;
        history.replaceState(null, "", "#t=" + t);
        if (navigator.clipboard) {
          navigator.clipboard.writeText(url).then(
            function () { toast("Link zu " + fmt(t) + " kopiert"); },
            function () { toast(url); });
        } else {
          toast(url);
        }
      });
    }

    // AV1 not supported (e.g. older Apple devices): the media element errors out.
    var notice = document.getElementById("no-av1");
    video.addEventListener("error", function () {
      var err = video.error;
      if (notice && err && (err.code === 3 || err.code === 4)) notice.hidden = false;
    });
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();
})();
