# TARA AI 0.2 — personal Android assistant

TARA now supports general conversation, research, writing, personal notes, reminders and connected software actions. The email workspace remains an optional tool. The APK is a signed **debug build**, not a production-certified release. Compilation and eight JVM unit tests passed; real-phone voice, OAuth, live account actions and battery behavior still require validation.

## Install the update

Install the supplied APK over TARA 0.1. Both APKs have the same package and signing certificate. The Room 1→2 migration preserves existing records and adds the action review queue. Minimum Android version: 8.0 (API 26). Version code: 2; target API: 34; compiled against API 35.

No laptop or private server is needed to operate the installed app. Local Ollama must be running on the same phone if you select it. Cloud providers and connected software require internet and your own authorized accounts.

## Set up an AI engine

1. Open **Settings → AI engines**. Select a provider.
2. Enter that provider's key. Use **Load models** to retrieve available model IDs. Clear the model field to see more results, or type part of a model ID to filter the list.
3. Choose a model, then **Save & use**. Use **Test this model** to verify a real response.
4. Models used for actions must support structured tool calling. If a model rejects tools, select another model, or turn off **AI tool calling** for conversation only.

| Provider | Connection | Availability notes |
| --- | --- | --- |
| Groq | API key, live model list | Free plan with account/model quotas |
| Google Gemini | Google AI Studio key, compatible endpoint | Free tiers on eligible models; account and data-use terms apply |
| OpenRouter | API key, free-model filter | `:free` IDs/free router have limits; other IDs may cost money |
| Mistral | API key, live model list | Free experimentation where eligible |
| Cerebras | API key, choose a model from the live list | Current account access and limits apply |
| OpenAI | API key | Paid API; ChatGPT subscription is separate |
| Ollama | Localhost endpoint, installed model | Local engine on the phone; no API key required |
| Custom API | HTTPS OpenAI-compatible endpoint and model | Tool support and pricing depend on the service |

This is a configurable catalog, not a promise that every free model exists forever or that all listed models are free. TARA does not bypass quotas or automatically sign up for accounts.

### Ollama on your phone

- Keep the phone's Ollama server running. If your installation provides the CLI, start it with `ollama serve` and check `ollama list` for installed models. TARA does not start or install a separate app's server for you.
- In TARA select **Ollama on this phone**. Default base URL: `http://127.0.0.1:11434/v1`. Keep localhost; do not substitute your laptop's address.
- Tap **Load models**, select an installed model and **Test this model**. Then use **Save & use** for local primary inference.
- To use it as a side engine, configure/test Ollama first, select your cloud primary engine, and enable **Use local Ollama if cloud fails**. Model settings saved by Load/Test remain available after changing the primary provider.
- A local model without function calling can chat when AI tool calling is off. Slow models can exceed the task timeout. Available RAM, model size and the phone's process manager affect reliability.

## Screen-off voice

1. In **Settings → Hands-free voice**, download the English recognition model (about 40 MB). The download is checked against a fixed SHA-256 checksum and extracted into the app's private files.
2. Keep TARA visible, tap **Start listening**, and grant microphone/notification permissions.
3. Say **Tara**, wait for the greeting, then speak your command. You can turn the display off while the microphone service is running.
4. Stop through Settings or the persistent notification. A session ends automatically after four hours; start another session from the app as needed.

The wake detector and English command transcription use Vosk on the phone. Tamil command transcription uses the phone's speech service and may need internet. Tanglish recognition quality varies. Speech output uses installed Android voices; speed and response personality are configurable.

This is a software foreground-service listener, not a hardware hotword/default-assistant implementation. It cannot restart itself after a force-stop or guarantee survival through reboot, OEM battery killing, microphone conflicts or revoked permissions. Start it again from the visible app after those events. Listening consumes CPU and battery. The app opens Android settings so you can review its battery and permission controls.

There is no speaker authentication. Spoken private answers while locked are disabled by default. With that setting off, TARA processes the command but says the result is ready to review after unlocking. External write actions always require an unlocked on-screen approval. While TARA speaks, the wake detector is paused; use the notification to stop. Continuous voice barge-in is not implemented. Push-to-talk stops the background listener to avoid two recorders competing; restart hands-free afterwards.

## Connect your software

Open **Settings → Software connections**, enter the authorized token and a fixed destination, then Save and Test. Credentials are encrypted on the phone. No live credentials are bundled. Provider tokens may expire; replace/revoke them at the provider when needed.

| Connection | Implemented action | Required setup |
| --- | --- | --- |
| LinkedIn | Propose and, after approval, publish a public text post | Developer app with posting access; user token with `w_member_social`; `urn:li:person:...`; valid LinkedIn API version |
| GitHub | Read recent repositories; propose/create an issue | Fine-grained token and `owner/repo`; Issues write for creation |
| Telegram | Propose/send a bot message | BotFather token; destination chat ID; user must first start the bot chat |
| Slack | Propose/send a message | Installed Slack app token with `chat:write`; channel ID; bot access to that channel |
| Notion | Propose/create a page under a selected parent | Integration token with insert-content access; parent page shared with integration |
| Brave Search | Public web search | Brave Search API subscription token; current subscription limits apply |
| Gmail | Authorized draft creation with optional selected attachment | Google Cloud Android OAuth client, Gmail API and consent setup |

LinkedIn Test calls its profile endpoint and may require `openid`/`profile` access in addition to posting access. A successful profile test does not prove publishing entitlement. The returned person identifier can help configure the author URN. No unrestricted LinkedIn jobs or feed scraping is included. Instagram and WhatsApp adapters are not implemented.

All proposed external writes appear in **Tools → Action review & history** with their destination and content. **Approve & execute** sends that immutable proposal. Approval expires after 24 hours; an atomic database claim blocks repeated taps. TARA stores returned provider identifiers. If an action is interrupted or its delivery is uncertain, it is not retried automatically: inspect the external service before proposing another copy. An identical pending proposal is reused.

### Gmail setup

Enable Gmail API in your Google Cloud project. Create an Android OAuth client for `ai.tara.personal` with the installed APK's signing certificate SHA-1:

`AB:66:E0:62:AF:89:13:24:DC:AE:4D:16:B2:D3:11:A4:B3:8B:7F:30`

Configure your consent screen and test user as appropriate. TARA requests `gmail.compose` when you tap the Gmail draft action. This scope is subject to Google's restricted-scope policies. No server secret belongs in the APK.

Open **Tools → Email & resume workspace** to select a resume, paste job/email details, generate a draft and review it. PDF attachments are supported; PDF text extraction is not. Use a text resume or supply qualifications in the description for tailoring. This workspace creates Gmail drafts, not sent emails.

## Try general tasks

- “What is the weather in Chennai?” (Open-Meteo, no key required.)
- “Search the web for Kotlin coroutine guides.” (Brave key required.)
- “Remember that I prefer concise answers.”
- “Write a note with my study plan for tomorrow.” (Saved under Memory.)
- “Remind me in 20 minutes to take a break.” (Enable notifications.)
- “Draft a LinkedIn post about my cybersecurity project.” (Connected account; review in Tools.)
- “Create a GitHub issue describing this bug.” (Connected repository; review in Tools.)

Reminders use WorkManager: they are approximate and can be delayed by Doze/battery management. They are not exact alarms. Notes and memories are local and exportable as plaintext using Android's file picker. Semantic vector search, full document editing, PDF creation, Gmail search/send and recurring application automation remain outside this build.

## Data and safeguards

- No other app's private storage is read. Selected-file access uses Android's document picker.
- Keys, conversation content, memory text and queued-action payloads are AES-GCM encrypted with an Android Keystore key. Room metadata such as IDs, timestamps, document names and result IDs is not encrypted; this is field encryption, not whole-database encryption. Android backup is disabled.
- Selected cloud models receive conversation history and tool results used for that task. When Ollama is the primary engine, inference requests are restricted to the phone's localhost. Cloud fallback is never automatically enabled from local mode.
- Cloud presets require HTTPS and their expected host; the custom provider is explicitly user-configured. Cleartext is allowed only for localhost. Posting endpoints are fixed in code.
- The model cannot send external actions directly. The agent is bounded to six model rounds, eight tool calls and a three-minute task timeout. Failures are reported without claiming completion. External data is marked untrusted, but prompt filtering alone is not a complete defense; deterministic permissions and the review queue enforce external writes.
- Delete all local data stops the voice service, cancels local reminders and clears app records/settings. It does not revoke provider tokens or delete items already created at external services. Flash storage does not provide a forensic secure-erasure guarantee. Exports remain wherever you saved them.

## Build and validation

Use a full JDK 17, Gradle 8.7 wrapper, Android SDK 35 and build tools 34 or newer:

```sh
./gradlew clean assembleDebug testDebugUnitTest
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. The source project does not contain API keys, a signing keystore, a voice-model download, or your personal data. Choose an Android Studio release that supports macOS Monterey/Intel, or use compatible Java/SDK command-line tools. Do not assume every future IDE release supports that Mac.

See `docs/TEST_REPORT.md` for what was actually verified and `docs/ROADMAP.md` for remaining release gates.
