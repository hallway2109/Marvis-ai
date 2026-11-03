# Marvis AI v1.0.1

## New in this release
- 3D holographic HUD: say "Analyze the Mark 85 armour", "show the arc reactor blueprint", "show the globe" or "show the DNA helix". A rotating wireframe appears over any app; drag to rotate, pinch to zoom, double-tap to reset. The analysis fills in beside it. (Stylised procedural models, not official designs.)
- Floating mini hologram: a small rotating wireframe you can drag anywhere. It changes colour with what Marvis is doing and shows live background tasks (research, cleaning, analysis).
- Smart-mute: while you talk to Marvis, video/music fades out instead of pausing, then returns and rewinds by exactly the muted time (exact rewind needs notification access).
- Silent, instant actions: "scroll down", "pause", "play", "rewind 10 seconds", "next short", "open shorts" run immediately with no AI call and no talking before. Marvis then says "Done sir, anything else? Are you enjoying?"
- Direct YouTube Shorts / Instagram Reels opening.
- Capture reliability: chime instead of speech after the wake word, faster mic reopening, tuned silence windows, cloud speech for commands.
- New minimal HUD design for the app and the floating interface. No overlay mic or screen-share buttons; Marvis sees the screen by itself when asked.
- Research engine, system cleaner, deep voice, instant replies (see earlier notes).

## Known limits
- Android cannot mute a single app, so the whole media volume fades and Marvis speaks through the alarm volume meanwhile.
- Exact rewind needs notification access for Marvis; without it the media is still muted and restored, just not rewound.
- Android gives apps no control over microphone gain.
- Shorts/Reels buttons and WhatsApp screens change between app versions, so tapping may need adjusting.
- The default AI model is paid and needs OpenRouter credits; the app falls back to the free router without them.
