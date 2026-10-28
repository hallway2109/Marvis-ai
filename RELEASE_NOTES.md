# Marvis AI v1.0.1

## New in this release
- New default brain: OpenRouter's "~google/gemini-flash-latest" (always the newest Gemini Flash). The old gemini-2.5-flash is gone. Automatic fallback to google/gemini-3.7-flash, then the free router.
- Instant answers for greetings, thanks, time and date. "Thinking" and "Let me think..." only for complex questions.
- Spoken status for every action ("Finding YouTube...", then "YouTube opened. What would you like next?"), and the mic stays open for the next command.
- Minimal, compact floating orb. No mic button or screen-share button. Marvis looks at the screen by itself when you ask about it.
- Longer, more patient listening; cloud speech for commands (better at a distance); self-healing recognizer.
- Autonomous research ("Research <topic>"): plans, searches in parallel (web, Wikipedia, DuckDuckGo, YouTube with your key), writes a report, saves it to Memory.
- "Clean up background apps" (Stark-style) frees memory and reports honestly what changed.
- Deep male voice tuning with a voice picker, and TTS error recovery.
- Wake word without "Hey", live text box, media control, YouTube/WhatsApp automation, secure voice unlock (see earlier notes).

## Known limits
- The new default model is paid; it needs OpenRouter credits (the app falls back to the free router without them).
- Android gives apps no control over microphone gain; far-field pickup relies on cloud speech and your phone's microphone.
- Cleaning background apps only ends background processes; Android restarts some, and the gain is often small.
- Lock-screen unlock depends on the phone maker.
