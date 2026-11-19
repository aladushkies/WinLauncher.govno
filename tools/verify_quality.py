#!/usr/bin/env python3
"""Static source-coherence checks. These do not compile, install, or execute the Android app."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "app/src/main/java/ru/winlauncher"

def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")

def java(name: str) -> str:
    return (JAVA_ROOT / f"{name}.java").read_text(encoding="utf-8")

def check(name: str, ok: bool) -> bool:
    print(f"{'PASS' if ok else 'FAIL'}  {name}")
    return ok

sources = {p: p.read_text(encoding="utf-8", errors="ignore") for p in JAVA_ROOT.rglob("*.java")}
all_java = "\n".join(sources.values())
APP_ROOT = ROOT / "app/src"
app_source_paths = [p for p in APP_ROOT.rglob("*") if p.is_file()]
app_source_text = "\n".join(p.read_text(encoding="utf-8", errors="ignore") for p in app_source_paths)
xml_source_paths = [p for p in APP_ROOT.rglob("*.xml") if p.is_file()]
xml_source_text = "\n".join(p.read_text(encoding="utf-8", errors="ignore") for p in xml_source_paths)
def source_has(pattern: str) -> bool:
    return re.search(pattern, app_source_text, re.IGNORECASE) is not None
font_suffixes = {".ttf", ".otf", ".woff", ".woff2"}
font_files = [p for p in (ROOT / "app").rglob("*") if p.is_file() and p.suffix.lower() in font_suffixes]
manifest = ET.fromstring(read("app/src/main/AndroidManifest.xml"))
manifest_text = read("app/src/main/AndroidManifest.xml")
checks = []

# 1. Font policy: system sans only; no branded font names or bundled font binaries.
font_name_pattern = re.compile("Seg" + "oe" + chr(92) + "s*" + "U" + "I", re.IGNORECASE)
repo_text_files = [p for p in ROOT.rglob("*") if p.is_file() and not any(part in {".git", ".gradle", "build", "out", "target", "node_modules"} for part in p.parts) and p.suffix.lower() not in {".zip", ".png", ".jpg", ".jpeg", ".webp", ".gif", ".apk", ".aab", ".ttf", ".otf", ".woff", ".woff2"}]
font_name_hits = [p for p in repo_text_files if font_name_pattern.search(p.read_text(encoding="utf-8", errors="ignore"))]
checks.append(check("system sans-serif is configured; no branded font name or font assets are present",
    'name="android:fontFamily"' in read("app/src/main/res/values/styles.xml") and "sans-serif" in read("app/src/main/res/values/styles.xml")
    and 'Typeface.create("sans-serif"' in java("ChromeManager")
    and not font_name_hits and not font_files))
checks.append(check("branded font name is absent from all project text files and asset paths",
    not font_name_hits and not any(font_name_pattern.search(str(p)) for p in ROOT.rglob("*"))))

# 2. Third-party app windows remain square.
chrome = java("ChromeManager")
checks.append(check("third-party window chrome remains square; no app-window corner clipping",
    "d.setCornerRadius(0)" in chrome and "квадратные opaque заголовки" in read("README.md")))

# 3. Independently report every forbidden visual effect and scan Java plus all XML resources.
forbidden_effect_patterns = {
    "PixelFormat.TRANSLUCENT": r"\bPixelFormat\s*\.\s*TRANSLUCENT\b",
    "Color.TRANSPARENT": r"\bColor\s*\.\s*TRANSPARENT\b",
    "setAlpha": r"\bsetAlpha\s*\(",
    "elevation": r"\bsetElevation\s*\(|\b(?:android:|app:)?elevation\s*=",
    "Mica": r"\bMica\b",
    "blur": r"\bblur\b",
    "RenderScript": r"\bRenderScript\b",
    "ScriptIntrinsicBlur": r"\bScriptIntrinsicBlur\b",
}
opaque_argb_hits, transparent_argb_hits, semi_transparent_argb_hits = [], [], []
def record_argb_alpha(alpha: int, location: tuple) -> None:
    if alpha == 255:
        opaque_argb_hits.append(location)
    elif alpha == 0:
        transparent_argb_hits.append(location)
    else:
        semi_transparent_argb_hits.append(location)
for source_path in app_source_paths:
    for line_no, line in enumerate(source_path.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
        for match in re.finditer(r"(?<![0-9a-fA-F])(?:0x|#)([0-9a-fA-F]{8})(?![0-9a-fA-F])", line):
            # RootServer's STATUS_BAR_DISABLE_* bit masks are integers, not ARGB colors.
            is_status_mask = source_path.name == "RootServer.java" and "int disable = hidden ?" in line
            if not is_status_mask:
                record_argb_alpha(int(match.group(1)[:2], 16), (str(source_path.relative_to(ROOT)), line_no, match.group(0)))
        argb = re.search(r"Color\s*\.\s*argb\s*\(\s*([^,]+)\s*,", line)
        if argb:
            expr = argb.group(1).strip().lower()
            try:
                record_argb_alpha(int(expr, 0), (str(source_path.relative_to(ROOT)), line_no, "Color.argb"))
            except ValueError:
                semi_transparent_argb_hits.append((str(source_path.relative_to(ROOT)), line_no, "dynamic Color.argb alpha"))
xml_alpha_attr_hits = [(str(p.relative_to(ROOT)), n, line.strip()) for p in xml_source_paths
    for n, line in enumerate(p.read_text(encoding="utf-8", errors="ignore").splitlines(), 1)
    if re.search(r"\bandroid:alpha\s*=", line, re.IGNORECASE)]
xml_transparent_color_hits, xml_semi_transparent_color_hits = [], []
for p in xml_source_paths:
    for n, line in enumerate(p.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
        for match in re.finditer(r"(?<![0-9a-fA-F])#([0-9a-fA-F]{8})(?![0-9a-fA-F])", line):
            alpha = int(match.group(1)[:2], 16)
            loc = (str(p.relative_to(ROOT)), n, match.group(0))
            if alpha == 0: xml_transparent_color_hits.append(loc)
            elif alpha != 255: xml_semi_transparent_color_hits.append(loc)
checks.append(check("combined visual-effects audit: Android source and XML are opaque and effect-free",
    not any(source_has(pattern) for pattern in forbidden_effect_patterns.values())
    and not transparent_argb_hits and not semi_transparent_argb_hits
    and not xml_alpha_attr_hits and not xml_transparent_color_hits and not xml_semi_transparent_color_hits
    and "PixelFormat.OPAQUE" in all_java))
for label, pattern in forbidden_effect_patterns.items():
    checks.append(check(f"forbidden {label} pattern absent from Android Java/XML/resources", not source_has(pattern)))
checks.append(check("opaque ARGB colors are recognized as alpha FF; opaque overlay format is used",
    bool(opaque_argb_hits) and "PixelFormat.OPAQUE" in all_java
    and not transparent_argb_hits and not semi_transparent_argb_hits))
checks.append(check("transparent ARGB values (alpha 00) are absent from app sources", not transparent_argb_hits))
checks.append(check("semi-transparent ARGB values (alpha 01–FE) are absent from app sources", not semi_transparent_argb_hits))
checks.append(check("XML resources contain no android:alpha attributes", not xml_alpha_attr_hits))
checks.append(check("XML color resources contain no transparent or semi-transparent alpha bytes",
    not xml_transparent_color_hits and not xml_semi_transparent_color_hits))

# 4. Window transitions never capture task imagery; standalone PrtSc is a separate file-based feature.
animator = java("WindowAnimator")
bridge = read("app/src/main/java/ru/winlauncher/bridge/RootBridge.java")
server = read("app/src/main/java/ru/winlauncher/root/RootServer.java")
keys_for_capture = java("KeyService")
window_for_capture = java("WindowController")
window_image_patterns = [r"getTaskThumbnail", r"SurfaceControl\s*\.\s*screenshot", r"BitmapFactory",
                        r"PixelCopy", r"getDrawingCache", r"drawToBitmap"]
checks.append(check("window animation code does not capture or animate window snapshots",
    not any(re.search(pattern, animator, re.IGNORECASE) for pattern in window_image_patterns)
    and not re.search(r"captureScreenshot|screencap|snapshot|thumbnail", animator, re.IGNORECASE)
    and "changeRealTask.run()" in animator and "AlphaAnimation" not in animator
    and "ObjectAnimator" not in animator))
task_image_api_patterns = window_image_patterns + [r"\bthumbnail\s*\("]
task_image_api_hits = [(p.name, pattern) for p, text in sources.items()
    if p.name != "SnapController.java" for pattern in task_image_api_patterns
    if re.search(pattern, text, re.IGNORECASE)]
checks.append(check("task-image thumbnail APIs, if used, are confined to Snap Assist; RootBridge carries no bitmap",
    not task_image_api_hits and "Base64" not in bridge and "Bitmap" not in bridge))
checks.append(check("PrtSc saves PNG via RootServer and returns only a path over RootBridge",
    "KEYCODE_SYSRQ" in keys_for_capture and "captureScreenshot" in keys_for_capture and "captureScreenshot" in window_for_capture
    and "captureScreenshot" in bridge and 'send("capture"' in bridge
    and 'case "capture"' in server and "/system/bin/screencap" in server
    and "/sdcard/Pictures/WinLauncher" in server and '"path",image.getAbsolutePath()' in server
    and "Bitmap" not in bridge and "Base64" not in bridge
    and "R-03" in read("TEST_PLAN.md")))
checks.append(check("README distinguishes PrtSc file capture from forbidden animation snapshots",
    "PrtSc сохраняет" in read("README.md") and "bitmap/Base64 не пересылаются" in read("README.md")
    and "снимки для анимаций запрещены" in read("README.md")
    and "только в Snap Assist" in read("README.md")
    and "(R-03)" in read("DEVICE_CHECKLIST.md") and "RootServer writes a PNG" in read("TEST_PLAN.md")))

# 5. Six snap layouts and assist use app icons, not captured window content.
snap = java("SnapController")
checks.append(check("six Snap Layouts, Snap Assist icons and optional thumbnails stay in the assist component",
    all(x in snap for x in ["TWO_HALF", "LEFT_66", "RIGHT_66", "THREE_MAIN", "THREE_COLUMNS", "FOUR_GRID", "class Assist", "getApplicationIcon", "dismissAssist()", "KEYCODE_ESCAPE", "ACTION_OUTSIDE"])
    and "public void updatePreview(Win w,float x,float y) { }" in snap))

# 6–8. Window core, SnapGroup, fullscreen edge zones, and docked pair.
window = java("WindowController")
pair = java("DockedPairManager")
edge = java("FullscreenEdgeManager")
taskview = java("TaskViewOverlay")
checks.append(check("window core has persistent minimize/restore state and grouped operations",
    all(x in window for x in ["minimizeSingle(", "restorePersistedMinimizedState", "aeroShake(", "toggleShowDesktop()", "snapController.groupMembers(w)"])
    and "clearSavedSnapGroup" in java("LauncherPrefs")))
checks.append(check("SnapGroup membership, group operations, divider and occupied-cell swap are present",
    all(x in snap for x in ["Map<Integer, Integer> tasks", "groupMembers(", "minimizeGroup(", "detachFromGroup(", "finishDividerDrag()", "group.tasks.put(swap,other.taskId)"])))
checks.append(check("fullscreen edge zones support per-app exclusions, double swipe and all panels",
    all(x in edge for x in ["enum Side { TOP,BOTTOM,LEFT,RIGHT }", "areEdgesDisabled(", "doubleSwipeEdges()", "showMini(active)", "showTaskView()", "showSingleton(context)", "showTemporary()"])))
checks.append(check("docked pair uses stack 3, constrained divider ratio and exit routes",
    "DOCKED_STACK_ID=3" in window and "root.moveToStack(w.taskId,DOCKED_STACK_ID,true" in window
    and "ratio=.5f" in pair and "clampRatio(" in pair and "moveFromDockedStack" in pair
    and "controller.enterDockedPair" in taskview and "exitDockedPair" in taskview))

# 9. Virtual desktops persisted and wired to taskbar / Task View.
prefs = java("LauncherPrefs")
taskbar = java("TaskbarOverlay")
checks.append(check("1–4 virtual desktops persist task assignment and filter the taskbar",
    all(x in prefs for x in ["virtualDesktopCount()", "taskDesktop(", "setTaskDesktop(", "current_virtual_desktop"])
    and "switchVirtualDesktop(" in window and "moveWindowToDesktop(" in window
    and "w.desktopId==controller.currentDesktopId()" in taskbar
    and all(x in taskview for x in ["createVirtualDesktop()", "switchVirtualDesktop(", "chooseDesktop(w)"])))

# 10. Requested keyboard mappings and documented Android restrictions.
keys = java("KeyService")
checks.append(check("implemented global shortcuts are wired and Win+L/system-reserved limits are documented",
    all(x in keys for x in ["KEYCODE_F11", "KEYCODE_SPACE", "KEYCODE_F4", "KEYCODE_TAB", "KEYCODE_Z", "KEYCODE_T", "KEYCODE_MOVE_HOME", "KEYCODE_DPAD_LEFT", "KEYCODE_DPAD_RIGHT", "KEYCODE_DPAD_UP", "KEYCODE_DPAD_DOWN", "META_META_ON", "KEYCODE_SYSRQ", "captureScreenshot"])
    and all(x in read("README.md") for x in ["Win+L", "Ctrl+Shift+Esc", "USES_POLICY_FORCE_LOCK", "android.permission.DEVICE_POWER", "Alt+Tab"])
    and "TaskManagerActivity.class" in keys and "K-20" in read("TEST_PLAN.md")))

# 11. Action Center: Android notification listener and root-backed control operations.
action = java("ActionCenterOverlay")
notification = java("LauncherNotificationListener")
server = read("app/src/main/java/ru/winlauncher/root/RootServer.java")
checks.append(check("Action Center can display real notifications and query/request root radio actions",
    "BIND_NOTIFICATION_LISTENER_SERVICE" in manifest_text and "getActiveNotifications()" in notification
    and all(x in notification for x in ["onNotificationPosted", "contentIntent", "EXTRA_TITLE"])
    and all(x in action for x in ["LauncherNotificationListener.snapshot()", "deviceState(action", "deviceAction(action", "ACTION_NOTIFICATION_LISTENER_SETTINGS"])
    and all(x in server for x in ["device_state", "mobile_data", "bluetooth"])))

# 12. Edge Lite browser and download integration.
browser = java("EdgeLiteActivity")
checks.append(check("Edge Lite has WebView tabs, DDG omnibox/history, drag reorder and DownloadManager",
    all(x in browser for x in ["WebView", "duckduckgo.com", "browserHistory()", "addBrowserHistory", "startDragAndDrop", "DownloadManager", "DIRECTORY_DOWNLOADS"])
    and "ru.winlauncher.EdgeLiteActivity" in manifest_text and "android.permission.INTERNET" in manifest_text))

# 13–14. AppWidgetHost must be mounted in DesktopActivity, not a separate screen.
desktop = java("DesktopActivity")
checks.append(check("AppWidgetHost bind/configure/persistence is integrated into DesktopActivity",
    all(x in desktop for x in ["AppWidgetHost", "ACTION_APPWIDGET_BIND", "ACTION_APPWIDGET_CONFIGURE", "appWidgetIds()", "deleteAppWidgetId", "renderWidgets()", "showWidgetPicker()"])
    and "ru.winlauncher.DesktopActivity" in manifest_text and "WidgetHostActivity" not in manifest_text))
checks.append(check("Desktop Home, opaque Bloom-inspired drawing, context menu and slideshow are wired",
    "android.intent.category.HOME" in manifest_text and "drawPath" in desktop
    and all(x in desktop for x in ["wallpaperSlideshow()", "setWallpaperIndex", "showBackgroundMenu", "EdgeLiteActivity", "AppWidgetHostView"])))

# 15. Ctrl+Shift+Esc opens a close-capable task list, not Task View.
task_manager = java("TaskManagerActivity")
ctrl_escape_branch = java("KeyService").split("if(ctrlShiftEsc)", 1)[1].split("if(key==KeyEvent.KEYCODE_ESCAPE)", 1)[0]
checks.append(check("Ctrl+Shift+Esc launches simple Task Manager with task rows, close and restore",
    "TaskManagerActivity.class" in ctrl_escape_branch and "taskView" not in ctrl_escape_branch
    and "ru.winlauncher.TaskManagerActivity" in manifest_text
    and all(x in task_manager for x in ["onWindowsChanged", "controller.close(win)", "controller.activateWindow(win)", "Диспетчер задач"])))

# 15. Documentation coverage and build/runtime honesty.
readme = read("README.md")
testplan = read("TEST_PLAN.md")
device = read("DEVICE_CHECKLIST.md")
checks.append(check("README links a comprehensive test plan and does not claim build/device success",
    "PLAN.md" in readme and "TEST_PLAN.md" in readme and "APK не собран" in readme and "не проверены" in readme
    and "not prove" not in readme.lower()))
checks.append(check("test plan covers build, Stage 0/E-1…E-10 and requested subsystems with Pass/Fail fields",
    all(x in testplan for x in ["B-01", "T0", "E-1", "E-10", "getTaskThumbnail", "injectInputEvent", "resizeDockedStack", "K-01", "K-20", "☐ Pass ☐ Fail", "Samsung", "P-05"])
    and all(x in testplan for x in ["Virtual desktops", "Action Center", "Edge Lite", "AppWidgetHostView", "TaskManagerActivity", "rotation", "gestures"])))
checks.append(check("PLAN.md preserves exact E-1…E-10 hypotheses and Stage 1–4/Stage 5 decision",
    all(x in read("PLAN.md") for x in ["E‑1…E‑10", "am stack movetask taskId 2 true", "3 px", "resizeMode 0/1", "StackInfo", "SurfaceControl.screenshot", "/dev/input", "IStatusBarService.disable", "resizeDockedStack", "Этап 1", "Этап 2", "Этап 3", "Этап 4", "Этап 5"])))
checks.append(check("device checklist covers the ten hypotheses without claiming snapshot visuals",
    "fade" not in device.lower()
    and all(x in device for x in ["TEST_PLAN.md", "E-1…E-10", "getTaskThumbnail", "injectInputEvent", "resizeDockedStack", "stack IDs", "Wi‑Fi", "USES_POLICY_FORCE_LOCK", "AppWidgetHostView", "Ctrl+Shift+Esc"])))

# 16. GitHub hygiene.
gitignore = read(".gitignore")
checks.append(check("GitHub hygiene ignores local SDK/Gradle/build/signing artifacts",
    all(x in gitignore for x in ["local.properties", ".gradle/", "build/", "*.apk", "*.aab", "*.keystore", "*.jks"])))

# 17. Cheap lexical balance check for accidental editing damage (not a Java parser/compiler).
def balanced(text: str) -> bool:
    stack=[]; i=0; state="code"; pairs={')':'(',']':'[','}':'{'}
    while i<len(text):
        c=text[i]; n=text[i+1] if i+1<len(text) else ""
        if state=="line":
            if c=='\n': state="code"
        elif state=="block":
            if c=='*' and n=='/': state="code"; i+=1
        elif state in ("string", "char"):
            if c=='\\': i+=1
            elif (state=="string" and c=='"') or (state=="char" and c=="'"): state="code"
        elif c=='/' and n=='/': state="line"; i+=1
        elif c=='/' and n=='*': state="block"; i+=1
        elif c=='"': state="string"
        elif c=="'": state="char"
        elif c in "([{": stack.append(c)
        elif c in ")]}":
            if not stack or stack.pop()!=pairs[c]: return False
        i+=1
    return not stack and state not in ("block", "string", "char")
xml_ok=True
try:
    for p in (ROOT / "app/src/main").rglob("*.xml"): ET.parse(p)
except ET.ParseError:
    xml_ok=False
checks.append(check("Java delimiter balance and Android XML well-formedness (not compilation)",
    all(balanced(s) for s in sources.values()) and xml_ok))

# 18. Field-initializer hygiene. Three of the delivery bugs were exactly this class:
#     (b) a duplicated `lastWindows` declaration, (c)/(d) a lambda-field initializer reading a final
#     field the constructor assigns later. A duplicate or a direct forward read is a Java compile
#     error; a lambda read of a constructor-assigned final is a definite-assignment error.
def brace_depths(text: str):
    """Brace depth per character, ignoring comments, strings and chars."""
    depth, out, i, state = 0, [], 0, "code"
    while i < len(text):
        c, n = text[i], text[i + 1] if i + 1 < len(text) else ""
        if state == "code":
            if c == "/" and n == "/":
                state = "line"
            elif c == "/" and n == "*":
                state = "block"
            elif c == '"':
                state = "string"
            elif c == "'":
                state = "char"
            elif c == "{":
                depth += 1
            elif c == "}":
                depth = max(0, depth - 1)
        elif state == "line" and c == "\n":
            state = "code"
        elif state == "block" and c == "*" and n == "/":
            state, i = "code", i + 1
        elif state in ("string", "char"):
            if c == "\\":
                i += 1
            elif (state == "string" and c == '"') or (state == "char" and c == "'"):
                state = "code"
        out.append(depth)
        i += 1
    return out

field_forward_hits, duplicate_field_hits = [], []
for path, text in sources.items():
    body = re.sub(r'//[^\n]*|/\*.*?\*/', '', text, flags=re.S)
    depths = brace_depths(body)
    final_fields, declared_order = set(), {}
    for match in re.finditer(r'(?:^|\n)[ \t]*((?:public|private|protected|static|final|volatile|transient)[^\n;={}()]*?)[ \t](\w+)[ \t]*(?:=|;)', body):
        if depths[match.start()] != 1:
            continue
        modifiers, name = match.group(1), match.group(2)
        if 'final' in modifiers.split():
            final_fields.add(name)
        if name in declared_order:
            duplicate_field_hits.append((path.name, name))
        declared_order.setdefault(name, len(declared_order) + 1)
    assigned_in_constructor = set()
    for ctor in re.finditer(r'\b(?:public|private|protected)\s+' + re.escape(path.stem) + r'\s*\([^)]*\)\s*\{', body):
        depth, i = 0, ctor.end() - 1
        while i < len(body):
            if body[i] == '{':
                depth += 1
            elif body[i] == '}':
                depth -= 1
                if depth == 0:
                    assigned_in_constructor |= set(re.findall(r'this\.(\w+)\s*=', body[ctor.end():i]))
                    break
            i += 1
    for match in re.finditer(r'(?:^|\n)[ \t]*(?:public|private|protected|static|final|volatile|transient)[^\n;={}()]*?[ \t](\w+)[ \t]*=([^;\n]*);', body):
        if depths[match.start()] != 1:
            continue
        name, init, position = match.group(1), match.group(2), len(declared_order)
        # Anonymous-class bodies are methods, where reading a constructor final is legal, so the
        # initializer is cut at the first `){`. Lambda initializers (the (c)/(d) bug class) keep
        # their body because `()->{...}` does not contain `){`.
        direct = init.split('){')[0]
        for ref in re.findall(r'(?<![.\w])(\w+)\b(?!\s*=(?!=))', direct):
            if ref == name or ref in {'this', 'true', 'false', 'null'}:
                continue
            if ref in declared_order and declared_order[ref] > position:
                field_forward_hits.append((path.name, name, ref, 'forward'))
            elif ref in final_fields and ref in assigned_in_constructor:
                field_forward_hits.append((path.name, name, ref, 'constructor-final'))
checks.append(check("no duplicate fields and no field initializer reads a later or constructor-assigned final field",
    not field_forward_hits and not duplicate_field_hits))

# 19. Signature coherence. This is the class of the four delivery bugs that were already fixed in
#     the bundle before upload: a call with the wrong arity (a), and a field whose declaration order
#     or duplication broke the initializer (b/c/d). Both bare same-file calls and calls through a
#     known first-party receiver are checked against declared arities (varargs excluded).
def blank_comments(text: str) -> str:
    """Replace comments with spaces so string literals keep their original offsets."""
    out, i, state = [], 0, "code"
    while i < len(text):
        c, n = text[i], text[i + 1] if i + 1 < len(text) else ""
        if state == "code":
            if c == "/" and n == "/":
                state, out = "line", out + ["  "]
                i += 2
                continue
            if c == "/" and n == "*":
                state, out = "block", out + ["  "]
                i += 2
                continue
            out.append(c)
        elif state == "line":
            if c == "\n":
                state = "code"
                out.append(c)
            else:
                out.append(" ")
        else:
            if c == "*" and n == "/":
                state, out = "code", out + ["  "]
                i += 2
                continue
            out.append("\n" if c == "\n" else " ")
        i += 1
    return "".join(out)

def count_args(text: str, open_paren: int) -> int:
    """Arity of the call whose '(' is at open_paren, ignoring strings, chars and nested groups."""
    depth, commas, i, state = 0, 0, open_paren, "code"
    while i < len(text):
        c, n = text[i], text[i + 1] if i + 1 < len(text) else ""
        if state == "code":
            if c == '"' or c == "'":
                state = "string" if c == '"' else "char"
            elif c == "/" and n == "/":
                state = "line"
            elif c in "([{":
                depth += 1
            elif c in ")]}":
                depth -= 1
                if depth == 0:
                    return 0 if text[open_paren + 1:i].strip() == "" else commas + 1
            elif c == "," and depth == 1:
                commas += 1
        elif state in ("string", "char"):
            if c == "\\":
                i += 1
            elif (state == "string" and c == '"') or (state == "char" and c == "'"):
                state = "code"
        elif state == "line" and c == "\n":
            state = "code"
        i += 1
    return -1

declared_methods, varargs_methods = {}, set()
for path, text in sources.items():
    cleaned = blank_comments(text)
    for match in re.finditer(r'(?:public|private|protected)[\w\s<>\[\],.?@]*?\b(\w+)\s*\(([^)]*)\)\s*(?:throws[^{;]*)?[{;]', cleaned):
        name, params = match.group(1), match.group(2)
        declared_methods.setdefault(name, set()).add(0 if not params.strip() else len([p for p in params.split(',') if p.strip()]))
        if '...' in params:
            varargs_methods.add(name)

receiver_types = {'WindowController', 'SnapController', 'LauncherPrefs', 'RootBridge', 'DockedPairManager',
                  'ChromeManager', 'TaskbarOverlay', 'TaskViewOverlay', 'ActionCenterOverlay', 'ResizeGripManager'}
receiver_names = {name[0].lower() + name[1:]: name for name in receiver_types} | {'bridge': 'RootBridge', 'root': 'RootBridge'}
call_arity_hits = []
for path, text in sources.items():
    cleaned = blank_comments(text)
    own = {}
    for match in re.finditer(r'(?:public|private|protected)[\w\s<>\[\],.?@]*?\b(\w+)\s*\(([^)]*)\)\s*(?:throws[^{;]*)?[{;]', cleaned):
        params = match.group(2)
        own.setdefault(match.group(1), set()).add(0 if not params.strip() else len([p for p in params.split(',') if p.strip()]))
    for match in re.finditer(r'(?<![.\w])([a-zA-Z_]\w*)\s*\(', cleaned):
        name = match.group(1)
        if name in ('if', 'for', 'while', 'switch', 'catch', 'return', 'new', 'super', 'this', 'synchronized'):
            continue
        if name in own and name not in varargs_methods:
            arity = count_args(cleaned, match.end() - 1)
            if arity not in own[name]:
                call_arity_hits.append((path.name, name, arity, sorted(own[name]), 'same-file'))
    for match in re.finditer(r'\b([a-z]\w*)\.(\w+)\s*\(', cleaned):
        receiver, name = match.group(1), match.group(2)
        if receiver not in receiver_names or name not in declared_methods or name in varargs_methods:
            continue
        arity = count_args(cleaned, match.end() - 1)
        if arity not in declared_methods[name]:
            call_arity_hits.append((path.name, f'{receiver}.{name}', arity, sorted(declared_methods[name]), receiver_names[receiver]))
# Constructors of first-party classes follow the same rule (a stale `new X(a, b)` after a signature
# change is exactly how the delivery bugs started).
ctor_arities = {}
for path, text in sources.items():
    cleaned = blank_comments(text)
    for match in re.finditer(r'\b(?:public|private|protected)\s+(\w+)\s*\(([^)]*)\)\s*(?:throws[^{;]*)?\{', cleaned):
        if match.group(1) != path.stem:
            continue
        params = match.group(2)
        ctor_arities.setdefault(match.group(1), set()).add(0 if not params.strip() else len([p for p in params.split(',') if p.strip()]))
ctor_hits = []
for path, text in sources.items():
    cleaned = blank_comments(text)
    for match in re.finditer(r'\bnew\s+([A-Z]\w*)\s*\(', cleaned):
        name = match.group(1)
        if name not in ctor_arities or name in varargs_methods:
            continue
        arity = count_args(cleaned, match.end() - 1)
        if arity not in ctor_arities[name]:
            ctor_hits.append((path.name, name, arity, sorted(ctor_arities[name])))
checks.append(check("constructor invocations match declared constructor arity for first-party classes",
    not ctor_hits))
checks.append(check("call arity matches declared method arity for same-file calls and first-party receivers",
    not call_arity_hits))

# 20. The root bridge must authenticate in both directions with a token-bound, nonce-fresh proof.
root_bridge = read("app/src/main/java/ru/winlauncher/bridge/RootBridge.java")
root_server = read("app/src/main/java/ru/winlauncher/root/RootServer.java")
root_launcher = read("app/src/main/java/ru/winlauncher/bridge/RootServerLauncher.java")
checks.append(check("root bridge performs mutual token authentication with a fresh nonce",
    all(x in root_bridge for x in ['newNonce()', 'clientProof(token, nonce)', 'serverProof(token, nonce)', 'optString("proof"'])
    and re.search(r'clientProof\(authToken,\s*nonce\)', root_server) is not None
    and re.search(r'serverProof\(authToken,\s*nonce\)', root_server) is not None
    and 'put("proof"' in root_server and 'nonce.length() < 16' in root_server
    and re.search(r'String nonce\s*=\s*newNonce\(\)', root_bridge) is not None
    and 'setSoTimeout(IO_TIMEOUT_MS)' in root_bridge))

# 21. Regression checks for the D-01…D-14 fixes from the external defect report (2026-11-19).
edge_lite = java("EdgeLiteActivity")
keys_text = java("KeyService")
checks.append(check("D-01: Edge Lite tab label selects the tab and a separate button closes it",
    "selectTab(tab)" in edge_lite and "closeTab(tab)" in edge_lite and "closeTab(current)" in edge_lite
    and "closeOrSelect" not in edge_lite))
checks.append(check("D-02: docked pair commits bounds without the freeform clamp",
    "commitDockedBounds" in window and "controller.commitDockedBounds(first)" in pair
    and "controller.commitMove(first)" not in pair))
checks.append(check("D-03: windows-button behavior is read from prefs, not a cached field",
    "public boolean windowsButtonBehavior() { return prefs.windowsButtonBehavior(); }" in window
    and "private boolean windowsButtonBehavior;" not in window))
checks.append(check("D-04: Bluetooth state reads Settings.Global with a secure fallback",
    '"bluetooth".equals(action)){namespace="global"' in server
    and 'readSetting("secure","bluetooth_on")' in server))
checks.append(check("D-05: RootBridge matches response ids, drops broken sockets, retries idempotent reads once; server errors carry the id",
    'parsed.optLong("id", -1) == r.id' in bridge and '"response id mismatch"' in bridge
    and "idempotent" in bridge and 'put("id", id)' in server))
checks.append(check("D-06: work area uses the real status-bar height instead of a hardcoded 24 px",
    "statusInsetPx = statusBarHeight()" in window and "int top = hideStatusBar ? 0 : statusInsetPx;" in window))
checks.append(check("D-07: controller-dependent shortcuts pass through when the controller is stopped",
    "needsController" in keys_text and "if (controller == null && needsController) return false;" in keys_text))
checks.append(check("D-08: widget configure goes through AppWidgetHost with failure cleanup",
    "startAppWidgetConfigureActivityForResult" in desktop and "Не удалось настроить виджет" in desktop))
checks.append(check("D-09: Snap Assist heading is readable in the light theme",
    "heading.setTextColor(dark?0xfff5f5f5:0xff1a1a1a)" in snap))
checks.append(check("D-10: taskbar order is stable by taskId and the clock has a minute tick",
    "taskbarOrder()" in window and "Comparator.comparingInt(w -> w.taskId)" in window
    and "clockTick" in taskbar and "controller.taskbarOrder()" in taskbar))
checks.append(check("D-11: airplane mode chains with && and reports the read-back state",
    'airplane_mode_on "+(enabled?"1":"0")+" &&' in server and 'readDeviceState("airplane")' in server))
close_body = window.split("public void close(Win w)", 1)[1].split("moveToStackThenResize", 1)[0]
checks.append(check("D-12: close() defers model cleanup to adoptTasks, which clears all restore state",
    "refreshTasks(null);" in close_body and "windows.remove" not in close_body
    and "pendingDesktopRestoreIds.remove(id)" in window and "virtualDesktopHiddenTasks()" in window))
checks.append(check("D-13: OPAQUE overlay outer containers are square (no black rounded corners)",
    "round(dark?0xff292929:0xfff5f5f5,0)" in action and "round(dark?0xff292929:0xfff3f3f3,0)" in edge))
checks.append(check("D-14: caption calibration is throttled, state-change only, exact Samsung id",
    "lastCaptionScan" in keys_text and "TYPE_WINDOW_STATE_CHANGED) return;" in keys_text
    and 'id.equals("com.samsung.android.framework.res:id/caption")' in keys_text
    and 'endsWith(":id/caption")' not in keys_text))
checks.append(check("PrtSc fallback to /data/local/tmp is flagged in the JSON response and surfaced in the toast",
    'response.put("fallback",true)' in server and 'optBoolean("fallback",false)' in keys_text))

# 22. Regression checks for the BUG-01…BUG-25 debug round (2026-11-19).
# 22a. The BUG-01/BUG-02 class: a // comment inserted mid-line in compressed one-line code
# silently comments out every statement after it on that line, and neither javac nor the
# older checks notice. A line comment whose text contains at least two pure-code segments
# (statements separated by ';') is treated as live code hiding inside a comment.
def _is_code_segment(seg: str) -> bool:
    seg = seg.strip()
    if not seg:
        return False
    out, depth = "", 0
    for ch in seg:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif depth == 0:
            out += ch
    out = out.strip()
    return re.fullmatch(r"[A-Za-z_][\w.]*(\s*=[^=].*)?", out) is not None and ("(" in seg or "=" in seg)

def _comment_code_hits(text: str):
    hits, i, state, line = [], 0, "code", 1
    while i < len(text):
        c, n = text[i], text[i + 1] if i + 1 < len(text) else ""
        if c == "\n":
            line += 1
        if state == "code":
            if c == "/" and n == "/":
                j = text.find("\n", i)
                comment = text[i + 2:(j if j != -1 else len(text))]
                if sum(1 for s in comment.split(";") if _is_code_segment(s)) >= 2:
                    hits.append(line)
                i = (j if j != -1 else len(text))
                continue
            elif c == "/" and n == "*":
                state = "block"; i += 1
            elif c == '"':
                state = "string"
            elif c == "'":
                state = "char"
        elif state == "block":
            if c == "*" and n == "/":
                state = "code"; i += 1
        elif state in ("string", "char"):
            if c == "\\":
                i += 1
            elif (state == "string" and c == '"') or (state == "char" and c == "'"):
                state = "code"
        i += 1
    return hits

comment_code_hits = [(p.name, ln) for p, text in sources.items() for ln in _comment_code_hits(text)]
checks.append(check("no live code hides after a // comment on the same line (BUG-01/BUG-02 class)",
    not comment_code_hits))

settings_activity = java("LauncherSettingsActivity")
main_activity = java("MainActivity")
dock_service = java("DockService")
styles_text = read("app/src/main/res/values/styles.xml")
checks.append(check("BUG-01: fullscreen mini header builds title, buttons and listeners (not commented out)",
    "titleLp.gravity=Gravity.CENTER_VERTICAL;row.addView(title,titleLp)" in edge
    and "min.setOnClickListener(v->{" in edge
    and "controller.minimize(w);" in edge
    and "close.setOnClickListener(v->{" in edge))
checks.append(check("BUG-02: Snap Assist heading is sized, padded and added to the root",
    "root.addView(heading)" in snap and "heading.setTextSize(18)" in snap))
checks.append(check("BUG-03: tab drag starts from the label; closeTab guards a stale index",
    "label.setOnLongClickListener" in edge_lite and "cell.startDragAndDrop" in edge_lite
    and "int index=tabs.indexOf(tab);if(index<0)return;" in edge_lite))
checks.append(check("BUG-04: handshake proofs are domain-separated and the token travels via stdin, not argv",
    '"client:" + token + ":" + nonce' in root_bridge and '"server:" + token + ":" + nonce' in root_bridge
    and '"client:" + token + ":" + nonce' in root_server and '"server:" + token + ":" + nonce' in root_server
    and "handshakeProof" not in root_bridge and "handshakeProof" not in root_server
    and "readTokenFromStdin" in root_server and "getOutputStream()" in root_launcher
    and 'RootServer " + token' not in root_launcher))
checks.append(check("BUG-05: docked pair clears busy and runs pendingEnter after begin, release and task removal",
    "busy=false;Runnable pending=pendingEnter" in pair
    and "public void release(){first=null;second=null;removeDivider();busy=false;pendingEnter=null;}" in pair
    and "removeDivider();busy=false;pendingEnter=null;" in pair))
checks.append(check("BUG-06: dragging the divider to the near edge keeps the second window; divider colors differ by state",
    "exit(next<.04f?second:first)" in pair and "active?(dark?0xff0097ff:0xff0067c0):(dark?0xff888888:0xff666666)" in pair))
checks.append(check("BUG-07: desktop background uses generic-motion/context-click handlers on wallpaper, widget container and screen",
    "setOnGenericMotionListener" in desktop and "setOnContextClickListener" in desktop
    and "attachBackgroundHandlers(widgetContainer)" in desktop and "attachBackgroundHandlers(screen)" in desktop))
checks.append(check("BUG-08: Edge Lite survives rotation via configChanges and saves/restores its tabs",
    "configChanges" in manifest_text and '"tab_urls"' in edge_lite and "onSaveInstanceState" in edge_lite))
checks.append(check("BUG-09: fullscreen edges rebuild when the display size changes",
    "lastScreenW" in edge and "dm.widthPixels==lastScreenW&&dm.heightPixels==lastScreenH" in edge))
checks.append(check("BUG-10: snap groups and Assist candidates stay on the current desktop and freeform stack",
    "sameDesktop(candidate,w.desktopId)" in snap and "sameDesktop(Group g, int desktopId)" in snap
    and "w.desktopId==controller.currentDesktopId()&&w.stackId==2" in snap))
checks.append(check("BUG-11: Action Center shows the state read back by the server, not the requested one",
    'boolean actual=done.optBoolean("enabled",requested);' in action and 'done.optBoolean("known",true)' in action))
checks.append(check("BUG-12: shortcuts execute on ACTION_DOWN, swallow ACTION_UP via swallowedKeys, Ctrl+D is exact",
    "swallowedKeys" in keys_text and "boolean ctrlD=ctrl&&!alt&&!shift&&!win" in keys_text
    and "needsTaskView" in keys_text and "KEYCODE_SYSRQ" in keys_text.split("needsController")[1].split(";")[0]))
checks.append(check("BUG-13: policy_control backup survives a failed restore, missing backup self-heals, subprocesses are drained and destroyed",
    "if (!hidden && settingsApplied && backup.exists()) backup.delete();" in root_server
    and "execAndWait" in root_server and "redirectErrorStream(true)" in root_server))
checks.append(check("BUG-14: protocol error frames return immediately and handshake failures close the socket",
    'parsed.optLong("id", -1) == -1 && !parsed.optBoolean("ok", true)' in root_bridge
    and "catch (Throwable t)" in root_bridge and "s.close()" in root_bridge))
checks.append(check("BUG-15: task z ranks stacks in reverse (top stack wins) instead of a flat counter",
    "stackRank" in root_server and '"z", stackRank * 1000 + i' in root_server))
checks.append(check("BUG-16: chrome defers z-order rebuild during drags, themes its buttons, cancels flyout on removal",
    "draggingTaskId" in chrome and "removeCallbacks(c.showLayoutFlyout)" in chrome
    and "minimize.setBackgroundColor(buttonBg)" in chrome))
checks.append(check("BUG-17: taskbar buttons use one active-aware behavior and the jump menu offset fits its rows",
    "attachTaskButtonBehavior" in taskbar and "labels.size()*dp(32)+dp(20)" in taskbar
    and "clockDark" in taskbar))
checks.append(check("BUG-18: Task View fullscreen rows separate focus from pair selection and refresh after a desktop move",
    "focusButton" in taskview and "ensureDesktop(w);" in taskview
    and "show();" in taskview.split("private void chooseDesktop")[1]))
checks.append(check("BUG-19: settings app list is a recycling ListView fed from a background thread",
    "ListView" in settings_activity and "BaseAdapter" in settings_activity
    and "runOnUiThread" in settings_activity and "ViewHolder" in settings_activity))
checks.append(check("BUG-20: layouts popup closes on Escape, outer-edge drag is a no-op, Assist cards scroll",
    "hasLayoutsPopup()" in snap and "dismissLayoutsForEscape()" in snap
    and "if(old<=0.01f||old>=0.99f)return old;" in snap and "cardScroll" in snap
    and "dismissLayoutsForEscape" in keys_text))
checks.append(check("BUG-21: remaining OPAQUE overlay roots are square (fullscreen menu, shake preview, group hint)",
    "bg.setCornerRadius(0);panel.setBackground(bg)" in chrome
    and "bg.setCornerRadius(0);bg.setStroke(dp(1),0xff888888)" in chrome
    and "bg.setCornerRadius(0);bg.setStroke(dp(1),dark?0xff777777:0xff888888)" in snap))
checks.append(check("BUG-22: widget height is not dp-scaled twice, slideshow/clock run only while started, stale widget ids are pruned",
    "Math.max(dp(120),info.minHeight)" in desktop and "lastWidgetIdSignature" in desktop
    and "onStop(){handler.removeCallbacks(slideshow);handler.removeCallbacks(desktopClockTick);" in desktop))
checks.append(check("BUG-23: MainActivity syncs checkboxes on resume and overlays check the draw permission first",
    "syncingChecks" in main_activity and "windowsBehaviorBox" in main_activity
    and "Settings.canDrawOverlays(this)" in main_activity and "hasOverlayPermission()" in dock_service))
checks.append(check("BUG-24: notification listener clears its cache on disconnect and guards getActiveNotifications",
    "synchronized(LOCK){entries.clear();}super.onListenerDisconnected();" in notification
    and "try{active=getActiveNotifications();}catch(RuntimeException ignored){}" in notification))
checks.append(check("BUG-25: proguard file exists, nav bar color is dark, Home activity is singleTask/stateNotNeeded",
    (ROOT / "app/proguard-rules.pro").is_file()
    and 'android:navigationBarColor">#202020' in styles_text
    and 'android:launchMode="singleTask"' in manifest_text and 'android:stateNotNeeded="true"' in manifest_text))

win_model = java("Win")
checks.append(check("WC2-01: a recycled taskId with a new package/component retires the old window state",
    "boolean identityChanged = !pkg.equals(w.packageName) || !component.equals(w.component);" in window
    and "w.minimizeRestoreBounds.setEmpty();" in window
    and "w.desktopId = prefs.taskDesktop(id, prefs.currentVirtualDesktop());" in window))
checks.append(check("WC2-02: the supported-stack filter also applies to already-tracked tasks",
    "if (stack != freeformStackId && stack != fullscreenStackId && stack != DOCKED_STACK_ID) continue;" in window))
checks.append(check("WC2-03: an externally resized NORMAL window refreshes normalBounds",
    "stateBeforePoll == Win.State.NORMAL" in window and "w.normalBounds.set(w.bounds);" in window))
checks.append(check("WC2-04: stateSignature covers package, component, title and snap group",
    ".append(':').append(w.packageName).append(':').append(w.component)" in window
    and ".append(':').append(w.title).append(':').append(w.snapGroupId)" in window))
checks.append(check("WC2-05: task removal clears the keyboard move/resize target",
    "keyboardModeTarget==removedWindow" in window and "keyboardModeTarget=null;" in window))
checks.append(check("WC2-06: async continuations are guarded by a per-window operation sequence",
    "public long operationSequence;" in win_model
    and "operationCurrent(w,operation)" in window and "operationCurrent(w, operation)" in window
    and "private boolean operationCurrent(Win w, long operation)" in window))
checks.append(check("WC2-07: RootBridge already delivers callbacks on the main thread (verified, no change)",
    "main.post(() -> callback.onResult(value))" in root_bridge))
checks.append(check("WC2-08: focus() routes a minimized window through restore() instead of fronting it",
    "if (w.state == Win.State.MINIMIZED) { restore(w); return; }" in window))
checks.append(check("WC2-09: a Win+D during a Show Desktop restore is queued and replayed",
    "showDesktopRestoringIds" in window and "showDesktopToggleQueued=true" in window
    and "checkShowDesktopRestoreComplete" in window))
checks.append(check("WC2-10: fullscreen on a docked member exits the pair with a continuation",
    "dockedPair.exit(w,()->fullscreen(w))" in window))
checks.append(check("WC2-11: snap detach is deferred until maximize/fullscreen/restore is confirmed",
    "final boolean wasSnapped = w.state == Win.State.SNAPPED;" in window
    and "if (wasSnapped) snapController.detach(w)" in window
    and "if(wasSnapped)snapController.detach(w);" in window))
checks.append(check("WC2-12: fullscreen commits model state only after a successful resize, with rollback",
    "final int originalStack = w.stackId;" in window
    and "root.moveToStack(w.taskId,originalStack,true,rolledBack->refreshTasks(null))" in window))
checks.append(check("WC2-13: moveToStackThenResize rolls the native stack back when the resize fails",
    "final int oldStack = w.stackId;" in window
    and "root.moveToStack(w.taskId, oldStack, rollbackToTop, rolledBack -> refreshTasks(null))" in window))
checks.append(check("WC2-14: restore() returns after the snap-group branch instead of restoring twice",
    "second restore for w itself" in window
    and "if(w.state==Win.State.MINIMIZED)return;" not in window))
checks.append(check("WC2-15: a rejected requestBounds target reverts requestedBounds to confirmed bounds",
    "w.requestedBounds.set(w.bounds);\n\n                if (!result.optString(\"error\", \"\").contains(\"superseded\")) report(result, null);" in window))
checks.append(check("WC2-16/17: fallback bounds use dp limits and centre inside the work-area origin",
    "Math.min(dp(640), Math.max(dp(240)" in window and "Math.min(640, Math.max(240" not in window
    and "int left = work.left + Math.max(0, (work.width() - width) / 2);" in window
    and "int top = work.top + Math.max(0, (work.height() - height) / 3);" in window))
checks.append(check("WC2-18: root bounds are parsed strictly and must have positive size",
    "if (right > left && bottom > top) out.set(left, top, right, bottom);" in window
    and "if (right <= left || bottom <= top) return null;" in window))
checks.append(check("WC2-19: task titles are cached per package and re-resolved only on identity change",
    "titleCache.get(pkg)" in window
    and "if (identityChanged) w.title = resolveTitle(pkg, component);" in window))

# R-01…R-05: fixes for regressions found in the wc2 delivery review.
launcher = read("app/src/main/java/ru/winlauncher/bridge/RootServerLauncher.java")
checks.append(check("R-01: the root token reaches the backgrounded server via a private temp file bound to fd 0",
    "IFS= read -r T" in launcher
    and "umask 077" in launcher
    and "wl_tok_" in launcher
    and 'exec < \\"$1\\"' in launcher
    and 'rm -f \\"$1\\"' in launcher
    and "String shell" not in launcher))
checks.append(check("R-02: an interrupted minimize clears pendingMinimizeIds in both stale-operation callbacks",
    window.count("if (!operationCurrent(w, operation)) { clearStaleMinimizeState(w); pendingMinimizeIds.remove(w.taskId); restoreShowDesktopWhenReady(); return; }") == 2))
checks.append(check("R-03: identity change invalidates in-flight callbacks and requestBounds checks window liveness",
    "nextOperation(w);\n                    ++w.resizeSequence;" in window
    and "windows.get(w.taskId) != w || seq != w.resizeSequence" in window))
checks.append(check("R-04: the WC2-13 rollback does not raise a minimized (parked) window to the front",
    "final boolean rollbackToTop = w.state != Win.State.MINIMIZED;" in window
    and "root.moveToStack(w.taskId, oldStack, rollbackToTop, rolledBack -> refreshTasks(null))" in window))
checks.append(check("R-05: moveToStackThenResize runs the continuation before notifying listeners",
    "if (after != null) after.run();\n\n                notifyListeners();" in window))

# F-01…F-15: fixes from the sequential review of the rfix code base.
f_server = read("app/src/main/java/ru/winlauncher/root/RootServer.java")
f_launcher = read("app/src/main/java/ru/winlauncher/bridge/RootServerLauncher.java")
f_taskview = read("app/src/main/java/ru/winlauncher/TaskViewOverlay.java")
f_actioncenter = read("app/src/main/java/ru/winlauncher/ActionCenterOverlay.java")
f_edges = read("app/src/main/java/ru/winlauncher/FullscreenEdgeManager.java")
f_dock = read("app/src/main/java/ru/winlauncher/DockService.java")
f_main = read("app/src/main/java/ru/winlauncher/MainActivity.java")
f_chrome = read("app/src/main/java/ru/winlauncher/ChromeManager.java")
f_taskbar = read("app/src/main/java/ru/winlauncher/TaskbarOverlay.java")
f_key = read("app/src/main/java/ru/winlauncher/KeyService.java")
f_desktop = read("app/src/main/java/ru/winlauncher/DesktopActivity.java")
f_edge = read("app/src/main/java/ru/winlauncher/EdgeLiteActivity.java")
f_settings = read("app/src/main/java/ru/winlauncher/LauncherSettingsActivity.java")
f_taskmgr = read("app/src/main/java/ru/winlauncher/TaskManagerActivity.java")
checks.append(check("F-01: RootServer drains child output to EOF before waitFor (no SIGPIPE) and closeStreams is gone",
    "runProcess" in f_server and "redirectErrorStream(true)" in f_server and "closeStreams" not in f_server))
checks.append(check("F-05: the launcher printf format carries a double backslash in the Java source",
    "printf '%s\\\\n'" in f_launcher))
checks.append(check("F-06: readSetting trims its value and a settings failure degrades inside setStatusBarHidden",
    "value=value.trim();" in f_server and "settingsError" in f_server))
checks.append(check("F-03: moveWindowToDesktop moves the whole SnapGroup and records hidden ids for the target desktop",
    "// F-03: a SnapGroup travels as one unit" in window
    and "hidden.add(String.valueOf(member.taskId))" in window))
checks.append(check("F-14: both stale minimize branches clear the saved window state",
    window.count("clearStaleMinimizeState(w);") == 2))
checks.append(check("F-02: overlays dismiss through the static owner and the edge manager uses the shared instances",
    "dismissActive();\n        active=this;" in f_taskview
    and "dismissActive();\n        active=this;" in f_actioncenter
    and "fallbackTaskView" in f_edges and "showSingleton(context)" in f_edges
    and "=new TaskViewOverlay(context,ctl)" not in f_edges
    and "=new ActionCenterOverlay(context)" not in f_edges))
checks.append(check("F-04/M-03: Task View and Task Manager activate on the window's own desktop via activateWindow",
    "controller.activateWindow(w)" in f_taskview
    and "controller.activateWindow(win)" in f_taskmgr
    and "if (w.desktopId != currentDesktopId()) switchVirtualDesktop(w.desktopId);" in window))
checks.append(check("F-15: the Task View card uses LinearLayout params and the empty Task Manager text follows the theme",
    "new LinearLayout.LayoutParams(cardWidth,-2)" in f_taskview
    and "empty.setTextColor" in f_taskmgr))
checks.append(check("F-10: chrome autostart is gated by an explicit request flag and MainActivity tracks its applied theme",
    "chromeRequested" in f_dock and "setChromeRequested(true)" in f_main and "appliedDark" in f_main))
checks.append(check("F-11: ChromeManager tracks the dragged task by id instead of a boolean flag",
    "draggingTaskId" in f_chrome and "dragInProgress" not in f_chrome))
checks.append(check("F-12: the fullscreen mini title watches outside touches and auto-hides on a timer",
    "FLAG_WATCH_OUTSIDE_TOUCH" in f_edges and "MINI_AUTO_HIDE_MS" in f_edges))
checks.append(check("F-13: the taskbar keeps its jump menu in a field and paints the active label white",
    "dismissJumpMenuForEscape" in f_taskbar
    and "if(isActive)item.setTextColor(Color.WHITE);" in f_taskbar))
checks.append(check("F-07: Ctrl+Shift+Esc fires on ACTION_DOWN and swallowed keys are released before any early return",
    "F-07(a)" in f_key and "F-07(b)" in f_key
    and "TaskbarOverlay.dismissJumpMenuForEscape()" in f_key))
checks.append(check("F-08: the desktop widget area fills the viewport, the background menu is single, dead widget ids are released",
    "setFillViewport(true)" in f_desktop and "backgroundMenu" in f_desktop
    and "widgetHost.deleteAppWidgetId(id);Set<String>" in f_desktop))
checks.append(check("F-09: the omnibox paints for the theme, Settings recreates on theme change and guards its app loader",
    "setHintTextColor" in f_edge and "initialDark" in f_settings
    and "isDestroyed()) return;" in f_settings))

# G-01…G-04: nuances from the line-by-line review of the ffix diff.
checks.append(check("G-01: a docked pair is assembled on a single desktop (second window is aligned first)",
    "moveWindowToDesktop(selected.get(1),selected.get(0).desktopId)" in f_taskview))
checks.append(check("G-02: the active taskbar button keeps blue hover/pressed states under its white label",
    "isActive?0xff005a9e" in f_taskbar and "isActive?0xff004b85" in f_taskbar))
checks.append(check("G-03: moving a window onto the current desktop removes its ids from the hidden set",
    "if(desktop==current){" in window and "hidden.remove(String.valueOf(member.taskId))" in window))
checks.append(check("G-04: background menu is dismissed on destroy, the screenshot toast keeps its space, no duplicated Javadoc",
    "backgroundMenu.dismiss()" in f_desktop
    and "галерее): \"" in f_key
    and f_server.count("S36 helper: runs screencap") == 1
    and f_server.count("Hides status UI and disables shade expansion") == 1))

# H-01…H-06, M-01…M-07, U-01…U-08: round H (repeat audit of the gfix build, 2026-11-19).
f_dockedpair = read("app/src/main/java/ru/winlauncher/DockedPairManager.java")
f_grips = read("app/src/main/java/ru/winlauncher/ResizeGripManager.java")
checks.append(check("H-01: DockService follows rotation and input devices; maximized windows refit on geometry change",
    "onConfigurationChanged" in f_dock and "registerInputDeviceListener" in f_dock
    and "configChanges" in manifest_text and "if(sizeChanged){" in window
    and "requestBounds(w,area,0)" in window))
checks.append(check("H-02: deferred snap detach cannot evict the new cell owner; displaced membership is cleared first",
    "currentOwner=g.tasks.get(w.snapCell)" in snap and "other.snapGroupId=null;other.snapCell=-1;" in snap))
checks.append(check("H-03: a superseded resize is benign and the first drag move out of MAXIMIZED only restores",
    '"superseded by newer resize".equals(result.optString("error"))' in window
    and "downX=x;downY=y;return true;" in chrome))
checks.append(check("H-04: draggingTaskId is set when the drag starts; a skipped z-order rebuild is finished at drag end",
    "zOrderDirty" in chrome and "finishDragZOrder" in chrome and "moving=true;draggingTaskId=w.taskId;" in chrome))
checks.append(check("H-05: chrome and dividers hide above a fullscreen window",
    "syncVisibility" in snap and "syncVisibility" in f_dockedpair
    and "snapController.syncVisibility(copy)" in window
    and "w.state==Win.State.FULLSCREEN){removeAll();grips.update(windows,-1);return;}" in chrome))
checks.append(check("H-06: the jump menu restores minimized windows and un-maximizes the rest",
    "else controller.restoreNormal(w)" in f_taskbar))
checks.append(check("M-01: MainActivity re-attaches the service controller after recreate()/rotation",
    "serviceController=DockService.controller()" in f_main and "controller!=serviceController)ensureController()" in f_main))
checks.append(check("M-02: one system menu at a time, all three popups on the system-alert layer, no duplicate right-click",
    "popup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)" in chrome
    and "popup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)" in snap
    and "popup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)" in f_taskbar
    and "dragArea.setOnContextClickListener" not in chrome))
checks.append(check("M-03: a single activateWindow path serves Task View and the task manager; pair entry bumps operations",
    "public void activateWindow(Win w)" in window and "controller.activateWindow(w)" in f_taskview
    and "controller.activateWindow(win)" in f_taskmgr and "controller.nextOperation(a)" in f_dockedpair))
checks.append(check("M-04: resize grips keep their drag window, cancel rolls back, LauncherPrefs is cached",
    "Win dragWin" in f_grips and "controller.requestMove(cancelled,new Rect(start))" in f_grips
    and "private final LauncherPrefs prefs" in f_grips))
checks.append(check("M-05: Assist requires a live dim and a live group; layout snap needs an in-bounds release",
    "assist.dim != null" in snap and "SnapController.this.assist=null" in snap
    and "groups.get(group.id)!=group" in snap and "boolean inside=e.getX()>=0" in snap))
checks.append(check("M-06: the temporary taskbar survives while its jump menu is open and closes the menu on remove",
    "if(isJumpMenuShowing()){renewTemporary();return;}" in f_taskbar
    and "private void remove(){dismissJumpMenu();if(bar!=null)" in f_taskbar))
checks.append(check("M-07: mini bar follows window/size changes, aborted pairs drop the queue, stale show-desktop set clears, F11/chrome hotkeys gated",
    "if(!sameWindow||!sameSize)hideMini()" in f_edges and "abortFirstTransfer" in f_dockedpair
    and "hasMinimizedShowDesktopWindow" in window and "ChromeManager.isChromeEnabled()" in f_key
    and "KEYCODE_F11&&!ctrl&&!shift&&!win" in f_key))
checks.append(check("U-01: desktop widgets get an always-reachable non-overlapping delete strip",
    "LinearLayout wrap=new LinearLayout(this)" in f_desktop and "dp(28)" in f_desktop))
checks.append(check("U-02: MainActivity page scrolls with a tall status; the settings list is the root with a header",
    "pageScroll" in f_main and "status.setMinHeight(dp(96))" in f_main
    and "addHeaderView(page, null, false)" in f_settings and "setContentView(appListView)" in f_settings))
checks.append(check("U-03: Task View card is clickable, the scroller fits the display, pair/nav buttons have disabled states",
    "card.setClickable(true)" in f_taskview and "scrollMetrics.heightPixels/2" in f_taskview
    and "enterPair.setEnabled(selected.size()==2)" in f_taskview
    and "previousDesktop.setEnabled(controller.currentDesktopId()>0)" in f_taskview))
checks.append(check("U-04: taskbar buttons scroll horizontally with margins and the clock stays pinned right",
    "HorizontalScrollView scroller=new HorizontalScrollView(context)" in f_taskbar
    and "itemLp.setMarginEnd(dp(4))" in f_taskbar))
checks.append(check("U-05: Action Center dismisses for Settings and outside touches; cards ellipsize with margins",
    "FLAG_WATCH_OUTSIDE_TOUCH" in f_actioncenter and "private void openSettings(String action){dismiss();try{" in f_actioncenter
    and "item.setMaxLines(3)" in f_actioncenter and "TextUtils.TruncateAt.END" in f_actioncenter))
checks.append(check("U-06: apps dialog dismisses on launch, icons form one row, the strip follows the theme, silent widget bind first",
    "appsDialog" in f_desktop and "bindAppWidgetIdIfAllowed" in f_desktop
    and "icons.setOrientation(LinearLayout.HORIZONTAL)" in f_desktop
    and "prefs.darkTheme()?0xff202020:0xffe8e8e8" in f_desktop))
checks.append(check("U-07: browser tabs know their URL at once, downloads keep server names, narrow buttons do not clip, task manager follows the theme",
    "URLUtil.guessFileName(url,contentDisposition,mimeType)" in f_edge and "tab.url=safeUrl" in f_edge
    and "go.setMinWidth(0)" in f_edge and "close.setAllCaps(false)" in f_taskmgr and "appliedDark" in f_taskmgr))
checks.append(check("U-08: dark-aware dividers, single-line mini title offset below the status bar, cached-label sort and readable package colour",
    "0xffaaaaaa" in f_settings and "namesLp.leftMargin=dp(8)" in f_settings
    and "Collections.sort(loaded" in f_settings and "DividerView(Context c,boolean d)" in snap
    and "title.setSingleLine(true)" in f_edges and "miniParams.y=controller.workArea().top" in f_edges))


# I-01…I-12: round I (final debug audit of the hfix build, 2026-11-19).
checks.append(check("I-01: DockedPairManager.syncVisibility excludes pair members from foreign fullscreen check",
    "boolean foreignFs=false,pairVisible=false;" in f_dockedpair and "if(contains(w))" in f_dockedpair))
checks.append(check("I-02: abortFirstTransfer rolls back to the real original stack via rollbackDockedTransfer",
    "public void rollbackDockedTransfer(Win w, int originalStackId)" in window
    and "controller.rollbackDockedTransfer(w,originalStackId)" in f_dockedpair))
checks.append(check("I-03: ChromeManager guards re-entrant render(), removes zOrder before finishDragZOrder, clears shake preview",
    "private boolean rendering;" in chrome and "if(zOrderDirty&&draggingTaskId==-1&&!rendering)" in chrome
    and "zOrder.remove((Integer)id);" in chrome and "dismissShakePreview();" in chrome))
checks.append(check("I-04: taskbar buttons keep 44/52dp hit width inside HorizontalScrollView with fillViewport",
    "int taskBtnW=dp(prefs.mouseProfile()?44:52);" in f_taskbar
    and "new LinearLayout.LayoutParams(taskBtnW,-1)" in f_taskbar
    and "scroller.setFillViewport(true);" in f_taskbar))
checks.append(check("I-05: EdgeLiteActivity normalizes null tab URLs and falls back on empty download guesses",
    'String safeUrl=(url==null||url.length()==0)?"about:blank":url;' in f_edge
    and '"about:blank".equals(tab.url)' in f_edge and "guessed=fileName(url);" in f_edge))
checks.append(check("I-06: MainActivity uses one pageScroll without a nested ScrollView around status",
    "pageScroll.setFillViewport(true);" in f_main
    and "root.addView(status, new LinearLayout.LayoutParams(-1, -2));" in f_main))
checks.append(check("I-07: LauncherSettingsActivity adds the header view before setAdapter and pads list rows",
    f_settings.index("appListView.addHeaderView(page, null, false);") < f_settings.index("appListView.setAdapter(appAdapter);")
    and "row.setPadding(dp(16), dp(4), dp(16), dp(4));" in f_settings))
checks.append(check("I-08: DesktopActivity widget strip does not overlap content, bindWidget catches RuntimeException, appsDialog clears on dismiss",
    "wrap.addView(strip,new LinearLayout.LayoutParams(-1,dp(28)));" in f_desktop
    and "try{startActivityForResult(bind,REQUEST_WIDGET_BIND);}" in f_desktop
    and "dialog.setOnDismissListener(d->{if(appsDialog==dialog)appsDialog=null;});" in f_desktop))
checks.append(check("I-09: ResizeGripManager commits cancel rollback with mode 0 and clears dragWin on removeAll",
    "controller.requestMove(cancelled,new Rect(start)); controller.commitMove(cancelled);" in f_grips
    and "g.dragWin = null;" in f_grips))
checks.append(check("I-10: SnapController.syncVisibility also hides dividers when snapped windows become invisible (e.g. Home)",
    "boolean needDividers=false;" in snap and "if(!needDividers&&!dividerViews.isEmpty()){removeDividers();}" in snap))
checks.append(check("I-11: FullscreenEdgeManager centers mini title text vertically inside the 32dp TextView",
    "title.setGravity(Gravity.CENTER_VERTICAL);" in f_edges))
checks.append(check("I-12: DockService manifest entry does not carry the activity-only android:configChanges attribute",
    'android:name="ru.winlauncher.DockService"\n            android:exported="false"' in manifest_text))

if not all(checks):
    sys.exit(1)
print(f"{len(checks)}/{len(checks)} static source-coherence checks passed; not a Java build, APK test, or device/runtime acceptance.")
