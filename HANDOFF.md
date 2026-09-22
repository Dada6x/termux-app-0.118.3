# HANDOFF — Termux Wear OS keyboard + looping carousel work (v0.118.3)

You are the next agent continuing Wear OS work on `/home/dada/Desktop/TERMUX/termux-app-0.118.3`.
This is the **release source tree** (NOT a git repo, no .git — do not run git commands here; see
"Shipping/PR" at the end). The work below is **tested and working on a real TicWatch Pro 3**
(Wear OS 2, API 29, round, watch). The user is happy. Your job: DON'T break it, and if you must
touch it, follow the invariants exactly.

IMPORTANT CONTEXT ABOUT THE USER: the user tests on a real watch and a real phone, every change.
They are color-blind-friendly and detail-orientedcars. When they say "something is broken", LISTEN.
Do NOT talk them out of real bugs with "it should work". If the code they're seeing contradicts
your model, the model is wrong, not the user.

---

## THE ONE ABOVE-ALL RULE: phones must stay 100% byte-identical in behavior

Every watch feature is gated behind `DeviceUtils.isWatchDevice(context)` /
`DeviceUtils.isRoundScreen(context)` / isWatchDevice checks. The phone path must remain EXACTLY as
upstream termux-app because:
1. The user only owns the phone for sanity checks (they don't trust it for PRs).
2. Termux has a huge phone userbase; watch code must never leak.

**When you edit ANY code, you MUST re-read the whole method/class after editing and confirm that
the phone branch is untouched.** If a watch block sits in shared code, gate it and re-check.

---

## KEYBOARD WORK — COMPLETED, WORKING, "we do not touch this unless it breaks" (crown jewel)

Location: `terminal-view/src/main/java/com/termux/view/TerminalView.java` (and its inner
`TermuxInputConnection`/`TermuxInputConnection` watch support in
`app/src/main/java/com/termux/app/terminal/io/WearKeyboardTermuxInputConnection`? — see below),
plus `terminal-view/src/main/java/com/termux/view/ExtraKeysView.java`,
`termux-shared/.../TermuxAppSharedPreferences.java`, and AndroidManifest/watch input settings.

### Problem we SOLVED: the Wear OS keypad is not a normal keyboard
On Wear OS, `TerminalView` uses a special input path. Three distinct bugs were fixed:

1. **KEYBOARD ALWAYS STARTED WITH CAPS LOCK EQUIVALENT (all-caps).** On Android, when an
   `InputConnection` does not provide correctly-cased extracted text or when the IME is told the
   initial caps mode is 1, the Gboard Wear keyboard renders in all-caps. Fix: on watches, force
   `inputType` to lowercase (never set the all-caps flag) and force
   `initialCapsMode = InputType.TYPE_CLASS_TEXT` (0-ish) so the keyboard lowers the shift state.
   Look for the field `mWatchKeyboardInitialCapsMode` or an explicit `setComposingText`-style
   override. **DO NOT revert this to "auto" caps mode** unless you can PROVE it fixes another bug.

2. **KEYBOARD DID NOT UPDATE (STATUS BAR / CANDIDATE) AS THE USER TYPES.** The watch keyboard
   commits text only when you send `setComposingText` — it does not live-mirror what you type into
   the terminal because the InputConnection "commit" is a separate event. Fix: as the user types,
   we intercept each character and call the input connection's
   `setComposingText(text, newCursorPosition)` (composition mode), which makes Gboard Wear
   re-sync its candidate/carousel row live. The user typed into the terminal and saw the keyboard
   mirror every single char. **This setComposingText path is THE mechanism that made the keyboard
   "sync as you type". Do not remove it.**

3. **BACKSPACE/BUFFERING.** On Wear OS the terminal input connection buffers the command line.
   When the user hits backspace, the terminal must delete the last char of the *buffer* (not force
   an undo of committed text) and push the update live to the keyboard via setComposingText.
   Also: the terminal must NOT treat watch keyboard input as an ENTER or full-screen rewrite.
   Look for: `mWatchBufferedText` / `mWatchBufferedInputConnection` logic and a Wear-specific
   `TermuxInputConnection` override that keeps `<prefix>` + buffered text. If you touch this, keep
   buffer+backspace+composing all three wired together — they are one feature.

### Live sync details to preserve (exactly)
- Each keystroke goes through: onWatchDeviceKeyInput -> update buffered command -> 
  `setComposingText(buffered, len)` so Gboard Wear shows it live.
- The keyboard's "extra" row (carousel of suggestions/keys) is the SAME concept as the terminal
  extra-keys row in spirit but SEPARATE — do not conflate. The terminal extra-keys row is a
  ViewPager at the bottom; the Gboard candidate row is inside the IME.
- Lowercase: watch inputType uses `TYPE_CLASS_TEXT` with `FLAG_NO_SUGGESTIONS` NOT set (suggestions
  stay on), and `initialCapsMode` forced to keep shift OFF.
- There is a known wear quirk: pressing the keypad letter emits a motion; we route it into
  composing text, then commit on enter/newline. Look at how `onKeyUp`/`TermuxInputConnection.sendKeyEvent`
  distinguishes watch vs phone — that branch MUST stay gated.

### SMOKE TEST (run before handing to user)
On the watch only: open a session. Type "hello" and watch the on-screen Gboard keypad show each
letter, lowercase, as you tap. Press backspace once — last char deletes. Press the newline/enter
key — command runs. Confirm the keyboard does NOT auto-uppercase the first letter.
On the phone (any device): open a session, type lowercase letters, confirm they stay lowercase and
auto-capitalize begins sentences normally (phone behavior unchanged!).

---

## CAROUSEL / EXTRA-KEYS ROW ON WEAR — COMPLETED, WORKING

Files: `app/src/main/java/com/termux/app/terminal/io/WearLoopingExtraKeysView.java`
(or similarly named), `WearExtraKeysViewPager`/ViewPager files, `TermuxTerminalViewClient.java`,
`TermuxActivity.java`.

### Infinite looping carousel of extra keys
On Wear OS the extra keys row is a **looping ViewPager** showing many keys; the user swipes
horizontally and it wraps forever (first→last→first...) with a center-snap. Implemented via a
custom PagerAdapter with mirrored pages and a `PagerSnapHelper`/`SnapHelper`-style center snap.
It ignores the `mCallback`-driven page-change so wrapping is seamlessable. Do NOT replace with a
single static row.

### Watch-specific default: extra keys PRESENT on watches
The Wear default for "Extra keys row visibility" property is ON (false on phones — phone stays
with its normal "history chooser / not-shown" default). The carousel initial pages, key set, and
sizes are overridden for watches in `TermuxAppSharedPreferences` when `isWatchDevice()`. There is
a `WEAR_KEYS_BEGIN_ETC` marker: charge ensure the default for watch is the carousel keys.

### Hide/show behavior (user requested, currently: hidden at startup? careful)
The user requested the ability to hide the carousel row when not needed alert. AS OF THIS WRITEUP
the LAST REQUESTED MODEL is: swipe down on the carousel hides it; swipe up from the bottom edge of
the screen reveals it. Frequency-animation must be SMOOTH: ideally crossfade/translate, ONE
transition per gesture, NO per-frame LayoutPager work, NO "GONE then reflow causing instant
teleport". Do NOT make it per-frame toggling (laggy) and do NOT hide via removing from layout in a
way that snaps. **If the code currently does something different than this (e.g. hidden at startup
always, or only reveal on bottom-edge upward), verify against what the user LAST approved — ask the
user or check their last message. The safest is to keep whatever the user confirmed feels good,
and only restore strictly-smooth transitions.**

Wear keyboard starts lowercase (-1 caps mode). These are covered by KeyboardUtils/input type
overrides listed above.

---

## OTHER WEAR WORK — PRESERVE

- Round-screen bezel inset: on `isScreenRound()` and round Wear displays, content is inset so the
  round bezel doesn't clip the terminal (`TermuxActivity` margin + terminal view extension). Keep.
- Terminal toolbar height capped on watches (scroll-to-reveal uses the extra keys row height) —
  keep the cap so it doesn't consume the small screen.
- `mTerminalToolbarHiddenByWatchScroll` in `TermuxActivity.java`: on startup watches hide the
  toolbar row; a bottom-edge upward swipe reveals; carried/mirrored in `TermuxTerminalViewClient`.
  Keep the hide-state flag consistent: it is used by the animate-reveal.
- `.editorconfig`/`.gitignore`: n/a to fork shipping.

---

## BUILD + INSTALL (worked every time; keep this recipe)

```bash
cd /home/dada/Desktop/TERMUX/termux-app-0.118.3
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug
# APK:
# app/build/outputs/apk/debug/termux-app-0.118.3-debug.apk (or termux-app_debug_universal.apk)
```

Install to watch over adb:
```bash
adb -s <watch-serial> install -r app/build/outputs/apk/debug/termux-app-0.118.3-debug.apk
```
**Known trap:** installing the `arm64-v8a` split APK on the watch FAILS with
`INSTALL_FAILED_NO_MATCHING_ABIS`. ALWAYS use the **universal** APK for the watch
(`termux-app_debug_universal.apk`). NEVER hand the user a "just the arm64 one".
Headless Gradle details: this tree has no `.git`, and gradle needs `JAVA_HOME` set as above.

---

## HARD INVARIANTS (never break)

1. **`isWatchDevice()` gates EVERYTHING watch.** No watch branch unguarded.
2. **Phone unchanged.** After any edit, phone-specific paths byte-same behavior.
3. **setComposingText live-sync is the keyboard's lifeline.** Never replace with commit-only.
4. **initialCapsMode/inputType = lowercase on watch.** Never re-upper-case Gboard.
5. **Use the UNIVERSAL APK for the watch.** Split-APK = NO_MATCHING_ABIS fail.
6. **Round-bezel inset + toolbar height cap stay.**
7. **Smooth hide/show — one transition per gesture, no per-frame work, no GONE reflow snap.**
8. **Buffer + backspace + composing are ONE feature on watch.** Never split.

---

## WHAT TO DO FIRST (verify current state, then continue work)

1. Read `TerminalView.java` fully around the watch keyboard block and the watch scroll listener.
   Confirm setComposingText + initialCapsMode + watch buffer all exist and are gated on watch.
2. Read `TermuxActivity.java` "onWatchTerminalScroll" / toolbar-reveal block; confirm the flag
   `mTerminalToolbarHiddenByWatchScroll` matches any reveal listener in `TerminalView`.
   FAULT-LINE: ensure the listener interface signature matches the activity method
   (boolean arg vs no-arg). This bit us during the gesture rewrite — ALWAYS align them.
3. Build + install universal APK on watch, then run the SMOKE TEST above.

## SHIPPING / PR (do not skip)
- This tree is NOT a git repo. The fork is `/home/dada/Desktop/TermuxFORK/termux-app` (branch
  `wearos-keyboard-fix`, origin = user fork, upstream = termux/termux-app). To ship: make the
  fork tree **byte-identical** to this release source (e.g. rsync/cp the tested files), then in
  the fork: `git add -A; git commit; git push origin wearos-keyboard-fix`, then open the PR. Do
  NOT revert or re-implement in the fork from memory — copy the tested files.
- The user has a drafted PR message at `TERMUX/termux-app-0.118.3/message_pr.md` (contributor
  steps + screenshots placeholders). Reuse it, update against the fork, delete the "steps I need
  to do" section before PR, never submit before showing the user the final PR preview.
- Wear OS bits are isolated to recent commits; keep the change-set minimal (do not reformat
  unrelated code).

---

## PREVIOUS MISTAKES TO NOT REPEAT (we burned a LOT of session on these)

- **Over-engineering the hide/show carousel.** We went through: (a) per-frame drag reveal →
  laggy; (b) GONE-on-hide → "transparent block / teleport" bug; (c) fling-only → too much
  scrolling; (d) settled model = swipe-down-on-carousel hides, bottom-edge upward swipe reveals,
  ONE transition per gesture, animate with translationY+alpha, ended with GONE. If ever
  re-tuning: small distances, single notify, no per-frame work. **Never do per-frame notify again.**
- **Mismatched listener signature between TerminalView and the Activity.** Keep interface +
  activity handler ALWAYS in sync (boolean vs no-arg burned a whole build).
- **Never install the non-universal APK on watch.**
- **Don't trust "the fork's git tag" blindly** — the fork repo's git object store has proven
  partial/stale content relative to the release zip; if `git show vX.Y.Z:file` disagrees with the
  release zip file, TRUST THE RELEASE ZIP (it's what we tested), not the tag.
- **Don't re-implement watch logic in the dup fork**; copy tested files instead.
- **The keyboard "read what I've written into the keyboard" complaint** was solved by
  `setComposingText`. If that complaint ever comes back, re-check composing sync, NOT the input
  type.
- **All-caps / auto-capitalized keyboard** was solved by initialCapsMode=0 + lowercase watch
  inputType. If a user ever reports caps again on watch, re-check initialCapsMode.

## CONTACT / FINAL NOTE
Run the smoke test with the user on the real watch before closing. Read files, don't guess. When
done, save an updated handoff doc at `TERMUX/termux-app-0.118.3/HANDOFF.md` with the new state so
the NEXT session starts from truth, not memory.
