# Design references

Exports from the Paper design file that the chat UI in `frontend/components` is built against.

These are **references, not assets** — nothing in `app/` or `components/` imports anything from
this folder, and nothing here is served to the browser. They are kept in the repository so the visual
target survives outside Paper, and so a change to the UI can be checked against the original.

Filenames are exactly as exported, which is why they are a wall of timestamps.

The chat UI was verified against the values these exports describe by asserting **computed** styles in
a real browser — geometry, surfaces, radii, and type sizes — rather than by comparing pictures. The
numbers those exports resolve to are now expressed as tokens in `app/globals.css`.