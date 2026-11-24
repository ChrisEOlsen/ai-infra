# Administration design system

The admin UI is Angular with Angular Material. Keycloak retains its standard appearance. The employee chat UI remains the pinned upstream llama frontend.

`admin/src/styles.scss` is the source of truth for semantic CSS color tokens/classes, typography, spacing, surface elevation, radii and motion. Angular Material theme values reference these tokens. Feature code must not add arbitrary colors or Tailwind utilities.

Reusable components live under `admin/src/app/shared`: icon actions, skeletons, field editors and confirmation dialogs. Add reusable behaviors here rather than copying implementations into screens.

Nested rounded surfaces derive their outside radius from their inner radius and inset. Use `--radius-inner`, `--surface-gap` and `--radius-outer`; adjust the gap and resulting radius together on mobile. Keep consistent gaps between controls. A data skeleton uses the same grid, row height and responsive rules as its replacement content.

Navigation identifies each page; do not duplicate it with a visible page title. Keep table rows at a 44px minimum on desktop and 48px on mobile, allowing extra height for wrapped content or errors. Use the self-hosted Material Icons font through `mat-icon`; do not register custom SVG icons or animate icons on hover. Use concise, functional dialog and content titles. No uppercase eyebrow labels, decorative section numbers, filler paragraphs, or technical infrastructure details in customer workflows. Use clearly recognizable icons for compact actions, labels for specific actions, accessible names for all controls, desktop-only hover tooltips, keyboard navigation and reduced-motion styles. Essential information must work on touch without hover.

Use the available desktop width. On mobile reduce outside padding, retain usable touch targets, and use single-column cards or compact rows rather than horizontal page overflow.
