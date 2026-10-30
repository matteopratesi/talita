// JavaScript of the app: the only script besides htmx. No inline scripts in the templates.
//
// What it does (docs/design/README.md, section 5):
// - the 404 and 500 pages show up as normal pages when they arrive from a link of the app, and
//   when the back button leads to them; the same for the 403 of a page that the person cannot see,
//   while a request that does not change the page and is refused (403) shows the message «Accesso negato»;
// - a form that comes back with its errors (422), with a limit (429) or with a refusal that depends on the state of things (409,
//   for example a step of a gathering that is no longer possible) replaces the page or the piece of page that sent it, with the
//   fields still filled in, and the focus goes to the first wrong field or to the notice; it leaves no second entry in the history;
// - a request that does not change the page and gets a 5xx, a missing network or no answer in
//   time leaves the page as it is and shows a pop-up message (texts are in the layout, as <template>);
//   the same for the back button without network;
// - touching another link while a page change is on its way: the last touch wins;
// - the address of the information page of a gathering can be copied and shared (`data-azione="copia"` and `"condividi"`, shown only if the
//   browser can) and the preview, the register, the list for the venue and the sheet for the teachers can be printed (`"stampa"`, hidden in the page and shown here):
//   the texts are in the template, the code only does;
// - the list of the arrivals can be searched by name (`data-filtro`, shown only here: without JavaScript the list is whole): the search runs in the
//   browser on the names that are in the page, asks the server nothing and writes no name in an address or in a log;
// - after a piece of a page is replaced the focus goes to the first wrong field or to its notice, without moving the page when the element is already in sight
//   (the choices of «Camere» are made one after the other on a long page);
// - the form that sends a file (`data-carica`, the menu of the gathering) is not taken by htmx: the browser sends it and shows its own progress; while the file
//   leaves, the form takes the class that htmx gives to a request on its way (so the button says «Caricamento…» and the page dims, as for every other request)
//   and its button is disabled, so a second tap does not send the file twice;
// - after a change of page the focus goes to the heading, so a screen reader announces it; if the address has a fragment
//   (`/convivenza/12/date#luogo`, a link of «Prima di aprire le iscrizioni») it goes to the element with that id, the field to fix.
// The loading indicator is CSS on the `htmx-request` class: it needs no code, except for one detail
// (see the beforeSwap listener).
"use strict";

(function () {
  const TEMPLATES = {
    network: "messaggio-rete-assente",
    slow: "messaggio-rete-lenta",
    server: "messaggio-errore-server",
    forbidden: "messaggio-accesso-negato",
  };

  /** What failed: the element that made the request, the verb, whether it was a link of the app. */
  function describe(detail) {
    const config = detail.requestConfig || {};
    return {
      elt: config.elt || detail.elt,
      verb: String(config.verb || "").toLowerCase(),
      boosted: Boolean(detail.boosted || config.boosted),
      eventType: config.triggeringEvent ? config.triggeringEvent.type : null,
    };
  }

  /** The same request again, as if the person had touched the element a second time. */
  function retry(request) {
    if (request.eventType === "click") {
      request.elt.click();
    } else {
      htmx.trigger(request.elt, request.eventType || "click");
    }
  }

  /**
   * The dialogs that are open are in the top layer of the page, above everything: a message that appears
   * while one is open would stay under its backdrop, invisible. When a request fails the dialogs are closed
   * first, so that the message is seen and the page is as the person left it.
   */
  function closeDialogs() {
    document.querySelectorAll("dialog[open]").forEach((dialog) => dialog.close());
  }

  function dismissAll() {
    const area = document.getElementById("messaggi");
    if (area) area.replaceChildren();
  }

  /** One message per kind: a new one replaces the old. Errors stay until closed or until a request succeeds. */
  function showMessage(kind, request) {
    const area = document.getElementById("messaggi");
    const template = document.getElementById(TEMPLATES[kind]);
    if (!area || !template) return;

    closeDialogs();
    area.querySelectorAll('[data-messaggio="' + TEMPLATES[kind] + '"]').forEach((old) => old.remove());

    const message = template.content.firstElementChild.cloneNode(true);
    const retryButton = message.querySelector('[data-azione="riprova"]');
    // "Riprova" only for a read: a form that was sent stays filled in and is sent again by touching its button.
    // (The message of a refused request has no such button at all: to repeat it changes nothing.)
    if (retryButton) {
      if (request.verb === "get" && request.elt && request.elt.isConnected) {
        retryButton.addEventListener("click", () => {
          message.remove();
          retry(request);
        });
      } else {
        retryButton.remove();
      }
    }
    message.querySelector('[data-azione="chiudi"]').addEventListener("click", () => message.remove());
    area.appendChild(message);
  }

  /**
   * Even with the history cache switched off, htmx 2 keeps the current path of the page in
   * sessionStorage. The path may carry a search, and the criterion is that the browser keeps nothing.
   * htmx does not need it: it falls back to the address of the page.
   */
  function forgetPath() {
    try {
      sessionStorage.removeItem("htmx-current-path-for-history");
    } catch (error) {
      // storage not available: nothing to forget
    }
  }

  function focusHeading() {
    const heading = document.querySelector("main h1");
    if (heading) heading.focus({ preventScroll: true });
  }

  /** The element is inside the screen, between the bars that cover the top and the bottom of the phone (the scroll padding of the page says how much). */
  function isInView(element) {
    const rect = element.getBoundingClientRect();
    const style = getComputedStyle(document.documentElement);
    const top = parseFloat(style.scrollPaddingTop) || 0;
    const bottom = parseFloat(style.scrollPaddingBottom) || 0;
    return rect.top >= top && rect.bottom <= window.innerHeight - bottom;
  }

  /** The element that the fragment of the address names (`#luogo`), if there is one. */
  function fragmentTarget() {
    if (!location.hash) return null;
    try {
      return document.getElementById(decodeURIComponent(location.hash.slice(1)));
    } catch (error) {
      return null;
    }
  }

  /**
   * After a page or a piece of it has been replaced, the focus goes where the person must look: the
   * first wrong field (a screen reader reads its error through aria-describedby), else the first
   * notice (data-fuoco), else, if the page changed, the element that the fragment of the address names (the
   * field that a link says to fix), else the heading.
   */
  function focusAfterSwap(pageChanged, root) {
    const scope = root || document;
    const target = scope.querySelector('[aria-invalid="true"]') || scope.querySelector("[data-fuoco]");
    if (target) {
      // On a long page (thirty-five children in «Camere») the notice is where the person touched: if it is already in sight the focus does not move the page, so whoever
      // puts twenty children in a room one after the other does not go back to the top each time; if it is not, the focus scrolls to it as always.
      target.focus({ preventScroll: isInView(target) });
    } else if (pageChanged) {
      const named = fragmentTarget();
      if (named) {
        named.focus();
      } else {
        focusHeading();
      }
    }
  }

  // ---- A page change in flight: the last touch wins ----

  // Only the page changes (what replaces the whole body, and the requests of the back and forward
  // buttons) are synchronized with each other. A request for a fragment of the page never cancels a
  // page change, nor another fragment.
  let pageChange = null;

  function cancelPageChange() {
    if (pageChange && pageChange.readyState !== XMLHttpRequest.DONE && pageChange.readyState !== XMLHttpRequest.UNSENT) {
      pageChange.abort();
    }
  }

  // Before htmx decides what to do with a second request of the same element (it would queue it).
  document.addEventListener("htmx:confirm", (event) => {
    if (event.detail.target === document.body) cancelPageChange();
  });
  document.addEventListener("htmx:beforeRequest", (event) => {
    if (event.detail.target === document.body) pageChange = event.detail.xhr;
  });

  // ---- Answers ----

  document.addEventListener("htmx:beforeSwap", (event) => {
    const status = event.detail.xhr.status;
    const request = describe(event.detail);

    if (status >= 400) {
      if (status === 409 || status === 422 || status === 429) {
        // A form sent again with its errors, a limit, or a request that the state of things refuses (409): it is the answer, not a failure.
        event.detail.shouldSwap = true;
        event.detail.isError = false;
      } else if (status === 403) {
        if (request.boosted && request.verb === "get") {
          // A link, or the back button, to a page that the person cannot see: the page of the 403, like any other page.
          event.detail.shouldSwap = true;
          event.detail.isError = false;
        } else {
          // Any other request (a form, a piece of a page): the page stays as it is and a message says no.
          event.detail.shouldSwap = false;
          showMessage("forbidden", request);
        }
      } else if (request.boosted && (status === 404 || status === 500)) {
        // The error pages of the app replace the page like any other, with their own address and title.
        event.detail.shouldSwap = true;
        event.detail.isError = false;
      } else if (status >= 500 || request.boosted || request.verb !== "get") {
        // A server error; or a refusal of a form of a piece of a page (a 404 for a registration that is not there, asked by a touch on a button): a request that
        // changes something is never left without an answer. A read of a piece of a page that gets a 4xx is left to htmx, as before.
        event.detail.shouldSwap = false;
        showMessage("server", request);
      }
    }

    // The new body is put in place before the old one is taken away. If the touched link still had the
    // class of its request, the new loading bar would be born visible and then fade: a flash on every
    // fast answer. So the request is declared over before the page is swapped.
    if (event.detail.shouldSwap && event.detail.target === document.body && request.elt) {
      request.elt.classList.remove("htmx-request");
    }
  });

  document.addEventListener("htmx:sendError", (event) => showMessage("network", describe(event.detail)));
  document.addEventListener("htmx:timeout", (event) => showMessage("slow", describe(event.detail)));

  document.addEventListener("htmx:afterRequest", (event) => {
    if (event.detail.successful) dismissAll();
  });

  // ---- The back button ----

  // The address of the page on screen (the one htmx last put there).
  let shownPath = location.pathname + location.search;
  function rememberPath() {
    shownPath = location.pathname + location.search;
  }

  // htmx 2 replaces on a back only the answers 2xx and 3xx. The 404 and 500 pages are normal pages
  // with their own address: the server renders them right, so the page is loaded again.
  document.addEventListener("htmx:historyCacheMissLoadError", () => window.location.reload());

  // htmx says nothing when the network fails on a back, and sets no time limit. Without a network the
  // page on screen stays, a message says so, and the address goes back to the one of that page, so
  // that address and page never disagree. "Riprova" goes to the page the back button led to.
  document.addEventListener("htmx:historyCacheMiss", (event) => {
    cancelPageChange();
    const xhr = event.detail.xhr;
    // The request of a back is a page change like the others: the next gesture (another back, a
    // forward, a touch on an entry) cancels it, and its late answer does not replace the right page.
    // An abort fires no `error`, so no message either.
    pageChange = xhr;
    const wanted = location.pathname + location.search;
    xhr.timeout = 20000;
    xhr.addEventListener("error", () => backFailed("network", wanted));
    xhr.addEventListener("timeout", () => backFailed("slow", wanted));
  });

  function backFailed(kind, wanted) {
    history.replaceState({ htmx: true }, "", shownPath);
    // A link to the wanted page, as the others of the app: "Riprova" touches it.
    const link = document.createElement("a");
    link.href = wanted;
    link.hidden = true;
    document.body.append(link);
    htmx.process(link);
    showMessage(kind, { elt: link, verb: "get", eventType: "click" });
  }

  // ---- After a change of page ----

  document.addEventListener("htmx:afterSettle", (event) => {
    const pageChanged = describe(event.detail).boosted;
    if (pageChanged) {
      focusAfterSwap(true);
      return;
    }
    // A piece of a page that was replaced (a block that every action of a page answers with): the focus goes to what is
    // wrong in it or to its notice, and nowhere if it has neither. The target of an outerHTML swap is the old element,
    // gone from the page: the new one has the same id.
    const swapped = event.detail.target;
    const root = swapped && swapped.isConnected ? swapped : swapped && swapped.id ? document.getElementById(swapped.id) : null;
    if (root) focusAfterSwap(false, root);
  });

  // ---- Dialogs ----

  // A button with data-apri="<id>" opens the dialog with that id (a native <dialog>: Esc, focus kept inside,
  // the page behind inert, with no more code). Without JavaScript the button sends its form, which leads to a page
  // with the same words; with it, the form is not sent.
  // The listener is on the capture phase: a link that opens a dialog (the step of a gathering) is also a link that htmx boosts, and htmx
  // listens on the link itself and would start the page change before this listener, on the way up, could stop it.
  document.addEventListener(
    "click",
    (event) => {
      const opener = event.target.closest("[data-apri]");
      if (!opener) return;
      const dialog = document.getElementById(opener.getAttribute("data-apri"));
      if (!dialog || typeof dialog.showModal !== "function") return;
      event.preventDefault();
      event.stopPropagation();
      if (!dialog.open) {
        dialog.showModal();
        // The first focus is on «Annulla», at the bottom of the panel, and the browser scrolls it into view: on a small phone, where the panel is taller than
        // the screen, it would open already scrolled to the bottom, with the title and what is deleted now above the edge. The focus stays on «Annulla» (the
        // screen reader reads the title all the same, it is the name of the dialog) and the panel goes back to its top.
        dialog.scrollTop = 0;
      }
    },
    true,
  );

  // ---- The search by name in the list of the arrivals ----

  const SEARCH_ANNOUNCE_MS = 250;
  let searchTimer = null;

  /** How a name and what is typed are compared: no capitals, no accents, no apostrophes (so «dangelo» finds D'Angelo and «nicolo» finds Nicolò). */
  function plain(text) {
    return text.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase().replace(/['\u2019\u02bc]/g, "");
  }

  /**
   * Shows the rows in which every word that was typed is in the name or in the surname, and nothing else: the group without rows goes away, with no result the
   * card «Nessun bambino con questo nome» comes, and the summary gives way to the results. [announce]: say how many were found, after a pause in the typing.
   * Run again after every replacement of a piece of the page, so that a row that came back is shown or hidden like the others.
   */
  function applySearch(announce) {
    const field = document.getElementById("cerca");
    const block = document.querySelector("[data-filtro]");
    if (!field || !block) return;
    block.hidden = false;
    const typed = plain(field.value).split(/\s+/).filter(Boolean);
    let found = 0;
    document.querySelectorAll("[data-filtro-riga]").forEach((row) => {
      const target = row.querySelector("[data-filtro-testo]");
      const name = target ? plain(target.textContent) : "";
      const shown = typed.every((word) => name.includes(word));
      row.hidden = !shown;
      if (shown) found += 1;
    });
    document.querySelectorAll("[data-filtro-gruppo]").forEach((group) => {
      group.hidden = !group.querySelector("[data-filtro-riga]:not([hidden])");
    });
    const none = document.querySelector("[data-filtro-vuoto]");
    if (none) none.hidden = !(typed.length > 0 && found === 0);
    const summary = document.getElementById("riepilogo");
    if (summary) summary.hidden = typed.length > 0;
    if (!announce) return;
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      const status = document.getElementById("cerca-esito");
      if (!status) return;
      if (typed.length === 0) {
        status.textContent = "";
      } else if (found === 0) {
        status.textContent = "Nessun bambino con questo nome.";
      } else {
        status.textContent = found === 1 ? "Trovato 1 bambino." : "Trovati " + found + " bambini.";
      }
    }, SEARCH_ANNOUNCE_MS);
  }

  document.addEventListener("input", (event) => {
    if (event.target && event.target.id === "cerca") applySearch(true);
  });
  // «Invio» closes the keyboard and marks nobody: the field is not in a form.
  document.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && event.target && event.target.id === "cerca") {
      event.preventDefault();
      event.target.blur();
    }
  });
  document.addEventListener("htmx:afterSettle", () => applySearch(false));
  document.addEventListener("DOMContentLoaded", () => applySearch(false));
  applySearch(false);

  // ---- Copy, share and print the information page ----

  const COPIED_FOR_MS = 3000;
  let copiedTimer = null;

  /**
   * The buttons that copy and share are in the page hidden: they are shown only where the browser can do it. Without JavaScript the address is text to select. The
   * button that prints a document (the register, the list for the venue, the sheet for the teachers) is hidden in the same way and shown here: without JavaScript there
   * is no button, and the line of help under it says what to do.
   */
  function showCopyAndShare() {
    document.querySelectorAll('[data-azione="stampa"]').forEach((button) => {
      button.hidden = false;
    });
    const canCopy = Boolean(navigator.clipboard && typeof navigator.clipboard.writeText === "function");
    const canShare = typeof navigator.share === "function";
    document.querySelectorAll('[data-azione="copia"]').forEach((button) => {
      button.hidden = !canCopy;
    });
    document.querySelectorAll('[data-azione="condividi"]').forEach((button) => {
      button.hidden = !canShare;
    });
  }

  function copyLink(button) {
    const source = document.getElementById(button.getAttribute("data-copia"));
    const normal = button.querySelector("[data-copia-normale]");
    const done = button.querySelector("[data-copia-fatto]");
    const status = document.querySelector("[data-copia-stato]");
    const failure = document.querySelector("[data-copia-errore]");
    if (!source || !navigator.clipboard) return;
    navigator.clipboard.writeText(source.textContent.trim()).then(
      () => {
        // The confirmation is where the person touched: the button says it for three seconds, and a region for screen readers repeats it.
        if (failure) failure.hidden = true;
        if (normal) normal.hidden = true;
        if (done) done.hidden = false;
        if (status) status.textContent = status.getAttribute("data-copia-annuncio") || "";
        clearTimeout(copiedTimer);
        copiedTimer = setTimeout(() => {
          if (normal) normal.hidden = false;
          if (done) done.hidden = true;
          if (status) status.textContent = "";
        }, COPIED_FOR_MS);
      },
      () => {
        // It did not work (no permission, an old browser): the address is selected, the person presses and holds to copy it.
        if (failure) failure.hidden = false;
        window.getSelection().selectAllChildren(source);
      },
    );
  }

  function shareLink(button) {
    const source = document.getElementById(button.getAttribute("data-indirizzo-da"));
    if (!source || typeof navigator.share !== "function") return;
    // If the person cancels the sheet nothing happens, and nothing is said.
    navigator
      .share({ title: button.getAttribute("data-titolo"), text: button.getAttribute("data-testo"), url: source.textContent.trim() })
      .catch(() => {});
  }

  document.addEventListener("click", (event) => {
    const action = event.target.closest("[data-azione]");
    if (!action) return;
    switch (action.getAttribute("data-azione")) {
      case "copia":
        copyLink(action);
        break;
      case "condividi":
        shareLink(action);
        break;
      case "stampa":
        window.print();
        break;
      default:
        break;
    }
  });

  // The page is boosted: after every change of page the new buttons are shown (or not) again.
  document.addEventListener("htmx:afterSettle", showCopyAndShare);
  document.addEventListener("DOMContentLoaded", showCopyAndShare);
  showCopyAndShare();

  // ---- A form that sends a file ----

  // The form is a normal one (a whole page comes back), outside htmx, so the class of a request on its way is given here: `busy:`, `page-busy:` and `nav-busy:`
  // of the style sheet read it. Without JavaScript the browser shows its own progress and its own error.
  function setSending(form, sending) {
    form.classList.toggle("htmx-request", sending);
    form.querySelectorAll('button[type="submit"]').forEach((button) => {
      button.disabled = sending;
    });
  }

  document.addEventListener("submit", (event) => {
    const form = event.target;
    if (form && form.hasAttribute && form.hasAttribute("data-carica")) setSending(form, true);
  });

  // The back button can bring the page back as it was when it left, with the form in the middle of its sending: it is a form that can be used again.
  window.addEventListener("pageshow", () => {
    document.querySelectorAll("form[data-carica]").forEach((form) => setSending(form, false));
  });

  // A form that comes back with its errors stays at the address it had: no second entry in the history.
  document.addEventListener("htmx:beforeHistoryUpdate", (event) => {
    const status = event.detail.xhr ? event.detail.xhr.status : 0;
    if (status === 409 || status === 422 || status === 429) event.detail.history.type = "replace";
  });
  document.addEventListener("htmx:historyRestore", focusHeading);

  ["htmx:pushedIntoHistory", "htmx:replacedInHistory", "htmx:historyRestore"].forEach((name) =>
    document.addEventListener(name, rememberPath),
  );

  ["htmx:pushedIntoHistory", "htmx:replacedInHistory", "htmx:historyRestore", "htmx:afterSettle"].forEach((name) =>
    document.addEventListener(name, forgetPath),
  );
  document.addEventListener("DOMContentLoaded", forgetPath);
  window.addEventListener("load", forgetPath);
  forgetPath();
})();
