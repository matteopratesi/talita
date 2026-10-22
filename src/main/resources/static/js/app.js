// JavaScript of the app: the only script besides htmx. No inline scripts in the templates.
//
// What it does (docs/design/README.md, section 5):
// - the 404 and 500 pages show up as normal pages when they arrive from a link of the app, and
//   when the back button leads to them; the same for the 403 of a page that the person cannot see,
//   while a request that does not change the page and is refused (403) shows the message «Accesso negato»;
// - a form that comes back with its errors (422) or with a limit (429) replaces the page or the piece
//   of page that sent it, with the fields still filled in, and the focus goes to the first wrong field
//   or to the notice; it leaves no second entry in the history;
// - a request that does not change the page and gets a 5xx, a missing network or no answer in
//   time leaves the page as it is and shows a pop-up message (texts are in the layout, as <template>);
//   the same for the back button without network;
// - touching another link while a page change is on its way: the last touch wins;
// - after a change of page the focus goes to the heading, so a screen reader announces it.
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

  /**
   * After a page or a piece of it has been replaced, the focus goes where the person must look: the
   * first wrong field (a screen reader reads its error through aria-describedby), else the first
   * notice (data-fuoco), else, if the page changed, the heading.
   */
  function focusAfterSwap(pageChanged, root) {
    const scope = root || document;
    const target = scope.querySelector('[aria-invalid="true"]') || scope.querySelector("[data-fuoco]");
    if (target) {
      target.focus();
    } else if (pageChanged) {
      focusHeading();
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
      if (status === 422 || status === 429) {
        // A form sent again with its errors, or a limit: it is the answer, not a failure.
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
      } else if (status >= 500 || request.boosted) {
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
  document.addEventListener("click", (event) => {
    const opener = event.target.closest("[data-apri]");
    if (!opener) return;
    const dialog = document.getElementById(opener.getAttribute("data-apri"));
    if (!dialog || typeof dialog.showModal !== "function") return;
    event.preventDefault();
    if (!dialog.open) dialog.showModal();
  });

  // A form that comes back with its errors stays at the address it had: no second entry in the history.
  document.addEventListener("htmx:beforeHistoryUpdate", (event) => {
    const status = event.detail.xhr ? event.detail.xhr.status : 0;
    if (status === 422 || status === 429) event.detail.history.type = "replace";
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
