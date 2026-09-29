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
 * sj_audio.c — thin C shim over miniaudio for sound-jutsu.
 *
 * One ma_engine per output device. Each play allocates a tracked ma_sound
 * "voice" so sj_stop_all / sj_stop_voice can actually stop playback and
 * sj_voice_active can report real state. All realtime mixing stays in C;
 * Jolt only drives the control plane.
 *
 * Vendor miniaudio.h next to this file (`make vendor`), then `make native`.
 */
#define MINIAUDIO_IMPLEMENTATION
#include "miniaudio.h"

#include <stdlib.h>
#include <string.h>
#include <pthread.h>

/* ---- global context + cached device list --------------------------------- */

static ma_context      g_ctx;
static int             g_ctx_ready = 0;
static ma_device_info *g_playback = NULL;   /* owned by g_ctx, valid until re-enum */
static ma_uint32       g_playback_count = 0;

int sj_init(void) {
    if (!g_ctx_ready) {
        if (ma_context_init(NULL, 0, NULL, &g_ctx) != MA_SUCCESS) return -1;
        g_ctx_ready = 1;
    }
    if (ma_context_get_devices(&g_ctx, &g_playback, &g_playback_count,
                               NULL, NULL) != MA_SUCCESS) {
        return -1;
    }
    return (int)g_playback_count;
}

int sj_device_count(void) {
    return g_ctx_ready ? (int)g_playback_count : sj_init();
}

const char *sj_device_name(int idx) {
    if (!g_ctx_ready || idx < 0 || (ma_uint32)idx >= g_playback_count) return NULL;
    return g_playback[idx].name;
}

int sj_device_is_default(int idx) {
    if (!g_ctx_ready || idx < 0 || (ma_uint32)idx >= g_playback_count) return 0;
    return g_playback[idx].isDefault ? 1 : 0;
}

/* ---- per-device engine + tracked voices --------------------------------- */

#define SJ_MAX_VOICES 64

typedef struct {
    ma_engine       engine;
    int             initialized;
    ma_sound       *voices[SJ_MAX_VOICES];
    unsigned int    ids[SJ_MAX_VOICES];     /* per-voice handle, 0 = free slot */
    pthread_mutex_t lock;
} sj_engine;

static unsigned int g_next_voice_id = 1;    /* monotonic, process-wide */

/* caller must hold e->lock */
static void sweep_locked(sj_engine *e) {
    for (int i = 0; i < SJ_MAX_VOICES; i++) {
        ma_sound *s = e->voices[i];
        if (s && ma_sound_at_end(s)) {
            ma_sound_uninit(s);
            free(s);
            e->voices[i] = NULL;
            e->ids[i] = 0;
        }
    }
}

/* caller must hold e->lock */
static void stop_all_locked(sj_engine *e) {
    for (int i = 0; i < SJ_MAX_VOICES; i++) {
        ma_sound *s = e->voices[i];
        if (s) {
            ma_sound_stop(s);
            ma_sound_uninit(s);
            free(s);
            e->voices[i] = NULL;
            e->ids[i] = 0;
        }
    }
}

sj_engine *sj_engine_open(int idx) {
    if (!g_ctx_ready && sj_init() < 0) return NULL;

    sj_engine *e = calloc(1, sizeof(sj_engine));
    if (!e) return NULL;
    pthread_mutex_init(&e->lock, NULL);

    ma_engine_config cfg = ma_engine_config_init();
    cfg.pContext = &g_ctx;
    if (idx >= 0 && (ma_uint32)idx < g_playback_count) {
        cfg.pPlaybackDeviceID = &g_playback[idx].id;
    }

    if (ma_engine_init(&cfg, &e->engine) != MA_SUCCESS) {
        pthread_mutex_destroy(&e->lock);
        free(e);
        return NULL;
    }
    e->initialized = 1;
    return e;
}

void sj_engine_close(sj_engine *e) {
    if (!e) return;
    if (e->initialized) {
        pthread_mutex_lock(&e->lock);
        stop_all_locked(e);
        pthread_mutex_unlock(&e->lock);
        ma_engine_uninit(&e->engine);
    }
    pthread_mutex_destroy(&e->lock);
    free(e);
}

/* Start playback at the given linear volume (1.0 = unity).
 * Returns a positive voice id on success, or a negative ma_result on failure. */
int sj_play_file(sj_engine *e, const char *path, float volume) {
    if (!e || !e->initialized || !path) return MA_INVALID_ARGS;

    pthread_mutex_lock(&e->lock);
    sweep_locked(e);
    int slot = -1;
    for (int i = 0; i < SJ_MAX_VOICES; i++) if (!e->voices[i]) { slot = i; break; }
    if (slot < 0) { pthread_mutex_unlock(&e->lock); return MA_OUT_OF_MEMORY; }

    ma_sound *s = calloc(1, sizeof(ma_sound));
    if (!s) { pthread_mutex_unlock(&e->lock); return MA_OUT_OF_MEMORY; }

    ma_result r = ma_sound_init_from_file(
        &e->engine, path,
        MA_SOUND_FLAG_DECODE | MA_SOUND_FLAG_ASYNC | MA_SOUND_FLAG_NO_PITCH | MA_SOUND_FLAG_NO_SPATIALIZATION,
        NULL, NULL, s);
    if (r != MA_SUCCESS) {
        free(s);
        pthread_mutex_unlock(&e->lock);
        return (int)r;
    }
    unsigned int id = g_next_voice_id++;
    if (id == 0) id = g_next_voice_id++;    /* skip the "free slot" sentinel on wrap */
    ma_sound_set_volume(s, volume);
    ma_sound_start(s);
    e->voices[slot] = s;
    e->ids[slot] = id;
    pthread_mutex_unlock(&e->lock);
    return (int)id;
}

/* Stop and free every currently playing voice on this engine. */
void sj_stop_all(sj_engine *e) {
    if (!e || !e->initialized) return;
    pthread_mutex_lock(&e->lock);
    stop_all_locked(e);
    pthread_mutex_unlock(&e->lock);
}

/* Stop a single voice by id. Returns 1 if it was found and stopped, else 0. */
int sj_stop_voice(sj_engine *e, unsigned int id) {
    if (!e || !e->initialized || id == 0) return 0;
    int hit = 0;
    pthread_mutex_lock(&e->lock);
    for (int i = 0; i < SJ_MAX_VOICES; i++) {
        if (e->voices[i] && e->ids[i] == id) {
            ma_sound_stop(e->voices[i]);
            ma_sound_uninit(e->voices[i]);
            free(e->voices[i]);
            e->voices[i] = NULL;
            e->ids[i] = 0;
            hit = 1;
            break;
        }
    }
    pthread_mutex_unlock(&e->lock);
    return hit;
}

/* 1 if the voice id still exists and hasn't reached its end, else 0. */
int sj_voice_active(sj_engine *e, unsigned int id) {
    if (!e || !e->initialized || id == 0) return 0;
    int active = 0;
    pthread_mutex_lock(&e->lock);
    for (int i = 0; i < SJ_MAX_VOICES; i++) {
        if (e->voices[i] && e->ids[i] == id) {
            active = ma_sound_at_end(e->voices[i]) ? 0 : 1;
            break;
        }
    }
    pthread_mutex_unlock(&e->lock);
    return active;
}
