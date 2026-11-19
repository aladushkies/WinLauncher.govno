# WinLauncher Root — Acceptance and Device Test Plan

## Execution record

- **Current state:** tests are defined only; none of the APK, runtime, or device checks below has been run in this workspace.
- **Target device:** Samsung Galaxy Tab A 9.7 SM‑T555, Android 7.1.1 / API 25, rooted test image; record exact build fingerprint, root manager and density before testing.
- **Important:** this plan is not a build result. Static source-coherence checks are reported separately by `tools/verify_quality.py`, and `tools/compile_check.py` proves that all 23 sources compile against `android.jar` (API 35) with a real `javac` (73 class files, 0 errors). Neither of them proves hidden-API compatibility or runtime behavior.
- **Stage 0 hypotheses:** E‑1…E‑10 below reproduce the supplied TЗ statements and are device experiments, not source implementation claims.
- **Stage 5:** intentionally excluded because it was explicitly optional. Do not use the optional Stage 5 as a release gate.
- **Audit passes:** round‑by‑round records live in `docs/history/` (`AUDIT.md`, `FIXES.md` D‑01…14, `BUGFIXES.md`, `WC2FIXES.md`, `RFIXES.md`, `FFIXES.md`, `GFIXES.md`, `HFIXES.md`, `IFIXES.md`, `JFIXES.md`, `KFIXES.md`). The latest round J added the compile gate and fixed J‑01…J‑08; round K centralised the UI tokens, raised the primary touch targets to 48 dp and added the status/navigation/cutout inset model; static checks now report 174/174 PASS. This does **not** change any row below: no Gradle/AGP build ran, and no device or runtime case was executed.

For every case, perform the listed action, compare with the expected result, then mark one result. Leave both unchecked until actually tested; record build fingerprint, screenshots/logs and failure details in Notes.

| Result field | Mark |
|---|---|
| Pass | ☐ Pass |
| Fail | ☐ Fail |
| Not run | ☒ Not run (initial state) |

## A. Build, package, and install

| ID | Actions | Expected result | Result | Notes |
|---|---|---|---|---|
| B-01 | On a clean checkout with documented JDK/SDK, run `gradle :app:assembleDebug`. | Gradle completes; debug APK is created at `app/build/outputs/apk/debug/app-debug.apk`; no Java/resource/manifest errors. | ☐ Pass ☐ Fail | Not run |
| B-02 | Install that APK on Android 7.1.1 with `adb install -r …`; launch WinLauncher from the app drawer. | Package installs, main management activity opens, no immediate crash; record any target-SDK compatibility warning. | ☐ Pass ☐ Fail | Not run |
| B-03 | Select WinLauncher as the Home app from Android's Home-app chooser, press Home and relaunch. | The separate Desktop activity is offered as Home and opens; the management screen remains separately launchable. | ☐ Pass ☐ Fail | Not run |
| B-04 | Inspect the APK/release tree and repository status. | No bundled third-party font file, local SDK path, signing key, debug APK, or build output is committed or packaged as source. | ☐ Pass ☐ Fail | Not run |
| B-05 | Run `python3 tools/compile_check.py` with a JDK and `android.jar` (API 35) available, then `python3 tools/verify_quality.py`. | compile_check reports PASS with the class count; the verifier reports 174/174 PASS and exits 0. | ☐ Pass ☐ Fail | Not run |
| B-06 | Run `python3 tools/make_release.py` and rebuild the archive after a source change. | The archive is regenerated from the working tree and its printed SHA-256 changes with the sources; the archive itself is not tracked by git. | ☐ Pass ☐ Fail | Not run |

## B. Stage 0 and exact E-1…E-10 device hypotheses

| ID | Actions | Expected result / hypothesis decision | Result | Notes |
|---|---|---|---|---|
| T0 | Record device/API/build fingerprint, root manager, display size/density, test apps and recovery path. Use disposable tasks and back up system settings before root experiments. | Reproducible safe baseline exists; root UID is confirmed. | ☐ Pass ☐ Fail | Not run |
| E-1 | Launch a disposable freeform task; move it to fullscreen stack 1 under Home with `toTop=false`; verify it is hidden, then move it back to stack 2 and focus it. | Record whether stack-1 parking hides it and stack-2 restore recovers the same task/bounds. Accept/reject the hypothesis from observed device state. | ☐ Pass ☐ Fail | Not run |
| E-2 | Launch Chrome and two other apps; for each actual task ID execute `am stack movetask <taskId> 2 true`; inspect StackInfo and visibility. | Determine whether post-launch tasks enter freeform stack 2 and can be managed without app restart. Record unsupported shell syntax/API response. | ☐ Pass ☐ Fail | Not run |
| E-3 | On a disposable freeform task, request `resizeTask` rectangles at/near each screen edge; compare requested vs returned bounds and visible margins. | Determine whether Samsung clamps to 3 px margins; retain actual coordinates and whether the stated “no 3 px clamp” hypothesis holds. | ☐ Pass ☐ Fail | Not run |
| E-4 | Compare resizable test activities with resizeMode 0 and 1; resize below declared minimums and through launcher/root APIs; record task bounds and rejection. | Measure minimum width/height and differences between resizeMode 0/1; do not assume generic Android behavior equals Samsung behavior. | ☐ Pass ☐ Fail | Not run |
| E-5 | Query StackInfo repeatedly while focusing known bottom/top tasks; compare returned taskIds order with actual foreground/top task. | Determine whether the last taskId is the topmost task; record order across stack changes. | ☐ Pass ☐ Fail | Not run |
| E-6 | In a disposable root-side probe, call `getTaskThumbnail` and `SurfaceControl.screenshot`; inspect success, exceptions, image dimensions and required privilege. Delete probe captures afterwards; do not connect them to launcher effects. | Record whether both APIs are callable under root on this firmware. This is a capability experiment only; production launcher intentionally does not use task thumbnails or bitmap IPC. | ☐ Pass ☐ Fail | Not run |
| E-7 | Open several Samsung freeform apps; record caption bounds/height and side insets using the Samsung resource ID and accessibility tree; compare saved calibration. | Determine variation across apps and whether KeyService caption calibration gives usable values/fallback. | ☐ Pass ☐ Fail | Not run |
| E-8 | On a disposable device with a physical recovery path, identify keyboard/touchkey `/dev/input` nodes; test root-grab and controlled key/touch injection via `injectInputEvent`, then release grab immediately. | Determine whether root can read those devices and inject events without losing input. Record node permissions, event codes and failure; do not leave any device grabbed. | ☐ Pass ☐ Fail | Not run |
| E-9 | Back up current status-bar policy; test `IStatusBarService.disable` and `settings put global policy_control immersive.status=*` separately; verify immersive/status-bar state and restore original values. | Determine which mechanism works on this Samsung build and whether it survives service restart/reboot; preserve a recovery path. | ☐ Pass ☐ Fail | Not run |
| E-10 | In a disposable docked pair, probe the Samsung docked stack and invoke the available `resizeDockedStack` signature with explicit test bounds; inspect both tasks and divider after resize. | Determine whether docked-stack resizing accepts arbitrary bounds and which hidden signature/stack ID is actually supported. | ☐ Pass ☐ Fail | Not run |

## C. Root bridge and system policy

| ID | Actions | Expected result | Result | Notes |
|---|---|---|---|---|
| R-01 | Verify `ping`, `tasks`, and supported stack/bounds operations with a disposable app. | Root bridge exchanges bounded JSON lines and returns actionable failures; no task operation is silently reported as successful when rejected. | ☐ Pass ☐ Fail | Not run |
| R-02 | Toggle status-bar hiding off/on; inspect the device while launcher monitoring is active. | Status policy changes as requested; when visible, work-area geometry accounts for the device's actual inset. | ☐ Pass ☐ Fail | Not run |
| R-03 | Press PrtSc on a supported external keyboard. | RootServer writes a PNG under `/sdcard/Pictures/WinLauncher/`; KeyService reports the returned path or a clear error; RootBridge sends only the capture command and receives a JSON path, never bitmap/Base64 data. | ☐ Pass ☐ Fail | Not run |
| R-04 | Use each Action Center radio action on Wi‑Fi, Bluetooth, mobile data and airplane mode; compare with Android Settings before/after. | A known state flips once and matches system state; unsupported state/command opens or recommends Settings and is not falsely presented as successful. | ☐ Pass ☐ Fail | Not run |
| R-05 | Exercise system-setting writes only on a test device; reboot and inspect state. | Commands are limited to documented actions; airplane-mode broadcast is consistent; no unrelated global settings are modified. | ☐ Pass ☐ Fail | Not run |
| R-06 | **J-01:** resize a disposable freeform window by dragging its edge and by snapping it to a layout; read the JSON answer of the root bridge. | The task really changes size (root `resizeTask` succeeds through the boolean overload); no `NoSuchMethodException` and no "resize failed" toast. | ☐ Pass ☐ Fail | Not run |
| R-07 | **J-02:** open several different apps in freeform mode and read the task list (taskbar labels, Task View rows, window titles). | Every window shows a real application label and icon, not the generic “Окно”; the launcher's own activities never appear as managed windows. | ☐ Pass ☐ Fail | Not run |
| R-08 | **J-03:** toggle Wi‑Fi, Bluetooth and mobile data from the Action Center and compare the tile text with Android Settings afterwards. | The tile shows the state that was read back from the system; if a command is ignored by the firmware, the tile says the state is unconfirmed instead of “Вкл.” | ☐ Pass ☐ Fail | Not run |

## D. Windows, state persistence, taskbar, and groups

| ID | Actions | Expected result | Result | Notes |
|---|---|---|---|---|
| W-01 | Create test windows; move/resize, maximize, restore, fullscreen, minimize, restore, and close each. | State, bounds, taskbar label and button accessibility description agree; no stale overlay remains after close. | ☐ Pass ☐ Fail | Not run |
| W-02 | Minimize normal, maximized, fullscreen and snapped tasks; force-stop/reopen only the launcher service, then restore. | Stored restore-state/bounds are used where Samsung permits; failure is surfaced and does not corrupt another task. | ☐ Pass ☐ Fail | Not run |
| W-03 | Test minimize parking to stack 1 and both documented fallback paths with disposable tasks. | The window becomes hidden and remains recoverable; actual Samsung stack behavior and z-order are recorded. | ☐ Pass ☐ Fail | Not run |
| W-04 | Verify the taskbar on each virtual desktop, click a task, minimize/restore it and open its context menu. | Only current-desktop tasks appear; task actions and Snap Layouts menu target the correct task. | ☐ Pass ☐ Fail | Not run |
| W-05 | Use Win+D/Ctrl+D twice, including a second press before a minimize finishes. | Show Desktop hides/restores the eligible current desktop set; queued restore does not lose the list. | ☐ Pass ☐ Fail | Not run |
| W-06 | Minimize/focus/close one member of a SnapGroup and detach/maximize another; repeat with a minimized member. | Group operations affect intended members only; a detached survivor normalizes its saved restore-state. | ☐ Pass ☐ Fail | Not run |

## E. Snap, layout, assist, fullscreen, and docked pair

| ID | Actions | Expected result | Result | Notes |
|---|---|---|---|---|
| S-01 | Drag windows to left/right edges and four corners; use all six Layouts with 2–4 windows. | Expected cell geometry is applied; gap/inset and portrait transposition are respected. Drag preview is intentionally omitted to avoid a full-screen overlay. | ☐ Pass ☐ Fail | Not run |
| S-02 | Open Snap Layouts via MAX hover, long-press, title/context menu and taskbar menu; choose every cell. | Layout panel and selection work; no thumbnail capture occurs; chosen windows enter the expected cell. | ☐ Pass ☐ Fail | Not run |
| S-03 | After first snap, choose candidates in Snap Assist; dismiss with Esc, outside tap, and blank area. | Remaining free cells are filled in sequence; dismiss paths work; candidates use app icons, not task thumbnails. | ☐ Pass ☐ Fail | Not run |
| S-04 | Resize a two-window snap group from each shared divider; drag a member onto an occupied cell. | Adjacent bounds stay joined, min-size clamp remains stable, and occupied-cell drop swaps group membership. | ☐ Pass ☐ Fail | Not run |
| S-05 | Enter a fullscreen docked pair from Task View; drag divider both directions and to either exit edge. | Samsung accepts the selected stack profile; 50:50 entry, bounds, minimum ratio and stack-1 exit are correct. | ☐ Pass ☐ Fail | Not run |
| S-06 | Exit docked pair with Alt+F11, Alt+Space menu and Task View; test landscape and portrait. | Each route returns both tasks to fullscreen stack; no task becomes stranded. | ☐ Pass ☐ Fail | Not run |
| S-07 | Enter FULLSCREEN; test top, bottom, left and right edge swipe/hover, per-app exclusion and double-swipe mode. | Mini-title, temporary taskbar, Task View and Action Center appear only for enabled edges; 2 dp solid edge indicators do not create broad opaque masks. | ☐ Pass ☐ Fail | Not run |
| S-08 | Toggle the optional Escape setting and press Esc in fullscreen with/without other dismissible panels. | Esc dismisses the top eligible overlay; it exits FULLSCREEN only when the explicit setting is enabled. | ☐ Pass ☐ Fail | Not run |

## F. Virtual desktops

| ID | Actions | Expected result | Result | Notes |
|---|---|---|---|---|
| V-01 | Open Task View, create desks up to the cap, navigate previous/next and restart the launcher. | One to four desktops are retained; navigation is clamped; current desk persists. | ☐ Pass ☐ Fail | Not run |
| V-02 | Place normal, snapped, fullscreen and minimized windows on desk 1; switch away/back. | Non-current desk windows are hidden and restored on return; taskbar is filtered; SnapGroups move as a unit. | ☐ Pass ☐ Fail | Not run |
| V-03 | Long-press a Task View task to cycle its assigned desktop; switch to that desktop. | Task label/assignment updates and the task is visible only on the selected desk. | ☐ Pass ☐ Fail | Not run |
| V-04 | Use Win+Ctrl+Left/Right at first and last desk. | Desk navigation works and does not create desks beyond the four-desk limit. | ☐ Pass ☐ Fail | Not run |

## G. Keyboard shortcut matrix

For hardware-keyboard tests, enable KeyService through the documented user-approved accessibility path. Android reserves some system keys; record keys not delivered to the service as platform limits rather than claiming they passed.

| ID | Shortcut / actions | Expected result | Result | Notes |
|---|---|---|---|---|
| K-01 | Win+A | Opens Action Center. | ☐ Pass ☐ Fail | Not run |
| K-02 | Win+N | Opens Action Center / notifications section. | ☐ Pass ☐ Fail | Not run |
| K-03 | Win+I | Opens WinLauncher settings. | ☐ Pass ☐ Fail | Not run |
| K-04 | Press Win+L with KeyService enabled and verify Android's handling. | WinLauncher deliberately does not lock: `DevicePolicyManager.lockNow()` requires an explicitly activated Device Admin with `USES_POLICY_FORCE_LOCK`; `PowerManager.goToSleep()` requires signature permission `DEVICE_POWER`. | ☐ Pass ☐ Fail | Not run |
| K-05 | Win+Tab | Opens Task View. | ☐ Pass ☐ Fail | Not run |
| K-06 | Win+Z | Opens Snap Layouts for the focused window when launcher chrome is active. | ☐ Pass ☐ Fail | Not run |
| K-07 | Win+1, then Win+2 … Win+9 | Focuses/restores corresponding taskbar task where one exists. | ☐ Pass ☐ Fail | Not run |
| K-08 | Win+T repeatedly | Cycles taskbar windows. | ☐ Pass ☐ Fail | Not run |
| K-09 | Win+Home | Minimizes other eligible windows while retaining the focused window/group. | ☐ Pass ☐ Fail | Not run |
| K-10 | Ctrl+D and Win+D | Toggles Show Desktop and restores the same set. | ☐ Pass ☐ Fail | Not run |
| K-11 | Press Alt+Tab with normal, minimized and fullscreen windows. | Opens the simplified Task View (not a cyclic Windows Alt+Tab popup); selecting a row focuses/restores the intended task. | ☐ Pass ☐ Fail | Not run |
| K-12 | Alt+F4 | Closes the focused task; does nothing destructive when no managed task is focused. | ☐ Pass ☐ Fail | Not run |
| K-13 | Alt+Space | Opens the focused window system menu. | ☐ Pass ☐ Fail | Not run |
| K-14 | Press Ctrl+Shift+Esc with several normal/minimized tasks; close one task and restore one minimized task from the list. | Opens `TaskManagerActivity`, not Task View: each known task shows title/package/state, Close invokes RootServer task removal, and minimized tasks offer Restore. It is a window-task list, not an OS process/service manager. | ☐ Pass ☐ Fail | Not run |
| K-15 | Press PrtSc on a supported external keyboard. | RootServer saves a PNG under `/sdcard/Pictures/WinLauncher/` and reports the resulting path or an error. | ☐ Pass ☐ Fail | Not run |
| K-16 | Win+Left / Win+Right | Snaps/focuses a left/right half according to state. | ☐ Pass ☐ Fail | Not run |
| K-17 | Win+Up / Win+Down | Applies the documented maximize/restore/minimize transitions. | ☐ Pass ☐ Fail | Not run |
| K-18 | F11 and Alt+F11 | F11 toggles fullscreen; Alt+F11 exits docked pair. | ☐ Pass ☐ Fail | Not run |
| K-19 | Win+Ctrl+Left / Win+Ctrl+Right | Switches virtual desktops within the available range. | ☐ Pass ☐ Fail | Not run |
| K-20 | Esc with setting off/on | Esc exits fullscreen only when the preference is on; otherwise only dismisses supported overlays. | ☐ Pass ☐ Fail | Not run |

## H. Action Center and Android notifications

| ID | Actions | Expected result | Result | Notes |
|---|---|---|---|---|
| A-01 | Open Action Center without notification-listener access. | Panel explains the requirement and opens Android's notification-listener settings only after user action. | ☐ Pass ☐ Fail | Not run |
| A-02 | Grant access in Android Settings, post notifications from two apps, open panel and tap each. | Active notifications appear with app/title/text; valid content intents open; removal is reflected on next open. | ☐ Pass ☐ Fail | Not run |
| A-03 | Revoke notification access and reopen panel. | Access loss is handled without crash; contents are not represented as available. | ☐ Pass ☐ Fail | Not run |
| A-04 | Use all four radio controls; compare state with system Settings and test vendor unsupported commands. | Root bridge reflects known state, changes supported toggles and reports errors rather than faking success. | ☐ Pass ☐ Fail | Not run |

## I. Built-in apps, desktop, widgets, settings, and appearance

| ID | Actions | Expected result | Result | Notes |
|---|---|---|---|---|
| I-01 | Launch Edge Lite; create/switch/close/reorder tabs; open a URL, domain and search phrase. | Each WebView retains its own page; omnibox resolves URLs and uses DuckDuckGo for searches; drag reorders tabs. | ☐ Pass ☐ Fail | Not run |
| I-02 | Navigate several pages; open/clear history; close/reopen Edge Lite. | Bounded URL history persists and can be cleared; last URL is restored; no page text is stored by history. | ☐ Pass ☐ Fail | Not run |
| I-03 | Download a file with storage permission denied, then granted; inspect Android Downloads. | Denial is reported and user can retry; allowed downloads go through Android DownloadManager and its completion notification. | ☐ Pass ☐ Fail | Not run |
| I-04 | Set WinLauncher as Home; use desktop icons, app list, long-press/right-click background, cycle wallpaper and enable slideshow. | Desktop launches built-ins/installed apps; opaque geometric Bloom-inspired wallpaper changes on command/timer; no image blur or alpha compositing is used. | ☐ Pass ☐ Fail | Not run |
| I-05 | From DesktopActivity tap “Добавить виджет”, choose a provider, accept Android binding/configuration, then inspect the wallpaper desktop. | The configured `AppWidgetHostView` appears directly in the desktop widget area; no separate widget page is required. | ☐ Pass ☐ Fail | Not run |
| I-06 | Rotate/reopen the desktop, interact with the widget, long-press it and remove it. | The same saved widget IDs are rendered on desktop after recreation; widget interaction works; removal deletes the host ID and the view disappears. | ☐ Pass ☐ Fail | Not run |
| I-07 | Change dark/light theme, mouse/touch profile, snap gap, edge exclusions and status-bar behavior. | Preferences persist; visible surfaces use opaque solid palettes; third-party window corners remain untouched/square. | ☐ Pass ☐ Fail | Not run |
| I-08 | Inspect source/APK for bundled third-party font files, non-opaque colors, alpha/elevation attributes, blur effects, or task-image captures used in transitions. | Launcher surfaces remain opaque with lightweight color feedback; no window screenshots are captured for animations. PrtSc remains a separate PNG-to-file feature with path-only RootBridge IPC; Snap Assist currently uses application icons. | ☐ Pass ☐ Fail | Not run |

## J. Performance, rotation, gestures, and Samsung-specific checks

| ID | Actions | Expected result | Result | Notes |
|---|---|---|---|---|
| P-01 | On the low-memory tablet, cold launch desktop, open Task View/Action Center, snap four windows, scroll Edge Lite and monitor `dumpsys meminfo`, CPU and frame timing. | No blur/Mica/render-script, task-thumbnail capture, animation snapshots or alpha overlay work; standalone PrtSc writes directly to PNG without bitmap IPC. Record memory and frame latency. | ☐ Pass ☐ Fail | Not run |
| P-02 | Rotate in desktop, Edge Lite, Task View, Snap and docked split; repeat with mouse attached/detached. | Layouts recalculate and maintain sensible bounds; input profile follows the selected Auto/Mouse/Touch option. | ☐ Pass ☐ Fail | Not run |
| P-03 | Exercise title drag slop, double click, long press, hover and touch gestures; cancel a drag mid-way. | Gesture thresholds are stable; cancel cleans previews/state; no stuck pointer capture. | ☐ Pass ☐ Fail | Not run |
| P-04 | Test Samsung caption ID/calibration on several apps, freeform resize, stack IDs 1/2/3, task visibility and root screenshot. | Record actual Samsung resource IDs and hidden API results; deviations are documented and guarded by user-visible errors. | ☐ Pass ☐ Fail | Not run |
| P-05 | Verify the signed APK with `apksigner verify --verbose --print-certs`, then install it through Samsung's package installer. | APK signature validates, Samsung accepts installation/update, and certificate SHA-256 matches the project's separately recorded trusted signing certificate. No Samsung production key is bundled or claimed. | ☐ Pass ☐ Fail | Not run |

## K. Explicit non-goals / platform limits to record

- No device is available in the source workspace, so none of the tests above has been accepted by device evidence.
- The application cannot grant itself Notification Listener access; Android Settings consent is required.
- Win+L secure locking, reserved Android system keys, vendor-specific stack IDs/hidden APIs and `svc bluetooth` support are not guaranteed by public Android 7.1.1 APIs.
- Alt+Tab intentionally opens the simplified launcher Task View rather than a cyclic Windows-style switcher. Ctrl+Shift+Esc opens a simple window-task manager, not Android's process/service manager.
- Win+L is not implemented: `DevicePolicyManager.lockNow()` needs explicit active-admin `USES_POLICY_FORCE_LOCK` consent; `PowerManager.goToSleep()` needs signature permission `android.permission.DEVICE_POWER`.
- Desktop wallpaper is a generated geometric Bloom-inspired drawing, not the licensed original Windows wallpaper assets. Widgets are hosted directly in DesktopActivity after explicit system bind/configure consent.
- Drag snap preview is intentionally absent; snap is applied on release. This avoids painting a translucent/opaque full-screen preview over third-party app content.
- Stage 5 remains excluded by instruction.
