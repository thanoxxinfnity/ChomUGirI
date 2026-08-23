package com.chomugiri.app.core

/**
 * Why this file exists.
 *
 * Once the coder role is genuinely Kimi K3 — the same class of model Replit runs — the model is
 * no longer what separates the output. What separates it is that Replit's agent does not design
 * from scratch: it starts inside a scaffolded project where Tailwind and a real component library
 * (shadcn/ui) are already installed, so the model only composes a design system somebody else
 * already got right.
 *
 * This is the equivalent for a no-build, static-file target. The stylesheet below is written and
 * owned by the app — not generated per project — so its quality is fixed and does not depend on
 * the model having a good day. The model's job shrinks to writing semantic HTML against a known
 * class API, which is a far easier job to do well.
 *
 * It is injected into any web project that doesn't already ship its own theme.css, and the class
 * API is summarised for the model in [WEB_SCAFFOLD_CLASS_API].
 */
const val THEME_CSS_PATH = "theme.css"

/** Compact class reference handed to the coder model — the CSS itself never costs prompt tokens. */
const val WEB_SCAFFOLD_CLASS_API = """A design system is ALREADY PROVIDED at theme.css — you do not write it and must not
recreate it. Link it in <head> and compose these classes:

  <link rel="stylesheet" href="theme.css">

Layout:   .nav .nav-inner .brand .nav-links | .hero .hero-inner .eyebrow | .section .section-head
          .container | .grid .grid-2 .grid-3 .grid-4 | .footer | .stack (vertical rhythm)
Type:     .display .h1 .h2 .h3 .lead .muted .small | .gradient-text
Surfaces: .card .card-hover .panel .badge .divider
Actions:  .btn .btn-primary .btn-secondary .btn-ghost .btn-lg | .input .textarea .label .field
Theme:    dark by default; add data-theme="light" on <html> for the light palette.

ANIMATION — all wired already, you only add the class:
  .reveal            fade + rise as it scrolls into view (put it on section content)
  .reveal-left       .reveal-right      slide in from the side
  .reveal-scale      scale up into place
  .stagger           on a parent: its .reveal children come in one after another
  .float             gentle continuous float (hero art, a badge)
  .pulse-glow        soft pulsing accent glow (a CTA, a live dot)
  .spin-slow         very slow rotation (decorative rings/orbits)
  .marquee > .marquee-track   infinite horizontal scroll (logo strip, ticker)
  .typing            terminal-style caret on a line of text
  .shimmer           moving sheen across a surface (loading, hero panel)
  .tilt              subtle 3D tilt toward the cursor (cards) — JS already attached
  .count-up          number that animates from 0 — set data-to="1234" (and optional data-suffix)
  .progress > .progress-bar   set style="--val:72%" — fills when scrolled into view
  .parallax          set data-speed="0.3" — drifts slower than the page as you scroll
All of it respects prefers-reduced-motion automatically. Use motion where it means something
(a stat counting up, a hero settling in) — not on everything at once.

Tokens you may use directly: var(--bg) var(--surface) var(--border) var(--text) var(--muted)
var(--accent) var(--accent-2) var(--radius) var(--shadow)

Rules:
- Use these classes for structure and chrome. Only add a small extra <style> block for things
  genuinely specific to this page — never to restyle buttons/cards/nav that already exist here.
- Every page starts with .nav, has a .hero, then 3+ .section blocks, and ends with .footer.
- Put .reveal on section content so the page animates in as you scroll.
- Icons: inline <svg> with stroke="currentColor" — never emoji."""

/**
 * The design system itself. Deliberately hand-written rather than model-generated: this is the
 * one part of a generated site whose quality is guaranteed.
 */
const val THEME_CSS = """/* ChomuGiri design system — dark-first, no build step required. */
@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap');

:root {
  --bg: #08090c;
  --surface: #101218;
  --surface-2: #161923;
  --border: rgba(255,255,255,.09);
  --border-strong: rgba(255,255,255,.16);
  --text: #f2f4f8;
  --muted: #99a1b3;
  --accent: #7c5cff;
  --accent-2: #22d3ee;
  --accent-soft: rgba(124,92,255,.14);
  --radius: 16px;
  --radius-sm: 10px;
  --shadow: 0 18px 50px -12px rgba(0,0,0,.7);
  --shadow-sm: 0 2px 10px rgba(0,0,0,.35);
  --maxw: 1140px;
}

[data-theme="light"] {
  --bg: #ffffff;
  --surface: #f7f8fa;
  --surface-2: #eef0f5;
  --border: rgba(10,12,20,.10);
  --border-strong: rgba(10,12,20,.18);
  --text: #0d1017;
  --muted: #5b6273;
  --accent-soft: rgba(124,92,255,.10);
  --shadow: 0 18px 50px -18px rgba(15,20,40,.22);
  --shadow-sm: 0 1px 6px rgba(15,20,40,.10);
}

*, *::before, *::after { box-sizing: border-box; }
html { scroll-behavior: smooth; -webkit-text-size-adjust: 100%; }

body {
  margin: 0;
  background: var(--bg);
  color: var(--text);
  font-family: Inter, ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  font-size: 16px;
  line-height: 1.65;
  -webkit-font-smoothing: antialiased;
  overflow-x: hidden;
}

img, svg, video { max-width: 100%; display: block; }
a { color: inherit; text-decoration: none; }
:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; border-radius: 6px; }

/* ---------- layout ---------- */
.container { width: 100%; max-width: var(--maxw); margin-inline: auto; padding-inline: 22px; }
.section { padding: clamp(56px, 9vw, 108px) 0; }
.section-head { max-width: 640px; margin-bottom: clamp(28px, 5vw, 52px); }
.stack > * + * { margin-top: 16px; }
.center { text-align: center; margin-inline: auto; }

.grid { display: grid; gap: 20px; }
.grid-2 { grid-template-columns: repeat(2, minmax(0,1fr)); }
.grid-3 { grid-template-columns: repeat(3, minmax(0,1fr)); }
.grid-4 { grid-template-columns: repeat(4, minmax(0,1fr)); }
@media (max-width: 900px) { .grid-3, .grid-4 { grid-template-columns: repeat(2, minmax(0,1fr)); } }
@media (max-width: 640px) { .grid-2, .grid-3, .grid-4 { grid-template-columns: minmax(0,1fr); } }

/* ---------- nav ---------- */
.nav {
  position: sticky; top: 0; z-index: 50;
  background: color-mix(in srgb, var(--bg) 82%, transparent);
  backdrop-filter: saturate(160%) blur(14px);
  border-bottom: 1px solid var(--border);
}
.nav-inner {
  max-width: var(--maxw); margin-inline: auto; padding: 14px 22px;
  display: flex; align-items: center; justify-content: space-between; gap: 20px;
}
.brand { display: inline-flex; align-items: center; gap: 10px; font-weight: 700; letter-spacing: -.02em; font-size: 1.05rem; }
.nav-links { display: flex; align-items: center; gap: 26px; }
.nav-links a { color: var(--muted); font-size: .93rem; font-weight: 500; transition: color .18s ease; }
.nav-links a:hover { color: var(--text); }
@media (max-width: 720px) { .nav-links a:not(.btn) { display: none; } }

/* ---------- hero ---------- */
.hero { position: relative; padding: clamp(72px, 13vw, 152px) 0 clamp(56px, 9vw, 104px); overflow: hidden; }
.hero::before {
  content: ""; position: absolute; inset: -35% 0 auto 50%;
  width: min(1000px, 130vw); aspect-ratio: 1; transform: translateX(-50%);
  background: radial-gradient(circle, var(--accent-soft) 0%, transparent 66%);
  pointer-events: none;
}
.hero-inner { position: relative; max-width: var(--maxw); margin-inline: auto; padding-inline: 22px; }

.eyebrow {
  display: inline-flex; align-items: center; gap: 8px;
  padding: 6px 13px; margin-bottom: 22px;
  border: 1px solid var(--border-strong); border-radius: 999px;
  background: var(--surface);
  font-size: .8rem; font-weight: 500; color: var(--muted);
}

/* ---------- type ---------- */
.display, .h1, .h2, .h3 { margin: 0; letter-spacing: -.03em; line-height: 1.1; font-weight: 800; }
.display { font-size: clamp(2.5rem, 6.2vw, 4.4rem); }
.h1 { font-size: clamp(2rem, 4.6vw, 3.1rem); }
.h2 { font-size: clamp(1.6rem, 3.4vw, 2.3rem); letter-spacing: -.025em; }
.h3 { font-size: 1.17rem; font-weight: 700; letter-spacing: -.015em; }
.lead { font-size: clamp(1.03rem, 1.7vw, 1.2rem); color: var(--muted); margin: 18px 0 0; max-width: 60ch; }
.muted { color: var(--muted); }
.small { font-size: .87rem; }
.gradient-text {
  background: linear-gradient(105deg, var(--text) 20%, var(--accent) 62%, var(--accent-2) 100%);
  -webkit-background-clip: text; background-clip: text; color: transparent;
}

/* ---------- surfaces ---------- */
.card {
  background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius); padding: 26px; box-shadow: var(--shadow-sm);
}
.card-hover { transition: transform .22s ease, border-color .22s ease, box-shadow .22s ease; }
.card-hover:hover { transform: translateY(-4px); border-color: var(--border-strong); box-shadow: var(--shadow); }
.panel { background: var(--surface-2); border: 1px solid var(--border); border-radius: var(--radius); padding: 22px; }
.badge {
  display: inline-block; padding: 4px 11px; border-radius: 999px;
  background: var(--accent-soft); color: var(--accent);
  font-size: .76rem; font-weight: 600; letter-spacing: .01em;
}
.divider { height: 1px; background: var(--border); border: 0; margin: 0; }

/* ---------- actions ---------- */
.btn {
  display: inline-flex; align-items: center; justify-content: center; gap: 9px;
  padding: 11px 20px; border-radius: var(--radius-sm);
  border: 1px solid var(--border-strong); background: var(--surface); color: var(--text);
  font: inherit; font-size: .94rem; font-weight: 600; cursor: pointer; white-space: nowrap;
  transition: transform .16s ease, background .2s ease, border-color .2s ease, box-shadow .2s ease;
}
.btn:hover { transform: translateY(-1px); border-color: var(--accent); }
.btn:active { transform: translateY(0); }
.btn-primary {
  background: linear-gradient(135deg, var(--accent), #6d4bf0);
  border-color: transparent; color: #fff;
  box-shadow: 0 8px 22px -8px var(--accent);
}
.btn-primary:hover { box-shadow: 0 12px 30px -8px var(--accent); }
.btn-secondary { background: var(--surface-2); }
.btn-ghost { background: transparent; border-color: transparent; color: var(--muted); }
.btn-ghost:hover { color: var(--text); background: var(--surface); }
.btn-lg { padding: 14px 27px; font-size: 1rem; }

/* ---------- forms ---------- */
.field { display: flex; flex-direction: column; gap: 7px; }
.label { font-size: .86rem; font-weight: 600; color: var(--muted); }
.input, .textarea {
  width: 100%; padding: 11px 14px;
  background: var(--surface); color: var(--text);
  border: 1px solid var(--border); border-radius: var(--radius-sm);
  font: inherit; font-size: .95rem;
  transition: border-color .18s ease, box-shadow .18s ease;
}
.input:focus, .textarea:focus {
  outline: none; border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}
.textarea { min-height: 130px; resize: vertical; }
.input::placeholder, .textarea::placeholder { color: var(--muted); opacity: .75; }

/* ---------- footer ---------- */
.footer { border-top: 1px solid var(--border); padding: 40px 0; color: var(--muted); font-size: .9rem; }

/* ---------- scroll reveal ---------- */
.reveal, .reveal-left, .reveal-right, .reveal-scale {
  opacity: 0;
  transition: opacity .65s cubic-bezier(.22,.8,.3,1), transform .65s cubic-bezier(.22,.8,.3,1);
  will-change: opacity, transform;
}
.reveal        { transform: translateY(22px); }
.reveal-left   { transform: translateX(-34px); }
.reveal-right  { transform: translateX(34px); }
.reveal-scale  { transform: scale(.93); }
.reveal.is-visible, .reveal-left.is-visible,
.reveal-right.is-visible, .reveal-scale.is-visible { opacity: 1; transform: none; }

/* .stagger parent -> children arrive one after another */
.stagger > * { transition-delay: calc(var(--i, 0) * 90ms); }

/* ---------- continuous motion ---------- */
@keyframes cg-float { 0%,100% { transform: translateY(0); } 50% { transform: translateY(-12px); } }
.float { animation: cg-float 5.5s ease-in-out infinite; }

@keyframes cg-glow {
  0%,100% { box-shadow: 0 0 0 0 color-mix(in srgb, var(--accent) 45%, transparent); }
  50%     { box-shadow: 0 0 30px 6px color-mix(in srgb, var(--accent) 28%, transparent); }
}
.pulse-glow { animation: cg-glow 2.6s ease-in-out infinite; }

@keyframes cg-spin { to { transform: rotate(360deg); } }
.spin-slow { animation: cg-spin 26s linear infinite; }

/* ---------- marquee ---------- */
.marquee { overflow: hidden; position: relative; -webkit-mask-image: linear-gradient(90deg, transparent, #000 8%, #000 92%, transparent); mask-image: linear-gradient(90deg, transparent, #000 8%, #000 92%, transparent); }
.marquee-track { display: flex; gap: 44px; width: max-content; animation: cg-marquee 26s linear infinite; }
.marquee:hover .marquee-track { animation-play-state: paused; }
@keyframes cg-marquee { to { transform: translateX(-50%); } }

/* ---------- typing caret ---------- */
.typing { border-right: 2px solid var(--accent); padding-right: 3px; animation: cg-caret 1s step-end infinite; }
@keyframes cg-caret { 50% { border-color: transparent; } }

/* ---------- shimmer ---------- */
.shimmer { position: relative; overflow: hidden; }
.shimmer::after {
  content: ""; position: absolute; inset: 0;
  background: linear-gradient(105deg, transparent 38%, rgba(255,255,255,.10) 50%, transparent 62%);
  transform: translateX(-100%); animation: cg-shimmer 2.8s ease-in-out infinite;
}
@keyframes cg-shimmer { to { transform: translateX(100%); } }

/* ---------- tilt (JS supplies --rx/--ry) ---------- */
.tilt { transform: perspective(900px) rotateX(var(--rx, 0deg)) rotateY(var(--ry, 0deg)); transition: transform .18s ease-out; transform-style: preserve-3d; }

/* ---------- progress ---------- */
.progress { height: 7px; border-radius: 999px; background: var(--surface-2); overflow: hidden; }
.progress-bar { height: 100%; width: 0; border-radius: inherit; background: linear-gradient(90deg, var(--accent), var(--accent-2)); transition: width 1.1s cubic-bezier(.22,.8,.3,1); }
.progress.is-visible .progress-bar { width: var(--val, 0%); }

@media (prefers-reduced-motion: reduce) {
  html { scroll-behavior: auto; }
  *, *::before, *::after { animation: none !important; transition-duration: .01ms !important; }
  .reveal, .reveal-left, .reveal-right, .reveal-scale { opacity: 1; transform: none; }
  .progress-bar { width: var(--val, 0%); }
}
"""

/** Wires up .reveal without the model having to remember any of it. */
const val THEME_JS_PATH = "theme.js"

const val THEME_JS = """// ChomuGiri design system — scroll reveal, counters, tilt, parallax.
// Everything degrades to "just show it" when the browser can't help or the user asked for
// reduced motion, so a page is never left invisible because an effect didn't run.
(function () {
  var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var REVEAL = '.reveal, .reveal-left, .reveal-right, .reveal-scale, .progress';

  function showAll() {
    document.querySelectorAll(REVEAL).forEach(function (el) { el.classList.add('is-visible'); });
    document.querySelectorAll('.count-up').forEach(function (el) {
      el.textContent = (el.getAttribute('data-to') || el.textContent) + (el.getAttribute('data-suffix') || '');
    });
  }
  if (reduced || !('IntersectionObserver' in window)) { showAll(); return; }

  // stagger: index children so CSS can delay each one
  document.querySelectorAll('.stagger').forEach(function (parent) {
    Array.prototype.forEach.call(parent.children, function (child, i) {
      child.style.setProperty('--i', i);
    });
  });

  function countUp(el) {
    var to = parseFloat(el.getAttribute('data-to'));
    if (isNaN(to)) return;
    var suffix = el.getAttribute('data-suffix') || '';
    var decimals = (String(el.getAttribute('data-to')).split('.')[1] || '').length;
    var start = null, dur = 1400;
    function step(ts) {
      if (start === null) start = ts;
      var p = Math.min((ts - start) / dur, 1);
      var eased = 1 - Math.pow(1 - p, 3);
      el.textContent = (to * eased).toFixed(decimals) + suffix;
      if (p < 1) requestAnimationFrame(step);
    }
    requestAnimationFrame(step);
  }

  var io = new IntersectionObserver(function (entries) {
    entries.forEach(function (e) {
      if (!e.isIntersecting) return;
      e.target.classList.add('is-visible');
      if (e.target.classList.contains('count-up')) countUp(e.target);
      io.unobserve(e.target);
    });
  }, { rootMargin: '0px 0px -8% 0px', threshold: 0.06 });

  document.querySelectorAll(REVEAL + ', .count-up').forEach(function (el) { io.observe(el); });

  // marquee: duplicate the track once so the -50% loop is seamless
  document.querySelectorAll('.marquee-track').forEach(function (t) {
    if (t.dataset.cloned) return;
    t.dataset.cloned = '1';
    t.innerHTML += t.innerHTML;
  });

  // tilt toward the cursor (pointer devices only)
  if (window.matchMedia && window.matchMedia('(hover: hover)').matches) {
    document.querySelectorAll('.tilt').forEach(function (card) {
      card.addEventListener('mousemove', function (ev) {
        var r = card.getBoundingClientRect();
        var px = (ev.clientX - r.left) / r.width - 0.5;
        var py = (ev.clientY - r.top) / r.height - 0.5;
        card.style.setProperty('--ry', (px * 9).toFixed(2) + 'deg');
        card.style.setProperty('--rx', (-py * 9).toFixed(2) + 'deg');
      });
      card.addEventListener('mouseleave', function () {
        card.style.setProperty('--rx', '0deg');
        card.style.setProperty('--ry', '0deg');
      });
    });
  }

  // parallax drift
  var px = document.querySelectorAll('.parallax');
  if (px.length) {
    var ticking = false;
    window.addEventListener('scroll', function () {
      if (ticking) return;
      ticking = true;
      requestAnimationFrame(function () {
        var y = window.pageYOffset;
        px.forEach(function (el) {
          var sp = parseFloat(el.getAttribute('data-speed')) || 0.25;
          el.style.transform = 'translate3d(0,' + (y * sp).toFixed(1) + 'px,0)';
        });
        ticking = false;
      });
    }, { passive: true });
  }
})();
"""

/**
 * Adds the design system to a web project that doesn't already carry its own, and makes sure the
 * entry HTML actually links both. Non-web projects (no .html at all) are left untouched.
 */
fun withWebScaffold(files: List<GeneratedFile>): List<GeneratedFile> {
    if (files.none { it.path.endsWith(".html", ignoreCase = true) }) return files
    // A project that already ships these (e.g. a rebuild of an earlier one) keeps its own copies.
    val out = files.toMutableList()
    if (out.none { it.path.equals(THEME_CSS_PATH, ignoreCase = true) }) {
        out += GeneratedFile(THEME_CSS_PATH, THEME_CSS)
    }
    if (out.none { it.path.equals(THEME_JS_PATH, ignoreCase = true) }) {
        out += GeneratedFile(THEME_JS_PATH, THEME_JS)
    }
    return out.map { f ->
        if (!f.path.endsWith(".html", ignoreCase = true)) f else f.copy(content = ensureLinked(f.content))
    }
}

/**
 * The model is told to link theme.css, but a page whose <head> silently lost the tag would render
 * completely unstyled — so the link is repaired rather than trusted.
 */
private fun ensureLinked(html: String): String {
    var out = html
    if (!out.contains(THEME_CSS_PATH, ignoreCase = true)) {
        val link = "<link rel=\"stylesheet\" href=\"$THEME_CSS_PATH\">"
        out = when {
            out.contains("</head>", ignoreCase = true) ->
                out.replaceFirst(Regex("(?i)</head>"), "  $link\n</head>")
            out.contains("<body", ignoreCase = true) ->
                out.replaceFirst(Regex("(?i)<body"), "$link\n<body")
            else -> "$link\n$out"
        }
    }
    if (!out.contains(THEME_JS_PATH, ignoreCase = true)) {
        val script = "<script src=\"$THEME_JS_PATH\"></script>"
        out = if (out.contains("</body>", ignoreCase = true)) {
            out.replaceFirst(Regex("(?i)</body>"), "  $script\n</body>")
        } else {
            "$out\n$script"
        }
    }
    return out
}
