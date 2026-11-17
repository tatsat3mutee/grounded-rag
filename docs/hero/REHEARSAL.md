# Rehearsal: grounded (README hero)

Gold: `ai-chat`. Kept its stage structure, `Collapse` drop-in of the sent question, the status line
before the answer, `Stream` for word-by-word text with no reflow, and the composer `Field`. Changed:
`Thinking` became "Searching product" (retrieval is the product), and a Sources satellite was added
that straddles the window's right edge over its margin, present from frame one as a skeleton.

Beats:
1. The question is typed (0.7 s) and sent.
2. "Searching product" appears, and sources 1 and 2 resolve from skeleton to files.
3. The answer streams with `[1]` citations.
4. Payoff: "within 30 days" lights up in the answer and in the source excerpt in the same blue,
   source 1 takes the cited fill, and "Verified against sources" lands.

The stage plays once, and the poster is the payoff (about 13.5 s). The GIF holds the payoff for
2 s before it restarts.

Captured: 856 wide and 390 (compact: the window bleeds right, Sources straddles its bottom edge),
light theme, reduced-motion poster, and the full sequence as a contact sheet.

Gate: `rehearsal passed (necessary, not sufficient: references/verify.md and the fresh eye in review.md still stand)`

Film:
- Uneven gutters: the satellite was 48 px from the right while the gutter was 32. Fixed with one
  40 px gutter, and the satellite runs off the bottom over filler only.
- Clipped past-turn bars at the top of the thread: removed one bar row and set the gap to 12.
- Empty band inside Sources before results arrive: added excerpt skeleton lines in the same grid
  cell, crossfaded.
- Void under the source list: added a third, lower-ranked hit running off the bottom.
- Compact window narrower than tall: re-composed so the window bleeds right and is wider than tall.
- `jump: ss-field__chars "W"` while typing: present in the `ai-chat` gold too; it comes from the
  kit's `Field`. Left as is.
- Same-box content change on "30" at the payoff: intentional, it is the highlight fading in.
- Badge lands on a transform: added `will-change: transform`.
- Compact: "gr-sources leaves 1px below its last child" is measured on the stage-clipped card,
  which bleeds off the bottom by design.

Review: self-review against `references/craft.md` and `teaching.md`.
- The eye lands first on the answer and the lit phrase.
- The window leads; the satellite has a role (where the answer came from).
- There are no sparkles and no purple, and the only words are on the story.

A separate fresh-eye reviewer was not run.

Assumptions: GitHub renders the README at about 830-880 px wide; a GIF is used because README
`<video>` needs an uploaded asset.

Unresolved: the empty band above the past turns in the first two seconds, inherited from the gold's
bottom-anchored thread.

Not checked: dark theme, which the README does not use; the hover play mode; Safari font
rendering.
