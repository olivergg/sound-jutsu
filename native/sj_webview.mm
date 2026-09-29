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
 * sj_webview.mm — WebView window for sound-jutsu.
 *
 * Wraps the single-header `webview` library. The web UI calls
 * window.sj("play","soundux","akatsuki") etc.; the bind callback (UI thread)
 * pushes the raw JSON-array request string onto a mutex-guarded queue and
 * returns immediately. Jolt drains it via sj_ui_poll on a background thread —
 * no callback into the Scheme runtime. A JSON array of scalars is also valid
 * EDN (commas are whitespace), so Jolt reads it directly.
 *
 * webview_run() must own the OS main thread; Jolt's -main already is it.
 */
#include "webview.h"
#import <Cocoa/Cocoa.h>

#include <string.h>
#include <stdlib.h>
#include <pthread.h>

#define SJ_UIQ 256
static char           *g_q[SJ_UIQ];
static int             g_qh = 0, g_qt = 0;
static pthread_mutex_t g_qm = PTHREAD_MUTEX_INITIALIZER;
static char            g_poll_buf[16384];

static webview_t g_w = NULL;

extern "C" {

static void q_push(const char *s) {
    pthread_mutex_lock(&g_qm);
    int n = (g_qh + 1) % SJ_UIQ;
    if (n != g_qt) { g_q[g_qh] = strdup(s); g_qh = n; }
    pthread_mutex_unlock(&g_qm);
}

/* Next UI command (a JSON array string), or "" when the queue is empty. */
const char *sj_ui_poll(void) {
    pthread_mutex_lock(&g_qm);
    if (g_qt == g_qh) { pthread_mutex_unlock(&g_qm); g_poll_buf[0] = 0; return g_poll_buf; }
    char *s = g_q[g_qt];
    g_qt = (g_qt + 1) % SJ_UIQ;
    pthread_mutex_unlock(&g_qm);
    strncpy(g_poll_buf, s, sizeof(g_poll_buf) - 1);
    g_poll_buf[sizeof(g_poll_buf) - 1] = 0;
    free(s);
    return g_poll_buf;
}

static void sj_cb(const char *seq, const char *req, void *arg) {
    (void)arg;
    q_push(req);
    webview_return(g_w, seq, 0, "");
}

static NSString *json_quote(NSString *s) {
    NSMutableString *out = [NSMutableString stringWithString:@"\""];
    for (NSUInteger i = 0; i < s.length; i++) {
        unichar c = [s characterAtIndex:i];
        if (c == '"' || c == '\\') [out appendFormat:@"\\%C", c];
        else if (c == '\n') [out appendString:@"\\n"];
        else if (c == '\r') [out appendString:@"\\r"];
        else [out appendFormat:@"%C", c];
    }
    [out appendString:@"\""];
    return out;
}

/* Native folder picker. Runs synchronously on the UI thread (webview_bind
 * callbacks are always dispatched there), so a modal NSOpenPanel is safe.
 * Resolves the JS Promise to the chosen path, or "" if cancelled. */
static void sj_pick_folder_cb(const char *seq, const char *req, void *arg) {
    (void)req; (void)arg;
    NSOpenPanel *panel = [NSOpenPanel openPanel];
    panel.canChooseFiles = NO;
    panel.canChooseDirectories = YES;
    panel.allowsMultipleSelection = NO;
    panel.prompt = @"Choose";
    NSString *path = @"";
    if ([panel runModal] == NSModalResponseOK && panel.URLs.count > 0) {
        path = panel.URLs.firstObject.path ?: @"";
    }
    webview_return(g_w, seq, 0, [json_quote(path) UTF8String]);
}

/* Hide the title text and blend the (still native, still draggable) title bar
 * into the dark UI. */
static void integrate_chrome(void) {
    NSWindow *win = (NSWindow *)webview_get_window(g_w);
    if (!win) return;
    win.titleVisibility = NSWindowTitleHidden;
    win.titlebarAppearsTransparent = YES;
    win.backgroundColor = [NSColor colorWithSRGBRed:0.031 green:0.031
                                              blue:0.055 alpha:1.0];
}

int sj_ui_create(const char *title, int width, int height) {
    g_w = webview_create(0, NULL);
    if (!g_w) return -1;
    webview_set_title(g_w, title);
    webview_set_size(g_w, width, height, 0 /* WEBVIEW_HINT_NONE */);
    webview_bind(g_w, "sj", sj_cb, NULL);
    webview_bind(g_w, "sjPickFolder", sj_pick_folder_cb, NULL);
    integrate_chrome();
    return 0;
}

/* Dock icon + app menu name, for when we run without a .app bundle. */
void sj_ui_brand(const char *icon_path, const char *name) {
    if (icon_path && *icon_path) {
        NSImage *img = [[NSImage alloc]
            initWithContentsOfFile:[NSString stringWithUTF8String:icon_path]];
        if (img) {
            [NSApp setApplicationIconImage:img];  /* retains it; we still own our +1 */
            [img release];
        }
    }
    if (name && *name) {
        [[NSProcessInfo processInfo]
            setProcessName:[NSString stringWithUTF8String:name]];
    }
}

void sj_ui_set_html(const char *html) { if (g_w) webview_set_html(g_w, html); }

/* webview_eval must run on the UI thread; webview_dispatch marshals it there. */
static void eval_trampoline(webview_t w, void *arg) {
    char *js = (char *)arg;
    webview_eval(w, js);
    free(js);
}
void sj_ui_eval(const char *js) {
    if (g_w) webview_dispatch(g_w, eval_trampoline, strdup(js));
}

void sj_ui_run(void)  { if (g_w) webview_run(g_w); }
void sj_ui_stop(void) { if (g_w) webview_terminate(g_w); }

} // extern "C"
