package com.dustbook.app.utils

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Cosmetic filter engine, modelled on how Brave Shields and AdGuard actually
 * work rather than on hand-written heuristics.
 *
 * Every previous attempt guessed at Facebook's DOM shape and either left ads
 * on screen or removed the whole feed. Those guesses are gone. This loads real
 * rules published by uBlock Origin, AdGuard Base, AdGuard Social and AdGuard
 * Annoyances, and applies them the way an extension does:
 *
 *  - plain CSS selectors are injected as a stylesheet, so matching elements
 *    never paint at all
 *  - procedural rules - :has(), :has-text(), :contains(), :upward(),
 *    :matches-path() - are evaluated in JavaScript, because CSS cannot express
 *    them
 *
 * The important property is that a rule names exactly what to remove. There is
 * no walking up the tree hoping to find the right ancestor, so a rule cannot
 * take the page with it.
 */
object CosmeticFilters {

    private const val ASSET = "fb_cosmetic.txt"

    @Volatile private var plain: List<String> = emptyList()
    @Volatile private var procedural: List<String> = emptyList()

    @Volatile
    var isLoaded: Boolean = false
        private set

    fun plainCount(): Int = plain.size
    fun proceduralCount(): Int = procedural.size

    fun load(context: Context) {
        if (isLoaded) return
        synchronized(this) {
            if (isLoaded) return
            val p = ArrayList<String>()
            val q = ArrayList<String>()
            try {
                context.assets.open(ASSET).use { stream ->
                    BufferedReader(InputStreamReader(stream), 32 * 1024).use { r ->
                        var section = ""
                        var line = r.readLine()
                        while (line != null) {
                            val t = line.trim()
                            when {
                                t.isEmpty() || t.startsWith("!") -> {}
                                t == "[plain]" -> section = "plain"
                                t == "[procedural]" -> section = "proc"
                                section == "plain" -> p.add(t)
                                section == "proc" -> q.add(t)
                            }
                            line = r.readLine()
                        }
                    }
                }
            } catch (e: Exception) {
                // Asset missing: the built-in rules below still apply.
            }
            plain = p
            procedural = q
            isLoaded = true
        }
    }

    /** Escape a rule for embedding in a single-quoted JS string. */
    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'")

    /**
     * Stylesheet for the plain rules, injected at document start so matching
     * elements never render.
     */
    fun styleScript(): String {
        if (plain.isEmpty()) return "(function(){})();"
        val css = plain.joinToString(",") + "{display:none !important;}"
        return """
            (function() {
              var id = 'dustbook-cosmetic';
              if (document.getElementById(id)) return;
              var s = document.createElement('style');
              s.id = id;
              s.textContent = '${esc(css)}';
              (document.head || document.documentElement).appendChild(s);
            })();
        """.trimIndent()
    }

    /**
     * Procedural rule evaluator.
     *
     * Supports the operators the published Facebook rules actually use:
     *   selector:has(inner)          parent contains a match for inner
     *   selector:has-text(text)      element text contains text, /re/ allowed
     *   selector:contains(text)      alias of has-text
     *   selector:upward(n | sel)     climb n levels, or to the nearest sel
     *   selector:not(inner)          handled natively by querySelectorAll
     *   :matches-path(p)             only apply on matching paths
     */
    fun proceduralScript(): String {
        val list = procedural.joinToString(",") { "'${esc(it)}'" }
        return """
            (function() {
              'use strict';
              if (window.__dbCosmetic) { window.__dbCosmetic.run(); return; }

              var RULES = [$list];
              var TAG = 'data-db-hidden';

              // Comment content is never a rule target. Facebook's own lists
              // carry rules shaped like "the rounded card inside the card"
              // (their ad units and their comment cards share the same
              // border-radius shell), and those rules have repeatedly taken
              // the whole comment thread with them on the post page. Whatever
              // a rule matches, comment items, comment sections and anything
              // inside them are untouchable.
              function isCommentZone(el) {
                if (!el || el.nodeType !== 1 || !el.getAttribute) return false;
                var a = (el.getAttribute('data-sigil') || '').toLowerCase();
                if (a.indexOf('comment') !== -1) return true;
                a = (el.getAttribute('data-testid') || '').toLowerCase();
                if (a.indexOf('comment') !== -1) return true;
                a = (el.getAttribute('data-pagelet') || '').toLowerCase();
                if (a.indexOf('comment') !== -1) return true;
                a = (el.getAttribute('aria-label') || '').toLowerCase();
                if (a === 'comment' || a === 'comments' ||
                    a.indexOf('comment section') !== -1 ||
                    a.indexOf('comments on') !== -1) return true;
                return false;
              }

              function insideComments(el) {
                var n = el;
                for (var i = 0; i < 10 && n; i++) {
                  if (isCommentZone(n)) return true;
                  n = n.parentElement;
                }
                return false;
              }

              function hide(el) {
                if (!el || el.nodeType !== 1) return;
                var t = el.tagName;
                if (t === 'HTML' || t === 'BODY' || t === 'HEAD') return;
                if (isCommentZone(el) || insideComments(el)) return;
                if (el.hasAttribute(TAG)) return;
                el.setAttribute(TAG, '1');
                el.style.setProperty('display', 'none', 'important');
              }

              function textOf(el) {
                return (el.innerText || el.textContent || '');
              }

              // Split "a:has(b):upward(2)" into the base selector and its
              // operator chain, respecting nested brackets.
              function parse(rule) {
                var ops = [], base = '', depth = 0, i = 0, buf = '';
                while (i < rule.length) {
                  var c = rule[i];
                  if (c === '(') depth++;
                  if (c === ')') depth--;
                  if (c === ':' && depth === 0) {
                    var m = rule.slice(i).match(
                      /^:(has|has-text|contains|upward|matches-path|if)\(/
                    );
                    if (m) {
                      if (!base) base = buf;
                      buf = '';
                      var name = m[1];
                      var start = i + m[0].length;
                      var d = 1, j = start;
                      while (j < rule.length && d > 0) {
                        if (rule[j] === '(') d++;
                        else if (rule[j] === ')') d--;
                        if (d > 0) j++;
                      }
                      ops.push({ op: name, arg: rule.slice(start, j) });
                      i = j + 1;
                      continue;
                    }
                  }
                  buf += c;
                  i++;
                }
                if (!base) base = buf;
                return { base: base.trim() || '*', ops: ops };
              }

              function toRegex(arg) {
                var m = arg.match(/^\/(.*)\/([a-z]*)${'$'}/);
                if (m) { try { return new RegExp(m[1], m[2]); } catch (e) { return null; } }
                return null;
              }

              function apply(rule) {
                var parsed;
                try { parsed = parse(rule); } catch (e) { return; }

                // :matches-path gates the whole rule.
                for (var k = 0; k < parsed.ops.length; k++) {
                  if (parsed.ops[k].op === 'matches-path') {
                    var pr = toRegex(parsed.ops[k].arg);
                    var path = location.pathname;
                    var ok = pr ? pr.test(path) : path.indexOf(parsed.ops[k].arg) !== -1;
                    if (!ok) return;
                  }
                }

                var nodes;
                try { nodes = document.querySelectorAll(parsed.base); }
                catch (e) { return; }

                for (var i = 0; i < nodes.length && i < 3000; i++) {
                  var el = nodes[i];
                  var keep = true;

                  for (var o = 0; o < parsed.ops.length && keep; o++) {
                    var op = parsed.ops[o].op, arg = parsed.ops[o].arg;

                    if (op === 'has' || op === 'if') {
                      try { keep = !!el.querySelector(arg); }
                      catch (e) { keep = false; }
                    } else if (op === 'has-text' || op === 'contains') {
                      var re = toRegex(arg);
                      var txt = textOf(el);
                      keep = re ? re.test(txt) : txt.indexOf(arg) !== -1;
                    } else if (op === 'upward') {
                      var n = parseInt(arg, 10);
                      if (!isNaN(n)) {
                        for (var u = 0; u < n && el; u++) el = el.parentElement;
                      } else {
                        try { el = el.closest(arg); } catch (e) { el = null; }
                      }
                      if (!el) keep = false;
                    }
                  }

                  if (keep && el) hide(el);
                }
              }

              function run() {
                for (var i = 0; i < RULES.length; i++) apply(RULES[i]);
              }

              // Throttled observer: rules are cheap, but the feed mutates
              // constantly, so coalesce bursts.
              var queued = false, last = 0;
              function schedule() {
                if (queued) return;
                queued = true;
                var wait = Math.max(0, 250 - (Date.now() - last));
                setTimeout(function() {
                  queued = false;
                  last = Date.now();
                  (window.requestIdleCallback || window.requestAnimationFrame ||
                   function(f) { setTimeout(f, 0); })(run);
                }, wait);
              }

              var mo = new MutationObserver(function(muts) {
                for (var i = 0; i < muts.length; i++) {
                  if (muts[i].addedNodes && muts[i].addedNodes.length) { schedule(); return; }
                }
              });

              function start() {
                if (!document.body) return;
                mo.observe(document.body, { childList: true, subtree: true });
                run();
              }

              if (document.body) start();
              else document.addEventListener('DOMContentLoaded', start, { once: true });

              setTimeout(run, 600);
              setTimeout(run, 1800);
              setTimeout(run, 3500);

              window.__dbCosmetic = { run: run };
            })();
        """.trimIndent()
    }
}
