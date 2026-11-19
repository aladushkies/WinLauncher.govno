package ru.winlauncher;

/**
 * Central design tokens for the WinLauncher UI: palette, type scale, radii, spacing and
 * hit targets.
 *
 * Every overlay reads its colors and metrics from this class, so a theme change is a
 * one-place edit instead of a hunt through fourteen UI classes. The palette is opaque by
 * construction: the launcher draws with PixelFormat.OPAQUE, so every color carries a full
 * 0xFF alpha and interaction states are expressed as separate colors instead of alpha
 * blending.
 *
 * Colors are named after their role, not after the shade they happen to have, so a surface
 * stays a surface when its value is tuned. The {@code dark} flag selects the variant of the
 * active theme.
 */
public final class UiTokens {
    private UiTokens() { }

    // ------------------------------------------------------------------ type scale
    /** Screen title of the settings activity. */
    public static final float TEXT_HERO = 22f;
    /** Screen title of the main activity. */
    public static final float TEXT_TITLE = 21f;
    /** Page and card heading. */
    public static final float TEXT_HEADING = 20f;
    /** Heading of a section inside a card. */
    public static final float TEXT_SUBHEADING = 18f;
    /** Glyph of a window-chrome button. */
    public static final float TEXT_GLYPH = 17f;
    /** Title of a list item. */
    public static final float TEXT_ITEM = 16f;
    /** Title of a dense row. */
    public static final float TEXT_ROW = 15f;
    /** Menu row and button label. */
    public static final float TEXT_LABEL = 14f;
    /** Button caption, hint and status line. */
    public static final float TEXT_BUTTON = 13f;
    /** Secondary copy under a heading. */
    public static final float TEXT_CAPTION = 12f;
    /** Tertiary copy, such as the state of a task. */
    public static final float TEXT_MICRO = 11f;
    /** Package name of a list row. */
    public static final float TEXT_TINY = 10f;
    /** Glyph of a desktop tile. */
    public static final float TEXT_TILE = 28f;

    // ---------------------------------------------------------------------- radii
    // The unit is chosen by the call site, exactly as before the migration: the compact
    // radius is scaled with dp(), the panel radius is the legacy pixel value. Both numbers
    // are kept verbatim, so centralising them changes no pixel on screen.
    /** Square. Mandatory for the opaque overlay roots and for third-party window chrome. */
    public static final int RADIUS_NONE = 0;
    /** Compact controls such as taskbar buttons; scale it with dp(). */
    public static final int RADIUS_SMALL = 4;
    /** Cards, menus and panels; the legacy pixel value. */
    public static final int RADIUS_MEDIUM = 8;

    // -------------------------------------------------------------------- spacing
    public static final int SPACE_TIGHT = 2;
    public static final int SPACE_XS = 4;
    public static final int SPACE_SM = 8;
    public static final int SPACE_MD = 12;
    public static final int SPACE_LG = 16;
    public static final int SPACE_XL = 24;

    // ---------------------------------------------------------------- hit targets
    /** Minimum hit size of a primary control, in dp. */
    public static final int MIN_TOUCH_TARGET = 48;
    /** Minimum hit size of a dense list row, in dp. */
    public static final int MIN_TOUCH_TARGET_COMPACT = 44;
    /** Height of a row inside a context menu, in dp. */
    public static final int MENU_ROW_HEIGHT = 32;
    /** Side of the icon of a settings row, in dp; with the row padding it reaches 48 dp. */
    public static final int LIST_ICON_SIZE = 40;

    // ------------------------------------------------------------------- surfaces
    /** Full-screen background behind a card. */
    public static int page(boolean dark) { return dark ? 0xff202020 : 0xfff3f3f3; }
    /** Full-screen background of a dimmed overlay. */
    public static int pageSoft(boolean dark) { return dark ? 0xff202020 : 0xfff4f4f4; }
    /** The outer panel of the Action Center. */
    public static int surfacePanel(boolean dark) { return dark ? 0xff292929 : 0xfff5f5f5; }
    /** Menus, rows and buttons. */
    public static int surface(boolean dark) { return dark ? 0xff2b2b2b : 0xfff9f9f9; }
    /** Cards and secondary panels. */
    public static int surfaceCard(boolean dark) { return dark ? 0xff292929 : 0xfff4f4f4; }
    /** The bottom taskbar. */
    public static int surfaceBar(boolean dark) { return dark ? 0xff1c1c1c : 0xfff3f3f3; }
    /** The mini title bar of a fullscreen window. */
    public static int surfaceMini(boolean dark) { return dark ? 0xff292929 : 0xfff3f3f3; }
    /** A raised button, such as the tiles of the Action Center. */
    public static int surfaceButton(boolean dark) { return dark ? 0xff383838 : 0xffe8e8e8; }
    /** A list row that stands out from its page. */
    public static int surfaceRow(boolean dark) { return dark ? 0xff2b2b2b : 0xffe7e7e7; }
    /** A well: preview placeholder and thumbnail. */
    public static int surfaceWell(boolean dark) { return dark ? 0xff3a3a3a : 0xffe7e7e7; }
    /** An inactive browser tab. */
    public static int surfaceTab(boolean dark) { return dark ? 0xff3a3a3a : 0xffd8d8d8; }
    /** A muted action, such as the cancel button of the Snap Assist. */
    public static int surfaceMuted(boolean dark) { return dark ? 0xff3a3a3a : 0xffeeeeee; }
    /** A card of the Snap Assist. */
    public static int surfaceAssist(boolean dark) { return dark ? 0xff2b2b2b : 0xfff7f7f7; }
    /** The preview area of a Snap layout. */
    public static int surfacePreview(boolean dark) { return dark ? 0xff202020 : 0xffeeeeee; }
    /** A surface under the pointer. */
    public static int surfaceHover(boolean dark) { return dark ? 0xff454545 : 0xffe5e5e5; }
    /** A surface that is held down. */
    public static int surfacePressed(boolean dark) { return dark ? 0xff555555 : 0xffe0e0e0; }
    /** A raised surface under the pointer. */
    public static int surfaceHoverSoft(boolean dark) { return dark ? 0xff454545 : 0xffdedede; }
    /** A raised surface that is held down. */
    public static int surfacePressedSoft(boolean dark) { return dark ? 0xff525252 : 0xffd2d2d2; }
    /** A compact surface under the pointer. */
    public static int surfaceHoverCompact(boolean dark) { return dark ? 0xff424242 : 0xffdddddd; }
    /** A compact surface that is held down. */
    public static int surfacePressedCompact(boolean dark) { return dark ? 0xff5c5c5c : 0xffc9c9c9; }
    /** A muted surface under the pointer. */
    public static int surfaceHoverMuted(boolean dark) { return dark ? 0xff4a4a4a : 0xffe5e5e5; }
    /** A muted surface that is held down. */
    public static int surfacePressedMuted(boolean dark) { return dark ? 0xff555555 : 0xffd8d8d8; }
    /** The divider between two docked windows. */
    public static int surfaceDivider(boolean dark) { return dark ? 0xff444444 : 0xffb7b7b7; }
    /** An assist item under the pointer. */
    public static int surfaceHoverAccent(boolean dark) { return dark ? 0xff777777 : 0xffd9eaf7; }
    /** An assist item that is held down. */
    public static int surfacePressedAccent(boolean dark) { return dark ? 0xff5c5c5c : 0xffb7d7f2; }
    /** An idle cell of a Snap layout preview. */
    public static int surfaceCellIdle(boolean dark) { return dark ? 0xff444444 : 0xffdddddd; }
    /** The inactive title bar of a third-party window. */
    public static int chromeIdle(boolean dark) { return dark ? 0xff2b2b2b : 0xfffbfbfb; }
    /** The focused title bar of a third-party window. */
    public static int chromeFocused(boolean dark) { return dark ? 0xff202020 : 0xfff3f3f3; }
    /** The hairline of a third-party title bar. */
    public static int chromeBorder() { return 0xff777777; }
    /** The tile of a desktop shortcut. */
    public static int desktopTile() { return 0xff264678; }
    /** The delete strip above a desktop widget. */
    public static int widgetStrip(boolean dark) { return dark ? 0xff333333 : 0xff444444; }

    // ----------------------------------------------------------------------- text
    /** Headings, labels and anything that must read at a glance. */
    public static int textPrimary(boolean dark) { return dark ? 0xfff5f5f5 : 0xff1a1a1a; }
    /** Supporting copy, for example a state line under a heading. */
    public static int textSecondary(boolean dark) { return dark ? 0xffc5c5c5 : 0xff5c5c5c; }
    /** De-emphasised copy, for example a package name. */
    public static int textMuted(boolean dark) { return dark ? 0xffaaaaaa : 0xff666666; }
    /** Copy of a control that cannot be used right now. */
    public static int textDisabled(boolean dark) { return dark ? 0xff999999 : 0xff808080; }
    /** Copy of a title bar that is not focused. */
    public static int textChromeIdle(boolean dark) { return dark ? 0xff8a8a8a : 0xff5c5c5c; }
    /** Copy of a minimized task in the taskbar. */
    public static int textTaskMuted(boolean dark) { return dark ? 0xffb0b0b0 : 0xff555555; }
    /** The hint of an input field. */
    public static int textHint(boolean dark) { return dark ? 0xff9e9e9e : 0xff666666; }
    /** Copy that sits on a surface of its own, such as a menu row. */
    public static int textOnSurface(boolean dark) { return dark ? 0xfff5f5f5 : 0xff202020; }
    /** Text drawn on top of the accent fill. */
    public static int textOnAccent() { return 0xffffffff; }
    /** The hairline between two task rows. */
    public static int dividerLine() { return 0xffbbbbbb; }

    // ---------------------------------------------------------------------- lines
    /** Hairline around a card or a panel. */
    public static int outline(boolean dark) { return dark ? 0xff777777 : 0xff888888; }
    /** A softer hairline around a menu. */
    public static int outlineSoft(boolean dark) { return dark ? 0xff777777 : 0xffe0e0e0; }
    /** A stronger separator, for example a divider between windows. */
    public static int outlineStrong(boolean dark) { return dark ? 0xff444444 : 0xffb7b7b7; }
    /** The outline of an inactive snap cell. */
    public static int outlineMuted(boolean dark) { return dark ? 0xff666666 : 0xffb7b7b7; }

    // --------------------------------------------------------------------- accent
    /** The accent of the current theme. */
    public static int accent(boolean dark) { return dark ? 0xff0097ff : 0xff0067c0; }
    /** The accent of the current theme under pressure. */
    public static int accentPressed(boolean dark) { return dark ? 0xff004b85 : 0xff005a9e; }
    /** The accent of the light theme, used where the fill must not follow the theme. */
    public static int accentLight() { return 0xff0067c0; }
    /** A tinted accent, used for the selected cell of a Snap layout. */
    public static int accentTint() { return 0xffd9eaf7; }
    /** A stronger accent tint, used while a Snap cell is held down. */
    public static int accentTintStrong() { return 0xffb7d7f2; }

    // ---------------------------------------------------------------- interaction
    /** The destructive action color. */
    public static int danger() { return 0xffc42b1c; }
    /** The destructive action color under pressure. */
    public static int dangerPressed() { return 0xffb22a1b; }
    /** The destructive action color under pressure in a compact control. */
    public static int dangerPressedCompact() { return 0xffa91f12; }

    // ------------------------------------------------------------------ wallpaper
    /** The base color of the desktop wallpaper. */
    public static int wallpaperBase() { return 0xff163365; }
    /** The backdrop of the desktop wallpaper, one per wallpaper. */
    public static int wallpaperBackdrop(int index) {
        int[] backdrops = {0xff15345f, 0xff182642, 0xff23324a};
        return backdrops[Math.abs(index) % backdrops.length];
    }
    /** A petal of the desktop wallpaper, one per wallpaper and per petal. */
    public static int wallpaperPetal(int index, int petal) {
        int[][] petals = {
                {0xff187bd1, 0xff14a5d7, 0xff3158c9, 0xff6843c2},
                {0xff1c9a9a, 0xff31a76c, 0xff3479bd, 0xff6b59bb},
                {0xff5b78d0, 0xffbf5c95, 0xffe48b48, 0xff387cb8}};
        int[] row = petals[Math.abs(index) % petals.length];
        return row[Math.abs(petal) % row.length];
    }
}
