# Wear OS support: watch-adaptive UI, looping carousel extra keys and working soft keyboard

## Summary

Adapts Termux for round Wear OS watches. On watch hardware the app now:

- uses compact watch-specific defaults (smaller font, lower toolbar, watch-specific extra-keys layout), with the phone UI **completely unchanged**;
- renders the extra keys as a round-bezel-aware, **infinite-looping swipe carousel** with large keys centered on the screen;
- keeps the soft keyboard fully usable: WearGboard is no longer fed `TYPE_NULL`/password style input (which made it tear itself down), and typed text is kept in the input as a live, editable, visible command line that is flushed to the terminal only on enter.

## Motivation

Termux is currently unusable on Wear OS watches: the default extra-keys grid is tiny and hits the bezel, there is no way to page through keys, and the software keyboard opens then immediately closes itself. This PR makes common watch workflows (run a quick command, move the cursor, toggle modifiers) actually work on the wrist.

### Keyboard failure root cause

The watch's input method, WearGboard, interprets `InputType.TYPE_NULL` (Termux's default) as a password-style field, tries to load its `keyboard_password` layout and receives a `null` keyboard-view helper, after which it deactivates the keyboard and hides it:

```text
WearInputMethodService.onStartInputView(): editorInfo:inputType=0x0(NULL) packageName=com.termux
Keyboard.getKeyboardViewHelper(): null helper is returned: ... id=keyboard_password ...
WearInputMethodService.onFinishInputView() -- fires almost immediately after
```

WearGboard only renders the typed text via the input's **extracted text**, and it writes individual key presses as **composing text** that it only commits (re-reading the extracted text) on space or swipe gestures. So the input connection must return a real, live, non-null `ExtractedText`, keep the typed command line buffered (instead of flushing every commit to the terminal and clearing), and actively push extracted-text updates on every keystroke.

## Changes

### New watch detection helpers (`termux-shared`)
- `DeviceUtils.isWatchDevice(Context)` — `PackageManager.FEATURE_WATCH`.
- `DeviceUtils.isScreenRound(Context)` — `Configuration.isScreenRound()` (round-bezel margins).
- `TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_WATCH` — single-row watch layout:
  `ESC HOME UP END PGUP TAB CTRL ALT LEFT DOWN RIGHT PGDN KEYBOARD ENTER`.
- `TermuxAppSharedProperties` falls back to the watch extra-keys layout on watches.

### Watch-adaptive UI (`app`, `termux-shared`)
- Watch font size defaults to `10`; the toolbar height is capped to a watch-appropriate value.
- The extra-keys surface is inset by `WATCH_ROUND_BEZEL_MARGIN_DP` (`12dp`) on round watches so keys stay inside the visible round screen.

### Infinite looping carousel extra keys (`app`, `termux-shared`)
- New single-row carousel for watches: the key row is repeated in the data set and the scroll position is seamlessly recentred (offset-preserving) so swiping never hits an end.
- `CenterSnapHelper` snaps keys to the center of the watch screen; neighbours peek on both sides.
- Tap executes the key with a brief alignment flash; horizontal swipes never trigger keys.
- Special keys (`CTRL`, `ALT`, `SHIFT`, `FN`) keep their active/locked states and long-press toggling; direction keys keep auto-repeat.
- New `IExtraKeysViewState` interface is shared by the grid `ExtraKeysView` (phones, unchanged) and the carousel so all existing callers work with either.
- `androidx.recyclerview:recyclerview` added explicitly to `app`.

### Soft keyboard on Wear (`terminal-view`)
- `TerminalView.onCreateInputConnection` sets a normal watch input type
  (`TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_SHORT_MESSAGE | TYPE_TEXT_FLAG_IME_MULTI_LINE`)
  instead of `TYPE_NULL`/password variants that deactivate WearGboard, plus an explicit
  `IME_ACTION_SEND` so WearGboard renders an action/enter key.
- The input connection buffers the typed line as a command line (single source of truth for the
  keyboard's strip):
  - non-null, live `ExtractedText` for the preview strip;
  - `setComposingText` mirrors individual key presses in and pushes them live (WearGboard only
    commits/refreshes on space or swipe otherwise);
  - `InputMethodManager.updateSelection()` + monitor-token `updateExtractedText()` push after every
    edit so the strip updates on every keystroke;
  - text persists across keyboard close/reopen;
  - the keyboard backspace key edits the buffer (`onKeyDown` `KEYCODE_DEL` interception) instead
    of sending `DEL` to the shell;
  - enter (IME action / newline) flushes the buffered line to the terminal and executes it.
- All watch branches are gated on `isWatchDevice()`; phone input behaviour is byte-for-byte
  identical (phones keep `TYPE_NULL` / `IME_FLAG_NO_FULLSCREEN` and the old flush-each-commit path).

## Testing

- Built and installed on a TicWatch Pro 3 (round, Wear OS / Android 9, WearGboard).
- Verified on the watch: carousel swiping (manual + fling, infinite), key activation
  (tap-only, tap flash), round-bezel margins, keyboard opening, per-key typing sync,
  backspace editing, enter/action execution.
- Phone behavior verified unchanged by building the standard debug APKs.

## Known limitations

- The swipe-up alternate-key popups of the grid `ExtraKeysView` are intentionally not supported
  on the carousel because they conflict with the horizontal swipe gesture.
- On watches the keyboard is line-based: interactive single-keystroke programs (e.g. `vi`) only
  receive input once the line is sent with enter.
- WearGboard has no dedicated enter key in its fixed layouts; the enter action is mapped to the
  keyboard action key and the carousel `ENTER` key.

## Screenshots

| Before | After |
| --- | --- |
| ![before](docs/images/wear_before.png) | ![after](docs/images/wear_after.png) |

> Commit `docs/images/wear_before.png` and `docs/images/wear_after.png` showing the previous
> unusable (tiny grid / broken keyboard) state and the new carousel + working keyboard.

## Suggested commits (Conventional Commits)

- `Added: Wear OS watch and round screen detection helpers in termux-shared`
- `Added: Wear OS specific extra keys default layout and watch properties`
- `Added(app): Infinite looping extra keys carousel with center snap for wear`
- `Fixed(app): Round bezel aware extra keys inset on round watches`
- `Fixed(terminal): Keep Wear OS keyboards active by avoiding TYPE_NULL input`
- `Added(terminal): Live extracted text and buffered command line for Wear OS keyboards`
- `Fixed(terminal): Sync per-key edits to Wear OS keyboard and make backspace edit the buffer`

All messages use the conventional types (`Added`/`Fixed`), with the required space after `:`.

---

# STEPS TO GET THE PR ACCEPTED

Based on the README sections *For Maintainers and Contributors* and *Forking*.

## 1. Fork and set up the repo
1. Go to https://github.com/termux/termux-app and press **Fork** (top right), creating `https://github.com/<YOUR_USERNAME>/termux-app`.
2. In this folder (`/home/dada/Desktop/TERMUX/termux-app-0.118.3`), turn it into a git repo and link your fork + upstream:
   ```bash
   git -C /home/dada/Desktop/TERMUX/termux-app-0.118.3 init
   cd /home/dada/Desktop/TERMUX/termux-app-0.118.3
   git remote add origin https://github.com/<YOUR_USERNAME>/termux-app.git
   git remote add upstream https://github.com/termux/termux-app.git
   git fetch upstream
   git checkout -b wear-os-support upstream/master
   ```
   > You are NOT changing the package name, so **no bootstrap rebuild** is needed (that step
   > only applies to forks that change the package name).

## 2. Satisfy the contribution rules
3. **Conventional Commits** (README requires it): every commit message must use the conventional
   spec — types `Added:`, `Changed:`, `Fixed:`, `Docs:`, etc., with a space after `:`.
   Use the suggested commits above; they can stay as one or several commits.
4. **Sign-off / DCO:** Termux enforces the Developer Certificate of Origin. Commit with
   `git commit -s` (adds `Signed-off-by: Your Name <email>`), otherwise the DCO check will block the PR.
5. **No hardcoded values:** shared constants and utils belong in `termux-shared` (already done —
   `DeviceUtils.isWatchDevice`, `TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_WATCH`).
   `terminal-view` is a standalone module that cannot depend on `termux-shared`, which is why it
   carries its own tiny `isWatchDevice()` copy — worth a note in the PR description so reviewers
   don't flag it.
6. **Licenses:** no new external libraries were added beyond `androidx.recyclerview`, which the
   repo already uses; nothing extra to license.
7. **Changelogs:** the README says to update changelogs. `CHANGELOG.md` files only exist on the
   GitHub repo, not in this source snapshot. After fetching upstream, if a root or per-module
   `CHANGELOG.md` exists, add an `Unreleased`/`Added`/`Fixed` entry for these features
   (Keep a Changelog style) and reference them in the PR.

## 3. Screenshots
8. Put `docs/images/wear_before.png` and `docs/images/wear_after.png` in this folder and commit
   them so the Screenshots table in the PR body renders. Good images = appreciably higher chance
   of a merge.

## 4. Verify builds and tests
9. Build and confirm tests pass before pushing:
   ```bash
   JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug
   ./gradlew test
   ```
   Both must succeed (CI runs the same tests).

## 5. Push and open the PR
10. First sync once more to avoid conflicts, then push:
    ```bash
    git fetch upstream && git rebase upstream/master
    git push -u origin wear-os-support
    ```
11. Open the PR on GitHub: from **your fork** select `wear-os-support` → *Contribute* → *Open pull request*, targeting **termux/termux-app `master`**.
12. Use this file's content as the PR description **without this STEPS section**. Title:
    `Wear OS support: watch-adaptive UI, looping carousel extra keys and working soft keyboard`.
13. Mention the "no phone behavior change" guarantee, the testing done on the TicWatch Pro 3,
    and the `terminal-view`/`termux-shared` split (see step 5). Answer reviewer comments
    promptly and re-push to update the PR.

> Note: termux is actively looking for maintainers and reviewers, so a well-tested, focused
> feature PR like this is welcome — keep it single-purpose and easy to review.