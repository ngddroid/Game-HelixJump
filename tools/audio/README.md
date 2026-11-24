# Spindle Drop audio tools

`SynthStudio.java` is an offline synthesizer that renders every sound effect and music
loop in the game. Everything is computed by code: there are no samples, no downloads and no
borrowed melodies. All chord progressions, hooks and motifs are original and written in the
file as note lists.

## Re-render everything

```bash
tools/audio/render_all.sh                        # render, encode, verify (about 10 s)
tools/audio/render_all.sh --only sfx_coin,music_menu
```

Requirements: macOS (`afconvert`, `afinfo`) and Java 21+. The script:

1. compiles `SynthStudio.java` into `tools/audio/build/classes` (or uses `java SynthStudio.java` if `javac` is missing)
2. renders the SFX straight to `app/src/main/res/raw/*.wav` (16-bit PCM, mono, 44.1 kHz)
3. renders the music masters to `tools/audio/build/music/*.wav` (16-bit stereo, 44.1 kHz)
4. encodes the music with `afconvert -f m4af -d aac -b 160000` to `app/src/main/res/raw/music_*.m4a`
5. decodes the AAC again into `tools/audio/build/check/` and prints a verification table
   (duration, peak dBFS, RMS dBFS, loudness, leading silence, clipped samples, loop seam),
   then `afinfo` for every expected file and the total size.

`tools/audio/build/` holds only intermediates and is git-ignored.

Other commands:

```bash
java tools/audio/SynthStudio.java analyze [--loop] file.wav ...   # stats table for any WAV
java tools/audio/SynthStudio.java expected                        # list of files the game expects
```

## What is inside

| Part | Details |
|---|---|
| Oscillators | polyBLEP saw/square, sine, triangle, 2-op FM, white/pink noise |
| Filters | TPT state-variable filter (LP/HP/BP, per-sample cutoff envelopes), RBJ biquads (LP/HP/peak/shelf) |
| Effects | stereo chorus, ping-pong feedback delay, Freeverb, soft-knee compressor, look-ahead limiter, tanh saturation |
| Drums | kick (sine + pitch drop + click), snare (tone + filtered noise), clap, 808-style metallic hats, shaker, toms, crash, riser, impact |
| Instruments | FM e-piano, mallet/marimba, plucks, arp pluck, pop lead, soft lead, brass, super-saw pads/stabs, FM bell, sub/warm/bouncy/pulse bass |
| Music engine | stems with wrap-around note placement, swing, timing/velocity humanize, side-chain ducking, sends to delay and reverb |

### Loudness

* **SFX** are trimmed (sound starts at sample 0, < 2 ms to -40 dBFS), then normalised by
  perceived loudness: K-weighted, highest 80 ms window. The target is -9.5 dB, with an offset
  per sound type (bounces -3 dB, UI -4 to -6 dB, wheel tick -7 dB, big impacts and jingles up
  to +1.5 dB). A look-ahead limiter then caps peaks at **-1 dBFS**. It may take off up to 5 dB,
  but only on sharp transients. This keeps the set even in perceived loudness, so some peaks
  sit below -1 dBFS.
* **Music** is mixed by stem loudness targets, gets a gentle glue compressor and master EQ,
  is normalised to **-20 dBFS RMS**, and is limited at -1.5 dBFS (peaks land around -3 to -5 dBFS).

### Seamless loops

Each loop is exactly N bars long. Notes are added modulo the loop length, so a note's tail at
the end wraps into the start. Every stateful processor (filters, chorus, delay, reverb,
compressor, limiter) is first warmed up on the loop's own tail and then run over the loop. The
output is therefore the steady state of the loop playing forever, and the last sample flows
into the first one. Chorus LFOs run a whole number of cycles per loop. The verification table
compares the jump between the last and first sample with the 99.9th percentile of normal
sample-to-sample steps. A value of 1.0 or less counts as "seamless". The encoded `.m4a` files
carry iTunSMPB gapless info (2112 priming frames), which ExoPlayer/Media3 and Android's MP4
extractor use to trim the encoder delay.

## Files

| File | Notes |
|---|---|
| `sfx_bounce_1..4` | FM "plop/boing" with pitch drop and rise, a sub thump and a soft contact click; 4 pitches (Eb4, F4, Db4, G4) and timbres |
| `sfx_ring_break` | candy crunch grains, glass partials, sparkle bell. Designed for playback rates 0.8 to 1.6 |
| `sfx_combo_note` | marimba/bell hybrid on C5. Clean from rate 1.0 to 2.0 (highest partial is about 10 kHz at 2x) |
| `sfx_blaze_ignite` / `sfx_blaze_smash` | whoosh, fwoomp, crackle and rising saw stack / boom, crunch and debris |
| `sfx_death` | crack, thud and a soft descending slide |
| `sfx_shield_pop`, `sfx_powerup`, `sfx_coin`, `sfx_gem`, `sfx_star` | pickups (star is tuned for rates 1.0, 1.12, 1.26) |
| `sfx_gate_hit`, `sfx_gate_break`, `sfx_vault_break`, `sfx_crumble`, `sfx_boost` | world interactions |
| `sfx_level_complete`, `sfx_fail`, `sfx_wheel_win`, `sfx_reward` | jingles (original, C/G major; fail ends in C minor) |
| `sfx_ui_*`, `sfx_swoosh`, `sfx_purchase`, `sfx_error`, `sfx_unlock`, `sfx_countdown_tick`, `sfx_wheel_tick`, `sfx_chest_*` | UI and meta-game |
| `music_menu` | 90 BPM lo-fi, Ab major, FM e-piano, swung drums, mallet plus soft lead, vinyl bed; 24 bars, 64.0 s |
| `music_play_a` | 118 BPM bouncy pop, D major, plucks, claps, octave bass, pop-lead hook; 32 bars, 65.1 s |
| `music_play_b` | 110 BPM dreamy synth-pop, E major, super-saw pads, dotted-8th arps, airy lead; 32 bars, 69.8 s |
| `music_boss` | 128 BPM driving C minor, pulse bass, stabs, strings, brass "heroic lift" to C major; 36 bars, 67.5 s |
| `music_endless` | 130 BPM electro, F major / D minor, four-on-the-floor, arps, break, build, drop; 36 bars, 66.5 s |

## Tweaking

Each sound is one function (`sfxCoin()`, `musicBoss()`, ...). Melodies are written as
`"E5:0.5 G5:0.5 r:1 ..."` (note:beats, `r` = rest), drum patterns as 16-step strings
(`X x o g .` = accent, normal, soft, ghost, rest), and chords as voicing maps. Per-sound
loudness offsets are in `sfxDefs()`. Rendering is deterministic: fixed random seeds give the
same output every run.
