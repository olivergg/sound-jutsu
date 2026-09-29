/*
 * sound-jutsu — a soundboard with global hotkeys.
 * Copyright (C) 2026  Olivier G
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Additional permission under GNU AGPL version 3 section 7
 *
 * If you modify this Program, or any covered work, by linking or combining
 * it with Jolt (https://github.com/jolt-lang/jolt), including its runtime,
 * standard library and bundled libraries, and with Chez Scheme (or modified
 * versions of them), containing parts covered by the terms of the Eclipse
 * Public License version 1.0 or 2.0 or of the Apache License version 2.0,
 * the licensors of this Program grant you additional permission to convey
 * the resulting work.  Corresponding Source for a non-source form of such a
 * combination shall include the source code for the parts of Jolt and Chez
 * Scheme used as well as that of the covered work.
 */

/*
 * sj_hotkeys.c — global hotkeys for sound-jutsu.
 *
 * macOS: Carbon RegisterEventHotKey. sj_hotkeys_start installs the handler on
 *        the calling thread (Jolt's main thread, which then runs the
 *        webview's app event loop) and promotes the process with
 *        TransformProcessType — both are required for event delivery in a
 *        non-bundled CLI process. Fired ids land in a lock-free SPSC ring
 *        buffer that a Jolt background thread drains via sj_hotkey_poll — no
 *        callback into the Scheme runtime.
 *
 * Other platforms: stubs that report "unsupported".
 */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <strings.h>
#include <stdlib.h>

/* ---- SPSC ring buffer (shared by all platforms) ------------------------- */

#define SJ_RING 128
static volatile uint32_t g_ring[SJ_RING];
static volatile int      g_head = 0;   /* write index (handler thread) */
static volatile int      g_tail = 0;   /* read index  (Jolt poll thread) */

static void ring_push(uint32_t id) {
    int h = g_head;
    int n = (h + 1) % SJ_RING;
    if (n == g_tail) return;    /* full: drop */
    g_ring[h] = id;
    g_head = n;
}

/* Next fired hot-key id, or -1 when the queue is empty. */
int sj_hotkey_poll(void) {
    if (g_tail == g_head) return -1;
    uint32_t id = g_ring[g_tail];
    g_tail = (g_tail + 1) % SJ_RING;
    return (int)id;
}

#ifdef __APPLE__
#include <Carbon/Carbon.h>

static EventHotKeyRef g_refs[256];
static int            g_ref_count = 0;
static int            g_started = 0;

static OSStatus hotkey_handler(EventHandlerCallRef next, EventRef ev, void *ud) {
    (void)next; (void)ud;
    EventHotKeyID hk;
    if (GetEventParameter(ev, kEventParamDirectObject, typeEventHotKeyID, NULL,
                          sizeof(hk), NULL, &hk) == noErr) {
        ring_push(hk.id);
    }
    return noErr;
}

static void noop_timer(CFRunLoopTimerRef t, void *i) { (void)t; (void)i; }

/* US-layout virtual keycodes for the keys a soundboard actually binds. */
static int keycode_for(const char *k) {
    static const struct { const char *n; int c; } T[] = {
        {"a",0},{"s",1},{"d",2},{"f",3},{"h",4},{"g",5},{"z",6},{"x",7},{"c",8},
        {"v",9},{"b",11},{"q",12},{"w",13},{"e",14},{"r",15},{"y",16},{"t",17},
        {"o",31},{"u",32},{"i",34},{"p",35},{"l",37},{"j",38},{"k",40},{"n",45},
        {"m",46},
        /* number row — POSITIONAL (virtual keycodes are layout-independent):
         * "1".."0" and F-keys always mean the physical key at that spot,
         * whatever the layout prints on it. */
        {"1",18},{"2",19},{"3",20},{"4",21},{"5",23},{"6",22},{"7",26},{"8",28},
        {"9",25},{"0",29},
        /* AZERTY keycap aliases for the same physical keys (unshifted glyphs) */
        {"&",18},{"e-acute",19},{"é",19},{"\"",20},{"(",23},
        {"e-grave",26},{"è",26},{"_",28},{"c-cedilla",25},{"ç",25},
        {"a-grave",29},{"à",29},
        {"return",36},{"enter",36},{"tab",48},{"space",49},{"delete",51},
        {"escape",53},{"esc",53},{"-",27},{"=",24},{"[",33},{"]",30},
        {";",41},{"'",39},{",",43},{".",47},{"/",44},{"\\",42},{"`",50},
        {"left",123},{"right",124},{"down",125},{"up",126},
        {"f1",122},{"f2",120},{"f3",99},{"f4",118},{"f5",96},{"f6",97},
        {"f7",98},{"f8",100},{"f9",101},{"f10",109},{"f11",103},{"f12",111},
        {"f13",105},{"f14",107},{"f15",113},{"f16",106},{"f17",64},{"f18",79},
        {"f19",80},
        {"num0",82},{"num1",83},{"num2",84},{"num3",85},{"num4",86},{"num5",87},
        {"num6",88},{"num7",89},{"num8",91},{"num9",92},
        {"numdecimal",65},{"numenter",76},{"numadd",69},{"numsubtract",78},
        {"nummultiply",67},{"numdivide",75},
        {NULL,0}
    };
    for (int i = 0; T[i].n; i++) if (strcasecmp(T[i].n, k) == 0) return T[i].c;
    return -1;
}

static int parse_spec(const char *spec, uint32_t *keycode, uint32_t *mods) {
    char buf[128];
    strncpy(buf, spec, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = 0;

    *mods = 0;
    int have_key = 0;
    for (char *tok = strtok(buf, "+"); tok; tok = strtok(NULL, "+")) {
        if      (!strcasecmp(tok, "ctrl") || !strcasecmp(tok, "control")) *mods |= controlKey;
        else if (!strcasecmp(tok, "alt")  || !strcasecmp(tok, "opt")
              || !strcasecmp(tok, "option"))                              *mods |= optionKey;
        else if (!strcasecmp(tok, "cmd")  || !strcasecmp(tok, "command")
              || !strcasecmp(tok, "meta") || !strcasecmp(tok, "super"))   *mods |= cmdKey;
        else if (!strcasecmp(tok, "shift"))                               *mods |= shiftKey;
        else {
            int c = keycode_for(tok);
            if (c < 0) return -1;
            *keycode = (uint32_t)c;
            have_key = 1;
        }
    }
    return have_key ? 0 : -1;
}

int sj_hotkeys_start(void) {
    if (g_started) return 0;

    /* A plain CLI process does not receive Carbon hot-key events until it is
     * promoted to at least a UI-element ("agent") app. */
    ProcessSerialNumber psn = { 0, kCurrentProcess };
    TransformProcessType(&psn, kProcessTransformToUIElementApplication);

    EventTypeSpec et = { kEventClassKeyboard, kEventHotKeyPressed };
    if (InstallEventHandler(GetApplicationEventTarget(), hotkey_handler,
                            1, &et, NULL, NULL) != noErr)
        return -1;

    CFRunLoopTimerRef keepalive =
        CFRunLoopTimerCreate(NULL, CFAbsoluteTimeGetCurrent() + 1e11, 1e11,
                             0, 0, noop_timer, NULL);
    CFRunLoopAddTimer(CFRunLoopGetCurrent(), keepalive, kCFRunLoopCommonModes);
    CFRelease(keepalive);  /* the run loop retains it; we still own the +1 from Create */

    g_started = 1;
    return 0;
}

int sj_hotkey_register(uint32_t id, const char *spec) {
    if (g_ref_count >= (int)(sizeof(g_refs) / sizeof(g_refs[0]))) return -1;
    uint32_t code = 0, mods = 0;
    if (parse_spec(spec, &code, &mods) != 0) return -2;

    EventHotKeyID hk = { .signature = 'sjbx', .id = id };
    EventHotKeyRef ref;
    if (RegisterEventHotKey(code, mods, hk, GetApplicationEventTarget(), 0, &ref)
        != noErr)
        return -3;
    g_refs[g_ref_count++] = ref;
    return 0;
}

void sj_hotkey_unregister_all(void) {
    for (int i = 0; i < g_ref_count; i++) UnregisterEventHotKey(g_refs[i]);
    g_ref_count = 0;
}

#else  /* ---- non-macOS stubs ------------------------------------------- */

int  sj_hotkeys_start(void)                         { return -100; }
int  sj_hotkey_register(uint32_t id, const char *s) { (void)id; (void)s; return -100; }
void sj_hotkey_unregister_all(void)                 {}

#endif
