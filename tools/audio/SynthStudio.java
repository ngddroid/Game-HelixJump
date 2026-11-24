// ============================================================================
//  SynthStudio — offline synthesizer for "Spindle Drop".
//
//  Every sample in every SFX and music loop is computed by this file:
//  no audio samples, no downloads, no third-party melodies. All chord
//  progressions, motifs and hooks below are original compositions.
//
//  Usage (from the project root):
//    java -Xmx4g tools/audio/SynthStudio.java render [--out DIR] [--work DIR] [--only a,b,c]
//    java tools/audio/SynthStudio.java analyze [--loop] a.wav b.wav [--noloop] c.wav ...
//    java tools/audio/SynthStudio.java expected
//
//  Normally you just run tools/audio/render_all.sh (renders, encodes, verifies).
// ============================================================================

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class SynthStudio {

    static final int SR = 44100;
    static final double TAU = 2.0 * Math.PI;
    static final int SINE = 0, SAW = 1, SQUARE = 2, TRI = 3;
    static final int LP = 0, HP = 1, BP = 2;

    /** Loudness target (dB, K-weighted, max over 80 ms windows) for "main" SFX. Per-sound offsets below. */
    static final double SFX_TARGET = -9.5;
    static final double SFX_CEILING_DB = -1.0;
    static final double SFX_MAX_LIMIT_DB = 5.0;
    static final double MUSIC_RMS_DB = -20.0;
    static final double MUSIC_CEILING_DB = -1.5;

    // ========================================================================
    //  Small helpers
    // ========================================================================
    static int ns(double sec) { return (int) Math.round(sec * SR); }
    static double tt(int i) { return (double) i / SR; }
    static double mtof(double m) { return 440.0 * Math.pow(2.0, (m - 69.0) / 12.0); }
    static double dbToGain(double db) { return Math.pow(10.0, db / 20.0); }
    static double gainToDb(double g) { return 20.0 * Math.log10(Math.max(g, 1e-12)); }
    static double clamp(double x, double lo, double hi) { return x < lo ? lo : (x > hi ? hi : x); }
    static double frac(double x) { return x - Math.floor(x); }
    static double smoothstep(double e0, double e1, double x) { double t = clamp((x - e0) / (e1 - e0), 0, 1); return t * t * (3 - 2 * t); }
    static double sat(double x, double drive) { return Math.tanh(x * drive) / Math.tanh(drive); }
    static double ramp(double t, double a) { return t >= a ? 1.0 : (t <= 0 ? 0.0 : t / a); }
    /** 1 while gated, then exponential release reaching -60 dB after {@code rel} seconds. */
    static double relEnv(double t, double gate, double rel) { return t <= gate ? 1.0 : Math.exp(-(t - gate) * 6.9 / rel); }

    /** ADSR: attack (sine-shaped), exponential decay to sustain, exponential release. */
    static double adsr(double t, double gate, double a, double d, double s, double r) {
        if (t < 0) return 0;
        if (t <= gate) return adsrOn(t, a, d, s);
        return adsrOn(gate, a, d, s) * Math.exp(-(t - gate) * 6.9 / Math.max(r, 1e-4));
    }
    static double adsrOn(double t, double a, double d, double s) {
        if (t < a) return Math.sin(0.5 * Math.PI * t / a);
        return s + (1 - s) * Math.exp(-(t - a) * 4.6 / Math.max(d, 1e-4));
    }

    static final class Rng {
        final SplittableRandom r;
        Rng(long seed) { r = new SplittableRandom(seed); }
        double u() { return r.nextDouble(); }
        double bi() { return r.nextDouble() * 2.0 - 1.0; }
        double range(double a, double b) { return a + (b - a) * r.nextDouble(); }
        int i(int n) { return r.nextInt(n); }
        double gauss() { double u1 = Math.max(1e-12, r.nextDouble()), u2 = r.nextDouble(); return Math.sqrt(-2 * Math.log(u1)) * Math.cos(TAU * u2); }
        long seed() { return r.nextLong(); }
    }

    /** Note name ("C4", "F#3", "Bb2") to MIDI number (C4 = 60). */
    static int nn(String s) {
        s = s.trim();
        int base = switch (Character.toUpperCase(s.charAt(0))) {
            case 'C' -> 0; case 'D' -> 2; case 'E' -> 4; case 'F' -> 5; case 'G' -> 7; case 'A' -> 9; case 'B' -> 11;
            default -> throw new IllegalArgumentException("bad note " + s);
        };
        int i = 1;
        while (i < s.length() && (s.charAt(i) == '#' || s.charAt(i) == 'b')) { base += s.charAt(i) == '#' ? 1 : -1; i++; }
        return 12 * (Integer.parseInt(s.substring(i)) + 1) + base;
    }
    static int[] notes(String s) { String[] p = s.trim().split("\\s+"); int[] o = new int[p.length]; for (int i = 0; i < p.length; i++) o[i] = nn(p[i]); return o; }
    static Map<String, String> map(String... kv) { Map<String, String> m = new HashMap<>(); for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]); return m; }

    // ========================================================================
    //  Oscillators (polyBLEP band-limited saw / square), noise
    // ========================================================================
    static final class Osc {
        final int type; double ph; double pw = 0.5;
        Osc(int type) { this.type = type; }
        Osc(int type, double phase) { this.type = type; this.ph = frac(phase); }
        double next(double f) {
            double dt = Math.abs(f) / SR; if (dt > 0.45) dt = 0.45;
            double out;
            switch (type) {
                case SINE -> out = Math.sin(TAU * ph);
                case SAW -> out = 2 * ph - 1 - blep(ph, dt);
                case SQUARE -> { double v = ph < pw ? 1 : -1; v += blep(ph, dt); v -= blep(frac(ph + 1 - pw), dt); out = v; }
                default -> out = 4 * Math.abs(ph - 0.5) - 1;
            }
            ph += dt; if (ph >= 1) ph -= 1;
            return out;
        }
        static double blep(double t, double dt) {
            if (dt <= 0) return 0;
            if (t < dt) { t /= dt; return t + t - t * t - 1; }
            if (t > 1 - dt) { t = (t - 1) / dt; return t * t + t + t + 1; }
            return 0;
        }
    }

    /** Paul Kellet's pink-noise filter. */
    static final class Pink {
        double b0, b1, b2, b3, b4, b5, b6;
        double next(double w) {
            b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759; b2 = 0.96900 * b2 + w * 0.1538520;
            b3 = 0.86650 * b3 + w * 0.3104856; b4 = 0.55000 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.0168980;
            double p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362; b6 = w * 0.115926;
            return p * 0.11;
        }
    }

    // ========================================================================
    //  Filters: TPT state-variable filter (cheap to modulate) + RBJ biquads
    // ========================================================================
    static final class SVF {
        double ic1, ic2, g, k = 1, a1, a2, a3, lf = -1, lq = -1, low, band, high;
        void set(double fc, double q) {
            if (fc == lf && q == lq) return;
            lf = fc; lq = q;
            double f = clamp(fc, 5, SR * 0.47);
            g = Math.tan(Math.PI * f / SR); k = 1.0 / q;
            a1 = 1 / (1 + g * (g + k)); a2 = g * a1; a3 = g * a2;
        }
        void tick(double v0) {
            double v3 = v0 - ic2, v1 = a1 * ic1 + a2 * v3, v2 = ic2 + a2 * ic1 + a3 * v3;
            ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2; low = v2; band = v1; high = v0 - k * v1 - v2;
        }
        double lp(double x, double fc, double q) { set(fc, q); tick(x); return low; }
        double hp(double x, double fc, double q) { set(fc, q); tick(x); return high; }
        /** Band-pass normalised to unity gain at the centre frequency. */
        double bp(double x, double fc, double q) { set(fc, q); tick(x); return band * k; }
    }

    static final class Biquad {
        double b0, b1, b2, a1, a2, z1, z2;
        static Biquad make(String type, double f, double q, double gainDb) {
            Biquad b = new Biquad();
            double A = Math.pow(10, gainDb / 40), w0 = TAU * f / SR, cw = Math.cos(w0), sw = Math.sin(w0), al = sw / (2 * q);
            double B0, B1, B2, A0, A1, A2;
            switch (type) {
                case "lp" -> { B0 = (1 - cw) / 2; B1 = 1 - cw; B2 = (1 - cw) / 2; A0 = 1 + al; A1 = -2 * cw; A2 = 1 - al; }
                case "hp" -> { B0 = (1 + cw) / 2; B1 = -(1 + cw); B2 = (1 + cw) / 2; A0 = 1 + al; A1 = -2 * cw; A2 = 1 - al; }
                case "peak" -> { B0 = 1 + al * A; B1 = -2 * cw; B2 = 1 - al * A; A0 = 1 + al / A; A1 = -2 * cw; A2 = 1 - al / A; }
                case "lowshelf" -> {
                    double sq = 2 * Math.sqrt(A) * al;
                    B0 = A * ((A + 1) - (A - 1) * cw + sq); B1 = 2 * A * ((A - 1) - (A + 1) * cw); B2 = A * ((A + 1) - (A - 1) * cw - sq);
                    A0 = (A + 1) + (A - 1) * cw + sq; A1 = -2 * ((A - 1) + (A + 1) * cw); A2 = (A + 1) + (A - 1) * cw - sq;
                }
                case "highshelf" -> {
                    double sq = 2 * Math.sqrt(A) * al;
                    B0 = A * ((A + 1) + (A - 1) * cw + sq); B1 = -2 * A * ((A - 1) + (A + 1) * cw); B2 = A * ((A + 1) + (A - 1) * cw - sq);
                    A0 = (A + 1) - (A - 1) * cw + sq; A1 = 2 * ((A - 1) - (A + 1) * cw); A2 = (A + 1) - (A - 1) * cw - sq;
                }
                default -> throw new IllegalArgumentException(type);
            }
            b.b0 = B0 / A0; b.b1 = B1 / A0; b.b2 = B2 / A0; b.a1 = A1 / A0; b.a2 = A2 / A0;
            return b;
        }
        double p(double x) { double y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y; }
    }

    static void filt(double[] x, Biquad b) { for (int i = 0; i < x.length; i++) x[i] = b.p(x[i]); }
    static void lowpass(double[] x, double fc) { filt(x, Biquad.make("lp", fc, 0.707, 0)); }
    static void highpass(double[] x, double fc) { filt(x, Biquad.make("hp", fc, 0.707, 0)); }
    /** Filters a periodic (looping) signal in place: the filter state is warmed up on the loop's tail first. */
    static void filterPeriodic(double[] x, Biquad b, double warmSec) {
        int n = x.length, warm = ns(warmSec);
        for (int k = -warm; k < 0; k++) b.p(x[Math.floorMod(k, n)]);
        for (int i = 0; i < n; i++) x[i] = b.p(x[i]);
    }

    // ========================================================================
    //  Effects: delay line, chorus, ping-pong delay, Freeverb, compressor, limiter
    // ========================================================================
    static final class DelayLine {
        final double[] buf; int w;
        DelayLine(int max) { buf = new double[max + 4]; }
        void push(double x) { buf[w] = x; if (++w >= buf.length) w = 0; }
        /** Sample written {@code d} pushes ago (d >= 1), linear interpolation. */
        double tap(double d) {
            double rp = w - d; while (rp < 0) rp += buf.length;
            int i0 = (int) rp; double f = rp - i0; if (i0 >= buf.length) i0 -= buf.length;
            int i1 = i0 + 1; if (i1 >= buf.length) i1 = 0;
            return buf[i0] + (buf[i1] - buf[i0]) * f;
        }
    }

    /** Stereo processor. {@code pos}/{@code period} are set by the periodic runner so LFOs loop seamlessly. */
    static abstract class SP {
        double oL, oR; int pos, period;
        abstract void process(double l, double r);
    }

    static final class Chorus extends SP {
        final DelayLine dl = new DelayLine(ns(0.06)), dr = new DelayLine(ns(0.06));
        final double rate, base, depth, mix; double ph; long cycles = -1;
        Chorus(double rateHz, double baseMs, double depthMs, double mix) { rate = rateHz; base = baseMs; depth = depthMs; this.mix = mix; }
        void process(double l, double r) {
            double p;
            if (period > 0) { if (cycles < 0) cycles = Math.max(1, Math.round(rate * period / SR)); p = frac((double) pos * cycles / period); }
            else { ph = frac(ph + rate / SR); p = ph; }
            dl.push(l); dr.push(r);
            double d1 = (base + depth * Math.sin(TAU * p)) * SR / 1000.0;
            double d2 = (base + depth * Math.sin(TAU * (p + 0.25))) * SR / 1000.0;
            double d3 = (base * 1.35 + depth * Math.sin(TAU * (p + 0.6))) * SR / 1000.0;
            double wl = 0.65 * dl.tap(d1) + 0.35 * dr.tap(d3);
            double wr = 0.65 * dr.tap(d2) + 0.35 * dl.tap(d3);
            oL = l * (1 - 0.4 * mix) + wl * mix;
            oR = r * (1 - 0.4 * mix) + wr * mix;
        }
    }

    static final class PingPong extends SP {
        final DelayLine a, b; final double d, fb, lpHz;
        final SVF la = new SVF(), lb = new SVF(); final Biquad ha = Biquad.make("hp", 250, 0.707, 0), hb = Biquad.make("hp", 250, 0.707, 0);
        PingPong(double timeSec, double fb, double lpHz) { d = timeSec * SR; this.fb = fb; this.lpHz = lpHz; a = new DelayLine((int) d + 8); b = new DelayLine((int) d + 8); }
        void process(double l, double r) {
            double ya = a.tap(d), yb = b.tap(d);
            a.push(la.lp(ha.p(0.5 * (l + r) + yb * fb), lpHz, 0.707));
            b.push(lb.lp(hb.p(ya * fb), lpHz, 0.707));
            oL = ya; oR = yb;
        }
    }

    /** Freeverb (Jezar's public-domain design): 8 damped combs + 4 allpasses per channel. Wet output only. */
    static final class Freeverb extends SP {
        static final int[] COMB = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        static final int[] AP = {556, 441, 341, 225};
        final Comb[] cl = new Comb[8], cr = new Comb[8]; final Allpass[] al = new Allpass[4], ar = new Allpass[4];
        final double wet1, wet2; final DelayLine pre; final double preS; final Biquad hpIn, lpIn;
        Freeverb(double room, double damp, double width, double preDelay, double hpHz, double lpHz) {
            double fb = room * 0.28 + 0.7, dm = damp * 0.4;
            for (int i = 0; i < 8; i++) { cl[i] = new Comb(COMB[i], fb, dm); cr[i] = new Comb(COMB[i] + 23, fb, dm); }
            for (int i = 0; i < 4; i++) { al[i] = new Allpass(AP[i]); ar[i] = new Allpass(AP[i] + 23); }
            wet1 = width / 2 + 0.5; wet2 = (1 - width) / 2;
            preS = Math.max(1, preDelay * SR); pre = new DelayLine((int) preS + 4);
            hpIn = Biquad.make("hp", hpHz, 0.707, 0); lpIn = Biquad.make("lp", lpHz, 0.707, 0);
        }
        void process(double l, double r) {
            pre.push((l + r) * 0.015);
            double x = lpIn.p(hpIn.p(pre.tap(preS)));
            double sl = 0, sr = 0;
            for (int i = 0; i < 8; i++) { sl += cl[i].p(x); sr += cr[i].p(x); }
            for (int i = 0; i < 4; i++) { sl = al[i].p(sl); sr = ar[i].p(sr); }
            oL = sl * wet1 + sr * wet2; oR = sr * wet1 + sl * wet2;
        }
        static final class Comb {
            final double[] buf; int idx; double store; final double fb, d1, d2;
            Comb(int n, double fb, double damp) { buf = new double[n]; this.fb = fb; d1 = damp; d2 = 1 - damp; }
            double p(double in) {
                double out = buf[idx];
                store = out * d2 + store * d1;
                if (Math.abs(store) < 1e-25) store = 0;
                buf[idx] = in + store * fb;
                if (++idx >= buf.length) idx = 0;
                return out;
            }
        }
        static final class Allpass {
            final double[] buf; int idx;
            Allpass(int n) { buf = new double[n]; }
            double p(double in) {
                double bo = buf[idx], out = -in + bo;
                buf[idx] = in + bo * 0.5;
                if (++idx >= buf.length) idx = 0;
                return out;
            }
        }
    }

    /** Feed-forward soft-knee compressor (stereo linked, RMS detector). Returns a gain per sample. */
    static final class Compressor {
        final double thr, ratio, knee, att, rel, rmsC, makeup; double env, rms;
        Compressor(double thrDb, double ratio, double kneeDb, double attack, double release, double rmsSec, double makeupDb) {
            thr = thrDb; this.ratio = ratio; knee = kneeDb; makeup = makeupDb;
            att = Math.exp(-1.0 / (attack * SR)); rel = Math.exp(-1.0 / (release * SR)); rmsC = Math.exp(-1.0 / (rmsSec * SR));
        }
        double reduction(double lvl) {
            double over = lvl - thr;
            if (2 * over < -knee) return 0;
            if (2 * Math.abs(over) <= knee) { double x = over + knee / 2; return (1 - 1 / ratio) * x * x / (2 * knee); }
            return (1 - 1 / ratio) * over;
        }
        double next(double l, double r) {
            rms = rmsC * rms + (1 - rmsC) * Math.max(l * l, r * r);
            double target = reduction(10 * Math.log10(rms + 1e-20));
            env = target > env ? att * env + (1 - att) * target : rel * env + (1 - rel) * target;
            return dbToGain(makeup - env);
        }
    }

    /**
     * Look-ahead brickwall limiter (offline). Gain = box-smoothed sliding minimum of the required gain,
     * which guarantees |out| <= ceiling without distortion, followed by an exponential release.
     */
    static void limit(double[][] ch, double ceilDb, double lookSec, double relSec, boolean periodic) {
        int n = ch[0].length; double ceil = dbToGain(ceilDb); int W = Math.max(1, ns(lookSec));
        int pre = periodic ? Math.min(4 * n, W + ns(relSec * 10)) : 0;
        int post = W;
        int m = pre + n + post;
        double[] req = new double[m];
        for (int k = 0; k < m; k++) {
            int idx = k - pre; double v = 0;
            if (periodic) { int j = Math.floorMod(idx, n); for (double[] c : ch) v = Math.max(v, Math.abs(c[j])); }
            else if (idx >= 0 && idx < n) for (double[] c : ch) v = Math.max(v, Math.abs(c[idx]));
            req[k] = v > ceil ? ceil / v : 1.0;
        }
        double[] mn = new double[m]; int[] dq = new int[m]; int h = 0, t = 0;
        for (int k = m - 1; k >= 0; k--) {
            while (t > h && req[dq[t - 1]] >= req[k]) t--;
            dq[t++] = k;
            while (dq[h] > k + W) h++;
            mn[k] = req[dq[h]];
        }
        double rc = Math.exp(-1.0 / (relSec * SR)), g = 1, acc = 0;
        double[] gain = new double[m];
        for (int k = 0; k < m; k++) {
            acc += mn[k]; if (k - W - 1 >= 0) acc -= mn[k - W - 1];
            double s = acc / Math.min(k + 1, W + 1);
            if (s < g) g = s; else g = s + (g - s) * rc;
            gain[k] = g;
        }
        for (double[] c : ch) for (int i = 0; i < n; i++) c[i] = clamp(c[i] * gain[pre + i], -ceil, ceil);
    }

    /** Runs a stateful stereo processor over a looping signal; the state is first warmed up on the tail. */
    static double[][] runPeriodic(double[] L, double[] R, SP p, double warmSec) {
        int n = L.length, warm = ns(warmSec); p.period = n;
        for (int k = -warm; k < 0; k++) { int j = Math.floorMod(k, n); p.pos = j; p.process(L[j], R[j]); }
        double[] oL = new double[n], oR = new double[n];
        for (int i = 0; i < n; i++) { p.pos = i; p.process(L[i], R[i]); oL[i] = p.oL; oR[i] = p.oR; }
        return new double[][]{oL, oR};
    }

    // ========================================================================
    //  Loudness / statistics
    // ========================================================================
    static double[] kweight(double[] x) {
        Biquad a = Biquad.make("highshelf", 1681.97, 0.7072, 4.0), b = Biquad.make("hp", 38.13, 0.5003, 0);
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) y[i] = b.p(a.p(x[i]));
        return y;
    }
    /** BS.1770-style gated integrated loudness (LUFS-like). */
    static double loudness(double[] L, double[] R) {
        double[] kl = kweight(L), kr = R == null ? null : kweight(R);
        int blk = ns(0.4), hop = ns(0.1), n = L.length;
        List<Double> ms = new ArrayList<>();
        for (int s = 0; s + blk <= n; s += hop) {
            double sum = 0;
            for (int i = s; i < s + blk; i++) { sum += kl[i] * kl[i]; if (kr != null) sum += kr[i] * kr[i]; }
            ms.add(sum / blk);
        }
        if (ms.isEmpty()) return -120;
        double absG = Math.pow(10, (-70 + 0.691) / 10), sum = 0; int c = 0;
        for (double v : ms) if (v > absG) { sum += v; c++; }
        if (c == 0) return -120;
        double relG = (sum / c) * Math.pow(10, -1.0);
        double s2 = 0; int c2 = 0;
        for (double v : ms) if (v > absG && v > relG) { s2 += v; c2++; }
        return -0.691 + 10 * Math.log10(s2 / c2);
    }
    /** Short-sound loudness: max K-weighted mean-square over 80 ms windows. */
    static double sfxLoudness(double[] x) {
        double[] k = kweight(x); int win = ns(0.08), hop = ns(0.005);
        double[] cs = new double[k.length + 1];
        for (int i = 0; i < k.length; i++) cs[i + 1] = cs[i] + k[i] * k[i];
        double best = 0;
        for (int s = 0; s < Math.max(1, k.length - win + hop); s += hop) {
            int e = Math.min(k.length, s + win);
            best = Math.max(best, (cs[e] - cs[Math.min(s, k.length)]) / win);
        }
        return -0.691 + 10 * Math.log10(best + 1e-20);
    }
    static double rmsDb(double[]... ch) {
        double s = 0; long c = 0;
        for (double[] x : ch) { for (double v : x) s += v * v; c += x.length; }
        return 10 * Math.log10(s / Math.max(1, c) + 1e-20);
    }
    static double peak(double[]... ch) { double p = 0; for (double[] x : ch) for (double v : x) p = Math.max(p, Math.abs(v)); return p; }

    record Stats(double dur, double peakDb, double rmsDb, double loud, double leadMs, int clipped, double loopRatio) {}
    static Stats stats(double[][] ch, int sr, boolean loop) {
        int n = ch[0].length;
        double pk = peak(ch), rms = rmsDb(ch);
        int lead = n;
        for (double[] x : ch) for (int i = 0; i < n; i++) if (Math.abs(x[i]) >= 0.01) { lead = Math.min(lead, i); break; }
        int clipped = 0;
        for (double[] x : ch) for (double v : x) if (Math.abs(v) >= 32766.0 / 32768.0) clipped++;
        double loud = ch.length == 1 ? sfxLoudness(ch[0]) : loudness(ch[0], ch[1]);
        double ratio = Double.NaN;
        if (loop) {
            ratio = 0;
            for (double[] x : ch) {
                double[] d = new double[n - 1];
                for (int i = 1; i < n; i++) d[i - 1] = Math.abs(x[i] - x[i - 1]);
                Arrays.sort(d);
                double p999 = d[(int) (0.999 * (d.length - 1))];
                ratio = Math.max(ratio, Math.abs(x[0] - x[n - 1]) / Math.max(p999, 1e-9));
            }
        }
        return new Stats((double) n / sr, gainToDb(pk), rms, loud, lead * 1000.0 / sr, clipped, ratio);
    }
    static String statsHeader() {
        return String.format("%-22s %4s %8s %9s %9s %8s %8s %5s %s", "file", "ch", "dur(s)", "peak dB", "RMS dB", "loud", "lead ms", "clip", "loop-seam");
    }
    static String statsLine(String name, int ch, Stats s) {
        String loop = Double.isNaN(s.loopRatio) ? "" : String.format("%.2f %s", s.loopRatio, s.loopRatio <= 1.0 ? "seamless" : "CHECK");
        return String.format("%-22s %4d %8.3f %9.2f %9.2f %8.2f %8.2f %5d %s", name, ch, s.dur, s.peakDb, s.rmsDb, s.loud, s.leadMs, s.clipped, loop);
    }

    // ========================================================================
    //  WAV I/O (16-bit PCM)
    // ========================================================================
    static void writeWav(Path p, double[][] ch) throws IOException {
        int nch = ch.length, n = ch[0].length, data = n * nch * 2;
        ByteBuffer bb = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + data).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        bb.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) nch)
          .putInt(SR).putInt(SR * nch * 2).putShort((short) (nch * 2)).putShort((short) 16);
        bb.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(data);
        for (int i = 0; i < n; i++) for (int c = 0; c < nch; c++) {
            long s = Math.round(ch[c][i] * 32767.0);
            bb.putShort((short) Math.max(-32768, Math.min(32767, s)));
        }
        Files.write(p, bb.array());
    }

    record Wav(int sr, double[][] ch) {}
    static Wav readWav(Path p) throws IOException {
        byte[] b = Files.readAllBytes(p);
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        if (!new String(b, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")) throw new IOException("not RIFF: " + p);
        int pos = 12, nch = 0, bits = 0, fmt = 0, sr = 0;
        while (pos + 8 <= b.length) {
            String id = new String(b, pos, 4, StandardCharsets.US_ASCII); int size = bb.getInt(pos + 4), body = pos + 8;
            if (id.equals("fmt ")) {
                fmt = bb.getShort(body) & 0xffff; nch = bb.getShort(body + 2); sr = bb.getInt(body + 4); bits = bb.getShort(body + 14);
                if (fmt == 0xFFFE) fmt = bb.getShort(body + 24) & 0xffff;
            } else if (id.equals("data")) {
                int bps = bits / 8, frames = Math.min(size, b.length - body) / (nch * bps);
                double[][] out = new double[nch][frames];
                for (int i = 0; i < frames; i++) for (int c = 0; c < nch; c++) {
                    int o = body + (i * nch + c) * bps;
                    out[c][i] = switch (bits) {
                        case 16 -> bb.getShort(o) / 32768.0;
                        case 24 -> ((b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | (b[o + 2] << 16)) / 8388608.0;
                        case 32 -> fmt == 3 ? bb.getFloat(o) : bb.getInt(o) / 2147483648.0;
                        default -> throw new IOException("unsupported bits " + bits);
                    };
                }
                return new Wav(sr, out);
            }
            pos = body + size + (size & 1);
        }
        throw new IOException("no data chunk: " + p);
    }

    // ========================================================================
    //  SFX building blocks (mono, operate on double[] buffers)
    // ========================================================================
    static double[] buf(double sec) { return new double[ns(sec)]; }
    /** 3 ms raised-cosine fade at the end of a voice of {@code full} samples. */
    static double endFade(int i, int full) { int f = 132, rem = full - 1 - i; return rem >= f ? 1.0 : (rem <= 0 ? 0.0 : 0.5 - 0.5 * Math.cos(Math.PI * rem / f)); }

    /** Adds a band-limited oscillator voice with time-varying frequency and amplitude. */
    static void osc(double[] out, double t0, double len, int wave, DoubleUnaryOperator freq, DoubleUnaryOperator amp) {
        int s0 = ns(t0), n = Math.min(ns(len), out.length - s0);
        Osc o = new Osc(wave, 0); int full = ns(len);
        for (int i = 0; i < n; i++) { double t = tt(i); out[s0 + i] += o.next(freq.applyAsDouble(t)) * amp.applyAsDouble(t) * endFade(i, full); }
    }
    /** Adds filtered (white or pink) noise; mode LP/HP/BP; cutoff may vary over time. */
    static void noise(double[] out, double t0, double len, int mode, DoubleUnaryOperator fc, double q, DoubleUnaryOperator amp, boolean pink, Rng r) {
        int s0 = ns(t0), n = Math.min(ns(len), out.length - s0), full = ns(len);
        SVF f = new SVF(); Pink p = new Pink();
        for (int i = 0; i < n; i++) {
            double t = tt(i), w = r.bi();
            if (pink) w = p.next(w) * 2.5;
            f.set(fc.applyAsDouble(t), q); f.tick(w);
            double v = mode == LP ? f.low : mode == HP ? f.high : f.band * f.k;
            out[s0 + i] += v * amp.applyAsDouble(t) * endFade(i, full);
        }
    }
    /** Short resonant noise burst; level roughly independent of band-width. */
    static void grain(double[] out, double t0, double fc, double q, double tau, double amp, Rng r) {
        double norm = Math.min(6, Math.sqrt(q * SR * 0.5 / fc) * 0.4);
        noise(out, t0, tau * 7 + 0.001, BP, t -> fc, q, t -> amp * norm * (t < 0.0003 ? t / 0.0003 : Math.exp(-t / tau)), false, r);
    }
    /** Exponentially decaying sine partial. */
    static void partial(double[] out, double t0, double f, double amp, double tau, double attack) {
        osc(out, t0, Math.min(tau * 8, 4.0), SINE, t -> f, t -> amp * ramp(t, attack) * Math.exp(-t / tau));
    }
    /** Two-operator FM voice. */
    static void fm(double[] out, double t0, double len, double f, double ratio, DoubleUnaryOperator index, DoubleUnaryOperator amp) {
        int s0 = ns(t0), n = Math.min(ns(len), out.length - s0), full = ns(len);
        double pc = 0, pm = 0;
        for (int i = 0; i < n; i++) {
            double t = tt(i);
            out[s0 + i] += Math.sin(TAU * pc + index.applyAsDouble(t) * Math.sin(TAU * pm)) * amp.applyAsDouble(t) * endFade(i, full);
            pc = frac(pc + f / SR); pm = frac(pm + f * ratio / SR);
        }
    }
    static void bellNote(double[] out, double t0, double f, double amp, double tau, double ratio, double idx) {
        fm(out, t0, tau * 6, f, ratio, t -> idx * Math.exp(-t / (tau * 0.3)) + idx * 0.12, t -> amp * ramp(t, 0.0008) * Math.exp(-t / tau));
    }
    static void addInto(double[] out, double[] src, double t0, double gain) {
        int s0 = ns(t0);
        for (int i = 0; i < src.length && s0 + i < out.length; i++) if (s0 + i >= 0) out[s0 + i] += src[i] * gain;
    }
    static void addStereoAsMono(double[] out, double[][] src, double t0, double gain) {
        if (src.length == 1) { addInto(out, src[0], t0, gain); return; }
        int s0 = ns(t0);
        for (int i = 0; i < src[0].length && s0 + i < out.length; i++) if (s0 + i >= 0) out[s0 + i] += 0.5 * (src[0][i] + src[1][i]) * gain;
    }
    static void saturate(double[] x, double drive) { for (int i = 0; i < x.length; i++) x[i] = sat(x[i], drive); }
    static void fadeOut(double[] x, double sec) {
        int n = Math.min(x.length, ns(sec));
        for (int i = 0; i < n; i++) { double g = (double) i / n; x[x.length - 1 - i] *= g * g * (3 - 2 * g); }
    }
    static double truncExp(Rng r, double scale, double max) { return -scale * Math.log(1 - r.u() * (1 - Math.exp(-max / scale))); }

    /** Mono reverb for SFX: dry + wet (output length unchanged unless tail > 0). */
    static double[] reverbMono(double[] x, double room, double damp, double wet, double tail) {
        int n = x.length + ns(tail);
        double[] y = new double[n];
        Freeverb fv = new Freeverb(room, damp, 1.0, 0.008, 200, 8000);
        for (int i = 0; i < n; i++) {
            double in = i < x.length ? x[i] : 0;
            fv.process(in, in);
            y[i] = in + wet * 0.5 * (fv.oL + fv.oR);
        }
        return y;
    }
    /** Mono feedback echo for SFX. */
    static void echo(double[] x, double time, double fb, double wet, double lpHz) {
        DelayLine d = new DelayLine(ns(time) + 8); SVF lp = new SVF(); double ds = time * SR;
        for (int i = 0; i < x.length; i++) {
            double y = d.tap(ds);
            d.push(lp.lp(x[i] + y * fb, lpHz, 0.707));
            x[i] += y * wet;
        }
    }

    // ========================================================================
    //  Drum synthesis (shared by SFX and music)
    // ========================================================================
    static double[] kick(Rng r, double f0, double f1, double ptau, double decay, double click, double drive) {
        int n = ns(Math.min(0.9, decay * 4.5 + 0.03)); double[] y = new double[n];
        double ph = 0; SVF hc = new SVF();
        for (int i = 0; i < n; i++) {
            double t = tt(i), f = f1 + (f0 - f1) * Math.exp(-t / ptau);
            ph = frac(ph + f / SR);
            double body = Math.sin(TAU * ph) * ramp(t, 0.0006) * Math.exp(-t / decay);
            double cl = hc.hp(r.bi(), 3000, 0.7) * Math.exp(-t / 0.0015) * click * 0.6;
            y[i] = sat(body, drive) + cl;
        }
        fadeOut(y, 0.01);
        return y;
    }
    static double[] snare(Rng r, double tone, double ndecay, double snap, double lpHz) {
        int n = ns(Math.min(0.7, ndecay * 5 + 0.03)); double[] y = new double[n];
        double p1 = 0, p2 = 0; SVF hp = new SVF(), lp = new SVF(), sn = new SVF();
        for (int i = 0; i < n; i++) {
            double t = tt(i), f = tone * (1 + 0.45 * Math.exp(-t / 0.008));
            p1 = frac(p1 + f / SR); p2 = frac(p2 + f * 1.48 / SR);
            double body = 0.55 * Math.sin(TAU * p1) * Math.exp(-t / 0.055) + 0.25 * Math.sin(TAU * p2) * Math.exp(-t / 0.035);
            double w = r.bi();
            double nz = lp.lp(hp.hp(w, 1300, 0.7), lpHz, 0.7) * 0.85 * Math.exp(-t / ndecay);
            double sp = sn.bp(w, 4500, 0.9) * snap * Math.exp(-t / 0.004);
            y[i] = sat((body + nz + sp) * ramp(t, 0.0004) * 1.2, 1.2);
        }
        fadeOut(y, 0.01);
        return y;
    }
    static double[] clap(Rng r, double tail) {
        int n = ns(0.035 + tail * 5); double[] y = new double[n];
        SVF bp = new SVF(), hp = new SVF();
        double[] offs = {0, 0.0105, 0.0215};
        for (int i = 0; i < n; i++) {
            double t = tt(i), env = 0;
            for (double o : offs) if (t >= o) env = Math.max(env, Math.exp(-(t - o) / 0.0032));
            if (t >= 0.031) env = Math.max(env, Math.exp(-(t - 0.031) / tail));
            double w = r.bi();
            y[i] = (bp.bp(w, 1250, 1.1) + 0.35 * hp.hp(w, 3200, 0.7)) * env * ramp(t, 0.0003) * 1.3;
        }
        fadeOut(y, 0.01);
        return y;
    }
    static double[] hat(Rng r, double decay, double tone) {
        int n = ns(Math.min(1.2, decay * 6 + 0.012)); double[] y = new double[n];
        double[] fr = {205.3, 304.4, 369.6, 522.7, 540.0, 800.0};
        Osc[] o = new Osc[6]; for (int k = 0; k < 6; k++) o[k] = new Osc(SQUARE, r.u());
        SVF bp = new SVF(), hp = new SVF(), lp = new SVF();
        for (int i = 0; i < n; i++) {
            double t = tt(i), m = 0;
            for (int k = 0; k < 6; k++) m += o[k].next(fr[k] * 1.6 * tone);
            double v = 0.55 * m / 6 + 0.45 * r.bi();
            v = lp.lp(hp.hp(bp.bp(v, 8800 * tone, 0.8), 6000, 0.7), 13000, 0.7);
            y[i] = v * ramp(t, 0.0004) * Math.exp(-t / decay) * 2.5;
        }
        fadeOut(y, 0.006);
        return y;
    }
    static double[] shaker(Rng r) {
        int n = ns(0.09); double[] y = new double[n]; SVF bp = new SVF(), hp = new SVF();
        for (int i = 0; i < n; i++) {
            double t = tt(i), env = t < 0.008 ? (t / 0.008) * (t / 0.008) : Math.exp(-(t - 0.008) / 0.024);
            y[i] = hp.hp(bp.bp(r.bi(), 7600, 1.3), 4000, 0.7) * env * 1.6;
        }
        fadeOut(y, 0.01);
        return y;
    }
    static double[] tom(Rng r, double f) {
        int n = ns(0.45); double[] y = new double[n]; double ph = 0; SVF bp = new SVF();
        for (int i = 0; i < n; i++) {
            double t = tt(i), ff = f * (1 + 0.55 * Math.exp(-t / 0.025));
            ph = frac(ph + ff / SR);
            double v = Math.sin(TAU * ph) * Math.exp(-t / 0.17) + 0.25 * bp.bp(r.bi(), f * 2.6, 1.5) * Math.exp(-t / 0.02);
            y[i] = sat(v * ramp(t, 0.0006) * 1.3, 1.3);
        }
        fadeOut(y, 0.02);
        return y;
    }
    static double[] crash(Rng r, double decay) {
        int n = ns(Math.min(3.0, decay * 4.5)); double[] y = new double[n];
        double[] fr = {205.3, 304.4, 369.6, 522.7, 540.0, 800.0};
        Osc[] o = new Osc[6]; for (int k = 0; k < 6; k++) o[k] = new Osc(SQUARE, r.u());
        SVF hp = new SVF(), lp = new SVF();
        for (int i = 0; i < n; i++) {
            double t = tt(i), m = 0;
            for (int k = 0; k < 6; k++) m += o[k].next(fr[k] * 2.3);
            double v = 0.4 * m / 6 + 0.6 * r.bi();
            v = lp.lp(hp.hp(v, 3500, 0.7), 11000, 0.7);
            y[i] = v * ramp(t, 0.001) * (0.55 * Math.exp(-t / 0.05) + 0.45 * Math.exp(-t / decay)) * 1.6;
        }
        fadeOut(y, 0.2);
        return y;
    }
    static double[] riser(Rng r, double len) {
        int n = ns(len); double[] y = new double[n]; SVF bp = new SVF(); double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = tt(i), p = t / len;
            double fc = 300 * Math.pow(9000 / 300.0, Math.pow(p, 1.4));
            double v = bp.bp(r.bi(), fc, 2.0) * p * p;
            ph = frac(ph + (180 * Math.pow(6, p)) / SR);
            v += 0.18 * Math.sin(TAU * ph) * p * p * p;
            y[i] = v * (p > 0.97 ? (1 - p) / 0.03 : 1);
        }
        return y;
    }
    static double[] impact(Rng r) {
        double[] y = kick(r, 95, 30, 0.06, 0.45, 0.4, 1.6);
        double[] z = new double[Math.max(y.length, ns(1.2))];
        addInto(z, y, 0, 0.9);
        noise(z, 0, 1.2, LP, t -> 150 + 3000 * Math.exp(-t / 0.15), 0.8, t -> 0.5 * ramp(t, 0.002) * Math.exp(-t / 0.3), true, r);
        fadeOut(z, 0.2);
        return z;
    }

    // ========================================================================
    //  Instruments (music). Each returns {mono} or {L, R} including release tail.
    // ========================================================================
    interface Instr { double[][] note(double f, double dur, double vel, Rng r); }
    /** Layers two mono instruments into one voice. */
    static Instr layer(Instr a, double ga, Instr b, double gb) {
        return (f, d, v, r) -> {
            double[] x = a.note(f, d, v, r)[0], y = b.note(f, d, v, r)[0];
            double[] o = new double[Math.max(x.length, y.length)];
            for (int i = 0; i < x.length; i++) o[i] += x[i] * ga;
            for (int i = 0; i < y.length; i++) o[i] += y[i] * gb;
            return new double[][]{o};
        };
    }

    /** FM electric piano: 1:1 operator pair + fast-decaying high-ratio "tine", stereo tremolo. */
    static double[][] ePiano(double f, double dur, double vel, Rng r) {
        double rel = 0.3; int n = ns(dur + rel + 0.02);
        double[] L = new double[n], R = new double[n];
        double decay = 1.6 * Math.pow(262.0 / f, 0.4), bright = 0.45 + 0.65 * vel;
        double p1 = 0, p2 = r.u(), p3 = r.u(), trem = r.u(), det = 1 + r.range(-0.0008, 0.0008);
        SVF lp = new SVF();
        for (int i = 0; i < n; i++) {
            double t = tt(i);
            double env = ramp(t, 0.0015) * (0.3 * Math.exp(-t / 0.15) + 0.7 * Math.exp(-t / decay)) * relEnv(t, dur, rel);
            double idx = bright * (1.3 * Math.exp(-t / 0.3) + 0.75);
            double tine = bright * 1.2 * Math.exp(-t / 0.014);
            double v = Math.sin(TAU * p1 + Math.sin(TAU * p2) * idx + Math.sin(TAU * p3) * tine * 0.6);
            p1 = frac(p1 + f * det / SR); p2 = frac(p2 + f / SR); p3 = frac(p3 + f * 13.98 / SR);
            v = lp.lp(sat(v * (1.0 + 0.8 * vel) + 0.12, 1.6) - sat(0.12, 1.6), 6000, 0.7) * env * (0.5 + 0.5 * vel) / (1.0 + 0.4 * vel);
            double pan = 0.5 + 0.17 * Math.sin(TAU * trem); trem = frac(trem + 3.7 / SR);
            L[i] = v * Math.cos(pan * Math.PI / 2) * 1.414; R[i] = v * Math.sin(pan * Math.PI / 2) * 1.414;
        }
        return new double[][]{L, R};
    }
    /** Marimba / kalimba-like mallet (partials 1 : 3.98 : 9.15). */
    static double[][] mallet(double f, double dur, double vel, Rng r) {
        double tau0 = 0.36 * Math.pow(440 / f, 0.35); int n = ns(Math.min(1.8, tau0 * 6 + 0.05)); double[] y = new double[n];
        double[] ratio = {1, 3.98, 9.15}, amp = {1, 0.26 * (0.5 + vel), 0.07 * vel}, tau = {tau0, 0.055, 0.014};
        for (int k = 0; k < 3; k++) {
            if (f * ratio[k] > 15000) continue;
            double fr = f * ratio[k], a = amp[k], tk = tau[k];
            osc(y, 0, n / (double) SR, SINE, t -> fr, t -> a * ramp(t, 0.0008) * Math.exp(-t / tk) * relEnv(t, dur + 0.12, 0.15));
        }
        noise(y, 0, 0.01, LP, t -> 3000, 0.7, t -> 0.12 * Math.exp(-t / 0.0015), false, r);
        for (int i = 0; i < n; i++) y[i] *= vel;
        return new double[][]{y};
    }
    /** Subtractive pluck: saw + square through an enveloped low-pass. */
    static double[][] pluck(double f, double dur, double vel, Rng r, double bright, double decay) {
        double rel = 0.06; int n = ns(Math.min(dur, decay * 4) + rel); double[] y = new double[n];
        Osc a = new Osc(SAW, r.u()), b = new Osc(SQUARE, r.u()); SVF lp = new SVF();
        for (int i = 0; i < n; i++) {
            double t = tt(i);
            double v = 0.6 * a.next(f) + 0.4 * b.next(f * 1.004);
            double fc = f * 1.3 + 400 + bright * vel * 6500 * Math.exp(-t / 0.055);
            y[i] = lp.lp(v, fc, 1.0) * ramp(t, 0.001) * Math.exp(-t / decay) * relEnv(t, dur, rel) * vel;
        }
        return new double[][]{y};
    }
    static double[][] arpPluck(double f, double dur, double vel, Rng r, double bright) {
        double rel = 0.05; int n = ns(Math.min(dur, 0.5) + rel); double[] y = new double[n];
        Osc a = new Osc(SQUARE, r.u()), b = new Osc(SAW, r.u()); a.pw = 0.32; SVF lp = new SVF();
        for (int i = 0; i < n; i++) {
            double t = tt(i);
            double v = 0.55 * a.next(f) + 0.45 * b.next(f * 1.003);
            double fc = f + 300 + bright * vel * 5200 * Math.exp(-t / 0.05);
            y[i] = lp.lp(v, fc, 1.4) * ramp(t, 0.001) * Math.exp(-t / 0.14) * relEnv(t, dur, rel) * vel;
        }
        return new double[][]{y};
    }
    /** Bright pluck-lead (saw + square, enveloped filter) used for the catchy hooks. */
    static double[][] popLead(double f, double dur, double vel, Rng r) {
        double rel = 0.12; int n = ns(dur + rel); double[] y = new double[n];
        Osc a = new Osc(SAW, r.u()), b = new Osc(SQUARE, r.u()), s = new Osc(SINE, 0); SVF lp = new SVF();
        double vph = r.u();
        for (int i = 0; i < n; i++) {
            double t = tt(i);
            double vib = 1 + 0.004 * smoothstep(0.15, 0.4, t) * Math.sin(TAU * vph); vph = frac(vph + 5.5 / SR);
            double ff = f * vib;
            double v = 0.5 * a.next(ff * 0.998) + 0.35 * b.next(ff * 1.002);
            double fc = ff * 2 + 600 + 4200 * vel * Math.exp(-t / 0.11);
            v = lp.lp(v, fc, 0.9) + 0.35 * s.next(ff);
            y[i] = v * adsr(t, dur, 0.003, 0.3, 0.45, rel) * vel;
        }
        return new double[][]{y};
    }
    static double[][] lead(double f, double dur, double vel, Rng r, int wave, double cutoff, double vibCents, double air) {
        double rel = 0.16; int n = ns(dur + rel); double[] y = new double[n];
        Osc a = new Osc(wave, r.u()), b = new Osc(wave, r.u()); SVF lp = new SVF(), br = new SVF();
        double vph = r.u();
        for (int i = 0; i < n; i++) {
            double t = tt(i);
            double vib = vibCents * smoothstep(0.12, 0.45, t) * Math.sin(TAU * vph); vph = frac(vph + 5.2 / SR);
            double ff = f * Math.pow(2, vib / 1200.0);
            double v = 0.55 * a.next(ff * 0.99654) + 0.45 * b.next(ff * 1.00347);
            double fc = Math.min(16000, cutoff * (0.75 + 0.25 * vel) * (1 + 0.7 * Math.exp(-t / 0.09)));
            v = lp.lp(v, fc, 0.8);
            if (air > 0) v += air * br.bp(r.bi(), ff * 2, 3) * 1.5;
            y[i] = v * adsr(t, dur, 0.012, 0.25, 0.78, rel) * vel;
        }
        return new double[][]{y};
    }
    /** Brass-like synth: 3 detuned saws, pitch scoop, opening filter envelope, delayed vibrato. */
    static double[][] brass(double f, double dur, double vel, Rng r) {
        double rel = 0.18; int n = ns(dur + rel); double[] y = new double[n];
        Osc[] o = {new Osc(SAW, r.u()), new Osc(SAW, r.u()), new Osc(SAW, r.u())};
        double[] det = {0.99539, 1.0, 1.00463};
        SVF lp = new SVF(); double vph = r.u();
        for (int i = 0; i < n; i++) {
            double t = tt(i);
            double cents = -35 * Math.exp(-t / 0.03) + 10 * smoothstep(0.2, 0.5, t) * Math.sin(TAU * vph);
            vph = frac(vph + 5.0 / SR);
            double ff = f * Math.pow(2, cents / 1200.0), v = 0;
            for (int k = 0; k < 3; k++) v += o[k].next(ff * det[k]);
            double fe = t < 0.04 ? t / 0.04 : 0.55 + 0.45 * Math.exp(-(t - 0.04) / 0.25);
            v = lp.lp(v / 3, ff * (1.2 + 4.5 * vel * fe) + 300, 0.7);
            y[i] = v * adsr(t, dur, 0.02, 0.3, 0.8, rel) * vel;
        }
        return new double[][]{y};
    }
    /** Detuned super-saw (pads, strings, stabs). Stereo spread across voices. */
    static double[][] supersaw(double f, double dur, double vel, Rng r, double att, double rel, double cutoff, int voices, double detCents, double hpHz, double cutEnv) {
        int n = ns(dur + rel); double[] L = new double[n], R = new double[n];
        Osc[] o = new Osc[voices]; double[] fr = new double[voices], gl = new double[voices], gr = new double[voices];
        for (int v = 0; v < voices; v++) {
            double off = voices == 1 ? 0 : (v / (voices - 1.0) - 0.5) * 2;
            o[v] = new Osc(SAW, r.u());
            fr[v] = f * Math.pow(2, (off * detCents + r.range(-1.5, 1.5)) / 1200.0);
            double pan = 0.5 + off * 0.42;
            gl[v] = Math.cos(pan * Math.PI / 2); gr[v] = Math.sin(pan * Math.PI / 2);
        }
        SVF ll = new SVF(), lr = new SVF(); Biquad hl = Biquad.make("hp", hpHz, 0.707, 0), hr = Biquad.make("hp", hpHz, 0.707, 0);
        double lph = r.u(), norm = 1.6 / Math.sqrt(voices);
        for (int i = 0; i < n; i++) {
            double t = tt(i), sl = 0, sr = 0;
            for (int v = 0; v < voices; v++) { double s = o[v].next(fr[v]); sl += s * gl[v]; sr += s * gr[v]; }
            double fc = cutoff * (1 + cutEnv * Math.exp(-t / 0.07)) * (1 + 0.12 * Math.sin(TAU * (0.21 * t + lph)));
            double env = adsr(t, dur, att, 0.6, 0.85, rel) * vel * norm;
            L[i] = hl.p(ll.lp(sl, fc, 0.75)) * env;
            R[i] = hr.p(lr.lp(sr, fc, 0.75)) * env;
        }
        return new double[][]{L, R};
    }
    static double[][] warmBass(double f, double dur, double vel, Rng r) {
        double rel = 0.08; int n = ns(dur + rel); double[] y = new double[n]; double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = tt(i);
            double env = ramp(t, 0.008) * (0.75 + 0.25 * Math.exp(-t / 0.15)) * relEnv(t, dur, rel);
            double v = Math.sin(TAU * ph) + 0.28 * Math.sin(2 * TAU * ph) + 0.08 * Math.sin(3 * TAU * ph);
            ph = frac(ph + f / SR);
            y[i] = sat(v * 0.9, 1.3) * env * vel;
        }
        return new double[][]{y};
    }
    static double[][] subBass(double f, double dur, double vel, Rng r) {
        double rel = 0.05; int n = ns(dur + rel); double[] y = new double[n]; double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = tt(i);
            double v = Math.sin(TAU * ph) + 0.22 * Math.sin(2 * TAU * ph) + 0.1 * Math.sin(3 * TAU * ph);
            ph = frac(ph + f / SR);
            y[i] = sat(v, 1.3) * ramp(t, 0.005) * relEnv(t, dur, rel) * vel;
        }
        return new double[][]{y};
    }
    static double[][] bouncyBass(double f, double dur, double vel, Rng r) {
        double rel = 0.04; int n = ns(dur + rel); double[] y = new double[n];
        Osc a = new Osc(SAW, r.u()), b = new Osc(SQUARE, r.u()), s = new Osc(SINE, 0); SVF lp = new SVF();
        for (int i = 0; i < n; i++) {
            double t = tt(i);
            double v = 0.6 * a.next(f) + 0.4 * b.next(f);
            double fc = f * 1.5 + 150 + 2200 * vel * Math.exp(-t / 0.065);
            v = lp.lp(v, fc, 1.4) + 0.75 * s.next(f);
            y[i] = sat(v * adsr(t, dur, 0.002, 0.2, 0.75, rel) * vel, 1.2);
        }
        return new double[][]{y};
    }
    static double[][] pulseBass(double f, double dur, double vel, Rng r, double cutoff) {
        double rel = 0.03; int n = ns(dur + rel); double[] y = new double[n];
        Osc a = new Osc(SAW, r.u()), b = new Osc(SAW, r.u()), s = new Osc(SINE, 0); SVF lp = new SVF();
        for (int i = 0; i < n; i++) {
            double t = tt(i);
            double v = 0.55 * a.next(f) + 0.45 * b.next(f * 1.006);
            double fc = f + cutoff * (0.35 + 0.65 * Math.exp(-t / 0.06));
            v = lp.lp(v, fc, 1.15) + 0.6 * s.next(f);
            y[i] = sat(v * adsr(t, dur, 0.0015, 0.1, 0.8, rel) * vel, 1.4);
        }
        return new double[][]{y};
    }
    static double[][] bell(double f, double dur, double vel, Rng r) {
        double tau = 0.9 * Math.pow(880 / f, 0.4); int n = ns(Math.min(3.0, tau * 5)); double[] y = new double[n];
        double pc = 0, pm = 0, pg = 0;
        for (int i = 0; i < n; i++) {
            double t = tt(i);
            double idx = 1.6 * vel * Math.exp(-t / 0.18) + 0.25;
            double v = Math.sin(TAU * pc + idx * Math.sin(TAU * pm)) + 0.12 * Math.sin(TAU * pg) * Math.exp(-t / (tau * 0.3));
            pc = frac(pc + f / SR); pm = frac(pm + 2 * f / SR); pg = frac(pg + 2.76 * f / SR);
            y[i] = v * ramp(t, 0.001) * Math.exp(-t / tau) * relEnv(t, dur + 0.5, 0.4) * vel;
        }
        return new double[][]{y};
    }

    // ========================================================================
    //  Music engine: stems, wrap-around note placement, periodic mixing
    // ========================================================================
    static final class Stem {
        final String name; double[] L, R;
        double level = -14, rev = 0, dly = 0, duck = 0, hp = 0, lp = 0, chorus = 0;
        Stem(String name, int n) { this.name = name; L = new double[n]; R = new double[n]; }
        boolean silent() { for (int i = 0; i < L.length; i += 7) if (L[i] != 0 || R[i] != 0) return false; return peak(L, R) == 0; }
    }
    record Seg(int bar, double beat, double len, String name) {}
    record Ev(double beat, double dur, int midi, double vel) {}

    static final class Song {
        final String name; final double bpm, spb; final int bars, N; final Rng rng;
        final LinkedHashMap<String, Stem> stems = new LinkedHashMap<>();
        final List<Double> kickTimes = new ArrayList<>();
        double swing = 0.5, humanize = 0.004;
        double revRoom = 0.8, revDamp = 0.5, revRel = -14, revPre = 0.02;
        double dlyBeats = 0.75, dlyFb = 0.35, dlyRel = -18, dlyLp = 4500;
        double duckRel = 0.11, masterLp = 0, lowShelfDb = 0, highShelfDb = 0, presenceDb = 0;
        final StringBuilder log = new StringBuilder();

        Song(String name, double bpm, int bars, long seed) {
            this.name = name; this.bpm = bpm; this.bars = bars; spb = 60.0 / bpm; rng = new Rng(seed);
            N = (int) Math.round(bars * 4 * spb * SR);
        }
        Stem stem(String n) { return stems.computeIfAbsent(n, k -> new Stem(k, N)); }
        double sec(double beat) { return beat * spb; }
        double swingBeat(double beat) {
            double q = beat * 4; long qi = Math.round(q);
            if (Math.abs(q - qi) < 1e-6 && (qi & 1) == 1) return beat + (swing - 0.5) * 0.5;
            return beat;
        }
        /** Adds audio at time t (seconds) with wrap-around so tails continue at the loop start. */
        void place(Stem s, double t, double[][] a, double pan, double gain) {
            int start = (int) Math.round(t * SR);
            if (a.length == 1) {
                double gl = Math.cos((pan + 1) * Math.PI / 4) * 1.4142 * gain, gr = Math.sin((pan + 1) * Math.PI / 4) * 1.4142 * gain;
                double[] x = a[0];
                for (int i = 0; i < x.length; i++) { int j = Math.floorMod(start + i, N); s.L[j] += x[i] * gl; s.R[j] += x[i] * gr; }
            } else {
                double gl = gain * Math.min(1, 1 - pan), gr = gain * Math.min(1, 1 + pan);
                for (int i = 0; i < a[0].length; i++) { int j = Math.floorMod(start + i, N); s.L[j] += a[0][i] * gl; s.R[j] += a[1][i] * gr; }
            }
        }
        void note(Stem s, Instr ins, double beat, double durBeats, int midi, double vel, double pan, double offsetSec) {
            double t = sec(swingBeat(beat)) + offsetSec + rng.gauss() * humanize;
            double v = clamp(vel * (1 + rng.gauss() * 0.05), 0.05, 1.25);
            place(s, t, ins.note(mtof(midi), sec(durBeats), v, new Rng(rng.seed())), pan, 1.0);
        }
        void chord(Stem s, Instr ins, double beat, double durBeats, int[] v, double vel, double strum, double spread) {
            for (int k = 0; k < v.length; k++) {
                double pan = v.length == 1 ? 0 : -spread + 2 * spread * k / (v.length - 1.0);
                note(s, ins, beat, durBeats, v[k], vel * (0.9 + 0.1 * rng.u()), pan, k * strum);
            }
        }
        void melody(Stem s, Instr ins, int bar, String m, double vel, double pan, double legato) {
            double b = bar * 4, total = 0;
            for (String tok : m.trim().split("\\s+")) {
                String[] p = tok.split(":"); double d = Double.parseDouble(p[1]);
                if (!p[0].equals("r")) {
                    double v = p.length > 2 ? Double.parseDouble(p[2]) : 1.0;
                    note(s, ins, b, d * legato, nn(p[0]), vel * v, pan, 0);
                }
                b += d; total += d;
            }
            if (Math.abs(total - 4) > 1e-6 && Math.abs(total - 8) > 1e-6) System.err.println("WARN " + name + " bar " + bar + " melody sums to " + total);
        }
        void melodies(Stem s, Instr ins, int firstBar, String[] bars, double vel, double pan, double legato) {
            for (int i = 0; i < bars.length; i++) if (bars[i] != null && !bars[i].isBlank()) melody(s, ins, firstBar + i, bars[i], vel, pan, legato);
        }
        double[][][] vars(int count, Function<Rng, double[]> gen) {
            double[][][] v = new double[count][][];
            for (int i = 0; i < count; i++) v[i] = new double[][]{gen.apply(new Rng(rng.seed()))};
            return v;
        }
        /** Drum pattern: one char per step ('X' 1.0, 'x' .85, 'o' .6, 'g' .32, '.' rest). 16 or 32 steps per bar. */
        void pat(Stem s, double[][][] var, int bar, String p, double vel, double pan, boolean isKick) {
            double step = 4.0 / p.length();
            for (int k = 0; k < p.length(); k++) {
                double v = switch (p.charAt(k)) { case 'X' -> 1.0; case 'x' -> 0.85; case 'o' -> 0.6; case 'g' -> 0.32; default -> 0; };
                if (v == 0) continue;
                double beat = bar * 4 + k * step;
                double t = sec(p.length() == 16 ? swingBeat(beat) : beat) + (isKick ? 0 : rng.gauss() * humanize * 0.5);
                place(s, t, var[rng.i(var.length)], pan + rng.gauss() * 0.03, vel * v * (1 + rng.gauss() * 0.04));
                if (isKick) kickTimes.add(t);
            }
        }
        void hitAt(Stem s, double[][] a, double beat, double vel, double pan) { place(s, sec(beat), a, pan, vel); }
        List<Seg> segs(String[] prog) {
            List<Seg> l = new ArrayList<>();
            for (int b = 0; b < prog.length; b++) {
                String[] parts = prog[b].split("\\|"); double len = 4.0 / parts.length;
                for (int j = 0; j < parts.length; j++) l.add(new Seg(b, b * 4 + j * len, len, parts[j].trim()));
            }
            if (prog.length != bars) throw new IllegalStateException(name + ": progression has " + prog.length + " bars, expected " + bars);
            return l;
        }
        /** Lo-fi vinyl bed: periodic pink hiss + sparse crackles. */
        void vinyl(Stem s, double crackPerSec) {
            double[] w = new double[N]; for (int i = 0; i < N; i++) w[i] = rng.bi();
            Pink p = new Pink(); double[] h = new double[N];
            for (int k = -ns(3); k < N; k++) { double y = p.next(w[Math.floorMod(k, N)]); if (k >= 0) h[k] = y; }
            filterPeriodic(h, Biquad.make("lp", 4500, 0.707, 0), 1); filterPeriodic(h, Biquad.make("hp", 500, 0.707, 0), 1);
            int count = (int) (crackPerSec * N / SR);
            for (int c = 0; c < count; c++) {
                int pos = rng.i(N); double a = 2.5 * Math.pow(rng.u(), 3) + 0.15;
                double[] shape = {a, -0.7 * a, 0.35 * a, -0.12 * a};
                for (int i = 0; i < shape.length; i++) h[(pos + i) % N] += shape[i];
            }
            for (int i = 0; i < N; i++) { s.L[i] += h[i]; s.R[i] += h[i]; }
        }

        double[][] render() {
            // Side-chain shape from kick times (wraps around the loop).
            double[] duck = new double[N];
            for (double tk : kickTimes) {
                int s0 = (int) Math.round(tk * SR), len = ns(0.45);
                for (int i = 0; i < len; i++) {
                    double x = tt(i), v = x < 0.004 ? x / 0.004 : Math.exp(-(x - 0.004) / duckRel);
                    int j = Math.floorMod(s0 + i, N); if (v > duck[j]) duck[j] = v;
                }
            }
            double[] mL = new double[N], mR = new double[N], rvL = new double[N], rvR = new double[N], dlL = new double[N], dlR = new double[N];
            for (Stem st : stems.values()) {
                if (st.silent()) continue;
                if (st.hp > 0) { filterPeriodic(st.L, Biquad.make("hp", st.hp, 0.707, 0), 1); filterPeriodic(st.R, Biquad.make("hp", st.hp, 0.707, 0), 1); }
                if (st.lp > 0) { filterPeriodic(st.L, Biquad.make("lp", st.lp, 0.707, 0), 1); filterPeriodic(st.R, Biquad.make("lp", st.lp, 0.707, 0), 1); }
                if (st.chorus > 0) { double[][] o = runPeriodic(st.L, st.R, new Chorus(0.55, 11, 3.5, st.chorus), 0.3); st.L = o[0]; st.R = o[1]; }
                double loud = loudness(st.L, st.R), g = dbToGain(st.level - loud);
                log.append(String.format("    stem %-8s raw %7.2f LU -> target %6.1f%n", st.name, loud, st.level));
                for (int i = 0; i < N; i++) {
                    double gg = g * (1 - st.duck * duck[i]), l = st.L[i] * gg, r = st.R[i] * gg;
                    mL[i] += l; mR[i] += r; rvL[i] += l * st.rev; rvR[i] += r * st.rev; dlL[i] += l * st.dly; dlR[i] += r * st.dly;
                }
            }
            double dry = loudness(mL, mR);
            if (peak(dlL, dlR) > 0) {
                double[][] d = runPeriodic(dlL, dlR, new PingPong(sec(dlyBeats), dlyFb, dlyLp), 12);
                double g = dbToGain(dry + dlyRel - loudness(d[0], d[1]));
                for (int i = 0; i < N; i++) { mL[i] += d[0][i] * g; mR[i] += d[1][i] * g; rvL[i] += 0.3 * d[0][i] * g; rvR[i] += 0.3 * d[1][i] * g; }
            }
            if (peak(rvL, rvR) > 0) {
                double[][] w = runPeriodic(rvL, rvR, new Freeverb(revRoom, revDamp, 1.0, revPre, 250, 7500), 12);
                double g = dbToGain(dry + revRel - loudness(w[0], w[1]));
                for (int i = 0; i < N; i++) { mL[i] += w[0][i] * g; mR[i] += w[1][i] * g; }
            }
            // Master: rumble filter, optional warmth LP, glue compression, loudness normalise, limiter.
            filterPeriodic(mL, Biquad.make("hp", 28, 0.707, 0), 1); filterPeriodic(mR, Biquad.make("hp", 28, 0.707, 0), 1);
            if (lowShelfDb != 0) { filterPeriodic(mL, Biquad.make("lowshelf", 75, 0.707, lowShelfDb), 1); filterPeriodic(mR, Biquad.make("lowshelf", 75, 0.707, lowShelfDb), 1); }
            if (presenceDb != 0) { filterPeriodic(mL, Biquad.make("peak", 1800, 0.7, presenceDb), 1); filterPeriodic(mR, Biquad.make("peak", 1800, 0.7, presenceDb), 1); }
            if (highShelfDb != 0) { filterPeriodic(mL, Biquad.make("highshelf", 7500, 0.707, highShelfDb), 1); filterPeriodic(mR, Biquad.make("highshelf", 7500, 0.707, highShelfDb), 1); }
            if (masterLp > 0) { filterPeriodic(mL, Biquad.make("lp", masterLp, 0.707, 0), 1); filterPeriodic(mR, Biquad.make("lp", masterLp, 0.707, 0), 1); }
            double rms = rmsDb(mL, mR);
            Compressor c = new Compressor(rms + 4, 2.0, 6, 0.012, 0.18, 0.01, 0);
            int warm = ns(3);
            for (int k = -warm; k < 0; k++) { int j = Math.floorMod(k, N); c.next(mL[j], mR[j]); }
            for (int i = 0; i < N; i++) { double g = c.next(mL[i], mR[i]); mL[i] *= g; mR[i] *= g; }
            for (int it = 0; it < 6; it++) {
                double cur = rmsDb(mL, mR);
                if (Math.abs(cur - MUSIC_RMS_DB) < 0.05 && peak(mL, mR) <= dbToGain(MUSIC_CEILING_DB) + 1e-9) break;
                double g = dbToGain(MUSIC_RMS_DB - cur);
                for (int i = 0; i < N; i++) { mL[i] *= g; mR[i] *= g; }
                limit(new double[][]{mL, mR}, MUSIC_CEILING_DB, 0.003, 0.08, true);
            }
            return new double[][]{mL, mR};
        }
    }

    // ========================================================================
    //  MUSIC 1 — music_menu: warm lo-fi groove, 90 BPM, Ab major, 24 bars
    // ========================================================================
    static Song musicMenu() {
        Song s = new Song("music_menu", 90, 24, 7101);
        s.swing = 0.58; s.humanize = 0.007;
        s.revRoom = 0.8; s.revDamp = 0.55; s.revRel = -13; s.revPre = 0.025;
        s.dlyBeats = 0.75; s.dlyFb = 0.38; s.dlyRel = -19; s.dlyLp = 3500; s.masterLp = 12500; s.duckRel = 0.12; s.presenceDb = 2.5; s.lowShelfDb = -1.5;
        Map<String, String> V = map(
            "Dbmaj9", "F3 Ab3 C4 Eb4", "Cm7", "Eb3 G3 Bb3 D4", "Fm9", "Ab3 C4 Eb4 G4", "Eb9sus", "Ab3 Bb3 Db4 F4",
            "F7b9", "A3 C4 Eb4 Gb4", "Bbm9", "Db3 F3 Ab3 C4", "Eb13", "Db3 G3 C4 F4", "Abmaj7/C", "Eb3 G3 Ab3 C4",
            "Gm7b5", "F3 Bb3 Db4", "C7b9", "E3 Bb3 Db4 G4");
        Map<String, String> B = map("Dbmaj9", "Db2", "Cm7", "C2", "Fm9", "F2", "Eb9sus", "Eb2", "F7b9", "F2", "Bbm9", "Bb1",
            "Eb13", "Eb2", "Abmaj7/C", "C2", "Gm7b5", "G1", "C7b9", "C2");
        String[] prog = {
            "Dbmaj9", "Cm7", "Fm9", "Eb9sus", "Dbmaj9", "Cm7|F7b9", "Bbm9", "Eb13",
            "Fm9", "Dbmaj9", "Abmaj7/C", "Gm7b5|C7b9", "Fm9", "Bbm9", "Dbmaj9|Cm7", "Eb9sus|Eb13",
            "Dbmaj9", "Cm7", "Fm9", "Eb9sus", "Dbmaj9", "Cm7|F7b9", "Bbm9", "Eb13"};
        Stem keys = s.stem("keys"); keys.level = -10.5; keys.rev = 0.35; keys.chorus = 0.35; keys.hp = 110; keys.duck = 0.12;
        Stem bass = s.stem("bass"); bass.level = -12; bass.lp = 900;
        Stem kick = s.stem("kick"); kick.level = -11.5;
        Stem snr = s.stem("snare"); snr.level = -15; snr.rev = 0.25;
        Stem hats = s.stem("hats"); hats.level = -21; hats.rev = 0.08; hats.lp = 10000;
        Stem shk = s.stem("shaker"); shk.level = -27; shk.rev = 0.1;
        Stem lead = s.stem("lead"); lead.level = -12.5; lead.rev = 0.4; lead.dly = 0.5;
        Stem pad = s.stem("pad"); pad.level = -21; pad.rev = 0.5; pad.hp = 220;
        Stem vin = s.stem("vinyl"); vin.level = -36;

        Instr ep = SynthStudio::ePiano, wb = SynthStudio::warmBass;
        Instr ml = layer(SynthStudio::mallet, 1.0, (f, d, v, r) -> lead(f, d, v, r, SAW, 2400, 10, 0.25), 0.4);
        Instr pd = (f, d, v, r) -> supersaw(f, d, v, r, 0.9, 1.4, 1300, 5, 11, 150, 0);
        List<Seg> segs = s.segs(prog);
        for (int i = 0; i < segs.size(); i++) {
            Seg g = segs.get(i), nx = segs.get((i + 1) % segs.size());
            int[] vc = notes(V.get(g.name())); int root = nn(B.get(g.name())), nroot = nn(B.get(nx.name()));
            int approach = Math.abs(nroot - root) <= 2 ? root + 7 : (nroot > root ? nroot - 1 : nroot + 1);
            if (g.len() >= 4) {
                s.chord(keys, ep, g.beat(), 1.7, vc, 0.78, 0.012, 0.3);
                s.chord(keys, ep, g.beat() + 2.5, 1.3, vc, 0.56, 0.009, 0.3);
                s.note(bass, wb, g.beat(), 1.4, root, 0.9, 0, 0);
                s.note(bass, wb, g.beat() + 1.75, 0.4, root, 0.5, 0, 0);
                s.note(bass, wb, g.beat() + 2.5, 0.85, root + 7, 0.72, 0, 0);
                s.note(bass, wb, g.beat() + 3.5, 0.42, approach, 0.6, 0, 0);
            } else {
                s.chord(keys, ep, g.beat(), 1.8, vc, 0.74, 0.012, 0.3);
                s.note(bass, wb, g.beat(), 1.3, root, 0.88, 0, 0);
                s.note(bass, wb, g.beat() + 1.5, 0.42, approach, 0.55, 0, 0);
            }
            if (g.bar() >= 8) { int[] up = new int[vc.length]; for (int k = 0; k < vc.length; k++) up[k] = vc[k] + 12; s.chord(pad, pd, g.beat(), g.len(), up, 0.55, 0, 0.5); }
        }
        double[][][] K = s.vars(4, r -> kick(r, 115, 48, 0.03, 0.17, 0.15, 1.2));
        double[][][] S = s.vars(4, r -> snare(r, 185, 0.11, 0.3, 7500));
        double[][][] SH = s.vars(6, SynthStudio::shaker);
        double[][][] H = s.vars(6, r -> hat(r, 0.028, 1.0));
        double[][][] OH = s.vars(2, r -> hat(r, 0.15, 1.0));
        for (int b = 0; b < 24; b++) {
            String kp = (b % 4 == 3) ? "x......x.x...x.." : "x......x..x.....";
            if (b == 15) kp = "x.......x.......";
            s.pat(kick, K, b, kp, 0.95, 0, true);
            s.pat(snr, S, b, (b % 2 == 1) ? "....x.......x..g" : "....x.......x...", 0.85, 0.03, false);
            s.pat(hats, H, b, b < 8 ? "x.o.x.o.x.o.x.o." : "x.o.x.ogx.o.x.og", 0.8, 0.22, false);
            if (b % 4 == 3) s.pat(hats, OH, b, "..............o.", 0.6, 0.22, false);
            if (b >= 8) s.pat(shk, SH, b, "g.g.g.g.g.g.g.g.", 0.8, -0.3, false);
        }
        // Sparse answers in A, full melody in B and A'.
        s.melody(lead, ml, 1, "r:2 G5:0.5 Eb5:0.5 D5:1", 0.7, 0.12, 1.0);
        s.melody(lead, ml, 3, "r:2 F5:0.5 Eb5:0.5 Db5:1", 0.7, 0.12, 1.0);
        s.melody(lead, ml, 5, "r:2 A4:0.5 C5:0.5 Eb5:1", 0.7, 0.12, 1.0);
        s.melody(lead, ml, 7, "r:2 G5:0.5 F5:0.5 C5:1", 0.7, 0.12, 1.0);
        s.melodies(lead, ml, 8, new String[]{
            "r:0.5 C5:0.5 Eb5:0.5 F5:1 Eb5:0.5 C5:1",
            "Ab4:0.5 Bb4:0.5 C5:1.5 r:1.5",
            "r:0.5 Eb5:0.5 G5:0.5 Ab5:1 G5:0.5 Eb5:1",
            "F5:1 Db5:1 E5:1.5 r:0.5",
            "F5:0.5 G5:0.5 Ab5:1 G5:0.5 F5:0.5 Eb5:1",
            "Db5:1 C5:0.5 Db5:0.5 F5:2",
            "Eb5:1 C5:1 Bb4:1 G4:1",
            "Ab4:1 Bb4:1.5 r:1.5",
            "r:0.5 F5:0.5 Eb5:0.5 C5:0.5 Eb5:1.5 r:0.5",
            "Bb4:0.5 C5:0.5 D5:1 Eb5:1 r:1",
            "G5:1 F5:0.5 Eb5:0.5 C5:1.5 r:0.5",
            "Bb4:0.5 C5:0.5 Db5:1 C5:0.5 Bb4:1.5",
            "Ab5:1 F5:1 Eb5:1 C5:1",
            "D5:0.5 Eb5:0.5 G5:1 A5:1 Gb5:1",
            "F5:1.5 Db5:0.5 C5:2",
            "Bb4:1 C5:1 G4:2"}, 0.85, 0.12, 1.0);
        s.vinyl(vin, 4.0);
        return s;
    }

    // ========================================================================
    //  MUSIC 2 — music_play_a: bouncy pop, 118 BPM, D major, 32 bars
    // ========================================================================
    static Song musicPlayA() {
        Song s = new Song("music_play_a", 118, 32, 7202); s.lowShelfDb = -2.5; s.highShelfDb = -3;
        s.swing = 0.53; s.humanize = 0.004;
        s.revRoom = 0.72; s.revDamp = 0.5; s.revRel = -16; s.revPre = 0.015;
        s.dlyBeats = 0.75; s.dlyFb = 0.32; s.dlyRel = -19; s.dlyLp = 5000; s.duckRel = 0.1;
        Map<String, String> V = map(
            "D", "A3 D4 F#4 A4", "D/F#", "A3 D4 F#4 A4", "Bm7", "A3 B3 D4 F#4", "Gmaj7", "G3 B3 D4 F#4", "G", "G3 B3 D4 G4",
            "Asus4", "A3 D4 E4 A4", "A", "A3 C#4 E4 A4", "A7sus4", "G3 A3 D4 E4", "A7", "G3 A3 C#4 E4", "F#m7", "A3 C#4 E4 F#4",
            "Em7", "G3 B3 D4 E4", "A/C#", "A3 C#4 E4 A4", "D7", "A3 C4 D4 F#4");
        Map<String, String> B = map("D", "D2", "D/F#", "F#2", "Bm7", "B1", "Gmaj7", "G2", "G", "G2", "Asus4", "A1", "A", "A1",
            "A7sus4", "A1", "A7", "A1", "F#m7", "F#2", "Em7", "E2", "A/C#", "C#2", "D7", "D2");
        String[] prog = {
            "D", "Bm7", "Gmaj7", "A7sus4|A",
            "D", "Bm7", "Gmaj7", "Asus4|A", "D/F#", "G", "Em7", "A",
            "Gmaj7", "A", "F#m7", "Bm7", "Em7", "A", "D", "D7",
            "Em7", "F#m7", "Gmaj7", "A", "Bm7", "A/C#", "D", "A7sus4",
            "Gmaj7", "A", "F#m7|Bm7", "Em7|A7"};
        Stem kick = s.stem("kick"); kick.level = -10;
        Stem clp = s.stem("clap"); clp.level = -13.5; clp.rev = 0.2;
        Stem hats = s.stem("hats"); hats.level = -21; hats.lp = 11000;
        Stem shk = s.stem("shaker"); shk.level = -23;
        Stem bass = s.stem("bass"); bass.level = -10.5; bass.duck = 0.4;
        Stem stab = s.stem("stabs"); stab.level = -14; stab.rev = 0.25; stab.duck = 0.3; stab.chorus = 0.3; stab.hp = 150;
        Stem arp = s.stem("arp"); arp.level = -19; arp.dly = 0.6; arp.rev = 0.2; arp.duck = 0.25; arp.hp = 200;
        Stem lead = s.stem("lead"); lead.level = -11.5; lead.rev = 0.3; lead.dly = 0.35;
        Stem mal = s.stem("mallet"); mal.level = -14; mal.rev = 0.3; mal.dly = 0.3;
        Stem pad = s.stem("pad"); pad.level = -18; pad.rev = 0.6; pad.duck = 0.4; pad.hp = 200;
        Stem fx = s.stem("fx"); fx.level = -21; fx.rev = 0.3;

        Instr bb = SynthStudio::bouncyBass, ml = SynthStudio::mallet, pl = SynthStudio::popLead;
        Instr st = (f, d, v, r) -> pluck(f, d, v, r, 0.8, 0.18);
        Instr ap = (f, d, v, r) -> arpPluck(f, d, v, r, 0.55);
        Instr pd = (f, d, v, r) -> supersaw(f, d, v, r, 0.5, 1.0, 2200, 7, 14, 150, 0);
        int[] ARP = {0, 1, 2, 3, 1, 2, 3, 2};
        List<Seg> segs = s.segs(prog);
        for (Seg g : segs) {
            int bar = g.bar(); int sec = bar < 4 ? 0 : bar < 12 ? 1 : bar < 20 ? 2 : bar < 28 ? 3 : 4;
            int[] vc = notes(V.get(g.name())); int root = nn(B.get(g.name()));
            if (sec == 3) {
                s.note(bass, bb, g.beat(), 1.4, root, 0.9, 0, 0);
                if (g.len() >= 4) { s.note(bass, bb, g.beat() + 1.5, 0.4, root + 12, 0.6, 0, 0); s.note(bass, bb, g.beat() + 2, 0.9, root, 0.8, 0, 0); s.note(bass, bb, g.beat() + 3, 0.9, root + 7, 0.75, 0, 0); }
            } else {
                for (int k = 0; k < g.len() * 2; k++) {
                    int m = (k % 2 == 0) ? root : root + 12; if (k == 6) m = root + 7;
                    s.note(bass, bb, g.beat() + k * 0.5, 0.36, m, k % 2 == 0 ? 0.95 : 0.7, 0, 0);
                }
            }
            if (sec == 1) for (double o = 0.5; o < g.len(); o += 1.0) s.chord(stab, st, g.beat() + o, 0.3, vc, 0.7, 0.004, 0.35);
            if (sec == 2 || sec == 4) for (double o : new double[]{0, 0.75, 1.5, 2.5, 3.0}) if (o < g.len()) s.chord(stab, st, g.beat() + o, 0.35, vc, o == 0 ? 0.85 : 0.7, 0.004, 0.35);
            if (sec != 1) for (int k = 0; k < g.len() * 4; k++) {
                int m = vc[ARP[k % 8] % vc.length] + 12;
                s.note(arp, ap, g.beat() + k * 0.25, 0.22, m, (k % 4 == 0 ? 0.8 : 0.55) * (sec == 0 ? 0.7 + 0.1 * bar : 1), k % 2 == 0 ? -0.35 : 0.35, 0);
            }
            if (sec == 3) { int[] up = new int[vc.length]; for (int k = 0; k < vc.length; k++) up[k] = vc[k] + 12; s.chord(pad, pd, g.beat(), g.len(), up, 0.6, 0, 0.5); }
        }
        double[][][] K = s.vars(4, r -> kick(r, 150, 50, 0.03, 0.2, 0.35, 1.5));
        double[][][] C = s.vars(4, r -> clap(r, 0.08));
        double[][][] H = s.vars(6, r -> hat(r, 0.03, 1.05));
        double[][][] OH = s.vars(3, r -> hat(r, 0.12, 1.05));
        double[][][] SH = s.vars(6, SynthStudio::shaker);
        double[][] CR = {crash(new Rng(77), 0.7)};
        for (int b = 0; b < 32; b++) {
            int sec = b < 4 ? 0 : b < 12 ? 1 : b < 20 ? 2 : b < 28 ? 3 : 4;
            boolean endOf = b == 11 || b == 19 || b == 27 || b == 31;
            switch (sec) {
                case 0 -> { s.pat(kick, K, b, "x.......x.......", 0.9, 0, true); s.pat(hats, H, b, "..x...x...x...x.", 0.7, 0.2, false);
                    if (b == 3) s.pat(clp, C, b, "....x.......x.xx", 0.8, 0, false); }
                case 1 -> { s.pat(kick, K, b, "x.....x.x.......", 0.95, 0, true); s.pat(clp, C, b, endOf ? "....x.......x.xx" : "....x.......x...", 0.9, 0, false);
                    s.pat(hats, H, b, "o.x.o.x.o.x.o.x.", 0.75, 0.2, false); }
                case 2, 4 -> { s.pat(kick, K, b, "x...x...x...x...", 1.0, 0, true); s.pat(clp, C, b, endOf ? "....x.......x.xx" : "....x.......x...", 0.95, 0, false);
                    s.pat(hats, H, b, "x.g.x.g.x.g.x.g.", 0.7, 0.2, false); s.pat(hats, OH, b, "..o...o...o...o.", 0.6, -0.2, false); }
                default -> { s.pat(kick, K, b, "x.......x.......", 0.85, 0, true); s.pat(clp, C, b, endOf ? "....x...x.x.xxxx" : "....x.......x...", 0.75, 0, false); }
            }
            s.pat(shk, SH, b, "gggogggogggogggo", 0.7, 0.3, false);
            if (b == 4 || b == 12 || b == 28) s.hitAt(fx, CR, b * 4, 0.8, 0);
        }
        s.melodies(mal, ml, 4, new String[]{
            "r:2 A4:0.5 B4:0.5 D5:1", "F#5:1 E5:0.5 D5:0.5 B4:2", "r:2 B4:0.5 D5:0.5 F#5:1", "E5:1.5 D5:0.5 C#5:2",
            "r:2 A4:0.5 D5:0.5 F#5:1", "G5:1 F#5:0.5 E5:0.5 D5:2", "r:2 E5:0.5 F#5:0.5 G5:1", "A5:1.5 G5:0.5 E5:1 C#5:1"}, 0.75, 0.15, 1.0);
        String[] hook = {
            "D5:0.5 E5:0.5 F#5:0.5 A5:1 F#5:0.5 E5:0.5 D5:0.5",
            "E5:0.75 C#5:0.75 A4:0.5 r:0.5 E5:0.5 F#5:0.5 E5:0.5",
            "C#5:0.5 E5:0.5 F#5:0.5 A5:1 F#5:0.5 E5:0.5 C#5:0.5",
            "D5:0.75 B4:0.75 F#4:0.5 r:0.5 A4:0.5 B4:0.5 D5:0.5",
            "G5:0.5 F#5:0.5 E5:0.5 B5:1 A5:0.5 G5:0.5 F#5:0.5",
            "E5:0.75 F#5:0.75 E5:0.5 C#5:1 r:1",
            "D5:0.5 F#5:0.5 A5:0.5 D6:1.5 r:1",
            "C6:0.5 A5:0.5 F#5:0.5 D5:0.5 r:2"};
        s.melodies(lead, pl, 12, hook, 0.85, 0, 0.9);
        s.melodies(lead, pl, 20, new String[]{"B5:2 A5:1 G5:1", "A5:3 E5:1", "F#5:2 D5:1 B4:1", "C#5:3 r:1",
            "D5:2 F#5:1 A5:1", "E5:3 r:1", "F#5:2 E5:1 D5:1", "E5:2 r:2"}, 0.6, 0, 0.95);
        s.melodies(lead, pl, 28, new String[]{hook[0], hook[1], hook[2], hook[3]}, 0.85, 0, 0.9);
        return s;
    }

    // ========================================================================
    //  MUSIC 3 — music_play_b: dreamy synth-pop, 110 BPM, E major, 32 bars
    // ========================================================================
    static Song musicPlayB() {
        Song s = new Song("music_play_b", 110, 32, 7303); s.lowShelfDb = -2.5; s.highShelfDb = -1.5;
        s.humanize = 0.005;
        s.revRoom = 0.9; s.revDamp = 0.4; s.revRel = -11; s.revPre = 0.03;
        s.dlyBeats = 0.75; s.dlyFb = 0.42; s.dlyRel = -15; s.dlyLp = 4500; s.duckRel = 0.14;
        Map<String, String> V = map(
            "Emaj9", "E3 G#3 B3 D#4 F#4", "G#m7", "G#3 B3 D#4 F#4", "C#m9", "E3 G#3 B3 D#4", "Aadd9#11", "E3 B3 C#4 D#4 G#4",
            "B11", "A3 C#4 E4 F#4", "Amaj9", "C#4 E4 G#4 B4", "B6sus", "B3 E4 F#4 G#4", "F#m9", "A3 C#4 E4 G#4",
            "Amaj7", "A3 C#4 E4 G#4", "Emaj9/G#", "B3 D#4 E4 F#4");
        Map<String, String> B = map("Emaj9", "E2", "G#m7", "G#2", "C#m9", "C#2", "Aadd9#11", "A1", "B11", "B1", "Amaj9", "A1",
            "B6sus", "B1", "F#m9", "F#2", "Amaj7", "A1", "Emaj9/G#", "G#2");
        String[] prog = {
            "Emaj9", "G#m7", "C#m9", "Aadd9#11", "Emaj9", "G#m7", "C#m9", "B11",
            "Amaj9", "B6sus", "G#m7", "C#m9", "F#m9", "Amaj7", "Emaj9/G#", "B11",
            "C#m9", "Amaj9", "Emaj9", "B6sus", "C#m9", "Amaj9", "F#m9", "B11",
            "Emaj9", "G#m7", "C#m9", "Aadd9#11", "Emaj9", "G#m7", "F#m9", "B11"};
        Stem kick = s.stem("kick"); kick.level = -12;
        Stem clp = s.stem("clap"); clp.level = -17; clp.rev = 0.35;
        Stem hats = s.stem("hats"); hats.level = -23.5; hats.rev = 0.1; hats.lp = 10000;
        Stem shk = s.stem("shaker"); shk.level = -23.5; shk.rev = 0.1;
        Stem bass = s.stem("bass"); bass.level = -12; bass.duck = 0.35; bass.lp = 700;
        Stem pad = s.stem("pad"); pad.level = -12; pad.rev = 0.55; pad.duck = 0.3; pad.hp = 160; pad.chorus = 0.25;
        Stem arp = s.stem("arp"); arp.level = -15; arp.dly = 0.7; arp.rev = 0.35; arp.duck = 0.2; arp.hp = 200;
        Stem lead = s.stem("lead"); lead.level = -12; lead.rev = 0.45; lead.dly = 0.4;
        Stem bel = s.stem("bells"); bel.level = -18; bel.rev = 0.6; bel.dly = 0.3;
        Stem fx = s.stem("fx"); fx.level = -21; fx.rev = 0.5;

        Instr pd = (f, d, v, r) -> supersaw(f, d, v, r, 0.6, 1.2, 2300, 7, 16, 150, 0);
        Instr sb = SynthStudio::subBass, bl = SynthStudio::bell;
        Instr ld = (f, d, v, r) -> lead(f, d, v, r, TRI, 2600, 18, 0.3);
        int[] ARP = {0, 2, 1, 3, 2, 4, 3, 1};
        List<Seg> segs = s.segs(prog);
        for (Seg g : segs) {
            int bar = g.bar(); int sec = bar / 8;
            int[] vc = notes(V.get(g.name())); int root = nn(B.get(g.name()));
            s.chord(pad, pd, g.beat(), g.len() + 0.05, vc, 0.62, 0, 0.4);
            if (sec == 2) s.note(bass, sb, g.beat(), 3.8, root, 0.75, 0, 0);
            else { s.note(bass, sb, g.beat(), 2.3, root, 0.9, 0, 0); s.note(bass, sb, g.beat() + 2.5, 1.3, root, 0.75, 0, 0); if (sec == 3) s.note(bass, sb, g.beat() + 3.5, 0.4, root + 12, 0.6, 0, 0); }
            double bright = new double[]{0.45, 0.55, 0.28, 0.6}[sec];
            Instr ap = (f, d, v, r) -> arpPluck(f, d, v, r, bright);
            int[] ext = new int[vc.length + 2];
            for (int k = 0; k < vc.length; k++) ext[k] = vc[k] + 12;
            ext[vc.length] = vc[0] + 24; ext[vc.length + 1] = vc[1] + 24;
            for (int k = 0; k < g.len() * 4; k++) {
                int m = ext[ARP[k % 8] % ext.length];
                s.note(arp, ap, g.beat() + k * 0.25, 0.22, m, k % 4 == 0 ? 0.8 : 0.55, k % 2 == 0 ? -0.3 : 0.3, 0);
            }
            if ((sec == 0 || sec == 1) && bar % 2 == 0) s.note(bel, bl, g.beat(), 2, vc[vc.length - 1] + 12, 0.7, 0.25, 0);
            if (sec == 1 && bar % 2 == 1) s.note(bel, bl, g.beat() + 2.5, 1.5, vc[vc.length - 2] + 12, 0.55, -0.25, 0);
        }
        double[][][] K = s.vars(4, r -> kick(r, 110, 46, 0.035, 0.2, 0.12, 1.1));
        double[][][] C = s.vars(4, r -> clap(r, 0.11));
        double[][][] H = s.vars(5, r -> hat(r, 0.035, 0.95));
        double[][][] OH = s.vars(2, r -> hat(r, 0.18, 0.95));
        double[][][] SH = s.vars(6, SynthStudio::shaker);
        double[][] CR = {crash(new Rng(91), 0.9)};
        for (int b = 0; b < 32; b++) {
            int sec = b / 8;
            if (sec == 0) s.pat(kick, K, b, "x.......x.......", 0.9, 0, true);
            if (sec == 1 || sec == 3) { s.pat(kick, K, b, "x.......x.x.....", 0.95, 0, true); s.pat(clp, C, b, b == 31 ? "....x.......x.xx" : "....x.......x...", 0.85, 0, false);
                s.pat(hats, H, b, "..o...o...o...o.", 0.7, 0.2, false); }
            if (sec == 3) s.pat(hats, OH, b, "..............o.", 0.6, -0.2, false);
            if (sec == 2 && b >= 22) s.pat(kick, K, b, b == 23 ? "x...x...x...x.x." : "x...x...x...x...", 0.75, 0, true);
            if (sec != 2 || b >= 20) s.pat(shk, SH, b, "g.o.g.o.g.o.g.o.", 0.7, 0.3, false);
            if (b == 8 || b == 24) s.hitAt(fx, CR, b * 4, 0.6, 0);
        }
        s.hitAt(fx, new double[][]{riser(new Rng(55), s.sec(8))}, 22 * 4, 0.7, 0);
        s.melodies(bel, bl, 16, new String[]{"G#5:2 E5:2", "C#6:2 B5:2", "G#5:3 F#5:1", "E5:4", "G#5:2 B5:2", "C#6:2 E6:2", "C#6:2 A5:2", "B5:2 r:2"}, 0.8, 0, 1.0);
        s.melodies(lead, ld, 24, new String[]{
            "B4:1 D#5:1 F#5:1.5 E5:0.5", "D#5:2 B4:1 r:1", "E5:1 G#5:1 B5:1.5 G#5:0.5", "F#5:1 D#5:1 C#5:2",
            "B4:1 E5:1 F#5:1 G#5:1", "F#5:2 D#5:2", "C#5:1 E5:1 G#5:2", "F#5:2 E5:1 r:1"}, 0.85, 0.05, 0.95);
        return s;
    }

    // ========================================================================
    //  MUSIC 4 — music_boss: driving, minor with heroic lift, 128 BPM, C minor, 36 bars
    // ========================================================================
    static Song musicBoss() {
        Song s = new Song("music_boss", 128, 36, 7404); s.lowShelfDb = -2.5; s.highShelfDb = -2.5;
        s.humanize = 0.003;
        s.revRoom = 0.74; s.revDamp = 0.5; s.revRel = -16; s.revPre = 0.02;
        s.dlyBeats = 0.75; s.dlyFb = 0.3; s.dlyRel = -20; s.dlyLp = 4000; s.duckRel = 0.09;
        Map<String, String> V = map(
            "Cm", "G3 C4 Eb4 G4", "Ab", "Ab3 C4 Eb4 Ab4", "Eb", "G3 Bb3 Eb4 G4", "Bb", "F3 Bb3 D4 F4", "Fm", "F3 Ab3 C4 F4",
            "G", "G3 B3 D4 G4", "Gm", "G3 Bb3 D4 G4", "Eb/G", "G3 Bb3 Eb4 G4", "C", "G3 C4 E4 G4", "G7", "F3 G3 B3 D4");
        Map<String, String> B = map("Cm", "C2", "Ab", "Ab1", "Eb", "Eb2", "Bb", "Bb1", "Fm", "F2", "G", "G1", "Gm", "G1",
            "Eb/G", "G1", "C", "C2", "G7", "G1");
        String[] prog = {
            "Cm", "Cm", "Ab", "Bb",
            "Cm", "Ab", "Eb", "Bb", "Cm", "Ab", "Fm", "G",
            "Ab", "Bb", "Gm", "Cm", "Ab", "Bb", "Eb/G", "G",
            "Ab", "Bb", "Eb", "Cm", "Ab", "Bb", "C", "C",
            "Cm", "Ab", "Eb", "Bb", "Fm", "Ab", "G", "G7"};
        Stem kick = s.stem("kick"); kick.level = -9;
        Stem snr = s.stem("snare"); snr.level = -12; snr.rev = 0.25;
        Stem hats = s.stem("hats"); hats.level = -21; hats.lp = 12000;
        Stem toms = s.stem("toms"); toms.level = -14; toms.rev = 0.2;
        Stem bass = s.stem("bass"); bass.level = -10; bass.duck = 0.55;
        Stem stab = s.stem("stabs"); stab.level = -14; stab.duck = 0.35; stab.rev = 0.2;
        Stem str = s.stem("strings"); str.level = -15; str.duck = 0.35; str.rev = 0.45; str.hp = 200;
        Stem arp = s.stem("arp"); arp.level = -17; arp.dly = 0.5; arp.duck = 0.3; arp.hp = 200;
        Stem lead = s.stem("lead"); lead.level = -11; lead.rev = 0.3; lead.dly = 0.3;
        Stem brs = s.stem("brass"); brs.level = -11; brs.rev = 0.35;
        Stem fx = s.stem("fx"); fx.level = -18; fx.rev = 0.3;

        Instr stI = (f, d, v, r) -> supersaw(f, d, v, r, 0.003, 0.12, 2400, 5, 14, 180, 2.0);
        Instr strI = (f, d, v, r) -> supersaw(f, d, v, r, 0.25, 0.6, 3200, 7, 12, 220, 0);
        Instr apI = (f, d, v, r) -> arpPluck(f, d, v, r, 0.5);
        Instr ldI = (f, d, v, r) -> lead(f, d, v, r, SQUARE, 3200, 14, 0);
        Instr brI = SynthStudio::brass;
        int[] ARP = {0, 1, 2, 3, 2, 1, 2, 3};
        List<Seg> segs = s.segs(prog);
        for (Seg g : segs) {
            int bar = g.bar(); int sec = bar < 4 ? 0 : (bar - 4) / 8 + 1;
            int[] vc = notes(V.get(g.name())); int root = nn(B.get(g.name()));
            double cut = sec == 0 ? 350 + 300 * bar : 1500;
            Instr bI = (f, d, v, r) -> pulseBass(f, d, v, r, cut);
            int[] bp = {0, 0, 12, 0}; double[] bv = {1, 0.55, 0.8, 0.6};
            for (int k = 0; k < g.len() * 4; k++) s.note(bass, bI, g.beat() + k * 0.25, 0.2, root + bp[k % 4], bv[k % 4], 0, 0);
            if (sec == 1 || sec == 4) for (int st : new int[]{0, 3, 6, 10, 13}) s.chord(stab, stI, g.beat() + st * 0.25, 0.3, vc, st == 0 ? 0.85 : 0.7, 0.002, 0.4);
            if (sec == 3) for (int st : new int[]{0, 6, 10}) s.chord(stab, stI, g.beat() + st * 0.25, 0.45, vc, 0.75, 0.002, 0.4);
            if (sec == 0 && bar >= 2) for (int st : new int[]{0, 6, 10}) s.chord(stab, stI, g.beat() + st * 0.25, 0.3, vc, 0.5, 0.002, 0.4);
            if (sec == 2 || sec == 3) { int[] up = new int[vc.length]; for (int k = 0; k < vc.length; k++) up[k] = vc[k] + 12; s.chord(str, strI, g.beat(), g.len(), up, 0.65, 0, 0.5); }
            if (sec == 2 || sec == 3 || sec == 4) for (int k = 0; k < g.len() * 4; k++) {
                int m = vc[ARP[k % 8] % vc.length] + (k % 16 < 8 ? 12 : 24);
                s.note(arp, apI, g.beat() + k * 0.25, 0.2, m, (k % 4 == 0 ? 0.8 : 0.55) * (sec == 3 ? 0.8 : 1), k % 2 == 0 ? -0.35 : 0.35, 0);
            }
        }
        double[][][] K = s.vars(4, r -> kick(r, 180, 48, 0.03, 0.26, 0.5, 2.0));
        double[][][] S = s.vars(4, r -> snare(r, 195, 0.15, 0.6, 9500));
        double[][][] H = s.vars(6, r -> hat(r, 0.026, 1.1));
        double[][][] OH = s.vars(3, r -> hat(r, 0.11, 1.1));
        double[][] CR = {crash(new Rng(33), 1.0)};
        double[][][] T = new double[4][][];
        double[] tf = {210, 165, 130, 98};
        for (int i = 0; i < 4; i++) T[i] = new double[][]{tom(new Rng(500 + i), tf[i])};
        for (int b = 0; b < 36; b++) {
            int sec = b < 4 ? 0 : (b - 4) / 8 + 1;
            boolean tomFill = b == 19 || b == 35;
            s.pat(kick, K, b, tomFill ? "x...x...x......." : "x...x...x...x...", 1.0, 0, true);
            if (b == 3) s.pat(snr, S, b, "........x.x.xxxx", 0.85, 0, false);
            else if (sec > 0) s.pat(snr, S, b, tomFill ? "....x..........." : (b == 11 || b == 27) ? "....x.......x.xx" : "....x.......x...", 0.95, 0, false);
            if (sec == 0) s.pat(hats, H, b, "..x...x...x...x.", 0.75, 0.2, false);
            else if (sec == 2) s.pat(hats, H, b, "x.x.x.x.x.x.x.x.", 0.7, 0.2, false);
            else { s.pat(hats, H, b, "xgxgxgxgxgxgxgxg", 0.7, 0.2, false); s.pat(hats, OH, b, "..o...o...o...o.", 0.55, -0.2, false); }
            if (tomFill) {
                double[] at = {2.0, 2.5, 3.0, 3.25, 3.5, 3.75}; int[] which = {0, 0, 1, 2, 2, 3};
                for (int i = 0; i < at.length; i++) s.hitAt(toms, T[which[i]], b * 4 + at[i], 0.75 + 0.05 * i, which[i] < 2 ? 0.3 : -0.3);
            }
            if (b == 4 || b == 12 || b == 20 || b == 28) s.hitAt(fx, CR, b * 4, 0.8, 0);
        }
        s.hitAt(fx, new double[][]{riser(new Rng(61), s.sec(4))}, 3 * 4, 0.6, 0);
        s.hitAt(fx, new double[][]{riser(new Rng(62), s.sec(4))}, 19 * 4, 0.7, 0);
        s.hitAt(fx, new double[][]{riser(new Rng(63), s.sec(8))}, 26 * 4, 0.8, 0);
        String[] motif = {
            "G4:0.5 C5:0.5 Eb5:0.5 D5:0.5 C5:1 G4:1",
            "Ab4:0.5 C5:0.5 Eb5:0.5 F5:0.5 Eb5:1 C5:1",
            "Bb4:0.5 Eb5:0.5 G5:0.5 F5:0.5 Eb5:1 Bb4:1",
            "D5:1.5 C5:0.5 Bb4:1 F4:1"};
        s.melodies(lead, ldI, 4, motif, 0.85, 0, 0.92);
        s.melodies(lead, ldI, 8, new String[]{motif[0], "Ab4:0.5 C5:0.5 Eb5:0.5 F5:0.5 Eb5:1 Ab5:1",
            "G5:0.5 F5:0.5 Eb5:0.5 C5:0.5 Ab4:1 C5:1", "B4:1.5 C5:0.5 D5:1 B4:1"}, 0.85, 0, 0.92);
        s.melodies(brs, brI, 20, new String[]{
            "Eb5:1 Ab5:1 C6:1.5 Bb5:0.5", "Bb5:2 F5:1 D5:1", "Eb5:0.5 F5:0.5 G5:1 Bb5:1.5 G5:0.5", "G5:2 Eb5:1 C5:1",
            "C5:1 Eb5:1 Ab5:1.5 G5:0.5", "F5:1 Bb5:1 D6:2", "C6:1 G5:1 E5:1.5 G5:0.5", "C6:4"}, 0.9, 0, 0.95);
        s.melodies(lead, ldI, 28, motif, 0.85, 0, 0.92);
        s.melodies(lead, ldI, 32, new String[]{"C5:0.5 F5:0.5 Ab5:0.5 G5:0.5 F5:1 C5:1", "Eb5:0.5 Ab5:0.5 C6:0.5 Bb5:0.5 Ab5:1 Eb5:1",
            "D5:1 G5:1 B5:2", "Ab5:1 G5:1 F5:1 D5:1"}, 0.85, 0, 0.92);
        return s;
    }

    // ========================================================================
    //  MUSIC 5 — music_endless: fast electro, 130 BPM, F major / D minor, 36 bars
    // ========================================================================
    static Song musicEndless() {
        Song s = new Song("music_endless", 130, 36, 7505); s.lowShelfDb = -2.5; s.highShelfDb = -3;
        s.humanize = 0.003;
        s.revRoom = 0.72; s.revDamp = 0.5; s.revRel = -16; s.revPre = 0.02;
        s.dlyBeats = 0.75; s.dlyFb = 0.36; s.dlyRel = -18; s.dlyLp = 4500; s.duckRel = 0.1;
        Map<String, String> V = map(
            "F", "F3 A3 C4 F4", "Dm7", "F3 A3 C4 D4", "C", "G3 C4 E4 G4", "Bb", "F3 Bb3 D4 F4", "Bbmaj7", "F3 A3 Bb3 D4",
            "Am", "E3 A3 C4 E4", "Am7", "E3 G3 A3 C4", "Dm", "F3 A3 D4 F4", "Gm7", "F3 G3 Bb3 D4", "Csus4", "G3 C4 F4 G4",
            "C7sus", "Bb3 C4 F4 G4");
        Map<String, String> B = map("F", "F2", "Dm7", "D2", "C", "C2", "Bb", "Bb1", "Bbmaj7", "Bb1", "Am", "A1", "Am7", "A1",
            "Dm", "D2", "Gm7", "G1", "Csus4", "C2", "C7sus", "C2");
        String[] prog = {
            "F", "F", "Dm7", "C",
            "Bb", "C", "Am", "Dm", "Bb", "C", "Dm", "Dm",
            "Bbmaj7", "Am7", "Gm7", "Csus4",
            "Bb", "C", "Dm", "C",
            "Bb", "C", "Am", "Dm", "Bb", "C", "Gm7", "C7sus",
            "F", "Am7", "Bbmaj7", "C", "F", "Am7", "Bb", "C"};
        Stem kick = s.stem("kick"); kick.level = -9;
        Stem clp = s.stem("clap"); clp.level = -12.5; clp.rev = 0.25;
        Stem roll = s.stem("roll"); roll.level = -15; roll.rev = 0.2;
        Stem hats = s.stem("hats"); hats.level = -20.5; hats.lp = 12000;
        Stem ohat = s.stem("ohat"); ohat.level = -22.5; ohat.lp = 12000;
        Stem bass = s.stem("bass"); bass.level = -10; bass.duck = 0.6;
        Stem stab = s.stem("stabs"); stab.level = -14.5; stab.duck = 0.4; stab.rev = 0.2; stab.chorus = 0.2;
        Stem arp = s.stem("arp"); arp.level = -15; arp.dly = 0.55; arp.duck = 0.3; arp.rev = 0.15; arp.hp = 200;
        Stem lead = s.stem("lead"); lead.level = -11; lead.rev = 0.3; lead.dly = 0.35;
        Stem pad = s.stem("pad"); pad.level = -16; pad.rev = 0.5; pad.duck = 0.4; pad.hp = 200;
        Stem fx = s.stem("fx"); fx.level = -17; fx.rev = 0.25;

        Instr subI = SynthStudio::subBass, wb = SynthStudio::warmBass;
        Instr obI = (f, d, v, r) -> pulseBass(f, d, v, r, 1600);
        Instr stI = (f, d, v, r) -> supersaw(f, d, v, r, 0.003, 0.1, 2600, 5, 16, 200, 1.8);
        Instr pdI = (f, d, v, r) -> supersaw(f, d, v, r, 0.5, 1.0, 2400, 7, 14, 150, 0);
        Instr ldI = (f, d, v, r) -> lead(f, d, v, r, SAW, 3800, 12, 0);
        Instr mlI = SynthStudio::mallet;
        int[] ARP = {0, 1, 2, 3, 2, 1, 2, 3};
        List<Seg> segs = s.segs(prog);
        for (Seg g : segs) {
            int bar = g.bar();
            int sec = bar < 4 ? 0 : bar < 12 ? 1 : bar < 16 ? 2 : bar < 20 ? 3 : bar < 28 ? 4 : 5;
            int[] vc = notes(V.get(g.name())); int root = nn(B.get(g.name()));
            if (sec == 2) s.note(bass, wb, g.beat(), 3.8, root, 0.8, 0, 0);
            else if (sec == 3) for (int k = 0; k < 8; k++) s.note(bass, obI, g.beat() + k * 0.5, 0.35, root + 12, 0.55 + 0.05 * (bar - 16) + 0.02 * k, 0, 0);
            else for (int k = 0; k < 4; k++) {
                s.note(bass, subI, g.beat() + k, 0.42, root, 0.9, 0, 0);
                s.note(bass, obI, g.beat() + k + 0.5, 0.4, root + 12, 0.8, 0, 0);
            }
            if (sec == 1 || sec == 4 || (sec == 5 && bar < 32)) for (double o = 0.5; o < 4; o += 1.0) s.chord(stab, stI, g.beat() + o, 0.25, vc, sec == 5 ? 0.55 : 0.72, 0.002, 0.45);
            if (sec == 2 || sec == 4 || sec == 5) { int[] up = new int[vc.length]; for (int k = 0; k < vc.length; k++) up[k] = vc[k] + 12; s.chord(pad, pdI, g.beat(), 4, up, sec == 2 ? 0.7 : 0.5, 0, 0.5); }
            double bright = switch (sec) { case 0 -> 0.15 + 0.1 * bar; case 1 -> 0.6; case 2 -> 0.35; case 3 -> 0.35 + 0.15 * (bar - 16); case 4 -> 0.7; default -> 0.55; };
            Instr apI = (f, d, v, r) -> arpPluck(f, d, v, r, bright);
            for (int k = 0; k < 16; k++) {
                int m = vc[ARP[k % 8] % vc.length] + (k < 8 ? 12 : 24);
                s.note(arp, apI, g.beat() + k * 0.25, 0.2, m, k % 4 == 0 ? 0.85 : 0.6, k % 2 == 0 ? -0.3 : 0.3, 0);
            }
        }
        double[][][] K = s.vars(4, r -> kick(r, 165, 50, 0.028, 0.22, 0.45, 1.8));
        double[][][] C = s.vars(4, r -> clap(r, 0.09));
        double[][][] S = s.vars(4, r -> snare(r, 210, 0.1, 0.5, 9000));
        double[][][] H = s.vars(6, r -> hat(r, 0.024, 1.1));
        double[][][] OH = s.vars(3, r -> hat(r, 0.1, 1.1));
        double[][] CR = {crash(new Rng(44), 1.0)};
        for (int b = 0; b < 36; b++) {
            int sec = b < 4 ? 0 : b < 12 ? 1 : b < 16 ? 2 : b < 20 ? 3 : b < 28 ? 4 : 5;
            if (sec != 2) s.pat(kick, K, b, b == 19 ? "x...x..........." : "x...x...x...x...", 1.0, 0, true);
            if (sec == 1 || sec == 4 || sec == 5) {
                s.pat(clp, C, b, (b == 11 || b == 27 || b == 35) ? "....x.......x.xx" : "....x.......x...", 0.95, 0, false);
                s.pat(hats, H, b, "xgxgxgxgxgxgxgxg", 0.7, 0.2, false);
                s.pat(ohat, OH, b, "..x...x...x...x.", 0.7, -0.2, false);
            }
            if (sec == 0) s.pat(hats, H, b, "..x...x...x...x.", 0.6 + 0.08 * b, 0.2, false);
            if (sec == 2) s.pat(hats, H, b, "..g...g...g...g.", 0.8, 0.2, false);
            if (sec == 3) {
                s.pat(hats, H, b, "x.x.x.x.x.x.x.x.", 0.6, 0.2, false);
                String rp = switch (b) { case 16 -> "x...x...x...x..."; case 17 -> "x.x.x.x.x.x.x.x."; case 18 -> "xxxxxxxxxxxxxxxx"; default -> "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"; };
                s.pat(roll, S, b, rp, 0.45 + 0.15 * (b - 16), 0, false);
            }
            if (b == 4 || b == 28) s.hitAt(fx, CR, b * 4, 0.75, 0);
        }
        s.hitAt(fx, new double[][]{riser(new Rng(71), s.sec(16))}, 16 * 4, 0.9, 0);
        s.hitAt(fx, CR, 20 * 4, 0.85, 0);
        s.hitAt(fx, new double[][]{impact(new Rng(72))}, 20 * 4, 0.7, 0);
        s.melody(lead, mlI, 5, "r:2.5 E5:0.5 G5:0.5 C6:0.5", 0.7, 0.15, 1.0);
        s.melody(lead, mlI, 7, "r:2.5 F5:0.5 A5:0.5 D6:0.5", 0.7, 0.15, 1.0);
        s.melody(lead, mlI, 9, "r:2.5 E5:0.5 G5:0.5 C6:0.5", 0.7, 0.15, 1.0);
        s.melody(lead, mlI, 11, "r:2 D6:0.5 C6:0.5 A5:0.5 F5:0.5", 0.7, 0.15, 1.0);
        s.melodies(lead, mlI, 12, new String[]{"D5:2 F5:2", "E5:2 C5:2", "D5:2 Bb4:2", "C5:2 F5:1 G5:1"}, 0.6, 0.1, 1.0);
        String[] hook = {
            "D5:0.75 F5:0.75 Bb5:0.5 A5:0.5 F5:0.5 D5:1",
            "E5:0.75 G5:0.75 C6:0.5 Bb5:0.5 G5:0.5 E5:1",
            "C5:0.75 E5:0.75 A5:0.5 G5:0.5 E5:0.5 C5:1",
            "D5:0.5 F5:0.5 A5:1 G5:0.5 F5:0.5 E5:0.5 F5:0.5"};
        s.melodies(lead, ldI, 20, hook, 0.85, 0, 0.9);
        s.melodies(lead, ldI, 24, new String[]{hook[0], hook[1], "Bb5:0.75 A5:0.75 G5:0.5 F5:0.5 D5:0.5 F5:1", "G5:1 F5:0.5 G5:0.5 C6:2"}, 0.85, 0, 0.9);
        s.melodies(lead, ldI, 28, new String[]{"F5:0.75 A5:0.75 C6:0.5 Bb5:0.5 A5:0.5 F5:1", "E5:0.75 G5:0.75 A5:0.5 G5:0.5 E5:0.5 C5:1",
            "D5:0.75 F5:0.75 A5:0.5 F5:0.5 D5:0.5 Bb4:1", "C5:1 E5:1 G5:2"}, 0.7, 0, 0.9);
        return s;
    }

    // ========================================================================
    //  SOUND EFFECTS
    // ========================================================================
    static double[] sfxBounce(int k) {
        double[] f0s = {311.13, 349.23, 277.18, 392.00}, lens = {0.19, 0.17, 0.21, 0.16}, ratios = {2.0, 2.0, 1.0, 3.0};
        double[] idxs = {0.9, 0.7, 1.15, 0.5}, taus = {0.05, 0.044, 0.06, 0.04}, rises = {0.06, 0.035, 0.085, 0.05};
        double f0 = f0s[k]; double[] y = buf(lens[k]); Rng r = new Rng(1000 + k);
        double pc = 0, pm = 0, pt = 0, wob = 26 + 4 * k; SVF nlp = new SVF(), out = new SVF();
        for (int i = 0; i < y.length; i++) {
            double t = tt(i);
            double f = f0 * (1 + 0.85 * Math.exp(-t / 0.007)) * (1 + rises[k] * (1 - Math.exp(-t / 0.045))) * (1 + 0.025 * Math.sin(TAU * wob * t) * Math.exp(-t / 0.05));
            double idx = idxs[k] * Math.exp(-t / 0.02) + 0.12;
            double body = Math.sin(TAU * pc + idx * Math.sin(TAU * pm));
            pc = frac(pc + f / SR); pm = frac(pm + f * ratios[k] / SR);
            double env = ramp(t, 0.0012) * (0.8 * Math.exp(-t / taus[k]) + 0.2 * Math.exp(-t / 0.012));
            double ft = f0 * 0.5 * (1 + 0.6 * Math.exp(-t / 0.01));
            double thump = Math.sin(TAU * pt) * Math.exp(-t / 0.028) * 0.45 * ramp(t, 0.0008);
            pt = frac(pt + ft / SR);
            double click = nlp.lp(r.bi(), 2400, 0.7) * Math.exp(-t / 0.002) * 0.3;
            y[i] = out.lp(sat(body * env * 1.1 + thump, 1.4) + click, 5200, 0.6);
        }
        fadeOut(y, 0.03);
        return y;
    }

    static double[] sfxRingBreak() {
        double[] y = buf(0.36); Rng r = new Rng(2000);
        osc(y, 0, 0.08, SINE, t -> 150 + 110 * Math.exp(-t / 0.012), t -> 0.55 * ramp(t, 0.0008) * Math.exp(-t / 0.022));
        noise(y, 0, 0.08, BP, t -> 1300 - 400 * Math.min(1, t / 0.05), 0.9, t -> 0.55 * ramp(t, 0.0005) * Math.exp(-t / 0.016), false, r);
        for (int i = 0; i < 24; i++) {
            double t0 = 0.13 * Math.pow(r.u(), 1.8);
            grain(y, t0, r.range(1400, 6500), r.range(2.5, 7), r.range(0.003, 0.01), Math.max(0.1, (0.55 - 2.2 * t0) * r.range(0.6, 1)), r);
        }
        for (int i = 0; i < 7; i++) partial(y, i == 0 ? 0 : r.range(0, 0.14), r.range(2300, 5200), r.range(0.08, 0.16), r.range(0.035, 0.11), 0.0006);
        bellNote(y, 0.025, 2637.0, 0.1, 0.1, 2.0, 0.6);
        for (int i = 0; i < 8; i++) grain(y, r.range(0.05, 0.28), r.range(6000, 9000), 6, 0.003, 0.06, r);
        highpass(y, 120); lowpass(y, 11000);
        fadeOut(y, 0.04);
        return y;
    }

    static double[] sfxComboNote() {
        double f = 523.25; double[] y = buf(0.42); Rng r = new Rng(2100);
        partial(y, 0, f, 1.0, 0.15, 0.0008);
        partial(y, 0, 2 * f, 0.16, 0.09, 0.0008);
        partial(y, 0, 3.98 * f, 0.22, 0.035, 0.0005);
        partial(y, 0, 9.6 * f, 0.05, 0.01, 0.0004);
        fm(y, 0, 0.1, f, 4.0, t -> Math.exp(-t / 0.015), t -> 0.18 * ramp(t, 0.0006) * Math.exp(-t / 0.03));
        noise(y, 0, 0.01, LP, t -> 3500, 0.7, t -> 0.15 * Math.exp(-t / 0.0012), false, r);
        fadeOut(y, 0.08);
        return y;
    }

    static double[] sfxBlazeIgnite() {
        double D = 0.72; double[] y = buf(D); Rng r = new Rng(2200);
        noise(y, 0, 0.35, LP, t -> 250 + 1600 * (1 - Math.exp(-t / 0.05)), 0.8, t -> 0.9 * ramp(t, 0.003) * Math.exp(-t / 0.1), true, r);
        osc(y, 0, 0.3, SINE, t -> 65 + 90 * (1 - Math.exp(-t / 0.06)), t -> 0.55 * ramp(t, 0.002) * Math.exp(-t / 0.09));
        noise(y, 0, D, BP, t -> 350 * Math.pow(3200 / 350.0, Math.min(1, t / 0.5)), 1.1,
            t -> 0.5 * (t < 0.25 ? 0.35 + 0.65 * t / 0.25 : Math.exp(-(t - 0.25) / 0.16)), true, r);
        double tc = 0.05;
        while (true) {
            tc += -Math.log(1 - r.u()) / 60.0; if (tc > 0.66) break;
            grain(y, tc, r.range(2200, 6000), 1.2, r.range(0.0008, 0.0025), r.range(0.08, 0.35) * (1 - tc / 0.8), r);
        }
        Osc a = new Osc(SAW, 0.1), b = new Osc(SAW, 0.5), c = new Osc(SAW, 0.8), d = new Osc(SAW, 0.3); SVF lp = new SVF();
        for (int i = 0; i < y.length; i++) {
            double t = tt(i), p = smoothstep(0, 0.62, t);
            double f = 196 * Math.pow(4.0, p) * (1 + 0.006 * Math.sin(TAU * 6.5 * t));
            double v = (a.next(f * 0.993) + b.next(f) + c.next(f * 1.007)) / 3 + 0.5 * d.next(f * 1.5);
            double env = (t < 0.18 ? 0.15 + 0.85 * t / 0.18 : 1) * (t > 0.5 ? Math.max(0, 1 - (t - 0.5) / 0.22) : 1);
            y[i] += 0.28 * lp.lp(v, 500 + 3800 * p, 1.6) * env;
        }
        saturate(y, 1.3); highpass(y, 40);
        fadeOut(y, 0.05);
        return y;
    }

    static double[] sfxBlazeSmash() {
        double D = 0.72; double[] y = buf(D); Rng r = new Rng(2300);
        double[] boom = buf(D);
        osc(boom, 0, D, SINE, t -> 52 + 130 * Math.exp(-t / 0.03), t -> ramp(t, 0.0005) * Math.exp(-t / 0.2));
        saturate(boom, 2.2); addInto(y, boom, 0, 0.95);
        noise(y, 0, 0.02, HP, t -> 3500, 0.7, t -> 0.6 * Math.exp(-t / 0.0018), false, r);
        noise(y, 0, 0.12, BP, t -> 900, 1.0, t -> 0.8 * ramp(t, 0.0004) * Math.exp(-t / 0.028), false, r);
        double[] cr = buf(0.25);
        noise(cr, 0, 0.25, LP, t -> 5000 - 3000 * Math.min(1, t / 0.2), 0.7, t -> Math.exp(-t / 0.06), false, r);
        saturate(cr, 3.0); addInto(y, cr, 0.004, 0.35);
        for (int i = 0; i < 34; i++) { double t0 = 0.2 * Math.pow(r.u(), 1.6); grain(y, t0, r.range(700, 4500), r.range(2, 6), r.range(0.004, 0.02), r.range(0.3, 0.6) * (1 - t0 * 2.5), r); }
        for (int i = 0; i < 28; i++) {
            double t0 = 0.12 + 0.55 * Math.pow(r.u(), 1.7);
            grain(y, t0, r.range(2000, 7000), r.range(4, 10), r.range(0.002, 0.006), 0.16 * (1 - (t0 - 0.12) / 0.62) * r.range(0.5, 1), r);
        }
        for (int i = 0; i < 5; i++) { double f0 = r.range(300, 700); osc(y, r.range(0.1, 0.5), 0.05, SINE, t -> f0 * (1 + 0.3 * Math.exp(-t / 0.005)), t -> 0.12 * Math.exp(-t / 0.012)); }
        noise(y, 0.03, 0.65, LP, t -> 1400, 0.7, t -> 0.18 * ramp(t, 0.05) * Math.exp(-t / 0.2), true, r);
        highpass(y, 30);
        fadeOut(y, 0.06);
        return y;
    }

    static double[] sfxDeath() {
        double D = 0.8; double[] y = buf(D); Rng r = new Rng(2400);
        noise(y, 0, 0.03, LP, t -> 6000, 0.7, t -> 0.5 * Math.exp(-t / 0.004), false, r);
        grain(y, 0.0, 2600, 2.5, 0.004, 0.75, r); grain(y, 0.006, 3600, 3, 0.003, 0.5, r); grain(y, 0.015, 1900, 2.5, 0.005, 0.45, r);
        osc(y, 0, 0.5, SINE, t -> 55 + 70 * Math.exp(-t / 0.035), t -> 0.9 * ramp(t, 0.0008) * Math.exp(-t / 0.11));
        Osc a = new Osc(SQUARE, 0), b = new Osc(TRI, 0.25); SVF lp = new SVF();
        for (int i = ns(0.09); i < y.length; i++) {
            double t = tt(i) - 0.09, p = Math.min(1, t / 0.55);
            double f = 520 * Math.pow(0.5, Math.pow(p, 0.85)) * (1 + 0.012 * Math.sin(TAU * 6 * t));
            double env = ramp(t, 0.015) * (t < 0.45 ? 1 : Math.exp(-(t - 0.45) / 0.08)) * (0.8 + 0.2 * Math.exp(-t / 0.1));
            y[i] += 0.32 * lp.lp(0.35 * a.next(f) + 0.65 * b.next(f), 1700 - 900 * p, 0.8) * env;
        }
        highpass(y, 30);
        fadeOut(y, 0.05);
        return y;
    }

    static double[] sfxShieldPop() {
        double D = 0.4; double[] y = buf(D); Rng r = new Rng(2500);
        osc(y, 0, 0.06, SINE, t -> 1500 - 1150 * Math.exp(-t / 0.006), t -> 0.9 * ramp(t, 0.0005) * Math.exp(-t / 0.018));
        noise(y, 0, 0.01, HP, t -> 2500, 0.7, t -> 0.35 * Math.exp(-t / 0.0012), false, r);
        double[] sh = {2093.0, 2637.0, 3136.0, 4186.0}, am = {0.2, 0.16, 0.12, 0.07};
        for (int k = 0; k < 4; k++) {
            double st = 0.008 + 0.012 * k, tr = 19 + 3 * k, fk = sh[k], ak = am[k];
            osc(y, st, D - st, SINE, t -> fk * (1 - 0.012 * (1 - Math.exp(-t / 0.15))), t -> ak * ramp(t, 0.004) * Math.exp(-t / 0.16) * (0.65 + 0.35 * Math.sin(TAU * tr * t)));
        }
        for (int i = 0; i < 10; i++) grain(y, r.range(0.02, 0.3), r.range(5500, 9000), 7, 0.0025, 0.08 * r.range(0.5, 1), r);
        y = reverbMono(y, 0.5, 0.4, 0.12, 0);
        fadeOut(y, 0.05);
        return y;
    }

    static double[] sfxPowerup() {
        double D = 0.62; double[] y = buf(D); Rng r = new Rng(2600);
        String[] names = {"E5", "G#5", "B5", "E6", "G#6", "B6"};
        for (int k = 0; k < names.length; k++) {
            double t0 = 0.048 * k, f = mtof(nn(names[k]));
            bellNote(y, t0, f, 0.42 * (1 - 0.06 * k), k == names.length - 1 ? 0.22 : 0.1, 2.0, 1.1);
            osc(y, t0, 0.15, SQUARE, t -> f, t -> 0.05 * ramp(t, 0.001) * Math.exp(-t / 0.04));
        }
        osc(y, 0, 0.45, TRI, t -> 330 * Math.pow(4, Math.min(1, t / 0.3)), t -> 0.14 * Math.sin(Math.PI * Math.min(1, t / 0.45)));
        for (int i = 0; i < 16; i++) partial(y, r.range(0.08, 0.5), r.range(4000, 8000), r.range(0.03, 0.07), r.range(0.015, 0.04), 0.0005);
        echo(y, 0.085, 0.35, 0.28, 5000);
        lowpass(y, 12000);
        fadeOut(y, 0.06);
        return y;
    }

    static void coinTone(double[] y, double t0, double f, double len, double tau, double amp) {
        double[] ah = {1, 0.35, 0.25, 0.12, 0.08};
        for (int h = 1; h <= 5; h++) {
            double fh = f * h, a = amp * ah[h - 1];
            if (fh > 15000) continue;
            osc(y, t0, len, SINE, t -> fh, t -> a * ramp(t, 0.0007) * Math.exp(-t / tau) * Math.min(1, Math.max(0, (len - t) / 0.01)));
        }
        partial(y, t0, f * 2.76, amp * 0.08, 0.03, 0.0005);
    }
    static double[] sfxCoin() {
        double[] y = buf(0.3); Rng r = new Rng(2700);
        coinTone(y, 0.0, 987.77, 0.072, 0.5, 0.55);
        coinTone(y, 0.065, 1318.51, 0.235, 0.09, 0.8);
        noise(y, 0, 0.01, HP, t -> 4000, 0.7, t -> 0.2 * Math.exp(-t / 0.0008), false, r);
        fadeOut(y, 0.04);
        return y;
    }

    static void chime(double[] y, double t0, double f, double amp, double tau) {
        double[] ratio = {1, 2.76, 5.40}, a = {1, 0.35, 0.12}, ta = {1, 0.35, 0.15};
        for (int k = 0; k < 3; k++) {
            if (f * ratio[k] > 15000) continue;
            partial(y, t0, f * ratio[k], amp * a[k] * 0.55, tau * ta[k], 0.0006);
            partial(y, t0, f * ratio[k] + 2.5, amp * a[k] * 0.45, tau * ta[k], 0.0006);
        }
    }
    static double[] sfxGem() {
        double[] y = buf(0.52); Rng r = new Rng(2800);
        chime(y, 0.0, 1046.5, 0.5, 0.33); chime(y, 0.045, 1568.0, 0.38, 0.28); chime(y, 0.09, 2093.0, 0.2, 0.22);
        for (int i = 0; i < 12; i++) grain(y, r.range(0, 0.4), r.range(6000, 9500), 6, 0.003, 0.07, r);
        for (int i = 0; i < 6; i++) partial(y, r.range(0.05, 0.35), r.range(5000, 8000), 0.04, 0.03, 0.0005);
        y = reverbMono(y, 0.55, 0.35, 0.18, 0);
        fadeOut(y, 0.06);
        return y;
    }

    static double[] sfxGateHit() {
        double D = 0.46; double[] y = buf(D); Rng r = new Rng(2900);
        double[] th = buf(D);
        osc(th, 0, D, SINE, t -> 55 + 70 * Math.exp(-t / 0.028), t -> ramp(t, 0.0006) * Math.exp(-t / 0.13));
        saturate(th, 2.0); addInto(y, th, 0, 0.9);
        noise(y, 0, 0.25, BP, t -> 340, 1.2, t -> 0.65 * ramp(t, 0.0005) * Math.exp(-t / 0.05), false, r);
        noise(y, 0, 0.15, BP, t -> 820, 2, t -> 0.35 * Math.exp(-t / 0.03), false, r);
        double[] mf = {431, 1187, 1873, 2711, 3390}, ma = {0.22, 0.18, 0.12, 0.08, 0.05}, mt = {0.22, 0.14, 0.1, 0.07, 0.05};
        for (int k = 0; k < mf.length; k++) partial(y, 0.002, mf[k] * r.range(0.99, 1.01), ma[k], mt[k], 0.0005);
        for (int i = 0; i < 6; i++) grain(y, r.range(0.003, 0.05), r.range(1800, 4200), 3, 0.003, 0.45, r);
        for (int i = 0; i < 9; i++) { double t0 = r.range(0.06, 0.38); grain(y, t0, r.range(1500, 5000), 5, 0.003, 0.1 * (1 - t0 / 0.45), r); }
        fadeOut(y, 0.05);
        return y;
    }

    static double[] sfxGateBreak() {
        double D = 1.22; double[] y = buf(D); Rng r = new Rng(3000);
        double[] th = buf(D);
        osc(th, 0, D, SINE, t -> 42 + 60 * Math.exp(-t / 0.05), t -> ramp(t, 0.0006) * Math.exp(-t / 0.33));
        saturate(th, 2.0); addInto(y, th, 0, 0.9);
        noise(y, 0, D, LP, t -> 180 + 3400 * Math.exp(-t / 0.18), 0.8, t -> 0.9 * ramp(t, 0.002) * Math.exp(-t / 0.28), true, r);
        for (int i = 0; i < 6; i++) grain(y, r.range(0, 0.03), r.range(1500, 4000), 2.5, 0.004, 0.55, r);
        noise(y, 0, 0.4, BP, t -> 600, 1.5, t -> 0.5 * ramp(t, 0.0005) * Math.exp(-t / 0.08), false, r);
        for (int i = 0; i < 70; i++) {
            double t0 = 0.04 + truncExp(r, 0.35, 1.08);
            grain(y, t0, r.range(300, 2200), r.range(1.5, 4), r.range(0.008, 0.03), 0.45 * Math.exp(-t0 / 0.45) * r.range(0.4, 1), r);
        }
        for (int i = 0; i < 10; i++) {
            double t0 = r.range(0.08, 0.95), f0 = r.range(110, 260), a = 0.28 * Math.exp(-t0 / 0.5);
            osc(y, t0, 0.15, SINE, t -> f0 * (1 + 0.5 * Math.exp(-t / 0.006)), t -> a * ramp(t, 0.0005) * Math.exp(-t / 0.03));
        }
        for (int i = 0; i < 45; i++) { double t0 = r.range(0.15, 1.15); grain(y, t0, r.range(2500, 7500), 5, r.range(0.002, 0.005), 0.08 * (1 - t0 / 1.3), r); }
        noise(y, 0, D, LP, t -> 220, 0.7, t -> 0.35 * ramp(t, 0.02) * Math.exp(-t / 0.45), true, r);
        highpass(y, 28);
        fadeOut(y, 0.15);
        return y;
    }

    static void clink(double[] y, double t0, double f, double amp, Rng r) {
        double[] ratio = {1, 2.32, 4.25}, a = {1, 0.5, 0.22}, ta = {0.07, 0.035, 0.015};
        for (int k = 0; k < 3; k++) if (f * ratio[k] < 15000) partial(y, t0, f * ratio[k], amp * a[k], ta[k], 0.0004);
        grain(y, t0, 7000, 2, 0.0008, amp * 0.3, r);
    }
    static double[] sfxVaultBreak() {
        double D = 0.92; double[] y = buf(D); Rng r = new Rng(3100);
        osc(y, 0, 0.3, SINE, t -> 60 + 90 * Math.exp(-t / 0.02), t -> 0.6 * ramp(t, 0.0005) * Math.exp(-t / 0.09));
        noise(y, 0, 0.1, BP, t -> 1100, 0.9, t -> 0.5 * ramp(t, 0.0004) * Math.exp(-t / 0.02), false, r);
        for (int i = 0; i < 16; i++) grain(y, 0.1 * Math.pow(r.u(), 1.5), r.range(1500, 6000), r.range(3, 7), r.range(0.003, 0.009), r.range(0.25, 0.45), r);
        bellNote(y, 0.012, 1760, 0.22, 0.25, 1.41, 1.5);
        for (int i = 0; i < 18; i++) {
            double t0 = 0.05 + 0.8 * Math.pow(i / 18.0, 1.25) + r.range(-0.015, 0.015);
            double amp = 0.32 * (1 - 0.5 * i / 18.0) * r.range(0.7, 1), f = r.range(2400, 4300);
            clink(y, t0, f, amp, r);
            if (r.u() < 0.4) clink(y, t0 + r.range(0.018, 0.035), f * r.range(0.98, 1.02), amp * 0.45, r);
        }
        y = reverbMono(y, 0.5, 0.4, 0.12, 0);
        fadeOut(y, 0.08);
        return y;
    }

    static double[] sfxCrumble() {
        double D = 0.36; double[] y = buf(D); Rng r = new Rng(3200);
        grain(y, 0, 2200, 2.5, 0.006, 0.8, r); grain(y, 0.011, 3100, 2.5, 0.005, 0.55, r); grain(y, 0.027, 1700, 2.5, 0.007, 0.5, r);
        osc(y, 0, 0.15, SINE, t -> 180 + 90 * Math.exp(-t / 0.006), t -> 0.5 * ramp(t, 0.0004) * Math.exp(-t / 0.035));
        noise(y, 0, 0.15, BP, t -> 900, 1.2, t -> 0.55 * ramp(t, 0.0003) * Math.exp(-t / 0.03), false, r);
        noise(y, 0, 0.08, BP, t -> 1400, 1, t -> 0.4 * ramp(t, 0.0003) * Math.exp(-t / 0.012), false, r);
        for (int i = 0; i < 18; i++) {
            double t0 = 0.04 + 0.29 * Math.pow(r.u(), 1.5);
            grain(y, t0, r.range(1000, 4500), r.range(3, 7), r.range(0.004, 0.012), 0.3 * (1 - (t0 - 0.04) / 0.33) * r.range(0.5, 1), r);
        }
        fadeOut(y, 0.04);
        return y;
    }

    static double[] sfxBoost() {
        double D = 0.5; double[] y = buf(D); Rng r = new Rng(3300);
        Osc s1 = new Osc(SAW, 0), s2 = new Osc(SQUARE, 0); SVF lp = new SVF();
        for (int i = 0; i < y.length; i++) {
            double t = tt(i), p = Math.min(1, t / 0.42);
            double f = 1900 * Math.pow(110 / 1900.0, Math.pow(p, 0.75)) * (1 + 0.04 * Math.sin(TAU * 32 * t));
            double v = 0.6 * s1.next(f) + 0.4 * s2.next(f * 0.5 * 1.003);
            double env = ramp(t, 0.0015) * Math.pow(Math.max(0, 1 - t / D), 1.4);
            y[i] += 0.4 * lp.lp(v, Math.min(12000, f * 3.5 + 300), 3.0) * env;
        }
        noise(y, 0, D, BP, t -> 5200 * Math.pow(400 / 5200.0, Math.min(1, t / 0.45)), 1.3,
            t -> 0.45 * (t < 0.06 ? 0.3 + 0.7 * t / 0.06 : Math.exp(-(t - 0.06) / 0.14)), false, r);
        partial(y, 0, 2600, 0.25, 0.008, 0.0003);
        saturate(y, 1.4);
        fadeOut(y, 0.04);
        return y;
    }

    static double[] sfxLevelComplete() {
        double D = 2.6; double[] y = buf(D); Rng r = new Rng(3400);
        // drum fill -> hits
        double[] sn = snare(new Rng(1), 200, 0.12, 0.5, 9000);
        double[] kk = kick(new Rng(2), 160, 48, 0.03, 0.22, 0.4, 1.6);
        double[] cr = crash(new Rng(3), 0.7);
        double[][] tm = {tom(new Rng(4), 220), tom(new Rng(5), 175), tom(new Rng(6), 140)};
        addInto(y, sn, 0.00, 0.3); addInto(y, sn, 0.10, 0.36); addInto(y, sn, 0.20, 0.42);
        addInto(y, tm[0], 0.30, 0.4); addInto(y, tm[1], 0.40, 0.42); addInto(y, tm[2], 0.50, 0.45);
        addInto(y, kk, 0.60, 0.55); addInto(y, cr, 0.60, 0.14);
        for (int i = 0; i < 5; i++) addInto(y, sn, 1.00 + 0.05 * i, 0.25 + 0.05 * i);
        addInto(y, kk, 1.25, 0.6); addInto(y, cr, 1.25, 0.18);
        // brass melody + chords
        double[][] mel = {{0.00, 67, 0.10}, {0.10, 72, 0.10}, {0.20, 76, 0.10}, {0.30, 79, 0.28}, {0.60, 81, 0.18},
            {0.80, 79, 0.09}, {0.90, 81, 0.09}, {1.00, 83, 0.23}, {1.25, 84, 1.05}};
        for (double[] m : mel) addInto(y, brass(mtof(m[1]), m[2], 0.95, r)[0], m[0], 0.5);
        double[][] ch = {{0.60, 0.36, 65, 69, 72}, {1.00, 0.24, 67, 71, 74}, {1.25, 1.05, 64, 67, 72}};
        for (double[] c : ch) for (int k = 2; k < c.length; k++) addInto(y, brass(mtof(c[k]), c[1], 0.7, r)[0], c[0], 0.2);
        addInto(y, brass(mtof(76), 1.05, 0.7, r)[0], 1.25, 0.18);
        double[][] bs = {{0.0, 48, 0.3}, {0.6, 41, 0.36}, {1.0, 43, 0.24}, {1.25, 36, 1.1}};
        for (double[] b : bs) addInto(y, subBass(mtof(b[1]), b[2], 0.9, r)[0], b[0], 0.35);
        // bells + sparkle + shimmer
        String[] bn = {"C6", "E6", "G6", "C7"};
        for (int k = 0; k < 4; k++) bellNote(y, 1.25 + 0.07 * k, mtof(nn(bn[k])), 0.22, 0.5, 2.0, 1.0);
        for (int i = 0; i < 22; i++) partial(y, r.range(1.3, 2.3), r.range(3000, 8000), r.range(0.03, 0.07) * 0.8, r.range(0.02, 0.06), 0.0005);
        for (String nm : new String[]{"C5", "E5", "G5"}) {
            double f = mtof(nn(nm));
            osc(y, 1.25, 1.3, SINE, t -> f, t -> 0.07 * Math.min(1, t / 0.1) * Math.exp(-t / 0.5) * (0.8 + 0.2 * Math.sin(TAU * 6 * t)));
        }
        y = reverbMono(y, 0.72, 0.4, 0.22, 0);
        fadeOut(y, 0.25);
        return y;
    }

    static double[] sfxStar() {
        double D = 0.45; double[] y = buf(D); Rng r = new Rng(3500);
        osc(y, 0, 0.06, SINE, t -> 1700 - 1200 * Math.exp(-t / 0.005), t -> 0.55 * ramp(t, 0.0005) * Math.exp(-t / 0.014));
        fm(y, 0.004, D, 1318.5, 2.0, t -> 1.6 * Math.exp(-t / 0.04) + 0.25, t -> 0.55 * ramp(t, 0.0008) * Math.exp(-t / 0.13));
        fm(y, 0.004, D, 1318.5 * 1.003, 2.0, t -> 1.2 * Math.exp(-t / 0.04) + 0.2, t -> 0.25 * ramp(t, 0.0008) * Math.exp(-t / 0.13));
        partial(y, 0.004, 3955.5, 0.12, 0.05, 0.0006);
        for (int i = 0; i < 8; i++) grain(y, r.range(0.02, 0.3), r.range(6000, 9000), 6, 0.0025, 0.07, r);
        y = reverbMono(y, 0.45, 0.4, 0.12, 0);
        fadeOut(y, 0.05);
        return y;
    }

    static double[] sfxFail() {
        double D = 1.62; double[] y = buf(D); Rng r = new Rng(3600);
        double[][] mel = {{0.00, 72, 0.2}, {0.21, 70, 0.2}, {0.42, 67, 0.2}, {0.63, 68, 0.24}, {0.88, 67, 0.62}};
        for (int k = 0; k < mel.length; k++) {
            double t0 = mel[k][0], f = mtof(mel[k][1]), dur = mel[k][2]; boolean last = k == mel.length - 1;
            Osc a = new Osc(SQUARE, 0), b = new Osc(SINE, 0); SVF lp = new SVF();
            int s0 = ns(t0), n = Math.min(ns(dur + 0.12), y.length - s0);
            for (int i = 0; i < n; i++) {
                double t = tt(i);
                double cents = last ? -45 * smoothstep(0.15, 0.6, t) + 14 * smoothstep(0.1, 0.3, t) * Math.sin(TAU * 5 * t) : 0;
                double ff = f * Math.pow(2, cents / 1200.0);
                double v = lp.lp(0.4 * a.next(ff), 1300, 0.7) + 0.6 * b.next(ff);
                y[s0 + i] += 0.35 * v * adsr(t, dur, k == 0 ? 0.003 : 0.012, 0.2, 0.8, 0.1);
            }
        }
        double[][] ch = {{0.0, 0.6, 53, 56, 60}, {0.63, 0.95, 55, 60, 63}};
        for (double[] c : ch) for (int k = 2; k < c.length; k++) addStereoAsMono(y, ePiano(mtof(c[k]), c[1], 0.5, r), c[0], 0.12);
        addInto(y, warmBass(mtof(41), 0.6, 0.8, r)[0], 0.0, 0.18);
        addInto(y, warmBass(mtof(36), 0.9, 0.8, r)[0], 0.63, 0.18);
        y = reverbMono(y, 0.6, 0.5, 0.18, 0);
        fadeOut(y, 0.2);
        return y;
    }

    static double[] sfxUiTap() {
        double[] y = buf(0.08); Rng r = new Rng(3700);
        osc(y, 0, 0.08, SINE, t -> 560 + 520 * (1 - Math.exp(-t / 0.009)), t -> ramp(t, 0.0005) * Math.exp(-t / 0.017));
        osc(y, 0, 0.04, SINE, t -> 300, t -> 0.25 * ramp(t, 0.0005) * Math.exp(-t / 0.008));
        noise(y, 0, 0.01, BP, t -> 4200, 1.5, t -> 0.12 * Math.exp(-t / 0.0008), false, r);
        lowpass(y, 6000);
        fadeOut(y, 0.015);
        return y;
    }
    static double[] sfxUiBack() {
        double[] y = buf(0.09); Rng r = new Rng(3701);
        osc(y, 0, 0.09, SINE, t -> 400 + 260 * Math.exp(-t / 0.012), t -> ramp(t, 0.0006) * Math.exp(-t / 0.022));
        osc(y, 0, 0.05, SINE, t -> 220, t -> 0.25 * ramp(t, 0.0006) * Math.exp(-t / 0.01));
        noise(y, 0, 0.01, BP, t -> 2500, 1.2, t -> 0.08 * Math.exp(-t / 0.0008), false, r);
        lowpass(y, 4000);
        fadeOut(y, 0.015);
        return y;
    }
    static void uiClick(double[] y, double t0, double a, Rng r) {
        grain(y, t0, 3200, 4, 0.0012, 0.5 * a, r);
        partial(y, t0, 2400, 0.25 * a, 0.004, 0.0002);
        partial(y, t0, 900, 0.25 * a, 0.003, 0.0003);
    }
    static double[] sfxUiToggle() {
        double[] y = buf(0.1); Rng r = new Rng(3702);
        uiClick(y, 0, 1.0, r); uiClick(y, 0.024, 0.55, r);
        osc(y, 0.008, 0.07, SINE, t -> 1150 + 350 * (1 - Math.exp(-t / 0.01)), t -> 0.45 * ramp(t, 0.002) * Math.exp(-t / 0.018));
        fadeOut(y, 0.015);
        return y;
    }
    static double[] sfxUiOpen() {
        double D = 0.3; double[] y = buf(D); Rng r = new Rng(3703);
        noise(y, 0, D, BP, t -> 700 * Math.pow(5, Math.min(1, t / 0.22)), 0.9, t -> 0.8 * (t < 0.1 ? 0.35 + 0.65 * t / 0.1 : Math.exp(-(t - 0.1) / 0.06)), true, r);
        osc(y, 0, D, SINE, t -> 600 * Math.pow(2, Math.min(1, t / 0.2)), t -> 0.08 * (t < 0.1 ? t / 0.1 : Math.exp(-(t - 0.1) / 0.06)));
        osc(y, 0, 0.04, SINE, t -> 700 + 400 * (1 - Math.exp(-t / 0.008)), t -> 0.3 * ramp(t, 0.0005) * Math.exp(-t / 0.012));
        fadeOut(y, 0.04);
        return y;
    }
    static double[] sfxSwoosh() {
        double D = 0.36; double[] y = buf(D); Rng r = new Rng(3704);
        DoubleUnaryOperator env = t -> 0.08 + 0.92 * Math.pow(Math.sin(Math.PI * Math.min(1, (t + 0.012) / (D + 0.012))), 1.6);
        DoubleUnaryOperator sweep = t -> 500 + 4300 * Math.pow(Math.sin(Math.PI * Math.min(1, t / D)), 1.5);
        noise(y, 0, D, BP, sweep, 2.2, env, false, r);
        noise(y, 0, D, BP, t -> sweep.applyAsDouble(t) * 1.7, 2.2, t -> 0.6 * env.applyAsDouble(t), false, r);
        noise(y, 0, D, LP, t -> 400, 0.7, t -> 0.35 * Math.pow(Math.sin(Math.PI * Math.min(1, (t + 0.012) / (D + 0.012))), 1.6), true, r);
        fadeOut(y, 0.03);
        return y;
    }

    static double[] sfxPurchase() {
        double D = 0.7; double[] y = buf(D); Rng r = new Rng(3900);
        grain(y, 0, 1800, 2, 0.006, 0.6, r);
        osc(y, 0, 0.08, SINE, t -> 120 + 70 * Math.exp(-t / 0.006), t -> 0.5 * ramp(t, 0.0004) * Math.exp(-t / 0.016));
        partial(y, 0, 2150, 0.2, 0.012, 0.0003); partial(y, 0, 3320, 0.15, 0.009, 0.0003);
        grain(y, 0.032, 2600, 3, 0.004, 0.45, r); partial(y, 0.032, 1500, 0.15, 0.01, 0.0003);
        fm(y, 0.06, D, 1760, 1.41, t -> 2.2 * Math.exp(-t / 0.1) + 0.3, t -> 0.5 * ramp(t, 0.0006) * Math.exp(-t / 0.32) * (1 + 0.25 * Math.sin(TAU * 14 * t)));
        fm(y, 0.075, D, 2637, 2.0, t -> 1.0 * Math.exp(-t / 0.08) + 0.15, t -> 0.3 * ramp(t, 0.0006) * Math.exp(-t / 0.25));
        partial(y, 0.06, 3520, 0.08, 0.15, 0.0006);
        for (int i = 0; i < 6; i++) clink(y, r.range(0.07, 0.3), r.range(2800, 4500), 0.12, r);
        y = reverbMono(y, 0.45, 0.4, 0.12, 0);
        fadeOut(y, 0.08);
        return y;
    }

    static void buzz(double[] y, double t0, double f0, double len, double amp) {
        Osc sq = new Osc(SQUARE, 0), sw = new Osc(SAW, 0), sb = new Osc(SINE, 0); SVF lp = new SVF();
        int s0 = ns(t0), n = Math.min(ns(len), y.length - s0);
        for (int i = 0; i < n; i++) {
            double t = tt(i), f = f0 * (1 - 0.025 * t / len);
            double env = ramp(t, 0.004) * (t > len - 0.025 ? 0.5 * (1 + Math.cos(Math.PI * (t - (len - 0.025)) / 0.025)) : 1);
            double v = lp.lp(0.5 * sq.next(f) + 0.5 * sw.next(f), 1300, 0.8) + 0.5 * sb.next(f);
            y[s0 + i] += amp * 0.5 * v * env;
        }
    }
    static double[] sfxError() {
        double[] y = buf(0.26);
        buzz(y, 0.0, 233.08, 0.085, 1.0); buzz(y, 0.125, 196.0, 0.11, 0.95);
        saturate(y, 1.2);
        fadeOut(y, 0.01);
        return y;
    }

    static double[] sfxReward() {
        double D = 1.02; double[] y = buf(D); Rng r = new Rng(4000);
        noise(y, 0, 0.3, BP, t -> 800 * Math.pow(6, Math.min(1, t / 0.25)), 1.0, t -> 0.25 * (t < 0.1 ? 0.3 + 0.7 * t / 0.1 : Math.exp(-(t - 0.1) / 0.06)), true, r);
        String[] pent = {"G5", "A5", "B5", "D6", "E6", "G6", "A6", "B6", "D7"};
        int[] order = {0, 1, 2, 3, 4, 5, 6, 7, 8, 7, 8, 6, 7, 8};
        for (int k = 0; k < order.length; k++) {
            double t0 = 0.035 * k + (k == 0 ? 0 : r.range(-0.004, 0.004)), f = mtof(nn(pent[order[k]]));
            bellNote(y, t0, f, 0.3 * (1 - 0.03 * k), 0.18 + 0.02 * k, 2.0, 1.0);
            partial(y, t0, f * 2.76, 0.03, 0.04, 0.0005);
        }
        for (String nm : new String[]{"G5", "B5", "D6"}) {
            double f = mtof(nn(nm));
            osc(y, 0.05, D - 0.05, SINE, t -> f, t -> 0.07 * Math.min(1, t / 0.15) * (t < 0.45 ? 1 : Math.exp(-(t - 0.45) / 0.25)) * (0.75 + 0.25 * Math.sin(TAU * 7 * t)));
        }
        for (int i = 0; i < 30; i++) { double t0 = r.range(0.05, 0.9); grain(y, t0, r.range(5000, 10000), 6, 0.0025, 0.07 * (1 - t0), r); }
        for (int i = 0; i < 10; i++) partial(y, r.range(0.1, 0.8), r.range(4000, 8000), 0.04, 0.03, 0.0005);
        echo(y, 0.11, 0.3, 0.22, 5000);
        y = reverbMono(y, 0.6, 0.4, 0.15, 0);
        fadeOut(y, 0.15);
        return y;
    }

    static void knock(double[] y, double t0, double f, double amp, Rng r) {
        double[] ratio = {1, 2.31, 3.9, 5.7}, a = {1, 0.55, 0.3, 0.15}, ta = {0.05, 0.03, 0.02, 0.012};
        for (int k = 0; k < 4; k++) partial(y, t0, f * ratio[k], amp * a[k] * 0.5, ta[k], 0.0003);
        noise(y, t0, 0.12, BP, t -> 320, 2, t -> 0.4 * amp * ramp(t, 0.0004) * Math.exp(-t / 0.02), false, r);
        grain(y, t0, f * 4, 2, 0.003, amp * 0.5, r);
        partial(y, t0, f * 0.5, amp * 0.3, 0.015, 0.0005);
    }
    static double[] sfxChestShake() {
        double D = 0.6; double[] y = buf(D); Rng r = new Rng(4100);
        double[] times = {0, 0.055, 0.105, 0.16, 0.21, 0.30, 0.355, 0.405, 0.46, 0.51}, amps = {0.8, 0.6, 0.9, 0.55, 0.75, 0.85, 0.6, 0.95, 0.5, 0.7};
        for (int i = 0; i < times.length; i++) {
            knock(y, times[i], r.range(170, 240), amps[i], r);
            for (int j = 0; j < 3; j++) grain(y, times[i] + r.range(0.006, 0.03), r.range(2500, 5000), 5, 0.002, 0.15 * amps[i], r);
        }
        fadeOut(y, 0.05);
        return y;
    }
    static double[] sfxChestOpen() {
        double D = 1.42; double[] y = buf(D); Rng r = new Rng(4200);
        knock(y, 0, 140, 1.0, r);
        noise(y, 0, 0.35, BP, t -> 500 * Math.pow(6, Math.min(1, t / 0.15)), 1.0, t -> 0.45 * ramp(t, 0.002) * Math.exp(-t / 0.08), true, r);
        for (String nm : new String[]{"D5", "F#5", "A5", "E6"}) {
            double[][] ss = supersaw(mtof(nn(nm)), 0.85, 0.8, r, 0.5, 0.45, 2600, 5, 14, 200, 0);
            addStereoAsMono(y, ss, 0.04, 0.12);
        }
        osc(y, 0.05, 0.9, SINE, t -> 880 * Math.pow(2, Math.min(1, t / 0.6)), t -> 0.06 * Math.sin(Math.PI * Math.min(1, t / 0.9)) * (0.7 + 0.3 * Math.sin(TAU * 9 * t)));
        String[] pent = {"D6", "E6", "F#6", "A6", "B6", "D7"};
        for (int i = 0; i < 14; i++) { double t0 = 0.22 + r.u(); bellNote(y, t0, mtof(nn(pent[r.i(pent.length)])), 0.18 * (1 - (t0 - 0.2) / 1.3), 0.2, 2.0, 0.8); }
        for (int i = 0; i < 25; i++) grain(y, r.range(0.15, 1.25), r.range(6000, 10000), 6, 0.0025, 0.06, r);
        y = reverbMono(y, 0.7, 0.4, 0.22, 0);
        fadeOut(y, 0.15);
        return y;
    }

    static double[] sfxWheelTick() {
        double[] y = buf(0.04); Rng r = new Rng(4300);
        grain(y, 0, 3800, 3, 0.001, 1.0, r);
        partial(y, 0, 2300, 0.35, 0.005, 0.0002);
        partial(y, 0, 1250, 0.25, 0.006, 0.0002);
        partial(y, 0, 820, 0.3, 0.003, 0.0003);
        fadeOut(y, 0.01);
        return y;
    }
    static double[] sfxWheelWin() {
        double D = 1.22; double[] y = buf(D); Rng r = new Rng(4400);
        String[] arp = {"G5", "B5", "D6", "G6"};
        for (int k = 0; k < 4; k++) {
            double f = mtof(nn(arp[k])), t0 = 0.07 * k;
            addInto(y, pluck(f, 0.12, 0.9, r, 0.9, 0.12)[0], t0, 0.3);
            bellNote(y, t0, f, 0.2, 0.2, 2.0, 1.0);
        }
        for (String nm : new String[]{"G4", "B4", "D5", "G5"}) addInto(y, brass(mtof(nn(nm)), 0.4, 0.8, r)[0], 0.28, 0.2);
        for (String nm : new String[]{"G6", "B6", "D7"}) bellNote(y, 0.28, mtof(nn(nm)), 0.18, 0.45, 2.0, 0.9);
        osc(y, 0.28, 0.3, SINE, t -> 50 + 70 * Math.exp(-t / 0.03), t -> 0.35 * ramp(t, 0.0006) * Math.exp(-t / 0.1));
        for (int i = 0; i < 18; i++) partial(y, r.range(0.32, 1.1), r.range(3000, 8000), 0.05, r.range(0.02, 0.05), 0.0005);
        y = reverbMono(y, 0.6, 0.4, 0.2, 0);
        fadeOut(y, 0.15);
        return y;
    }

    static double[] sfxUnlock() {
        double D = 0.6; double[] y = buf(D); Rng r = new Rng(4500);
        grain(y, 0, 2600, 5, 0.0015, 0.55, r); partial(y, 0, 1850, 0.2, 0.008, 0.0002); partial(y, 0, 3150, 0.12, 0.006, 0.0002);
        grain(y, 0.045, 1900, 3, 0.003, 0.8, r);
        osc(y, 0.045, 0.06, SINE, t -> 420 * (1 + 0.2 * Math.exp(-t / 0.004)), t -> 0.4 * ramp(t, 0.0004) * Math.exp(-t / 0.01));
        partial(y, 0.045, 2400, 0.2, 0.012, 0.0002);
        bellNote(y, 0.09, mtof(nn("E6")), 0.4, 0.28, 2.0, 0.9);
        bellNote(y, 0.16, mtof(nn("B6")), 0.32, 0.3, 2.0, 0.8);
        partial(y, 0.16, mtof(nn("B6")) * 2.76, 0.04, 0.08, 0.0005);
        y = reverbMono(y, 0.45, 0.4, 0.12, 0);
        fadeOut(y, 0.06);
        return y;
    }
    static double[] sfxCountdownTick() {
        double[] y = buf(0.12); Rng r = new Rng(4600);
        partial(y, 0, 1600, 0.55, 0.012, 0.0002);
        partial(y, 0, 1600 * 2.6, 0.2, 0.005, 0.0002);
        grain(y, 0, 5000, 2, 0.0007, 0.3, r);
        osc(y, 0.002, 0.1, SQUARE, t -> 880, t -> 0.25 * ramp(t, 0.001) * Math.exp(-t / 0.03));
        lowpass(y, 4500);
        fadeOut(y, 0.02);
        return y;
    }

    // ------------------------------------------------------------------------
    //  SFX finishing: trim, de-click, loudness-normalise, peak-limit to -1 dBFS
    // ------------------------------------------------------------------------
    static double[] finishSfx(double[] x, double offsetDb) {
        Biquad hp = Biquad.make("hp", 28, 0.707, 0);
        for (int i = 0; i < x.length; i++) x[i] = hp.p(x[i]);
        double pk = peak(x);
        int start = 0; while (start < x.length && Math.abs(x[start]) < pk * 0.003) start++;
        int end = x.length; double tthr = pk * dbToGain(-72); while (end > start + 1 && Math.abs(x[end - 1]) < tthr) end--;
        x = Arrays.copyOfRange(x, start, end);
        int fi = Math.min(8, x.length); for (int i = 0; i < fi; i++) x[i] *= (i + 1.0) / (fi + 1.0);
        fadeOut(x, Math.min(0.006, x.length / 4.0 / SR));
        double g = dbToGain(SFX_TARGET + offsetDb - sfxLoudness(x));
        double ceil = dbToGain(SFX_CEILING_DB), p = peak(x) * g;
        if (p > ceil * dbToGain(SFX_MAX_LIMIT_DB)) g = ceil * dbToGain(SFX_MAX_LIMIT_DB) / peak(x);
        for (int i = 0; i < x.length; i++) x[i] *= g;
        limit(new double[][]{x}, SFX_CEILING_DB, 0.0015, 0.04, false);
        return x;
    }

    record SfxDef(String name, double offsetDb, Supplier<double[]> gen) {}
    static List<SfxDef> sfxDefs() {
        List<SfxDef> l = new ArrayList<>();
        for (int k = 0; k < 4; k++) { int kk = k; l.add(new SfxDef("sfx_bounce_" + (k + 1), -3, () -> sfxBounce(kk))); }
        l.add(new SfxDef("sfx_ring_break", -1, SynthStudio::sfxRingBreak));
        l.add(new SfxDef("sfx_combo_note", -1, SynthStudio::sfxComboNote));
        l.add(new SfxDef("sfx_blaze_ignite", 0.5, SynthStudio::sfxBlazeIgnite));
        l.add(new SfxDef("sfx_blaze_smash", 1, SynthStudio::sfxBlazeSmash));
        l.add(new SfxDef("sfx_death", 0.5, SynthStudio::sfxDeath));
        l.add(new SfxDef("sfx_shield_pop", 0, SynthStudio::sfxShieldPop));
        l.add(new SfxDef("sfx_powerup", 0, SynthStudio::sfxPowerup));
        l.add(new SfxDef("sfx_coin", -1, SynthStudio::sfxCoin));
        l.add(new SfxDef("sfx_gem", -0.5, SynthStudio::sfxGem));
        l.add(new SfxDef("sfx_gate_hit", 1, SynthStudio::sfxGateHit));
        l.add(new SfxDef("sfx_gate_break", 1.5, SynthStudio::sfxGateBreak));
        l.add(new SfxDef("sfx_vault_break", 1, SynthStudio::sfxVaultBreak));
        l.add(new SfxDef("sfx_crumble", -1, SynthStudio::sfxCrumble));
        l.add(new SfxDef("sfx_boost", 0, SynthStudio::sfxBoost));
        l.add(new SfxDef("sfx_level_complete", 1, SynthStudio::sfxLevelComplete));
        l.add(new SfxDef("sfx_star", 0, SynthStudio::sfxStar));
        l.add(new SfxDef("sfx_fail", 0, SynthStudio::sfxFail));
        l.add(new SfxDef("sfx_ui_tap", -5, SynthStudio::sfxUiTap));
        l.add(new SfxDef("sfx_ui_back", -6, SynthStudio::sfxUiBack));
        l.add(new SfxDef("sfx_ui_toggle", -5, SynthStudio::sfxUiToggle));
        l.add(new SfxDef("sfx_ui_open", -5, SynthStudio::sfxUiOpen));
        l.add(new SfxDef("sfx_swoosh", -4, SynthStudio::sfxSwoosh));
        l.add(new SfxDef("sfx_purchase", 0, SynthStudio::sfxPurchase));
        l.add(new SfxDef("sfx_error", -4, SynthStudio::sfxError));
        l.add(new SfxDef("sfx_reward", 0.5, SynthStudio::sfxReward));
        l.add(new SfxDef("sfx_chest_shake", -1, SynthStudio::sfxChestShake));
        l.add(new SfxDef("sfx_chest_open", 1, SynthStudio::sfxChestOpen));
        l.add(new SfxDef("sfx_wheel_tick", -7, SynthStudio::sfxWheelTick));
        l.add(new SfxDef("sfx_wheel_win", 0.5, SynthStudio::sfxWheelWin));
        l.add(new SfxDef("sfx_unlock", 0, SynthStudio::sfxUnlock));
        l.add(new SfxDef("sfx_countdown_tick", -4, SynthStudio::sfxCountdownTick));
        return l;
    }
    static Map<String, Supplier<Song>> musicDefs() {
        Map<String, Supplier<Song>> m = new LinkedHashMap<>();
        m.put("music_menu", SynthStudio::musicMenu);
        m.put("music_play_a", SynthStudio::musicPlayA);
        m.put("music_play_b", SynthStudio::musicPlayB);
        m.put("music_boss", SynthStudio::musicBoss);
        m.put("music_endless", SynthStudio::musicEndless);
        return m;
    }

    // ========================================================================
    //  CLI
    // ========================================================================
    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); return; }
        switch (args[0]) {
            case "render" -> render(args);
            case "analyze" -> analyze(args);
            case "expected" -> { for (SfxDef d : sfxDefs()) System.out.println(d.name() + ".wav"); for (String m : musicDefs().keySet()) System.out.println(m + ".m4a"); }
            default -> usage();
        }
    }
    static void usage() {
        System.out.println("SynthStudio render [--out DIR] [--work DIR] [--only name1,name2]\nSynthStudio analyze [--loop|--noloop] files...\nSynthStudio expected   (prints every file name the game expects)");
    }

    static void render(String[] args) throws Exception {
        Path out = Paths.get("app/src/main/res/raw"), work = Paths.get("tools/audio/build");
        Set<String> only = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = Paths.get(args[++i]);
                case "--work" -> work = Paths.get(args[++i]);
                case "--only" -> only = new HashSet<>(Arrays.asList(args[++i].split(",")));
                default -> throw new IllegalArgumentException("unknown arg " + args[i]);
            }
        }
        Files.createDirectories(out); Files.createDirectories(work.resolve("music"));
        long t0 = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() - 1));
        List<Future<String>> sfxJobs = new ArrayList<>(), musJobs = new ArrayList<>();
        final Path outF = out, workF = work;
        for (Map.Entry<String, Supplier<Song>> e : musicDefs().entrySet()) {
            if (only != null && !only.contains(e.getKey())) continue;
            musJobs.add(pool.submit(() -> {
                long ts = System.currentTimeMillis();
                Song s = e.getValue().get();
                double[][] st = s.render();
                writeWav(workF.resolve("music").resolve(e.getKey() + ".wav"), st);
                return String.format("%s%n%s  (%d ms)%n%s", statsLine(e.getKey() + ".wav", 2, stats(st, SR, true)), s.log, System.currentTimeMillis() - ts, "");
            }));
        }
        for (SfxDef d : sfxDefs()) {
            if (only != null && !only.contains(d.name())) continue;
            sfxJobs.add(pool.submit(() -> {
                double[] x = finishSfx(d.gen().get(), d.offsetDb());
                writeWav(outF.resolve(d.name() + ".wav"), new double[][]{x});
                return statsLine(d.name() + ".wav", 1, stats(new double[][]{x}, SR, false));
            }));
        }
        System.out.println("== SFX (" + out + ") ==");
        System.out.println(statsHeader());
        for (Future<String> f : sfxJobs) System.out.println(f.get());
        System.out.println("== Music WAV masters (" + work.resolve("music") + ") ==");
        System.out.println(statsHeader());
        for (Future<String> f : musJobs) System.out.print(f.get());
        pool.shutdown();
        System.out.printf("Rendered in %.1f s%n", (System.currentTimeMillis() - t0) / 1000.0);
    }

    static void analyze(String[] args) throws Exception {
        boolean loop = false;
        System.out.println(statsHeader());
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--loop")) { loop = true; continue; }
            if (args[i].equals("--noloop")) { loop = false; continue; }
            Path p = Paths.get(args[i]);
            Wav w = readWav(p);
            String sr = w.sr() == SR ? "" : "  (sr " + w.sr() + ")";
            System.out.println(statsLine(p.getFileName().toString(), w.ch().length, stats(w.ch(), w.sr(), loop)) + sr);
        }
    }
}
