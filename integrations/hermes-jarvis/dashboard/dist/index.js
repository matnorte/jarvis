(function () {
  "use strict";
  const SDK = window.__HERMES_PLUGIN_SDK__;
  const { React: R, components: C } = SDK;
  const h = R.createElement;
  const ROOT = "/api/plugins/jarvis";

  function JarvisPage() {
    const [state, setState] = R.useState(null);
    const [form, setForm] = R.useState(null);
    const [secret, setSecret] = R.useState("");
    const [busy, setBusy] = R.useState(false);
    const [offline, setOffline] = R.useState(false);
    const [notice, setNotice] = R.useState("");
    const [changed, setChanged] = R.useState(false);
    const dirty = R.useRef(false);
    R.useEffect(() => {
      let active = true;
      async function load() {
        try {
          const data = await SDK.fetchJSON(ROOT + "/state");
          if (!active) return;
          setState(data); setOffline(false);
          if (!dirty.current) setForm(data.settings);
        } catch (_) { if (active) setOffline(true); }
      }
      load();
      const timer = setInterval(load, 3000);
      return () => { active = false; clearInterval(timer); };
    }, []);
    function update(name, value) {
      dirty.current = true; setChanged(true);
      setForm(previous => ({ ...previous, [name]: value }));
    }
    async function save(event) {
      event.preventDefault(); setBusy(true); setNotice("");
      try {
        const body = { settings: form };
        if (secret) body.elevenlabs_key = secret;
        const saved = await SDK.fetchJSON(ROOT + "/settings", { method: "PUT", body: JSON.stringify(body) });
        setSecret(""); dirty.current = false; setChanged(false); setForm(saved.settings);
        setState(previous => ({ ...previous, ...saved }));
        setNotice("Saved. Jarvis will apply these settings after the current conversation.");
      } catch (_) { setNotice("Could not save. Check your settings and try again."); }
      finally { setBusy(false); }
    }
    async function pairing(id, action) {
      setBusy(true); setNotice("");
      try {
        const response = await SDK.fetchJSON(ROOT + "/pairing/" + id + "/" + action, { method: "POST" });
        setNotice(response.ok ? (action === "approve" ? "Approved. Keep the pairing screen open on your phone." : "Pairing request declined.") : "This request has expired. Start again on your phone.");
        const data = await SDK.fetchJSON(ROOT + "/state"); setState(data);
      } catch (_) { setNotice("Could not update the pairing request. Try again."); }
      finally { setBusy(false); }
    }
    function card(title, subtitle, children) {
      return h(C.Card, { className: "jarvis-card", key: title },
        h(C.CardHeader, null, h(C.CardTitle, null, title), subtitle && h("p", { className: "jarvis-muted" }, subtitle)),
        h(C.CardContent, null, children));
    }
    function field(name, label, kind = "text", options) {
      const props = { id: "jarvis-" + name, name, value: form[name], disabled: busy,
        onChange: event => update(name, name === "front_camera" ? event.target.value === "true" : kind === "number" ? Number(event.target.value) : event.target.value) };
      const input = options ? h("select", props, options.map(([value, text]) => h("option", { key: value, value }, text)))
        : h(C.Input, { ...props, type: kind, ...(kind === "number" ? { step: name === "wake_sensitivity" ? .05 : 50,
            min: name === "wake_sensitivity" ? 0 : 100, max: name === "wake_sensitivity" ? 1 : 5000 } : {}) });
      return h("label", { className: "jarvis-field", key: name, htmlFor: props.id }, h("span", null, label), input);
    }
    function check(name, label, detail) {
      return h("label", { className: "jarvis-check", key: name },
        h("input", { type: "checkbox", checked: form[name], disabled: busy, onChange: event => update(name, event.target.checked) }),
        h("span", null, label, detail && h("small", { className: "jarvis-muted" }, detail)));
    }
    const heading = h("header", { className: "jarvis-heading" }, h("div", null,
      h("p", { className: "jarvis-eyebrow" }, "YOUR VOICE ASSISTANT"), h("h1", null, "Jarvis"),
      h("p", { className: "jarvis-muted" }, "One place for your voice, listening, vision and connected phone.")));
    if (!state || !form) return h("div", { className: "jarvis-control" }, heading,
      h("p", { role: "status" }, offline ? "Jarvis control is unavailable. Check the voice gateway service." : "Loading your assistant…"));
    const gateway = state.status.gateway;
    const phone = state.status.phone;
    return h("div", { className: "jarvis-control" }, heading,
      h("div", { className: "jarvis-status-grid" },
        h("div", { className: "jarvis-status" }, h("span", null, "VOICE GATEWAY"), h("strong", null,
          offline || !gateway.online ? "Offline" : gateway.error ? "Could not apply settings" : gateway.revision !== state.revision ? "Applying changes" : "Online")),
        h("div", { className: "jarvis-status" }, h("span", null, "ANDROID PHONE"), h("strong", null,
          !phone.online ? "Not connected" : phone.revision !== state.revision ? "Settings pending" : "Connected"))),
      offline && h("p", { className: "jarvis-notice", role: "alert" }, "Connection to Jarvis control was lost. Your saved settings are kept."),
      card("Connect your phone", "Open Pair with Hermes in Jarvis. Approve only the request identifier shown on your phone.",
        state.pairings.length ? state.pairings.map(request => h("div", { className: "jarvis-pairing", key: request.id },
          h("div", null, h("strong", { className: "jarvis-request-id" }, request.id.toUpperCase()),
            h("p", { className: "jarvis-muted" }, request.approved ? "Approved · waiting for phone" : "Expires in " + request.expires_in + " seconds")),
          h("div", { className: "jarvis-actions" },
            h(C.Button, { disabled: busy || request.approved, onClick: () => pairing(request.id, "approve") }, "Approve phone"),
            h(C.Button, { disabled: busy, variant: "outline", onClick: () => pairing(request.id, "decline") }, "Decline"))))
          : h("p", { className: "jarvis-muted" }, "No pending requests. Your device credential stays encrypted on the phone; no token copying is needed.")),
      h("form", { onSubmit: save }, h("div", { className: "jarvis-form-grid" },
        card("Voice", "Choose how Jarvis hears and speaks.", [
          field("stt_model", "Speech recognition", "text", [["tiny", "Tiny · lightest"], ["base", "Base · balanced"], ["small", "Small"], ["medium", "Medium"], ["large-v3", "Large v3"], ["turbo", "Turbo"]]),
          field("voice_id", "ElevenLabs voice ID"),
          field("tts_model", "Speech synthesis", "text", [["eleven_turbo_v2_5", "Turbo v2.5"], ["eleven_flash_v2_5", "Flash v2.5"], ["eleven_multilingual_v2", "Multilingual v2"]]),
          h("label", { className: "jarvis-field", key: "secret" }, h("span", null, "ElevenLabs API key · " + (state.secrets.elevenlabs ? "saved" : "required")),
            h(C.Input, { type: "password", value: secret, autoComplete: "off", spellCheck: false, disabled: busy,
              placeholder: state.secrets.elevenlabs ? "Leave blank to keep your saved key" : "Enter your API key",
              onChange: event => { setSecret(event.target.value); dirty.current = true; setChanged(true); } })),
          h("p", { className: "jarvis-muted", key: "secret-note" }, "Keys stay in protected server settings and are never returned to this page.")]),
        card("Listening", "Preferences sync to your phone and remain available offline.", [
          field("wake_sensitivity", "Wake sensitivity · 0 to 1", "number"),
          field("end_silence_ms", "Pause before sending · milliseconds", "number"),
          check("interrupt_by_speech", "Interrupt Jarvis by speaking"),
          check("start_on_boot", "Show a start reminder after reboot", "Android still requires a tap before microphone access.")]),
        card("Vision", "Camera use always starts on the phone, with your permission.", [
          check("vision_enabled", "Allow live vision", "This does not turn on the camera remotely."),
          field("front_camera", "Preferred camera", "text", [[false, "Back camera"], [true, "Front camera"]])]),
        card("Models & connection", "Hermes manages model providers, tools and agent configuration.", [
          field("fast_profile", "Fast Hermes profile"), field("deep_profile", "Deep Hermes profile"),
          h("div", { className: "jarvis-actions", key: "profiles" },
            h("a", { href: "/config?profile=" + encodeURIComponent(form.fast_profile) }, "Configure fast agent ↗"),
            h("a", { href: "/config?profile=" + encodeURIComponent(form.deep_profile) }, "Configure deep agent ↗")),
          field("public_url", "Public voice gateway address"),
          h("p", { className: "jarvis-muted", key: "address-note" }, "Use the secure WebSocket address your phone can reach.")])
      ), h("div", { className: "jarvis-save" }, h("p", { role: "status", className: "jarvis-muted" }, notice || "Changes apply after the current conversation finishes."),
        h(C.Button, { type: "submit", disabled: busy || (!changed && !gateway.error) || offline }, busy ? "Saving…" : "Save & apply"))));
  }
  window.__HERMES_PLUGINS__.register("jarvis", JarvisPage);
})();
