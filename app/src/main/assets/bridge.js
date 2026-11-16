/* Android bridge (free version): gives the page the same claude.use() it has on the web,
   backed entirely by the phone: on-device translation and text reading (Google ML Kit),
   Android speech recognition and Android text-to-speech. No account, no API key. */
(function () {
  "use strict";
  if (!window.AndroidBridge) return;
  /* languages the on-device translator does not have */
  window.__hideLangs = ["ku", "sr", "so", "am", "ti"];

  var pending = {}, n = 0, dictH = { partial: null, final: null, state: null };

  window.__native = {
    done: function (id, code, body) { var p = pending[id]; delete pending[id]; if (p) p(code, body); },
    dict: function (kind, text) {
      if (kind === "partial" && dictH.partial) dictH.partial({ text: text });
      else if (kind === "final" && dictH.final) dictH.final({ text: text });
      else if (kind === "state" && dictH.state) dictH.state({ state: text, reason: "stopped" });
      else if ((kind === "denied" || kind === "error") && dictH.state) dictH.state({ state: "idle", reason: kind });
    }
  };

  function b64(blob) {
    return new Promise(function (res, rej) {
      var r = new FileReader();
      r.onload = function () { res(String(r.result).split(",")[1]); };
      r.onerror = rej; r.readAsDataURL(blob);
    });
  }
  function call(signal, send) {
    return new Promise(function (res, rej) {
      var id = "r" + (++n);
      if (signal) {
        if (signal.aborted) { rej({ code: "aborted" }); return; }
        signal.addEventListener("abort", function () { delete pending[id]; rej({ code: "aborted" }); });
      }
      pending[id] = function (code, body) { if (code === 200) res(body); else rej({ code: body || "failed" }); };
      send(id);
    });
  }

  function sample(input, opts) {
    opts = opts || {};
    var job = window.__job || {};
    window.__job = null;
    var work;
    if (job.kind === "translate") {
      work = call(opts.signal, function (id) { window.AndroidBridge.translate(id, String(job.text), job.from || "", job.to); });
    } else if (job.kind === "ocr") {
      var imgs = opts.images || [], parts = [];
      work = imgs.reduce(function (chain, blob) {
        return chain.then(function () { return b64(blob); })
          .then(function (d) { return call(opts.signal, function (id) { window.AndroidBridge.ocr(id, d); }); })
          .then(function (t) { if (t && t.trim()) parts.push(t.trim()); });
      }, Promise.resolve()).then(function () { return parts.length ? parts.join("\n\n") : "∅"; });
    } else {
      return Promise.reject({ code: "failed" });
    }
    return work.then(function (t) {
      if (opts.onText) opts.onText({ text: t, delta: t });
      return { text: t, truncated: false };
    });
  }
  sample.limits = function () { return Promise.resolve({ images: true }); };

  var tts = {
    voices: [["", "صوت الجهاز"]],
    speak: function (text, o) { o = o || {}; window.AndroidBridge.speak(String(text), o.language || "", Number(o.rate) || 1); return Promise.resolve(); },
    stop: function () { window.AndroidBridge.stopSpeak(); },
    setRate: function () {}, onState: function () {}
  };
  var dictation = {
    start: function () { window.AndroidBridge.listen(window.__dictLang || ""); return Promise.resolve(); },
    stop: function () { window.AndroidBridge.stopListen(); return Promise.resolve(); },
    onPartial: function (f) { dictH.partial = f; },
    onFinal: function (f) { dictH.final = f; },
    onState: function (f) { dictH.state = f; }
  };
  var caps = { sample: sample, tts: tts, dictation: dictation };
  window.claude = { use: function (name) { return Promise.resolve(caps[name] || null); } };
})();
