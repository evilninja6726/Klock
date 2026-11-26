# Klock — Interface Design System (`system.md`)

## Intent & Domain
- **Human & Context**: Engineers, analysts, students, and knowledge workers waking up on anything from a compact 2 GB RAM budget phone to a flagship tablet — often groggy, on a locked screen in a dark room or bright morning light.
- **Primary Verb**: Schedule reliable alarms; wake up and solve 5 fresh, source-grounded technical or analytical questions to unlock silence.
- **Feel**: Braun horological instrument meets Swiss editorial specification sheet — tactile, high-contrast, quiet, zero ornament.
- **Signature Element**: **5-Segment Mechanical Lock Bar** (`01/05` -> `05/05`) paired with per-slot difficulty tier markers (`E`, `E/M`, `M`, `M/H`, `H`) and honest provenance badges (`DIRECTLY RETRIEVED`, `SOURCE-GROUNDED · LIVE`, `AI · SOURCE-GROUNDED`).

## Rejected Defaults
1. Purple/indigo AI gradients & glowing cards -> Warm archival stone (`#F6F5F1`) / obsidian (`#0E0E0D`) with single Braun signal vermilion accent (`#D94E1F` / `#EE6332`).
2. Glassmorphism & 24dp bubbly pill cards -> Flat `1dp` hairline borders (`#E4E2DC` / `#292825`) and disciplined `4dp / 6dp / 8dp` corner radii.
3. Decorative illustrations & multi-tab chrome -> Typographic tabular-numeral (`tnum`) alarm rows and focused top-level strip navigation.

## Tokens & Scales
- **Spacing grid**: `4dp` base (`4, 8, 12, 16, 20, 24, 32dp`).
- **Corner radius scale**: `4dp` badges/code blocks, `6dp` buttons/inputs, `8dp` containers/dialogs.
- **Depth**: Borders-only (`1dp` hairline) + subtle surface lightness steps; zero elevation drop shadows.
- **Touch targets**: Minimum `48dp x 48dp` on all interactive controls (WCAG AAA / Android accessibility).
