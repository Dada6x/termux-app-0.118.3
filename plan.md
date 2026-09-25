# Termux on Wear OS — Progress & Roadmap

## What we achieved

### 1. Wear OS UI adaptation
- Watch-specific extra-keys layout (`DEFAULT_IVALUE_EXTRA_KEYS_WATCH`) tuned for a single row on a
  small round/square screen.
- Soft keyboard no longer auto-opens on launch on a watch — it opens on terminal tap or via the
  `KEYBOARD` toggle key, so the terminal gets the full screen.
- Toolbars, pager and extra-keys view all take the watch branch of the existing dual-layout code.

### 2. Looping extra-keys carousel + smart auto-hide
- `WearLoopingExtraKeysView` renders the key row as a horizontally looping (infinite) carousel, so
  every key is reachable by swiping either direction with no dead ends.
- The toolbar row auto-hides to give the terminal back its vertical space, but no longer vanishes
  mid-interaction:
  - auto-hide delay raised to **6s** of true inactivity,
  - any terminal scroll resets the timer,
  - any touch on the carousel resets it — implemented in `dispatchTouchEvent` so the timer resets
    even when a key button consumes the touch.
- Scroll direction still reveals/hides the row, and swiping down on the row hides it immediately.

### 3. Type-on-phone command relay
Press `📱` (`PHONE` key) in the watch carousel to hand command entry to the phone:

- **watch** → sends `MESSAGE_PATH_REQUEST` over the Wear data layer; advertises/uses the
  `termux_wear_phone_input` capability for discovery.
- **phone** → if Termux is foreground, shows an in-app input dialog; otherwise posts a notification
  with a **RemoteInput reply box** (works with Termux closed, via the Play-services-bound
  `WearableListenerService`).
- **reply** → `TermuxWearReplyReceiver` reads the typed text and sends it back as
  `MESSAGE_PATH_REPLY`; the watch injects it into the open terminal and submits it with Enter.

### 4. Watch keyboard input + Wear OS 3.x fix
- Custom watch `InputConnection`: a command-line buffer that feeds WearGboard's preview strip via
  `getExtractedText`, with correct backspace handling and buffer persistence.
- The watch keyboard **send** button now closes the keyboard. Wear OS 2.26 auto-dismissed the IME
  after the app consumed the editor action; Wear OS 3.5 does not, so `performEditorAction` now hides
  the IME explicitly (immediately and again after 150 ms to beat the IME re-showing itself).

### 5. Bugs found and fixed along the way
- **Phone crash on remote input**: the reply action's `PendingIntent` was immutable; Android rejects
  immutable intents for `RemoteInput` actions, so `notify()` threw and killed the phone's listener
  service before the notification was ever shown. Now mutable.
- **Phone freeze / "not responding"**: the input dialog was created on the Wear binder thread; an
  `AlertDialog` built off the main thread has no prepared `Looper` → ANR. All UI work is now posted
  to the main thread first.

---

## Ideas for what to add next

### Interaction quality
1. **Wear OS 3 keyboard polish** — long-press the watch send key to submit *without* newline
   (useful for `y/n` prompts), plus a dedicated "interrupt" (Ctrl-C) key.
2. **Rotary crown / side button mapping** — crown scrolls the terminal, side button sends Enter.
3. **Round-screen key layout profile** — a second default key row shaped for fully-round displays.
4. **Swipe gestures on the terminal** — e.g. two-finger swipe = paste, swipe-and-hold = select text.
5. **Haptic feedback on every injected key** so blind typing on a watch is confirmable.

### Command entry
6. **Clipboard sync** — push the phone clipboard to the watch and add a `PASTE` key. Needs care:
   Android 10+ blocks background clipboard reads, so this has to run while Termux has focus.
7. **Command history relay** — keep the last N commands on the phone and expose them on the watch as
   a scrollable list; tap to re-run. Biggest ergonomic win after the current setup.
8. **Snippet / macro keys** — long-press an extra key to expand it into a stored snippet
   (e.g. `docker compose up -d`).
9. **Multi-line paste with bracketed-paste safety** — when a long snippet comes from the phone, warn
   before submitting lines that end in `rm`, `mv`, etc.

### Phone ⇄ watch bridge
10. **Delivery ACK + timeout** — today a request is fire-and-forget; a dropped message looks
    identical to a phone that is ignoring you. Add an ack, a retry, and a real "phone not
    responding" state on the watch.
11. **Targeted requests** — `requestPhoneInput` currently broadcasts to *every* connected node and
    relies on the receiver to filter. Use the advertised capability to pick exactly the phone.
12. **Batch queue** — send several queued commands in one go when the link is flaky.
13. **Output relay** — run a command on the phone and stream the last N lines of output back to the
    watch notification, so long jobs report progress.
14. **File transfer** — push a file picked on the phone into the watch's home directory.

### Sessions and services
15. **Watch-side session manager** — list running Termux sessions on the watch, switch, rename, kill.
16. **Service control from the watch** — start/stop a `termux-service` without touching the phone.
17. **Long-running job notifications mirrored to the watch** — build finished, download done, etc.

### Robustness / security
18. **Authenticate the bridge** — a shared secret exchanged over the Wear data layer so a rogue node
    can't inject commands into the terminal. Worth doing before this handles anything sensitive.
19. **Graceful offline behaviour** — queue input while the phone is out of range and flush on
    reconnect, instead of dropping it.
20. **Batteries** — exponential backoff on retries, and stop retrying entirely when the watch is
    critically low.

### Housekeeping
21. Add instrumentation/logging behind a flag — every bridge step currently logs at debug level with
    no runtime toggle, which makes field diagnosis awkward.
22. Strip dead code from the first iteration of the bridge now that the flow is stable.
