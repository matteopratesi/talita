// JavaScript of the passkeys (TALITA-15): served by the app like app.js, never in line, never from outside. It does three things, each one only where the page has the markup for it:
//
// 1. «Entra con una passkey» (`data-azione="entra-passkey"`, in `/accedi`): asks the server for the options (`POST /webauthn/authenticate/options`), asks the phone for the passkey
//    (`navigator.credentials.get`), sends the answer (`POST /login/webauthn`) and goes where the server says. It adds nothing to the page: the blocks that say «Nessuna passkey usata.» and
//    «Questa passkey non funziona.» are in it already, hidden (`data-passkey-esito`), and the script shows one of them.
// 2. «Aggiungi una passkey» (`data-azione="aggiungi-passkey"`, in the profile): `POST /webauthn/register/options`, `navigator.credentials.create`, `POST /webauthn/register`, and then the block of the
//    passkeys is asked again to the server, which draws it with the new row.
// 3. The confirmation before the notes on health, and before «Togli» and «Aggiungi» for a teacher who has a passkey (`data-conferma`): the tap that would send the request is held (htmx:confirm), the
//    server is asked whether a confirmation is needed (`POST /verifica-passkey/opzioni`), and if it is the phone is asked for the fingerprint, the face or the code (userVerification: required) and the
//    answer is sent (`POST /verifica-passkey`); only then the request goes on. A page that shows nothing without the confirmation has a button `data-azione="conferma-pagina"` that does the same and
//    reloads the page.
//
// `data-conferma` has a value that says what the confirmation is for (`togli`, `aggiungi`, `segnalazioni`, `pagina`): the blocks of its outcomes are `data-conferma-esito="<value>-annullata"` and
// `"<value>-rifiutata"`.
//
// What is written in the page stays in the page: the texts (waiting, cancelled, refused) are in `<template data-attesa>` and in hidden blocks (`data-passkey-esito`, `data-conferma-esito`), the script
// only shows and hides them and moves the focus. `data-ambito` is the container of the waiting line (`data-stato`) and of its templates, `data-esiti` the container of the hidden blocks. The binary fields
// of WebAuthn travel as base64url, converted here by hand (`parseCreationOptionsFromJSON` does not exist on iOS 16). Every request carries the CSRF code of the page. Nothing is written in the log of the
// browser, and nothing about a passkey is kept: the script has no storage.
"use strict";

(function () {
  const SUPPORTED = typeof window.PublicKeyCredential === "function" && Boolean(navigator.credentials && navigator.credentials.create && navigator.credentials.get);
  const REQUEST_TIMEOUT_MS = 20000;

  // ---- What the page shows only if the browser can use passkeys ----

  /** What carries `data-passkey` is hidden in the page and shown here; what carries `data-passkey-no` («Questo browser non sa usare le passkey») is shown without support and hidden with it. */
  function reveal() {
    document.querySelectorAll("[data-passkey]").forEach((element) => {
      element.hidden = !SUPPORTED;
    });
    document.querySelectorAll("[data-passkey-no]").forEach((element) => {
      element.hidden = SUPPORTED;
    });
  }

  // ---- base64url, by hand ----

  function toBuffer(text) {
    let base64 = text.replace(/-/g, "+").replace(/_/g, "/");
    while (base64.length % 4 !== 0) base64 += "=";
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
    return bytes.buffer;
  }

  function toText(buffer) {
    const bytes = new Uint8Array(buffer);
    let binary = "";
    for (let index = 0; index < bytes.length; index += 1) binary += String.fromCharCode(bytes[index]);
    return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }

  function descriptors(list) {
    return (list || []).map((item) => {
      const descriptor = { type: item.type, id: toBuffer(item.id) };
      if (item.transports && item.transports.length) descriptor.transports = item.transports;
      return descriptor;
    });
  }

  /** The options of an assertion, from the JSON of the server to what `navigator.credentials.get` takes. */
  function requestOptions(json) {
    const options = { challenge: toBuffer(json.challenge), rpId: json.rpId, userVerification: json.userVerification, allowCredentials: descriptors(json.allowCredentials) };
    if (json.timeout) options.timeout = json.timeout;
    return options;
  }

  /** The options of a registration, from the JSON of the server to what `navigator.credentials.create` takes. */
  function creationOptions(json) {
    const options = {
      rp: json.rp,
      user: { id: toBuffer(json.user.id), name: json.user.name, displayName: json.user.displayName },
      challenge: toBuffer(json.challenge),
      pubKeyCredParams: json.pubKeyCredParams,
      excludeCredentials: descriptors(json.excludeCredentials),
      authenticatorSelection: json.authenticatorSelection,
      attestation: json.attestation,
    };
    if (json.timeout) options.timeout = json.timeout;
    return options;
  }

  /** What the phone made or answered, in the JSON that the server reads. */
  function credentialToJson(credential) {
    const response = credential.response;
    const json = { id: credential.id, rawId: toText(credential.rawId), type: credential.type, clientExtensionResults: {} };
    if (credential.authenticatorAttachment) json.authenticatorAttachment = credential.authenticatorAttachment;
    if (response.attestationObject) {
      json.response = { attestationObject: toText(response.attestationObject), clientDataJSON: toText(response.clientDataJSON) };
      if (typeof response.getTransports === "function") json.response.transports = response.getTransports();
    } else {
      json.response = { authenticatorData: toText(response.authenticatorData), clientDataJSON: toText(response.clientDataJSON), signature: toText(response.signature) };
      if (response.userHandle) json.response.userHandle = toText(response.userHandle);
    }
    return json;
  }

  // ---- The server ----

  /** The CSRF code of the page: the one of any of its forms. */
  function csrf() {
    const field = document.querySelector('input[name="_csrf"]');
    return field ? field.value : "";
  }

  /** A POST with a JSON body (or none), with the CSRF code. Fails with a TypeError without network and with an AbortError if the server does not answer in time, like htmx. */
  function post(path, body) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    const headers = { Accept: "application/json", "X-CSRF-TOKEN": csrf() };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    return fetch(path, { method: "POST", headers, credentials: "same-origin", signal: controller.signal, body: body === undefined ? undefined : JSON.stringify(body) }).finally(() => clearTimeout(timer));
  }

  /** The pop-up messages of the app (no network, too slow, something did not work, forbidden) live in the layout and are shown by app.js. */
  function message(kind) {
    document.dispatchEvent(new CustomEvent("talita:messaggio", { detail: { kind } }));
  }

  /** What to tell the person about a request that did not work: the messages of the guide. */
  function failed(error) {
    if (error && error.name === "AbortError") message("slow");
    else if (error instanceof TypeError) message("network");
    else message("server");
  }

  /** An answer that is neither what was expected nor a refusal that the page knows: the access page if the access is gone, else the message of the guide. */
  function unexpected(response) {
    if (response.status === 401 || response.redirected) window.location.assign("/accedi");
    else if (response.status === 403) message("forbidden");
    else message("server");
  }

  // ---- Waiting, outcomes, focus ----

  /** The place where the waiting line and its texts are, for what the person tapped. */
  function ambito(element) {
    return element.closest("[data-ambito]") || document;
  }

  /** The place where the hidden blocks of the outcomes are. */
  function esiti(element) {
    return element.closest("[data-esiti]") || ambito(element);
  }

  /** What was tapped looks like a request on its way (the classes that htmx gives, which the style sheet reads) and cannot be touched twice. */
  function busy(element, on) {
    const form = element.matches("form") ? element : element.closest("form");
    if (form) {
      form.classList.toggle("htmx-request", on);
      form.querySelectorAll("button[type=submit]").forEach((button) => {
        button.disabled = on;
      });
    }
    if (element.matches("button, a")) {
      element.classList.toggle("htmx-request", on);
      if (element.matches("button")) element.disabled = on;
    }
  }

  /**
   * The waiting line: the text of the `<template data-attesa="[step]">` of the container goes in the live region `[data-stato]`, which gets its own content back afterwards (and its `hidden`, if
   * it had one). [step] is "primo" or "secondo" for the two requests of the phone when a teacher adds a passkey; without it, the template that has no value.
   */
  function waiting(place, on, step) {
    const state = place.querySelector("[data-stato]");
    if (!state) return;
    if (on) {
      const model = place.querySelector('template[data-attesa="' + (step || "") + '"]') || place.querySelector('template[data-attesa=""]');
      if (!model) return;
      if (!state.__saved) {
        state.__saved = Array.from(state.childNodes);
        state.__wasHidden = state.hidden;
      }
      state.replaceChildren(model.content.cloneNode(true));
      state.hidden = false;
    } else if (state.__saved) {
      state.replaceChildren(...state.__saved);
      state.hidden = state.__wasHidden;
      state.__saved = null;
    }
  }

  /** Shows the block `[attribute]="value"` of the container, hides the others of its kind and moves the focus to it. A dialog that was open is closed first, so that the block is seen. */
  function outcome(place, attribute, value, from) {
    place.querySelectorAll("[" + attribute + "]").forEach((element) => {
      element.hidden = element.getAttribute(attribute) !== value;
    });
    if (from) {
      const dialog = from.closest("dialog");
      if (dialog && dialog.open) dialog.close();
    }
    const shown = place.querySelector("[" + attribute + '="' + value + '"]');
    if (shown) {
      // A notice has no role while it is hidden; it gets the one of its variant as it is shown, so that it is announced.
      const role = shown.getAttribute("data-role");
      if (role) shown.setAttribute("role", role);
      shown.focus();
    }
  }

  function clearOutcomes(place, attribute) {
    place.querySelectorAll("[" + attribute + "]").forEach((element) => {
      element.hidden = true;
    });
  }

  // ---- 1. Entra con una passkey ----

  async function signIn(button) {
    const place = esiti(button);
    const line = ambito(button);
    busy(button, true);
    waiting(line, true);
    clearOutcomes(place, "data-passkey-esito");
    let going = false;
    try {
      let response = await post("/webauthn/authenticate/options");
      if (!response.ok) return unexpected(response);
      const options = requestOptions(await response.json());
      let credential = null;
      try {
        credential = await navigator.credentials.get({ publicKey: options });
      } catch (error) {
        // Cancelled, or there is no passkey of Talità on this phone: the browsers do not say which, on purpose.
        credential = null;
      }
      if (!credential) return outcome(place, "data-passkey-esito", "annullata");
      response = await post("/login/webauthn", credentialToJson(credential));
      if (response.status === 401) return outcome(place, "data-passkey-esito", "non-valida");
      if (!response.ok) return unexpected(response);
      const result = await response.json();
      going = true;
      window.location.assign(result.redirectUrl || "/");
    } catch (error) {
      failed(error);
    } finally {
      if (!going) {
        waiting(line, false);
        busy(button, false);
      }
    }
  }

  // ---- 3. The confirmation ----

  /**
   * Asks the server whether a confirmation is needed and, if it is, the phone for the fingerprint, the face or the code, and the server to check the answer. Gives back "non-serve", "ok",
   * "annullata" or "rifiutata"; throws the answer of a server that did not give what was expected, or the error of a network that fails.
   */
  async function confirmWithPasskey() {
    let response = await post("/verifica-passkey/opzioni");
    if (!response.ok) throw response;
    const body = await response.json();
    if (!body.serve) return "non-serve";
    let credential = null;
    try {
      credential = await navigator.credentials.get({ publicKey: requestOptions(body.options) });
    } catch (error) {
      credential = null;
    }
    if (!credential) return "annullata";
    response = await post("/verifica-passkey", credentialToJson(credential));
    if (response.status === 403) return "rifiutata";
    if (!response.ok) throw response;
    return "ok";
  }

  /**
   * Runs the confirmation for the element that was tapped, with its waiting line and its outcomes. Gives back whether what was held (the request, the reload, the creation of a passkey) may go on:
   * after a cancel or a refusal it may not, and the page says so in its own words. [step] is the waiting text, for a teacher who adds a passkey («primo»).
   */
  async function confirmFor(element, step) {
    const line = ambito(element);
    const place = esiti(element);
    busy(element, true);
    waiting(line, true, step);
    clearOutcomes(place, "data-conferma-esito");
    try {
      const result = await confirmWithPasskey();
      if (result === "annullata" || result === "rifiutata") {
        // What the confirmation was for (`data-conferma="togli"`, `"aggiungi"`, `"segnalazioni"`) names the block: every action has its own words for «cancelled» and «refused».
        const kind = element.getAttribute("data-conferma");
        outcome(place, "data-conferma-esito", kind ? kind + "-" + result : result, element);
        return false;
      }
      return true;
    } catch (error) {
      if (typeof Response !== "undefined" && error instanceof Response) unexpected(error);
      else failed(error);
      return false;
    } finally {
      waiting(line, false);
      busy(element, false);
    }
  }

  // A form whose request needs the confirmation first (`data-conferma`): the request is held until the phone has verified the person.
  document.addEventListener("htmx:confirm", (event) => {
    const element = event.detail.elt;
    if (!SUPPORTED || !element || typeof element.hasAttribute !== "function" || !element.hasAttribute("data-conferma")) return;
    event.preventDefault();
    confirmFor(element).then((go) => {
      if (go) event.detail.issueRequest(true);
    });
  });

  // ---- 2. Aggiungi una passkey ----

  /** The block of the passkeys, asked again to the server, which draws it with the new row and the notice. */
  function reloadPasskeys() {
    if (window.htmx) window.htmx.ajax("GET", "/profilo/passkey", { target: "#passkey-contenuto", swap: "outerHTML" });
    else window.location.reload();
  }

  /** The server refused to start or to finish a registration: the passkeys are at the most (`piena`), or a confirmation is needed (`serve-conferma`). */
  function refused(place, body) {
    outcome(place, "data-passkey-esito", body && body.stato === "piena" ? "piena" : "serve-conferma");
  }

  async function addPasskey(button) {
    const place = esiti(button);
    const line = ambito(button);
    clearOutcomes(place, "data-passkey-esito");
    clearOutcomes(place, "data-conferma-esito");
    const confirmFirst = button.hasAttribute("data-conferma");
    try {
      // A teacher who has a passkey confirms with it first, then creates the new one: two requests of the phone, and the waiting line says which.
      if (confirmFirst && !(await confirmFor(button, "primo"))) return;
      busy(button, true);
      waiting(line, true, confirmFirst ? "secondo" : "");
      let response = await post("/webauthn/register/options");
      if (response.status === 409) return refused(place, await response.json());
      if (!response.ok) return unexpected(response);
      const options = creationOptions(await response.json());
      let credential = null;
      try {
        credential = await navigator.credentials.create({ publicKey: options });
      } catch (error) {
        // «InvalidStateError»: this phone has a passkey of this account already (it was excluded in the options). Anything else is a cancel.
        return outcome(place, "data-passkey-esito", error && error.name === "InvalidStateError" ? "gia-presente" : "annullata");
      }
      if (!credential) return outcome(place, "data-passkey-esito", "annullata");
      response = await post("/webauthn/register", { publicKey: { credential: credentialToJson(credential), label: "" } });
      if (response.status === 409) return refused(place, await response.json());
      if (!response.ok) return unexpected(response);
      reloadPasskeys();
    } catch (error) {
      failed(error);
    } finally {
      waiting(line, false);
      busy(button, false);
    }
  }

  // ---- The page that shows nothing without the confirmation ----

  async function confirmPage(button) {
    if (await confirmFor(button)) window.location.reload();
  }

  document.addEventListener("click", (event) => {
    const action = event.target.closest("[data-azione]");
    if (!action || !SUPPORTED) return;
    switch (action.getAttribute("data-azione")) {
      case "entra-passkey":
        event.preventDefault();
        signIn(action);
        break;
      case "aggiungi-passkey":
        event.preventDefault();
        addPasskey(action);
        break;
      case "conferma-pagina":
        event.preventDefault();
        confirmPage(action);
        break;
      default:
        break;
    }
  });

  // The page is boosted: after every change of page, or of a piece of it, what is for passkeys is shown (or not) again.
  document.addEventListener("htmx:afterSettle", reveal);
  document.addEventListener("DOMContentLoaded", reveal);
  reveal();
})();
