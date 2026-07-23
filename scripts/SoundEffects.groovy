// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 2 of the License, or
// (at your option) any later version.

// Discussion thread: https://github.com/freeplane/freeplane/discussions/2945

/***
 * Sound effects for map editing actions.
 *
 * Plays a short sound when a node is selected, created, moved or deleted, and when a
 * branch is folded or unfolded.
 *
 * USAGE
 * -----
 * Run the script to switch the sounds on, run it again to switch them off, so a single
 * keyboard shortcut toggles the whole thing. Nothing is written to disk and nothing is
 * left behind: switching off unregisters every listener and closes every audio line.
 *
 * WHICH ACTIONS MAKE A SOUND
 * --------------------------
 * See the SETTINGS block below - one switch per action. An action set to false is not
 * merely silent, it is never installed: no listener is registered and no sound is loaded.
 *
 * SOUND FILES
 * -----------
 * By default the sounds are synthesized in memory, so the script works out of the box
 * with no assets at all. To use your own samples, drop uncompressed PCM .wav files into
 *
 *     <freeplane user directory>/sounds/
 *
 * named after the action they belong to:
 *
 *     select.wav   create.wav   move.wav   delete.wav   fold.wav   unfold.wav
 *
 * Any file whose name STARTS WITH the action name is picked up, so create1.wav,
 * create2.wav, create-dig.wav ... are used as random variations of the same action,
 * the way old games avoided sounding repetitive. Files that cannot be decoded are
 * ignored and the synthesized sound is used instead.
 */

import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.AWTEventListenerProxy
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import javax.swing.Timer
import javax.swing.UIManager

import org.freeplane.core.resources.ResourceController
import org.freeplane.features.map.IMapChangeListener
import org.freeplane.features.map.INodeSelectionListener
import org.freeplane.features.map.MapModel
import org.freeplane.features.map.NodeDeletionEvent
import org.freeplane.features.map.NodeModel
import org.freeplane.features.map.NodeMoveEvent
import org.freeplane.features.mode.Controller

// ===========================================================================
//                                 SETTINGS
//           everything worth changing is in this one block
// ===========================================================================

// --- 1. WHICH ACTIONS MAKE A SOUND -----------------------------------------
// Set a line to false and that action goes completely inert: its listener is
// never registered and its sound is never loaded, so it costs nothing at all.

final boolean SOUND_ON_SELECT = true    // a node becomes selected
final boolean SOUND_ON_CREATE = true    // a node is added to the map
final boolean SOUND_ON_MOVE   = true    // a node is moved somewhere else
final boolean SOUND_ON_DELETE = true    // a node is removed from the map
final boolean SOUND_ON_FOLD   = true    // a branch is folded
final boolean SOUND_ON_UNFOLD = true    // a branch is unfolded

// --- 2. SOUND THEME --------------------------------------------------------
//   'wood'    wooden chess pieces on a board: dry knocks, felt, a piece dropped in the box
//   'dungeon' stone, chains and timber in a small cave, with an echo
//   'classic' plain synthesized bleeps
// Ignored for an action that has its own .wav file (see the header).

final String SOUND_THEME = 'wood'

// --- 3. VOLUME -------------------------------------------------------------
// Attenuation applied to every sound, in decibels. Use -18f for something discreet,
// 0f for full level.

final float VOLUME_DB = -9f

// --- 4. TIMING (rarely worth touching) -------------------------------------

final int THROTTLE_MS = 70            // minimum distance between two sounds of the same action
final int SELECT_THROTTLE_MS = 110    // selection repeats while an arrow key is held down
final int FOLD_POLL_DELAY_MS = 90     // how long after an input the fold check runs
final int STRUCTURE_MUTE_MS = 600     // an edit changes the expanded count: do not hear it as a fold
final int SELECT_MUTE_MS = 350        // an edit reselects a node: keep the edit sound alone

// ===========================================================================

final String INSTALL_KEY = 'DungeonSfx.teardown'
final String SOUND_SUBDIR = 'sounds'

// ---------------------------------------------------------------------------
// sound bank: loads .wav variations if present, synthesizes them otherwise
// ---------------------------------------------------------------------------

class DungeonSfxBank {
    static final float SAMPLE_RATE = 44100f
    static final int CLIPS_PER_VARIATION = 2
    static final int SYNTH_VARIATIONS = 3
    static final List<String> EVENTS = ['select', 'create', 'move', 'delete', 'fold', 'unfold']

    private final File soundDir
    private final String theme
    private final List<String> enabledEvents
    private final float volumeDb
    private final int throttleMs
    private final Map<String, Integer> throttleOverrides
    private final Map<String, List<List<Clip>>> pools = new LinkedHashMap<String, List<List<Clip>>>()
    private final Map<String, Long> lastPlayed = new HashMap<String, Long>()
    private final Map<String, Long> mutedUntil = new HashMap<String, Long>()
    private final Random random = new Random()
    private final ExecutorService player = Executors.newSingleThreadExecutor({ Runnable task ->
        Thread thread = new Thread(task, 'dungeon-sfx-player')
        thread.setDaemon(true)
        return thread
    } as ThreadFactory)

    volatile boolean ready = false
    volatile String report = 'not loaded'

    DungeonSfxBank(File soundDir, String theme, List<String> enabledEvents, float volumeDb, int throttleMs,
            Map<String, Integer> throttleOverrides) {
        this.soundDir = soundDir
        this.theme = theme
        this.enabledEvents = enabledEvents
        this.volumeDb = volumeDb
        this.throttleMs = throttleMs
        this.throttleOverrides = throttleOverrides == null ? new HashMap<String, Integer>() : throttleOverrides
    }

    void loadAsync() {
        Thread loader = new Thread({ -> load() } as Runnable, 'dungeon-sfx-loader')
        loader.setDaemon(true)
        loader.start()
    }

    private void load() {
        int fromFiles = 0
        int synthesized = 0
        for (String event : enabledEvents) {
            List<Object> specs = fileVariations(event)
            if (specs.isEmpty()) {
                for (int v = 0; v < SYNTH_VARIATIONS; v++) {
                    specs.add(synthesize(theme, event, v))
                }
                synthesized++
            }
            else {
                fromFiles++
            }
            List<List<Clip>> variations = new ArrayList<List<Clip>>()
            for (Object spec : specs) {
                List<Clip> group = new ArrayList<Clip>()
                for (int i = 0; i < CLIPS_PER_VARIATION; i++) {
                    Clip clip = openClip(spec)
                    if (clip != null) {
                        group.add(clip)
                    }
                }
                if (!group.isEmpty()) {
                    variations.add(group)
                }
            }
            if (!variations.isEmpty()) {
                pools.put(event, variations)
            }
        }
        report = 'theme: ' + theme + ', events: ' + enabledEvents.join('/') + ', from .wav: ' + fromFiles +
                ', synthesized: ' + synthesized
        ready = !pools.isEmpty()
    }

    private List<Object> fileVariations(String event) {
        List<Object> found = new ArrayList<Object>()
        if (soundDir == null || !soundDir.isDirectory()) {
            return found
        }
        File[] files = soundDir.listFiles()
        if (files == null) {
            return found
        }
        List<File> matching = new ArrayList<File>()
        for (File file : files) {
            String name = file.getName().toLowerCase()
            if (file.isFile() && name.endsWith('.wav') && name.startsWith(event)) {
                matching.add(file)
            }
        }
        Collections.sort(matching, { File a, File b -> a.getName().compareTo(b.getName()) } as Comparator)
        found.addAll(matching)
        return found
    }

    private Clip openClip(Object spec) {
        try {
            Clip clip = AudioSystem.getClip()
            if (spec instanceof File) {
                AudioInputStream stream = AudioSystem.getAudioInputStream((File) spec)
                try {
                    clip.open(stream)
                }
                finally {
                    stream.close()
                }
            }
            else {
                byte[] pcm = (byte[]) spec
                clip.open(new AudioFormat(SAMPLE_RATE, 16, 1, true, false), pcm, 0, pcm.length)
            }
            applyGain(clip)
            return clip
        }
        catch (Throwable ignored) {
            return null
        }
    }

    private void applyGain(Clip clip) {
        try {
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN)
                float value = Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), volumeDb))
                gain.setValue(value)
            }
        }
        catch (Throwable ignored) {
        }
    }

    void mute(String event, long millis) {
        mutedUntil.put(event, Long.valueOf(System.currentTimeMillis() + millis))
    }

    void play(String event) {
        if (!ready) {
            return
        }
        long now = System.currentTimeMillis()
        Long silentUntil = mutedUntil.get(event)
        if (silentUntil != null && now < silentUntil.longValue()) {
            return
        }
        Integer override = throttleOverrides.get(event)
        int limit = override == null ? throttleMs : override.intValue()
        Long last = lastPlayed.get(event)
        if (last != null && now - last.longValue() < limit) {
            return
        }
        lastPlayed.put(event, Long.valueOf(now))
        if (!pools.containsKey(event)) {
            return
        }
        // The clip is started off the caller's thread. On average this is not measurably
        // faster (A/B on real selections: the difference changes sign between rounds, so it
        // is noise), but it keeps the event thread from ever waiting on an audio line, which
        // can block when the mixer is busy or the output device is being reconfigured.
        try {
            player.execute({ -> startClip(event) } as Runnable)
        }
        catch (Throwable ignored) {
        }
    }

    private void startClip(String event) {
        List<List<Clip>> variations = pools.get(event)
        if (variations == null || variations.isEmpty()) {
            return
        }
        List<Clip> group = variations.get(random.nextInt(variations.size()))
        Clip chosen = null
        for (Clip clip : group) {
            if (!clip.isRunning()) {
                chosen = clip
                break
            }
        }
        if (chosen == null) {
            chosen = group.get(0)
        }
        try {
            chosen.stop()
            chosen.setFramePosition(0)
            chosen.start()
        }
        catch (Throwable ignored) {
        }
    }

    void dispose() {
        ready = false
        try {
            player.shutdownNow()
        }
        catch (Throwable ignored) {
        }
        for (List<List<Clip>> variations : pools.values()) {
            for (List<Clip> group : variations) {
                for (Clip clip : group) {
                    try {
                        clip.stop()
                        clip.close()
                    }
                    catch (Throwable ignored) {
                    }
                }
            }
        }
        pools.clear()
    }

    // -----------------------------------------------------------------------
    // synthesis - small percussive sounds with a dungeon flavour
    // -----------------------------------------------------------------------

    private static byte[] synthesize(String theme, String event, int variation) {
        if ('classic' == theme) {
            return synthesizeClassic(event, variation)
        }
        if ('wood' == theme) {
            return synthesizeWood(event, variation)
        }
        return synthesizeDungeon(event, variation)
    }

    // Wooden chess pieces on a wooden board. Everything here is a short, heavily damped
    // knock: wood has the same inharmonic modes as metal but loses its energy in a tenth
    // of the time, and the only "room" is the small resonance of the board itself.
    private static byte[] synthesizeWood(String event, int variation) {
        double detune = 1d + (variation - 1) * 0.055d
        Random rnd = new Random(event.hashCode() * 197L + variation)

        if ('select' == event) {
            // fingertip touching the top of a piece before lifting it
            double[] out = buffer(110)
            addWoodTap(out, 0, 980d * detune, 0.30d, 70d, 0.55d, rnd)
            applyCave(out, 13d, 0.12d, 0.5d)
            normalize(out, 0.30d)
            return encode(out)
        }
        if ('create' == event) {
            // the clack of a piece being set down on the board
            double[] out = buffer(260)
            addWoodTap(out, 0, 430d * detune, 0.85d, 150d, 1d, rnd)
            addPartials(out, 0, millis(120), 152d * detune, [1d, 2.4d] as double[],
                    [0.26d, 0.08d] as double[], 2.6d)
            applyCave(out, 17d, 0.18d, 0.45d)
            normalize(out, 0.85d)
            return encode(out)
        }
        if ('move' == event) {
            // a piece dragged across the felt base, wood grazing wood
            double[] out = buffer(320)
            addRub(out, 0, millis(210), 340d * detune, 900d * detune, 0.9d, rnd)
            addWoodTap(out, millis(195), 380d * detune, 0.34d, 110d, 0.8d, rnd)
            applyCave(out, 15d, 0.14d, 0.5d)
            normalize(out, 0.62d)
            return encode(out)
        }
        if ('delete' == event) {
            // captured piece knocked over and dropped into the wooden box
            double[] out = buffer(600)
            addWoodTap(out, 0, 360d * detune, 0.9d, 150d, 1d, rnd)
            addPartials(out, 0, millis(130), 140d * detune, [1d, 2.4d] as double[],
                    [0.24d, 0.08d] as double[], 2.6d)
            addBounces(out, millis(120), 300d * detune, 0.45d, 4, 95d, rnd)
            applyCave(out, 21d, 0.20d, 0.42d)
            normalize(out, 0.92d)
            return encode(out)
        }
        if ('fold' == event) {
            // a heavy piece (rook) put down: low, short, closed
            double[] out = buffer(240)
            addWoodTap(out, 0, 250d * detune, 0.8d, 160d, 1d, rnd)
            addPartials(out, 0, millis(140), 118d * detune, [1d, 2.35d] as double[],
                    [0.30d, 0.09d] as double[], 2.4d)
            applyCave(out, 19d, 0.18d, 0.45d)
            normalize(out, 0.80d)
            return encode(out)
        }
        // unfold: a light piece (pawn) picked up - higher and drier
        double[] out = buffer(200)
        addWoodTap(out, 0, 660d * detune, 0.75d, 120d, 0.9d, rnd)
        addPartials(out, 0, millis(90), 210d * detune, [1d, 2.5d] as double[],
                [0.18d, 0.06d] as double[], 3.0d)
        applyCave(out, 15d, 0.16d, 0.48d)
        normalize(out, 0.78d)
        return encode(out)
    }

    // Stone, timber, chains and a cave tail: every sound ends with a short echo, which is
    // what makes a room sound like a dungeon rather than an office.
    private static byte[] synthesizeDungeon(String event, int variation) {
        double detune = 1d + (variation - 1) * 0.05d
        Random rnd = new Random(event.hashCode() * 131L + variation)

        if ('select' == event) {
            // a water drop falling into a puddle: pitch RISES as the cavity closes
            double[] out = buffer(260)
            addNoise(out, 0, millis(4), 6000d, 3000d, 0.10d, 2.6d, rnd)
            addSine(out, 0, millis(40), 820d * detune, 1750d * detune, 0.30d, 3.0d)
            applyCave(out, 58d, 0.24d, 0.32d)
            normalize(out, 0.34d)
            return encode(out)
        }
        if ('create' == event) {
            // pick striking rock, then a block settling into place
            double[] out = buffer(430)
            addNoise(out, 0, millis(14), 3600d, 900d, 0.85d, 1.8d, rnd)
            addPartials(out, 0, millis(230), 196d * detune, [1d, 2.42d, 4.15d] as double[],
                    [0.50d, 0.22d, 0.10d] as double[], 1.6d)
            addNoise(out, millis(58), millis(11), 2600d, 700d, 0.40d, 2.0d, rnd)
            addPartials(out, millis(58), millis(170), 131d * detune, [1d, 2.6d] as double[],
                    [0.30d, 0.12d] as double[], 1.9d)
            applyCave(out, 72d, 0.30d, 0.30d)
            normalize(out, 0.88d)
            return encode(out)
        }
        if ('move' == event) {
            // chain rattling while something heavy is dragged over stone
            double[] out = buffer(420)
            addRattle(out, 0, millis(240), 1500d * detune, 0.55d, 7, rnd)
            addNoise(out, 0, millis(210), 2400d, 800d, 0.30d, 1.3d, rnd)
            applyCave(out, 64d, 0.26d, 0.30d)
            normalize(out, 0.72d)
            return encode(out)
        }
        if ('delete' == event) {
            // a wall collapsing: low rumble, sub thud, then loose debris
            double[] out = buffer(680)
            addNoise(out, 0, millis(430), 1300d, 85d, 2.4d, 1.7d, rnd)
            addSine(out, 0, millis(350), 128d * detune, 36d * detune, 0.65d, 1.4d)
            addNoise(out, millis(155), millis(120), 2200d, 380d, 0.55d, 2.0d, rnd)
            addNoise(out, millis(275), millis(95), 1800d, 320d, 0.38d, 2.2d, rnd)
            applyCave(out, 96d, 0.38d, 0.24d)
            normalize(out, 0.95d)
            return encode(out)
        }
        if ('fold' == event) {
            // portcullis coming down: creaking timber, then the thud of stone on stone
            double[] out = buffer(430)
            addCreak(out, 0, millis(155), 770d * detune, 320d * detune, 0.32d, 11d, rnd)
            addNoise(out, millis(155), millis(65), 1400d, 240d, 0.90d, 1.8d, rnd)
            addPartials(out, millis(155), millis(210), 92d * detune, [1d, 2.1d] as double[],
                    [0.45d, 0.15d] as double[], 1.6d)
            applyCave(out, 78d, 0.31d, 0.27d)
            normalize(out, 0.85d)
            return encode(out)
        }
        // unfold: the latch clacks and the gate creaks upwards
        double[] out = buffer(430)
        addNoise(out, 0, millis(45), 1700d, 320d, 0.60d, 2.0d, rnd)
        addPartials(out, 0, millis(130), 108d * detune, [1d, 2.3d] as double[],
                [0.30d, 0.10d] as double[], 1.8d)
        addCreak(out, millis(42), millis(205), 300d * detune, 800d * detune, 0.32d, 12d, rnd)
        applyCave(out, 78d, 0.31d, 0.27d)
        normalize(out, 0.85d)
        return encode(out)
    }

    private static byte[] synthesizeClassic(String event, int variation) {
        double detune = 1d + (variation - 1) * 0.045d
        Random rnd = new Random(event.hashCode() * 31L + variation)
        if ('select' == event) {
            double[] out = buffer(70)
            addNoise(out, 0, millis(5), 7000d, 3000d, 0.16d, 2.2d, rnd)
            addSweep(out, 0, millis(55), 1050d * detune, 880d * detune, 0.17d, 2.6d)
            return encode(out)
        }
        if ('create' == event) {
            double[] out = buffer(190)
            addNoise(out, 0, millis(9), 6000d, 2500d, 0.55d, 1.6d, rnd)
            addSweep(out, 0, millis(75), 494d * detune, 512d * detune, 0.42d, 1.4d)
            addSweep(out, millis(70), millis(120), 740d * detune, 784d * detune, 0.40d, 1.8d)
            return encode(out)
        }
        if ('delete' == event) {
            double[] out = buffer(360)
            addNoise(out, 0, millis(330), 1500d, 180d, 1.9d, 1.9d, rnd)
            addSweep(out, 0, millis(300), 150d * detune, 46d * detune, 0.55d, 1.5d)
            return encode(out)
        }
        if ('move' == event) {
            double[] out = buffer(240)
            addWhoosh(out, 0, millis(230), 700d * detune, 2400d * detune, 1.5d, rnd)
            return encode(out)
        }
        if ('fold' == event) {
            double[] out = buffer(140)
            addNoise(out, 0, millis(8), 4000d, 1200d, 0.35d, 1.5d, rnd)
            addSweep(out, 0, millis(120), 660d * detune, 300d * detune, 0.45d, 1.7d)
            return encode(out)
        }
        double[] out = buffer(140)
        addNoise(out, 0, millis(8), 4000d, 1200d, 0.35d, 1.5d, rnd)
        addSweep(out, 0, millis(120), 300d * detune, 680d * detune, 0.45d, 1.7d)
        return encode(out)
    }

    private static int millis(double ms) {
        return (int) (SAMPLE_RATE * ms / 1000d)
    }

    private static double[] buffer(double ms) {
        return new double[millis(ms)]
    }

    private static double timbre(double phase) {
        return (Math.sin(phase) + 0.32d * Math.sin(2 * phase) + 0.12d * Math.sin(3 * phase)) / 1.44d
    }

    private static void addSweep(double[] out, int from, int count, double f0, double f1, double amp, double decayPower) {
        double phase = 0d
        for (int i = 0; i < count; i++) {
            int index = from + i
            if (index >= out.length) {
                break
            }
            double t = i / (double) count
            double frequency = f0 * Math.pow(f1 / f0, t)
            phase += 2 * Math.PI * frequency / SAMPLE_RATE
            double attack = Math.min(1d, i / (SAMPLE_RATE * 0.004d))
            out[index] += amp * attack * Math.pow(1d - t, decayPower) * timbre(phase)
        }
    }

    // pure sine sweep - a drop of water has almost no harmonics, unlike timbre()
    private static void addSine(double[] out, int from, int count, double f0, double f1, double amp, double decayPower) {
        double phase = 0d
        for (int i = 0; i < count; i++) {
            int index = from + i
            if (index >= out.length) {
                break
            }
            double t = i / (double) count
            double frequency = f0 * Math.pow(f1 / f0, t)
            phase += 2 * Math.PI * frequency / SAMPLE_RATE
            double attack = Math.min(1d, i / (SAMPLE_RATE * 0.003d))
            out[index] += amp * attack * Math.pow(1d - t, decayPower) * Math.sin(phase)
        }
    }

    // inharmonic partials: what makes struck stone and metal sound like stone and metal
    // instead of a flute. Higher partials are given a faster decay, as they are in reality.
    private static void addPartials(double[] out, int from, int count, double base, double[] ratios,
            double[] amplitudes, double decayPower) {
        for (int p = 0; p < ratios.length; p++) {
            double frequency = base * ratios[p]
            double decay = decayPower * (1d + p * 0.45d)
            double phase = 0d
            for (int i = 0; i < count; i++) {
                int index = from + i
                if (index >= out.length) {
                    break
                }
                double t = i / (double) count
                phase += 2 * Math.PI * frequency / SAMPLE_RATE
                double attack = Math.min(1d, i / (SAMPLE_RATE * 0.002d))
                out[index] += amplitudes[p] * attack * Math.pow(1d - t, decay) * Math.sin(phase)
            }
        }
    }

    // stick-slip: wood under load does not slide smoothly, it grabs and releases. Each grain
    // is a tiny decaying burst of an unstable tone, which the ear reads as a creak.
    private static void addCreak(double[] out, int from, int count, double f0, double f1, double amp,
            double grainMs, Random rnd) {
        double phase = 0d
        int nominalGrain = millis(grainMs)
        int grainLength = nominalGrain
        int grainPosition = nominalGrain
        double grainGain = 1d
        for (int i = 0; i < count; i++) {
            int index = from + i
            if (index >= out.length) {
                break
            }
            double t = i / (double) count
            double frequency = f0 * Math.pow(f1 / f0, t) * (1d + (rnd.nextDouble() - 0.5d) * 0.07d)
            phase += 2 * Math.PI * frequency / SAMPLE_RATE
            if (grainPosition >= grainLength) {
                grainPosition = 0
                grainLength = (int) Math.max(1d, nominalGrain * (0.5d + rnd.nextDouble()))
                grainGain = 0.55d + 0.45d * rnd.nextDouble()
            }
            double grain = grainGain * Math.pow(1d - grainPosition / (double) grainLength, 1.3d)
            grainPosition++
            double bell = Math.sin(Math.PI * t)
            out[index] += amp * bell * grain * timbre(phase)
        }
    }

    // One wooden knock. The contact noise is what says "wood" as much as the modes do:
    // a hard, dry click of a few milliseconds, then modes that are gone in a tenth of a second.
    private static void addWoodTap(double[] out, int at, double base, double amp, double lengthMs,
            double brightness, Random rnd) {
        addNoise(out, at, millis(5d + 3d * brightness), 4200d * brightness, 900d, 0.55d * amp, 2.4d, rnd)
        addPartials(out, at, millis(lengthMs), base, [1d, 2.65d, 4.8d] as double[],
                [0.62d * amp, 0.20d * amp, 0.07d * amp] as double[], 3.2d)
    }

    // piece toppling and settling: knocks that get closer together and quieter, like a bouncing body
    private static void addBounces(double[] out, int from, double base, double amp, int hits,
            double firstGapMs, Random rnd) {
        double gap = firstGapMs
        int at = from
        double gain = amp
        for (int h = 0; h < hits; h++) {
            at += millis(gap * (0.85d + rnd.nextDouble() * 0.3d))
            if (at >= out.length) {
                return
            }
            addWoodTap(out, at, base * (0.9d + rnd.nextDouble() * 0.35d), gain, 90d, 0.8d, rnd)
            gain *= 0.55d
            gap *= 0.62d
        }
    }

    // friction, not air: a narrow noise band with an irregular slow wobble on its amplitude,
    // which is what makes a drag sound like something touching something instead of a whoosh
    private static void addRub(double[] out, int from, int count, double fLow, double fHigh, double amp, Random rnd) {
        double low = 0d
        double lower = 0d
        double wobble = 0d
        for (int i = 0; i < count; i++) {
            int index = from + i
            if (index >= out.length) {
                break
            }
            double t = i / (double) count
            double bell = Math.sin(Math.PI * t)
            double center = fLow * Math.pow(fHigh / fLow, bell)
            double aHigh = 1d - Math.exp(-2 * Math.PI * (center * 1.5d) / SAMPLE_RATE)
            double aLow = 1d - Math.exp(-2 * Math.PI * (center * 0.75d) / SAMPLE_RATE)
            double white = rnd.nextDouble() * 2d - 1d
            low += aHigh * (white - low)
            lower += aLow * (white - lower)
            wobble += 0.0016d * ((rnd.nextDouble() * 2d - 1d) - wobble)
            double texture = Math.max(0.15d, 0.75d + 5d * wobble)
            out[index] += amp * bell * bell * texture * (low - lower)
        }
    }

    // a handful of small metallic hits scattered over the window - a chain, not a bell
    private static void addRattle(double[] out, int from, int count, double base, double amp, int hits, Random rnd) {
        for (int h = 0; h < hits; h++) {
            int offset = from + (int) (count * (h + rnd.nextDouble() * 0.8d) / hits)
            int length = millis(18d + rnd.nextDouble() * 22d)
            double frequency = base * (0.7d + rnd.nextDouble() * 0.9d)
            double gain = amp * (0.5d + 0.5d * rnd.nextDouble()) * Math.sin(Math.PI * (h + 0.5d) / hits)
            addPartials(out, offset, length, frequency, [1d, 2.76d, 5.4d] as double[],
                    [gain, gain * 0.5d, gain * 0.22d] as double[], 2.4d)
        }
    }

    // one damped delay line read in place: each echo feeds the next, so a single pass
    // leaves a decaying, progressively darker tail - a small stone room
    private static void applyCave(double[] out, double delayMs, double feedback, double damping) {
        int delay = millis(delayMs)
        if (delay <= 0 || delay >= out.length) {
            return
        }
        double low = 0d
        for (int i = delay; i < out.length; i++) {
            low += damping * (out[i - delay] - low)
            out[i] += feedback * low
        }
    }

    private static void normalize(double[] out, double peak) {
        double max = 0d
        for (int i = 0; i < out.length; i++) {
            double magnitude = Math.abs(out[i])
            if (magnitude > max) {
                max = magnitude
            }
        }
        if (max <= 0d) {
            return
        }
        double scale = peak / max
        for (int i = 0; i < out.length; i++) {
            out[i] *= scale
        }
    }

    private static void addNoise(double[] out, int from, int count, double fc0, double fc1, double amp, double decayPower, Random rnd) {
        double low = 0d
        for (int i = 0; i < count; i++) {
            int index = from + i
            if (index >= out.length) {
                break
            }
            double t = i / (double) count
            double cutoff = fc0 * Math.pow(fc1 / fc0, t)
            double coefficient = 1d - Math.exp(-2 * Math.PI * cutoff / SAMPLE_RATE)
            low += coefficient * ((rnd.nextDouble() * 2d - 1d) - low)
            out[index] += amp * Math.pow(1d - t, decayPower) * low
        }
    }

    private static void addWhoosh(double[] out, int from, int count, double fLow, double fHigh, double amp, Random rnd) {
        double low = 0d
        double lower = 0d
        for (int i = 0; i < count; i++) {
            int index = from + i
            if (index >= out.length) {
                break
            }
            double t = i / (double) count
            double bell = Math.sin(Math.PI * t)
            double center = fLow * Math.pow(fHigh / fLow, bell)
            double aHigh = 1d - Math.exp(-2 * Math.PI * (center * 1.8d) / SAMPLE_RATE)
            double aLow = 1d - Math.exp(-2 * Math.PI * (center * 0.6d) / SAMPLE_RATE)
            double white = rnd.nextDouble() * 2d - 1d
            low += aHigh * (white - low)
            lower += aLow * (white - lower)
            out[index] += amp * bell * bell * (low - lower)
        }
    }

    private static byte[] encode(double[] samples) {
        ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (int i = 0; i < samples.length; i++) {
            double value = Math.max(-1d, Math.min(1d, samples[i]))
            buffer.putShort((short) Math.round(value * 31000d))
        }
        return buffer.array()
    }
}

// ---------------------------------------------------------------------------
// fold detection
//
// Folding never reaches IMapChangeListener: NodeModel.setFolded only notifies the
// views. Instead of instrumenting every node, the watcher counts the expanded nodes
// of the current map shortly after each user input and compares with the previous
// count: fewer expanded nodes means a fold, more means an unfold. Structural edits
// change the count too, so the map listener mutes the watcher around them.
// ---------------------------------------------------------------------------

@groovy.transform.CompileStatic
class DungeonSfxCounter {
    static int countExpanded(NodeModel root) {
        int visited = 0
        ArrayDeque<NodeModel> pending = new ArrayDeque<NodeModel>()
        pending.push(root)
        while (!pending.isEmpty()) {
            NodeModel node = pending.pop()
            visited++
            if (node.isFolded()) {
                continue
            }
            List<NodeModel> children = node.getChildren()
            for (int i = 0; i < children.size(); i++) {
                pending.push(children.get(i))
            }
        }
        return visited
    }
}

class DungeonSfxFoldWatcher implements AWTEventListener {
    private final DungeonSfxBank bank
    private final Timer timer
    private MapModel lastMap = null
    private int lastCount = -1
    private long muteUntil = 0L

    DungeonSfxFoldWatcher(DungeonSfxBank bank, int delayMs) {
        this.bank = bank
        this.timer = new Timer(delayMs, { ActionEvent event -> check() } as ActionListener)
        this.timer.setRepeats(false)
    }

    @Override
    void eventDispatched(AWTEvent event) {
        int id = event.getID()
        boolean relevant = id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED ||
                id == MouseEvent.MOUSE_CLICKED || id == KeyEvent.KEY_PRESSED || id == KeyEvent.KEY_RELEASED
        if (relevant) {
            timer.restart()
        }
    }

    void mute(long millis) {
        muteUntil = System.currentTimeMillis() + millis
        timer.restart()
    }

    void stop() {
        timer.stop()
    }

    void resync() {
        check()
    }

    private void check() {
        Controller controller = Controller.getCurrentController()
        MapModel map = controller == null ? null : controller.getMap()
        if (map == null || map.getRootNode() == null) {
            lastMap = null
            lastCount = -1
            return
        }
        int count = DungeonSfxCounter.countExpanded(map.getRootNode())
        boolean comparable = lastMap != null && lastMap.is(map) && lastCount >= 0
        int previous = lastCount
        lastMap = map
        lastCount = count
        if (!comparable || System.currentTimeMillis() < muteUntil || count == previous) {
            return
        }
        bank.play(count > previous ? 'unfold' : 'fold')
    }
}

// ---------------------------------------------------------------------------
// structural events
// ---------------------------------------------------------------------------

class DungeonSfxMapListener implements IMapChangeListener {
    private final DungeonSfxBank bank
    private final DungeonSfxFoldWatcher watcher       // null when no fold sound is enabled
    private final long muteMillis
    private final long selectMuteMillis
    private final boolean playsStructuralSounds

    DungeonSfxMapListener(DungeonSfxBank bank, DungeonSfxFoldWatcher watcher, long muteMillis, long selectMuteMillis,
            boolean playsStructuralSounds) {
        this.bank = bank
        this.watcher = watcher
        this.muteMillis = muteMillis
        this.selectMuteMillis = selectMuteMillis
        this.playsStructuralSounds = playsStructuralSounds
    }

    @Override
    void onNodeInserted(NodeModel parent, NodeModel child, int newIndex) {
        fire('create', parent)
    }

    @Override
    void onNodeDeleted(NodeDeletionEvent event) {
        fire('delete', event.parent)
    }

    @Override
    void onNodeMoved(NodeMoveEvent event) {
        fire('move', event.child)
    }

    // Moving and deleting reselect a neighbour BEFORE the edit event arrives (measured:
    // PRE-MOVE, DESELECT, SELECT neighbour, MOVE - all within 6 ms), so muting the selection
    // sound only from onNodeMoved is one event too late. These two hooks run early enough.
    @Override
    void onPreNodeMoved(NodeMoveEvent event) {
        muteSelection(event.child)
    }

    @Override
    void onPreNodeDelete(NodeDeletionEvent event) {
        muteSelection(event.node)
    }

    private void muteSelection(NodeModel node) {
        if (node == null) {
            return
        }
        Controller controller = Controller.getCurrentController()
        MapModel current = controller == null ? null : controller.getMap()
        if (current == null || !current.is(node.getMap())) {
            return
        }
        bank.mute('select', selectMuteMillis)
    }

    private void fire(String sound, NodeModel node) {
        if (node == null) {
            return
        }
        Controller controller = Controller.getCurrentController()
        MapModel current = controller == null ? null : controller.getMap()
        if (current == null || !current.is(node.getMap())) {
            return
        }
        if (watcher != null) {
            watcher.mute(muteMillis)
        }
        bank.mute('select', selectMuteMillis)
        if (playsStructuralSounds) {
            bank.play(sound)
        }
    }
}

class DungeonSfxSelectionListener implements INodeSelectionListener {
    private final DungeonSfxBank bank

    DungeonSfxSelectionListener(DungeonSfxBank bank) {
        this.bank = bank
    }

    @Override
    void onSelect(NodeModel node) {
        if (node == null) {
            return
        }
        Controller controller = Controller.getCurrentController()
        MapModel current = controller == null ? null : controller.getMap()
        if (current == null || !current.is(node.getMap())) {
            return
        }
        bank.play('select')
    }
}

// ---------------------------------------------------------------------------
// install / uninstall (running the script again toggles the effects off)
// ---------------------------------------------------------------------------

Controller controller = Controller.getCurrentController()
def mapController = controller.getModeController().getMapController()
Toolkit toolkit = Toolkit.getDefaultToolkit()

boolean wasInstalled = false

Object teardown = UIManager.get(INSTALL_KEY)
if (teardown != null) {
    wasInstalled = true
    UIManager.put(INSTALL_KEY, null)
    try {
        ((Runnable) teardown).run()
    }
    catch (Throwable ignored) {
    }
}

// leftovers from an older compilation of this script (class identity differs, name does not)
mapController.getMapChangeListeners()
        .findAll { it.getClass().getSimpleName() == DungeonSfxMapListener.getSimpleName() }
        .each {
            wasInstalled = true
            mapController.removeMapChangeListener(it)
        }
new ArrayList(mapController.getNodeSelectionListeners())
        .findAll { it.getClass().getSimpleName() == DungeonSfxSelectionListener.getSimpleName() }
        .each {
            wasInstalled = true
            mapController.removeNodeSelectionListener(it)
        }
for (Object registered : toolkit.getAWTEventListeners()) {
    Object actual = registered instanceof AWTEventListenerProxy ? ((AWTEventListenerProxy) registered).getListener() : registered
    if (actual.getClass().getSimpleName() == DungeonSfxFoldWatcher.getSimpleName()) {
        wasInstalled = true
        toolkit.removeAWTEventListener((AWTEventListener) actual)
        try {
            actual.stop()
        }
        catch (Throwable ignored) {
        }
    }
}

String message
if (wasInstalled) {
    message = 'Sound effects: OFF'
}
else {
    List<String> enabled = new ArrayList<String>()
    if (SOUND_ON_SELECT) { enabled.add('select') }
    if (SOUND_ON_CREATE) { enabled.add('create') }
    if (SOUND_ON_MOVE) { enabled.add('move') }
    if (SOUND_ON_DELETE) { enabled.add('delete') }
    if (SOUND_ON_FOLD) { enabled.add('fold') }
    if (SOUND_ON_UNFOLD) { enabled.add('unfold') }

    boolean needsFoldWatcher = SOUND_ON_FOLD || SOUND_ON_UNFOLD
    boolean hasStructuralSound = SOUND_ON_CREATE || SOUND_ON_MOVE || SOUND_ON_DELETE
    // the map listener also mutes the fold watcher (an edit changes the expanded count) and
    // the selection sound (an edit reselects a neighbour), so it is needed whenever those exist
    boolean needsMapListener = hasStructuralSound || needsFoldWatcher

    if (enabled.isEmpty()) {
        message = 'Sound effects: nothing enabled'
    }
    else {
        File soundDir = new File(ResourceController.getResourceController().getFreeplaneUserDirectory(), SOUND_SUBDIR)
        Map<String, Integer> throttles = new HashMap<String, Integer>()
        throttles.put('select', Integer.valueOf(SELECT_THROTTLE_MS))
        DungeonSfxBank bank = new DungeonSfxBank(soundDir, SOUND_THEME, enabled, VOLUME_DB, THROTTLE_MS, throttles)
        bank.loadAsync()

        DungeonSfxFoldWatcher watcher = needsFoldWatcher ? new DungeonSfxFoldWatcher(bank, FOLD_POLL_DELAY_MS) : null
        DungeonSfxMapListener listener = needsMapListener ?
                new DungeonSfxMapListener(bank, watcher, STRUCTURE_MUTE_MS, SELECT_MUTE_MS, hasStructuralSound) : null
        DungeonSfxSelectionListener selectionListener = SOUND_ON_SELECT ? new DungeonSfxSelectionListener(bank) : null

        if (listener != null) {
            mapController.addMapChangeListener(listener)
        }
        if (selectionListener != null) {
            mapController.addNodeSelectionListener(selectionListener)
        }
        if (watcher != null) {
            toolkit.addAWTEventListener(watcher, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK)
            watcher.resync()
        }

        UIManager.put(INSTALL_KEY, { ->
            if (listener != null) {
                mapController.removeMapChangeListener(listener)
            }
            if (selectionListener != null) {
                mapController.removeNodeSelectionListener(selectionListener)
            }
            if (watcher != null) {
                toolkit.removeAWTEventListener(watcher)
                watcher.stop()
            }
            bank.dispose()
        } as Runnable)

        message = 'Sound effects: ON [' + enabled.join(' ') + ']'
    }
}

controller.getViewController().out(message)
return message
