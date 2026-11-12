/* =========================================================
   xiondigital.net/shizuku — interaction layer
   Same vocabulary as xiondigital.net/arcade:
   synthesised interface sound (no audio files),
   pointer spotlight, scroll reveal, copy affordances.
   ========================================================= */

(function () {
  "use strict";

  const SFX_KEY = "arcade:sfx";
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  const AudioCtx = window.AudioContext || window.webkitAudioContext;

  /* -------------------------------------------------------
     Interface sound
     ------------------------------------------------------- */

  const sfx = {
    enabled: localStorage.getItem(SFX_KEY) !== "off",
    ctx: null,
    last: 0,

    init() {
      if (!AudioCtx || this.ctx) return;
      this.ctx = new AudioCtx();
    },

    // a soft "bubble": sine with a small upward glide and a fast decay
    tone({ from, to, dur = 0.12, type = "sine", peak = 0.05, delay = 0 }) {
      if (!this.enabled) return;
      this.init();
      if (!this.ctx) return;

      const ctx = this.ctx;
      if (ctx.state === "suspended") ctx.resume();

      const t0 = ctx.currentTime + delay;
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = type;
      osc.frequency.setValueAtTime(from, t0);
      osc.frequency.exponentialRampToValueAtTime(to, t0 + dur);

      gain.gain.setValueAtTime(0.0001, t0);
      gain.gain.exponentialRampToValueAtTime(peak, t0 + 0.012);
      gain.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);

      osc.connect(gain).connect(ctx.destination);
      osc.start(t0);
      osc.stop(t0 + dur + 0.02);
    },

    hover() {
      const now = performance.now();
      if (now - this.last < 55) return; // keep it from chattering
      this.last = now;
      this.tone({ from: 620, to: 880, dur: 0.1, peak: 0.045 });
    },

    select() {
      this.tone({ from: 340, to: 520, dur: 0.11, type: "triangle", peak: 0.06 });
    },

    launch() {
      this.tone({ from: 420, to: 660, dur: 0.1, peak: 0.05 });
      this.tone({ from: 660, to: 990, dur: 0.14, peak: 0.045, delay: 0.07 });
    },
  };

  const sfxToggle = document.getElementById("sfxToggle");

  function paintSfx() {
    if (!sfxToggle) return;
    sfxToggle.setAttribute("aria-pressed", String(sfx.enabled));
    sfxToggle.title = sfx.enabled ? "Interface sound: on" : "Interface sound: off";
  }

  if (sfxToggle) {
    paintSfx();
    sfxToggle.addEventListener("click", function () {
      sfx.enabled = !sfx.enabled;
      localStorage.setItem(SFX_KEY, sfx.enabled ? "on" : "off");
      paintSfx();
      if (sfx.enabled) sfx.select();
    });
  }

  // Browsers only allow audio after a gesture, so unlock quietly on the first one.
  ["pointerdown", "keydown"].forEach(function (evt) {
    window.addEventListener(
      evt,
      function () {
        sfx.init();
        if (sfx.ctx && sfx.ctx.state === "suspended") sfx.ctx.resume();
      },
      { once: true, passive: true }
    );
  });

  /* -------------------------------------------------------
     Hover / click sounds
     ------------------------------------------------------- */

  document
    .querySelectorAll(".btn, .nav__link, .footer__link, .sfx, .brand, .card, .shot, .step")
    .forEach(function (el) {
      el.addEventListener("pointerenter", function () {
        sfx.hover();
      });
    });

  document.querySelectorAll('a[href^="https://github.com"], .btn--primary').forEach(function (el) {
    el.addEventListener("click", function () {
      sfx.launch();
    });
  });

  document.querySelectorAll('[data-copy]').forEach(function (el) {
    el.addEventListener("click", function () {
      sfx.select();
    });
  });

  /* -------------------------------------------------------
     Pointer spotlight
     ------------------------------------------------------- */

  document.querySelectorAll(".card, .shot").forEach(function (card) {
    card.addEventListener("pointermove", function (event) {
      if (reducedMotion.matches) return;
      const rect = card.getBoundingClientRect();
      card.style.setProperty("--mx", event.clientX - rect.left + "px");
      card.style.setProperty("--my", event.clientY - rect.top + "px");
    });
  });

  /* -------------------------------------------------------
     Copy buttons
     ------------------------------------------------------- */

  function copyText(text, button) {
    const done = function () {
      if (!button) return;
      const original = button.dataset.label || button.textContent;
      button.dataset.label = original;
      button.textContent = "Copied";
      setTimeout(function () {
        button.textContent = original;
      }, 1600);
    };

    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, function () {});
      return;
    }

    const area = document.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "");
    area.style.position = "fixed";
    area.style.opacity = "0";
    document.body.appendChild(area);
    area.select();
    try {
      document.execCommand("copy");
      done();
    } catch (e) {
      /* clipboard unavailable; leave the button untouched */
    }
    document.body.removeChild(area);
  }

  document.querySelectorAll("[data-copy]").forEach(function (button) {
    button.addEventListener("click", function () {
      const target = document.querySelector(button.dataset.copy);
      if (target) copyText(target.innerText.trim(), button);
    });
  });

  /* -------------------------------------------------------
     Scroll reveal
     ------------------------------------------------------- */

  const revealables = Array.prototype.slice.call(document.querySelectorAll("[data-reveal]"));

  // IntersectionObserver is the usual tool here, but it does not fire in every context (hidden
  // tabs, some embedded browsers), and a section that never reveals is a blank page. Reveal from
  // layout position on scroll as well, so content is never gated on an observer callback.
  function revealPass() {
    const limit = window.innerHeight * 0.92;
    for (let i = revealables.length - 1; i >= 0; i--) {
      const el = revealables[i];
      if (el.classList.contains("is-visible")) {
        revealables.splice(i, 1);
        continue;
      }
      if (el.getBoundingClientRect().top < limit) el.classList.add("is-visible");
    }
    if (!revealables.length) {
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", onScroll);
    }
  }

  let queued = false;
  function onScroll() {
    if (queued) return;
    queued = true;
    requestAnimationFrame(function () {
      queued = false;
      revealPass();
    });
  }

  if (reducedMotion.matches) {
    revealables.forEach(function (el) {
      el.classList.add("is-visible");
    });
  } else {
    revealPass();
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onScroll, { passive: true });
    window.addEventListener("load", revealPass);
  }

  /* -------------------------------------------------------
     Section highlighting in the header
     ------------------------------------------------------- */

  const navLinks = Array.prototype.slice.call(document.querySelectorAll(".nav__link"));
  const sections = navLinks
    .map(function (link) {
      const id = link.getAttribute("href");
      return id && id.charAt(0) === "#" ? document.querySelector(id) : null;
    })
    .filter(Boolean);

  if (sections.length && "IntersectionObserver" in window) {
    const spy = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (!entry.isIntersecting) return;
          navLinks.forEach(function (link) {
            const active = link.getAttribute("href") === "#" + entry.target.id;
            link.setAttribute("aria-current", active ? "true" : "false");
          });
        });
      },
      { rootMargin: "-45% 0px -45% 0px" }
    );
    sections.forEach(function (section) {
      spy.observe(section);
    });
  }
})();
