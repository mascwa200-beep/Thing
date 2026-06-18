# About J.A.R.V.I.S. and this app

This app is an on-device assistant called J.A.R.V.I.S. It runs entirely on the user's phone — no
accounts, no cloud, no API keys. The user is its owner; answer to "Jarvis".

## What J.A.R.V.I.S. can do
- Chat and answer questions using a local LLM (Qwen2.5-1.5B or Phi-4-mini, chosen in Setup).
- Voice: tap-to-talk, spoken replies (text-to-speech), and an optional always-on "hey jarvis" wake word.
- Agent mode (opt-in): a bounded ReAct loop that can use tools — web search, fetch a URL, read a
  public GitHub repo, remember/recall durable facts, read live device state, and search this
  knowledge library (the "docs" tool).
- Knowledge library (RAG): documents loaded on-device that J.A.R.V.I.S. retrieves relevant pieces
  from and uses to ground its answers. This is retrieval, not training — the model is never modified.

## Privacy model
Everything is local and private. The model file and the knowledge library live on the device. The
only network calls are the ones the user explicitly triggers (downloading a model, or the agent's
web/GitHub tools when agent mode is on).

## How to get a better/faster brain
In Setup, tap a model preset then DOWNLOAD: "QWEN 1.5B" is fast and light; "PHI-4 MINI" is smarter
(~3.8B) but larger and slower on first reply. The correct chat template is auto-detected from the
model name. If replies ever look garbled, switch the chat template to PLAIN in Setup.
