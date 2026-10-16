# Accessibility

Send targets WCAG 2.2 AA on its own screens: TalkBack labels and actions for every control, 48dp touch targets,
layouts that hold at 200% font size, and AA contrast in the light and dark themes.

## What the fixtures check

`app/src/androidTest/java/dev/send/matrix/AccessibilityUiTest.kt` (Compose v2 test rule) renders, with fake data:

- home (conversation list), conversation (bubbles, reaction pill, reply quote), the composer with a draft and the
  reply strip, and Settings at **200% font scale**. Key labels must be displayed, inside the screen and laid out
  without clipping or ellipsis (`TextLayoutResult.hasVisualOverflow`); icon buttons (Search, Account, Back, Video
  call, Voice call, More options, Attach file, Emoji, Gallery, Send, Cancel reply) must have ≥48×48dp touch
  bounds; every clickable node must carry text or a content description. Screenshots:
  `a11y-font200-home`, `a11y-font200-conversation`, `a11y-font200-composer`, `a11y-font200-settings`.
- the gesture-only actions as TalkBack custom actions: **Archive** on a conversation row (swipe) and **Reply** on a
  bubble (swipe), both invoked through `SemanticsActions.CustomActions`.
- the dark theme: `dark-home`, `dark-conversation`, `dark-details`, `dark-settings`.

Screenshots land in the `Send-UI-Screenshots` CI artifact as `send-<name>-device.png`.

## TalkBack conventions

- Icon-only controls use `IconButton`/`FilledIconButton` with a `contentDescription`; icons inside a labelled row or
  button use `contentDescription = null`.
- Rows (conversations, settings, people) are one clickable node that merges its texts and state icons, so TalkBack
  reads e.g. "Maya, Pinned, Now, See you at 7!, 2 unread". Selection mode exposes `selected`.
- Long-press menus have an `onLongClickLabel` ("Select message", "Select"); swipe gestures have custom actions
  ("Archive"/"Unarchive" on rows, "Reply" on bubbles); hold-to-record has "Record hands-free", which starts a
  recording already locked so the recorder panel and Send finish it.
- The reaction pill under a bubble reads "Reactions, ❤️".

## Touch targets

Everything interactive is at least 48dp. Deliberate exceptions: none on Send's own screens. Message bubbles shorter
than 48dp (one-line text) rely on Compose's automatic touch-target expansion; growing them would break the
Messages bubble rhythm. Element Call's WebView UI is outside Send's control.

## Contrast (WCAG 2.x ratios)

Measured with the WCAG relative-luminance formula against the colour actually drawn (alpha blended on its
background). Body text needs 4.5:1, large text and icons/component boundaries 3:1. Tokens refer to
`GoogleLightColors`/`GoogleDarkColors` in `MainActivity.kt`.

| Pair | Tokens | Light | Dark | Needs |
|---|---|---|---|---|
| Body text | `onSurface` on `surface` | 16.30 | 14.41 | 4.5 |
| Secondary text (snippets, times, summaries) | `onSurfaceVariant` on `surface` | 8.87 | 10.91 | 4.5 |
| Secondary text on headers | `onSurfaceVariant` on `surfaceContainer` | 7.98 | 9.60 | 4.5 |
| Header title | `onSurface` on `surfaceContainer` | 14.67 | 12.68 | 4.5 |
| Primary icons/accents | `primary` on `surface` | 6.12 | 10.90 | 3 |
| Own bubble text and links (links are bubble text, underlined) | `onPrimaryContainer` on `primaryContainer` | 13.28 | 7.22 | 4.5 |
| Incoming bubble text and links | `onSurface` on `surfaceContainerHigh` | 13.59 | 11.14 | 4.5 |
| Reply quote snippet, own (85% on 8% tint) | `onPrimaryContainer` | 8.03 | 4.83 | 4.5 |
| Reply quote snippet, incoming (85% on 8% tint) | `onSurface` | 8.05 | 7.01 | 4.5 |
| File size in bubble (75%) | `onSurface` on `surfaceContainerHigh` | 6.61 | 6.95 | 4.5 |
| Reply/edit strip title | `primary` on `surfaceContainerHigh` | 5.11 | 8.43 | 4.5 |
| Unread badge | `onPrimary` on `primary` | 6.42 | 7.70 | 4.5 |
| Error text | `error` on `surface` | 6.16 | 10.94 | 4.5 |
| Recording button glyph | `onError` on `error` | 6.46 | 7.72 | 3 |
| Composer icons | `onSurfaceVariant` on `surfaceContainerHigh` | 7.40 | 8.43 | 3 |
| Selected tab/chip | `onSecondaryContainer` on `secondaryContainer` | 13.21 | 7.21 | 4.5 |
| Voice button glyph | `onTertiaryContainer` on `tertiaryContainer` | 13.35 | 6.97 | 3 |
| Search match | `onSurface` on `tertiaryContainer` | 13.27 | 6.97 | 4.5 |
| Reaction pill | `onSurface` on `surfaceContainerLow` | 15.48 | 13.26 | 4.5 |
| Outline (field/boundary) | `outline` on `surface` | 4.27 | 5.84 | 3 |
| Disabled text (38%) | `onSurface` on `surface` | 2.34 | 3.04 | exempt |
| Avatar initial (white on fill, all 8 fills) | white on `AvatarColors` | 4.65–5.42 | same | 4.5 |

Changed in phase 8:

| Pair | Before | After |
|---|---|---|
| Avatar initial, white on fill | 1.55–3.69 (`#4285F4 #5BB974 #FCC934 #EE675C #AF5CF7 #4ECDE6 #FA903E #FF63B8`) | 4.65–5.42 (`#1967D2 #188038 #B06000 #D93025 #9334E6 #007B83 #B55400 #D01884`) |
| Reply quote snippet, own bubble, dark | 4.48 (80%) | 4.83 (85%) |
| Error text | 6.23 / 10.88 (M3 baseline `#B3261E` / `#F2B8B5`) | 6.16 / 10.94 (tonal-blue `#BA1A1A` / `#FFB4AB`) |

Tokens Messages doesn't show (secondary, tertiary, outline, error, background, inverse, surface dim/bright) used to
fall back to M3's purple baseline; they now come from the same tonal-blue scheme in both themes.

Fixed white-on-scrim colours remain on purpose where the backdrop is always dark: the media viewer, video badges
over thumbnails and the call screen's answer/decline buttons (white on `#1E8E3E` 4.21, on `#D93025` 4.77; icons, 3:1).

## Only verifiable on a device with TalkBack

Reading order and focus traversal, how TalkBack announces custom actions and the hold-to-record flow, Switch Access,
and the Element Call WebView.
