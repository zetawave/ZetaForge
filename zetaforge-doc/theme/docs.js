/**
 * Everything the documentation does in the browser.
 *
 * Four features, no framework: a theme toggle, client-side search, a table of
 * contents that follows the reader, and copy buttons on code blocks. The site
 * is entirely usable with JavaScript disabled — this only makes it pleasant.
 */
(function () {
  "use strict";

  var BASE = window.DOCS_BASE || "/";

  // -------------------------------------------------------------------------
  // Theme
  // -------------------------------------------------------------------------

  var root = document.documentElement;
  var toggle = document.querySelector(".theme-toggle");

  if (toggle) {
    toggle.addEventListener("click", function () {
      var next = root.dataset.theme === "dark" ? "light" : "dark";
      root.dataset.theme = next;
      try {
        localStorage.setItem("zf-theme", next);
      } catch (error) {
        /* private browsing: the choice simply does not persist */
      }
    });
  }

  // -------------------------------------------------------------------------
  // Mobile navigation
  // -------------------------------------------------------------------------

  var sidebar = document.getElementById("sidebar");
  var menu = document.querySelector(".menu-toggle");
  var backdrop = document.querySelector(".sidebar-backdrop");

  function setMenu(open) {
    if (!sidebar) return;
    sidebar.classList.toggle("open", open);
    if (backdrop) backdrop.hidden = !open;
    if (menu) menu.setAttribute("aria-expanded", String(open));
    document.body.style.overflow = open ? "hidden" : "";
  }

  if (menu) menu.addEventListener("click", function () { setMenu(!sidebar.classList.contains("open")); });
  if (backdrop) backdrop.addEventListener("click", function () { setMenu(false); });
  if (backdrop) backdrop.hidden = true;

  // -------------------------------------------------------------------------
  // Copy buttons
  // -------------------------------------------------------------------------

  document.querySelectorAll(".code-copy").forEach(function (button) {
    button.addEventListener("click", function () {
      var block = button.closest(".code-block");
      var code = block && block.querySelector("code");
      if (!code) return;

      var done = function () {
        button.textContent = "Copied";
        button.classList.add("copied");
        setTimeout(function () {
          button.textContent = "Copy";
          button.classList.remove("copied");
        }, 1600);
      };

      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(code.innerText).then(done, fallback);
      } else {
        fallback();
      }

      // Older browsers, and any page not served over https.
      function fallback() {
        var area = document.createElement("textarea");
        area.value = code.innerText;
        area.style.position = "fixed";
        area.style.opacity = "0";
        document.body.appendChild(area);
        area.select();
        try {
          document.execCommand("copy");
          done();
        } catch (error) {
          button.textContent = "Press Ctrl+C";
        }
        document.body.removeChild(area);
      }
    });
  });

  // -------------------------------------------------------------------------
  // Table of contents: highlight what is on screen
  // -------------------------------------------------------------------------

  var tocLinks = Array.prototype.slice.call(document.querySelectorAll(".toc a"));

  if (tocLinks.length && "IntersectionObserver" in window) {
    var targets = tocLinks
      .map(function (link) { return document.getElementById(link.hash.slice(1)); })
      .filter(Boolean);

    var visible = new Set();

    var observer = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) visible.add(entry.target.id);
          else visible.delete(entry.target.id);
        });

        // The topmost heading currently on screen is the one the reader is in.
        var current = null;
        for (var i = 0; i < targets.length; i++) {
          if (visible.has(targets[i].id)) { current = targets[i].id; break; }
        }
        if (!current) return;

        tocLinks.forEach(function (link) {
          link.classList.toggle("active", link.hash === "#" + current);
        });
      },
      // A band near the top of the viewport, so a heading counts as "current"
      // when it reaches reading position rather than when it first appears.
      { rootMargin: "-80px 0px -70% 0px", threshold: 0 },
    );

    targets.forEach(function (target) { observer.observe(target); });
  }

  // -------------------------------------------------------------------------
  // Search
  // -------------------------------------------------------------------------

  var input = document.getElementById("search-input");
  var results = document.getElementById("search-results");
  var index = null;
  var loading = false;
  var highlighted = -1;

  function loadIndex() {
    if (index || loading) return Promise.resolve(index);
    loading = true;
    return fetch(BASE + "assets/search-index.json")
      .then(function (response) { return response.json(); })
      .then(function (data) { index = data; loading = false; return data; })
      .catch(function () { loading = false; return null; });
  }

  if (input) {
    // The index is a few tens of KB; fetching it on first focus keeps it off
    // the critical path without making the first keystroke wait.
    input.addEventListener("focus", loadIndex, { once: true });

    input.addEventListener("input", function () {
      var query = input.value.trim();
      if (query.length < 2) return hideResults();
      loadIndex().then(function () { showResults(search(query)); });
    });

    input.addEventListener("keydown", function (event) {
      var items = results.querySelectorAll(".search-result");
      if (event.key === "Escape") { input.blur(); hideResults(); return; }
      if (!items.length) return;

      if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault();
        highlighted += event.key === "ArrowDown" ? 1 : -1;
        if (highlighted < 0) highlighted = items.length - 1;
        if (highlighted >= items.length) highlighted = 0;
        items.forEach(function (item, i) { item.classList.toggle("highlighted", i === highlighted); });
        items[highlighted].scrollIntoView({ block: "nearest" });
      } else if (event.key === "Enter" && highlighted >= 0) {
        event.preventDefault();
        items[highlighted].click();
      }
    });

    document.addEventListener("click", function (event) {
      if (!event.target.closest(".search")) hideResults();
    });

    // "/" focuses search, the way it does everywhere else a developer reads.
    document.addEventListener("keydown", function (event) {
      var typing = /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName);
      if (event.key === "/" && !typing && !event.metaKey && !event.ctrlKey) {
        event.preventDefault();
        input.focus();
      }
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        input.focus();
      }
    });
  }

  function hideResults() {
    if (!results) return;
    results.hidden = true;
    results.innerHTML = "";
    highlighted = -1;
  }

  /**
   * Ranked substring matching. Not fuzzy on purpose: on a set this size a
   * reader searching "schedule" wants the scheduling page first and nothing
   * clever, and a real fuzzy index would cost more code than the whole site.
   */
  function search(query) {
    if (!index) return [];
    var needle = query.toLowerCase();
    var words = needle.split(/\s+/).filter(Boolean);

    return index
      .map(function (page) {
        var title = page.title.toLowerCase();
        var score = 0;

        if (title === needle) score += 120;
        else if (title.indexOf(needle) === 0) score += 80;
        else if (title.indexOf(needle) >= 0) score += 55;

        if ((page.description || "").toLowerCase().indexOf(needle) >= 0) score += 25;

        var heading = null;
        for (var i = 0; i < page.headings.length; i++) {
          var text = page.headings[i].text.toLowerCase();
          if (text.indexOf(needle) >= 0) {
            score += 35;
            if (!heading) heading = page.headings[i];
            break;
          }
        }

        var body = page.text.toLowerCase();
        var position = body.indexOf(needle);
        if (position >= 0) score += 14;

        // Every word matching somewhere still counts, so a two-word query does
        // not fall to nothing when the phrasing differs.
        words.forEach(function (word) {
          if (title.indexOf(word) >= 0) score += 8;
          else if (body.indexOf(word) >= 0) score += 3;
        });

        return { page: page, score: score, heading: heading, position: position };
      })
      .filter(function (hit) { return hit.score > 0; })
      .sort(function (a, b) { return b.score - a.score; })
      .slice(0, 8);
  }

  function showResults(hits) {
    if (!results) return;
    highlighted = -1;

    if (!hits.length) {
      results.innerHTML = '<p class="search-empty">Nothing matched.</p>';
      results.hidden = false;
      return;
    }

    results.innerHTML = hits
      .map(function (hit) {
        var href = BASE + hit.page.slug + "/" + (hit.heading ? "#" + hit.heading.id : "");
        var snippet = hit.heading
          ? hit.heading.text
          : hit.page.description || hit.page.text.slice(0, 110) + "…";
        return (
          '<a class="search-result" href="' + href + '">' +
          '<span class="r-section">' + escapeHtml(hit.page.section) + "</span>" +
          '<span class="r-title">' + escapeHtml(hit.page.title) + "</span>" +
          '<span class="r-snippet">' + escapeHtml(snippet) + "</span>" +
          "</a>"
        );
      })
      .join("");
    results.hidden = false;
  }

  function escapeHtml(value) {
    return String(value).replace(/[&<>"]/g, function (character) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[character];
    });
  }
})();
