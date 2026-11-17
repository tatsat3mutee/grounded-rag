# README hero

`docs/images/hero.gif` (and its poster `hero.png`) is an authored illustration of the product,
not a screen recording. It was built with the Stageshow skill and kit
(https://github.com/benjitaylor/skills, MIT) from its `ai-chat` gold example.

| File | What it is |
|---|---|
| `index.tsx`, `grounded.css` | The stage: scene state, the script, layout. Imports the kit from `../../kit` |
| `capture.js` | Frame capture used for the GIF: runs the stage 6x slower and screenshots at an even 15 fps |
| `REHEARSAL.md` | What was checked, what the film tool flagged, and what was not checked |

To regenerate:

```bash
npx skills add benjitaylor/skills --skill stageshow
```

1. Copy this folder to the skill's `examples/grounded/`.
2. Run `npm install`, then `npm run dev`.
3. Film it with `node scripts/film.mjs "<preview>/?capture=grounded" --select '#example' --gate`.
4. Capture frames with `node capture.js "<preview>/?capture=grounded" frames 13.6 15 6`.
5. Build the GIF with ffmpeg's `palettegen` and `paletteuse`, scaled to 856 px wide.
