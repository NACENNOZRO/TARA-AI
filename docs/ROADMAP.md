# Remaining release gates

The general assistant, configurable providers, local Ollama path, connected-action review queue, WorkManager reminders and local wake service are implemented. The next validation work must use a real phone and authorized accounts:

1. Install/update on the actual Android model; validate Room migration, first launch, permission denial, process restart and selected-document grants.
2. Test Tara activation and commands with the display off, English/Tamil accents, background app pressure and battery saving. Measure false activations, CPU and battery use. Current sessions last four hours and do not restart after force-stop/reboot. Voice interruption during TTS remains a separate feature.
3. Test localhost Ollama model discovery, a real response, tool calling, service lifetime and optional cloud-to-local fallback on the phone.
4. Connect actual API accounts, confirm scopes and perform one approved action per adapter. Verify each destination, provider ID, duplicate-tap protection and uncertain-delivery behavior. LinkedIn publishing and Gmail require provider-specific authorization setup.
5. Add robust task resumption beyond the current bounded loop and persisted action queue. Exercise cancellation, token expiry, quota errors, slow requests and app termination during external writes.
6. Add semantic memory retrieval, PDF reading/creation, generalized file export and document editing as separate features. Current document support is text notes and selected resume attachments.
7. Gmail read/send, recurring outbound rules, Instagram/WhatsApp and deeper phone operations are not implemented; add only with appropriate official APIs and action-specific permissions.
8. Before release certification: physical-device UI/accessibility tests, instrumentation tests, 16 KB native-library compatibility checks where relevant, battery/security review, dependency review and a user-owned permanent signing key.
