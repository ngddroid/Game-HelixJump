package com.ngstudio.spindledrop.game.render

/** All GLSL ES 3.00 shader sources. Everything visual is procedural: no image assets. */
object Shaders {

    /** Surface fragment shader specialised for one world pattern (smaller, faster shader on mobile GPUs). */
    fun surfaceFs(pattern: Int): String = SURFACE_FS.replaceFirst("#version 300 es\n", "#version 300 es\n#define PAT $pattern\n")
    fun bakeFs(pattern: Int): String = BAKE_FS.replaceFirst("#version 300 es\n", "#version 300 es\n#define PAT $pattern\n")

    private const val NOISE = """
float hash21(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
vec2 hash22(vec2 p) { float n = hash21(p); return vec2(n, hash21(p + n + 17.17)); }
float vnoise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p);
    float a = hash21(i), b = hash21(i + vec2(1.0, 0.0)), c = hash21(i + vec2(0.0, 1.0)), d = hash21(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
float fbm(vec2 p) { float s = 0.0, a = 0.5; for (int i = 0; i < 3; i++) { s += a * vnoise(p); p = p * 2.03 + 11.7; a *= 0.5; } return s; }
// Distance to nearest Voronoi edge (approx) and cell id.
vec3 voronoi(vec2 p) {
    vec2 n = floor(p); vec2 f = fract(p);
    float d1 = 8.0, d2 = 8.0; vec2 id = vec2(0.0);
    for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) {
        vec2 g = vec2(float(i), float(j));
        vec2 o = hash22(n + g);
        vec2 r = g + o - f;
        float d = dot(r, r);
        if (d < d1) { d2 = d1; d1 = d; id = n + g; } else if (d < d2) { d2 = d; }
    }
    return vec3(sqrt(d2) - sqrt(d1), hash21(id), hash21(id + 3.1));
}
"""

    private const val LIGHTING = """
uniform vec3 uLightDir;
uniform vec3 uLightColor;
uniform vec3 uAmbSky;
uniform vec3 uAmbGround;
uniform vec3 uCamPos;
uniform vec3 uFogColor;
uniform vec2 uFog;
uniform float uFocusY;
uniform highp sampler2DShadow uShadow;
uniform float uShadowOn;
uniform vec2 uShadowTexel;
uniform float uTime;

float shadowAt(vec4 sp) {
    if (uShadowOn < 0.5) return 1.0;
    vec3 p = sp.xyz / sp.w * 0.5 + 0.5;
    if (p.x <= 0.0 || p.x >= 1.0 || p.y <= 0.0 || p.y >= 1.0 || p.z >= 1.0) return 1.0;
    float edge = smoothstep(0.0, 0.12, min(min(p.x, 1.0 - p.x), min(p.y, 1.0 - p.y)));
    float z = p.z - 0.0025;
    vec2 o = uShadowTexel * 1.2;
    float s = texture(uShadow, vec3(p.xy + vec2(-o.x, -o.y), z));
    s += texture(uShadow, vec3(p.xy + vec2(o.x, -o.y), z));
    s += texture(uShadow, vec3(p.xy + vec2(-o.x, o.y), z));
    s += texture(uShadow, vec3(p.xy + vec2(o.x, o.y), z));
    return mix(1.0, s * 0.25, edge);
}

vec3 lightIt(vec3 base, vec3 N, vec3 P, float gloss, float specK, float sh, float ao, float rimK) {
    vec3 V = normalize(uCamPos - P);
    float ndl = dot(N, uLightDir);
    float diff = clamp(ndl * 0.6 + 0.4, 0.0, 1.0) * mix(0.42, 1.0, sh);
    vec3 amb = mix(uAmbGround, uAmbSky, N.y * 0.5 + 0.5) * ao;
    vec3 H = normalize(uLightDir + V);
    float spec = pow(max(dot(N, H), 0.0), gloss) * specK * sh;
    float fres = pow(1.0 - max(dot(N, V), 0.0), 3.0);
    return base * (amb + uLightColor * diff) + uLightColor * spec + uAmbSky * fres * rimK;
}

vec3 fogIt(vec3 c, float y) {
    float f = smoothstep(uFog.x, uFog.y, uFocusY - y);
    return mix(c, uFogColor, f * 0.9);
}
"""

    // ---------------------------------------------------------------- surfaces (rings, pillar)
    val SURFACE_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aPolar;
uniform mat4 uModel;
uniform mat4 uViewProj;
uniform mat4 uLightVP;
out vec3 vWorld;
out vec3 vNormal;
out vec2 vPolar;
out vec4 vShadow;
out vec3 vLocal;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vNormal = mat3(uModel) * aNormal;
    vPolar = aPolar;
    vLocal = aPos;
    vShadow = uLightVP * (w + vec4(normalize(vNormal) * 0.035, 0.0));
    gl_Position = uViewProj * w;
}
"""

    val SURFACE_FS = """#version 300 es
precision highp float;
$LIGHTING
in vec3 vWorld;
in vec3 vNormal;
in vec2 vPolar;
in vec4 vShadow;
in vec3 vLocal;
uniform int uType;      // 0 normal 1 hazard 2 crumble 3 boost 4 shutter 5 pulse 6 gate 7 vault 8 finish 9 pillar
uniform vec3 uTop;
uniform vec3 uSide;
uniform vec3 uAccent;
uniform vec3 uHazard;
uniform vec3 uHazardGlow;
uniform vec3 uSpecial;
uniform float uGloss;
uniform float uState;
uniform float uAlpha;
uniform float uFlash;
uniform sampler2D uDetailA;   // baked overlay colour (rgb) + amount (a)
uniform sampler2D uDetailB;   // baked brightness*0.5 (r), emissive (g), phase (b), crack distance (a)
uniform sampler2D uNoise;     // tileable value noise
out vec4 o;

void main() {
    vec3 N = normalize(vNormal);
    if (!gl_FrontFacing) N = -N;
    float top = smoothstep(0.55, 0.95, N.y);
    float r = length(vLocal.xz);
    vec2 q = vLocal.xz;
    vec2 duv = q * 0.27027 + 0.5;
    float sh = shadowAt(vShadow);
    float ao = mix(0.6, 1.0, smoothstep(0.56, 0.9, r));
    float gloss = uGloss;
    float specK = 0.35;
    float rimK = 0.35;
    float bloom = 0.0;
    vec3 emis = vec3(0.0);
    float sideShade = mix(0.72, 1.0, smoothstep(-0.28, 0.0, vLocal.y));
    vec3 base = mix(uSide * sideShade, uTop, top);

    if (uType == 9) {
        // ---- Pillar ----
        float wy = vWorld.y;
        float ang = vPolar.x / 360.0;
        float stripe = smoothstep(0.42, 0.58, fract(ang * 6.0 + wy * 0.22));
        base = mix(uTop, uSide, stripe * 0.55);
        float n = texture(uNoise, vec2(ang * 3.0, wy * 0.19)).r;
        base *= 0.9 + n * 0.2;
#if PAT == 2 || PAT == 8
        float line = smoothstep(0.46, 0.49, abs(fract(wy * 0.5) - 0.5));
        emis += uAccent * line * 0.9;
        bloom = max(bloom, line * 0.8);
#endif
#if PAT == 3 || PAT == 10
        specK = 0.8; gloss = 60.0; rimK = 0.6;
#endif
        ao = 1.0;
        sh = mix(1.0, sh, 0.55);
    } else if (uType == 0 || uType == 2) {
        vec4 da = texture(uDetailA, duv);
        vec4 db = texture(uDetailB, duv);
        base *= db.r * 2.0;
        float ovA = da.a;
#if PAT == 1 || PAT == 12
        ovA *= top;
#elif PAT == 5
        ovA *= max(top, 0.4);
#endif
        base = mix(base, da.rgb, ovA);
        float em = db.g;
#if PAT == 1
        specK = 0.5; gloss = 48.0;
#elif PAT == 2
        float edge = smoothstep(0.86, 0.99, vPolar.y) * smoothstep(-0.06, 0.0, vLocal.y);
        emis += uAccent * (edge * 1.4 + em * top * 0.35);
        bloom = max(bloom, edge * 0.9 + em * top * 0.2);
        specK = 0.9; gloss = 80.0; rimK = 0.5;
#elif PAT == 3
        float tw = 0.5 + 0.5 * sin(uTime * 5.0 + db.b * 40.0);
        emis += vec3(em * tw) * top * 0.8;
        bloom = max(bloom, em * tw * top * 0.6);
        specK = 1.0; gloss = 90.0; rimK = 0.9;
#elif PAT == 4
        float pulse = 0.7 + 0.3 * sin(uTime * 2.0 + db.b * 6.0);
        emis += uAccent * em * top * pulse * 0.9;
        bloom = max(bloom, em * top * 0.7);
        specK = 0.2; gloss = 20.0;
#elif PAT == 5
        specK = 0.12; gloss = 12.0;
#elif PAT == 6
        float ca = sin(q.x * 6.0 + uTime * 1.3 + sin(q.y * 4.0 + uTime));
        float cb = sin(q.y * 6.5 - uTime * 1.1 + sin(q.x * 3.0 - uTime * 0.7));
        float caus = pow(1.0 - abs(ca * cb), 6.0) * top;
        emis += vec3(0.7, 0.95, 1.0) * caus * 0.45;
        bloom = max(bloom, caus * 0.25);
        specK = 0.7; gloss = 50.0; rimK = 0.6;
#elif PAT == 7
        specK = 0.1; gloss = 10.0;
#elif PAT == 8
        float strip = smoothstep(0.02, 0.0, abs(vLocal.y + 0.13) - 0.025) * (1.0 - top) * step(0.5, N.x * N.x + N.z * N.z);
        emis += uAccent * strip * 1.3;
        bloom = max(bloom, strip * 0.9);
        specK = 1.1; gloss = 70.0; rimK = 0.5;
#elif PAT == 9
        float rim = smoothstep(0.9, 1.0, vPolar.y) * top;
        base = mix(base, uAccent, rim * 0.8);
        specK = 1.0; gloss = 64.0;
#elif PAT == 10
        emis += uAccent * em * top * 0.8;
        bloom = max(bloom, em * top * 0.6);
        specK = 1.2; gloss = 110.0; rimK = 0.8;
#elif PAT == 11
        specK = 0.6; gloss = 36.0; rimK = 0.5;
#elif PAT == 12
        specK = 0.35; gloss = 24.0;
#endif
        if (uType == 2) {
            // ---- Crumble tiles: sandy with cracks that grow with hits ----
            base = mix(uSpecial * sideShade, uSpecial * 1.06, top) * (0.92 + db.b * 0.15);
            float w = mix(0.12, 0.35, clamp(uState, 0.0, 1.0));
            float crack = smoothstep(w, 0.0, db.a);
            base *= 1.0 - crack * mix(0.35, 0.7, uState);
            specK = 0.15; gloss = 14.0; emis = vec3(0.0); bloom = 0.0;
        }
    } else if (uType == 1 || uType == 5) {
        // ---- Hazards (static, or pulse when lit) ----
        float lit = uType == 1 ? 1.0 : uState;
        float stripes = step(0.5, fract((q.x + q.y) * 2.6 - uTime * 0.9));
        vec3 hz = uHazard * mix(1.0, 0.68, stripes);
        vec3 dim = mix(uSide * 0.8, uHazard * 0.6, 0.35) * mix(1.0, 0.85, stripes);
        base = mix(dim, hz, lit) * mix(0.8, 1.0, top);
        float pulse = 0.5 + 0.5 * sin(uTime * 6.0);
        emis += uHazardGlow * lit * (0.32 + 0.22 * pulse);
        bloom = max(bloom, lit * (0.45 + 0.25 * pulse));
        if (uType == 5 && lit < 0.5) {
            float flick = step(0.5, fract(uTime * 9.0)) * uAlpha;
            emis += uHazardGlow * flick * 0.5;
            bloom = max(bloom, flick * 0.4);
        }
        specK = 0.7; gloss = 40.0;
    } else if (uType == 3) {
        // ---- Boost pad ----
        float f = fract(r * 3.5 + uTime * 2.2);
        float bands = smoothstep(0.35, 0.5, f) * smoothstep(0.65, 0.5, f);
        base = mix(uSpecial * 0.75, uSpecial * 1.2, top);
        emis += uSpecial * (0.35 + bands * 0.9 * top);
        bloom = max(bloom, 0.35 + bands * 0.5);
        specK = 0.8; gloss = 50.0;
    } else if (uType == 4) {
        // ---- Shutter panel ----
        float stripes = smoothstep(0.45, 0.5, fract((q.x - q.y) * 3.0));
        base = mix(uAccent * 0.75, uAccent * 1.1, stripes) * 0.9;
        float edge = smoothstep(0.85, 1.0, vPolar.y) + smoothstep(0.15, 0.0, vPolar.y);
        emis += uAccent * (0.25 + edge * 0.6);
        bloom = max(bloom, 0.3 + edge * 0.4);
        specK = 1.0; gloss = 70.0;
    } else if (uType == 6) {
        // ---- Guardian Gate: stone with glowing runes, cracks grow with damage ----
        vec4 db = texture(uDetailB, duv);
        float n = texture(uNoise, q * 0.6 + vLocal.y * 0.3).r;
        base = uSpecial * (0.75 + n * 0.4) * mix(0.8, 1.05, top);
        float ang = atan(q.y, q.x);
        float rune = smoothstep(0.42, 0.46, abs(fract(ang * 1.909 + 0.25 * sin(r * 6.0)) - 0.5)) *
                     smoothstep(0.05, 0.0, abs(r - 1.18) - 0.32);
        float glow = 0.6 + 0.4 * sin(uTime * 3.0);
        emis += uAccent * rune * glow * (top * 0.9 + 0.3);
        bloom = max(bloom, rune * glow * 0.8);
        float crack = smoothstep(mix(0.0, 0.45, uState), 0.0, db.a) * step(0.01, uState);
        base *= 1.0 - crack * 0.75;
        emis += uAccent * crack * 0.9 * uState;
        specK = 0.4; gloss = 30.0;
    } else if (uType == 7) {
        // ---- Coin Vault ring ----
        vec3 gold = vec3(1.0, 0.78, 0.25);
        vec2 g = fract(q * 3.2) - 0.5;
        float coinEdge = smoothstep(0.03, 0.0, abs(length(g) - 0.3));
        base = mix(gold * 0.72, gold * 1.08, top) * (1.0 - coinEdge * 0.3);
        float ang = atan(q.y, q.x);
        float sweep = smoothstep(0.40, 0.48, abs(fract(ang / 6.2831 - uTime * 0.35) - 0.5));
        emis += vec3(1.0, 0.9, 0.6) * sweep * 0.6;
        bloom = max(bloom, 0.2 + sweep * 0.5);
        specK = 1.3; gloss = 70.0; rimK = 0.6;
    } else if (uType == 8) {
        // ---- Finish pad ----
        float ang = atan(q.y, q.x);
        float rays = smoothstep(0.3, 0.42, abs(fract(ang * 1.909 + uTime * 0.2) - 0.5));
        vec3 fin = mix(vec3(1.0, 0.86, 0.35), vec3(1.0), 0.35);
        base = mix(fin * 0.92, uAccent * 0.4 + fin * 0.6, rays * 0.5 * top) * mix(0.8, 1.0, top);
        float rim = smoothstep(0.9, 1.0, vPolar.y);
        emis += vec3(1.0, 0.85, 0.4) * (rim * 0.5 + rays * 0.08 * top);
        bloom = max(bloom, rim * 0.45);
        specK = 0.8; gloss = 60.0;
    }

    vec3 col = lightIt(base, N, vWorld, gloss, specK, sh, ao, rimK) + emis;
    col += vec3(uFlash);
    col = fogIt(col, vWorld.y);
    float a = (uType == 4) ? uAlpha : bloom;
    o = vec4(col, a);
}
"""

    /** Bakes a world's surface detail into two textures (run once per world). */
    val BAKE_FS = """#version 300 es
precision highp float;
$NOISE
in vec2 vUv;
uniform vec3 uAccent;
layout(location = 0) out vec4 oA;
layout(location = 1) out vec4 oB;

vec3 sprinkle(vec2 q, out float has) {
    vec2 g = q * 7.0;
    vec2 id = floor(g);
    vec2 f = fract(g) - 0.5;
    float h = hash21(id);
    has = 0.0;
    if (h < 0.42) {
        float ang = h * 31.0;
        vec2 d = vec2(cos(ang), sin(ang));
        vec2 off = (hash22(id + 5.0) - 0.5) * 0.4;
        vec2 p = f - off;
        float along = clamp(dot(p, d), -0.16, 0.16);
        float dist = length(p - d * along);
        has = smoothstep(0.075, 0.05, dist);
        float k = fract(h * 13.7);
        return k < 0.2 ? vec3(1.0, 0.35, 0.6) : k < 0.4 ? vec3(1.0, 0.86, 0.3) : k < 0.6 ? vec3(0.4, 0.9, 0.7) : k < 0.8 ? vec3(0.45, 0.7, 1.0) : vec3(1.0);
    }
    return vec3(0.0);
}

void main() {
    vec2 q = (vUv - 0.5) * 3.7;
    float r = length(q);
    float ang = atan(q.y, q.x);
    vec3 ov = vec3(1.0);
    float ovA = 0.0;
    float mult = 1.0;
    float em = 0.0;
    vec3 cv = voronoi(q * 4.0);
    float crack = clamp(cv.x / 0.5, 0.0, 1.0);
    float phase = cv.y;
#if PAT == 1
    ov = sprinkle(q, ovA);
#elif PAT == 2
    em = smoothstep(0.93, 1.0, max(abs(fract(q.x * 2.2) - 0.5), abs(fract(q.y * 2.2) - 0.5)) * 2.0);
#elif PAT == 3
    float n = fbm(q * 2.6);
    ov = vec3(1.0); ovA = smoothstep(0.55, 0.85, n) * 0.35;
    float hs = hash21(floor(q * 26.0));
    em = step(0.985, hs);
    phase = hs;
#elif PAT == 4
    float n = fbm(q * 3.0);
    mult = 0.75 + n * 0.5;
    vec3 v = voronoi(q * 2.6);
    em = smoothstep(0.06, 0.0, v.x);
    phase = v.y;
#elif PAT == 5
    float n = fbm(q * 2.2);
    float carve = smoothstep(0.44, 0.48, abs(fract(r * 3.0) - 0.5));
    mult = (0.85 + n * 0.3) * (1.0 - carve * 0.25);
    ov = uAccent; ovA = smoothstep(0.52, 0.68, fbm(q * 1.4 + 4.0)) * 0.8;
#elif PAT == 6
    float bands = sin(r * 18.0) * 0.5 + 0.5;
    mult = mix(1.0, 1.08, bands * 0.5);
#elif PAT == 7
    float band = sin(r * 10.0 + fbm(q * 1.5) * 3.0) * 0.5 + 0.5;
    mult = (0.9 + 0.12 * band) * (0.94 + 0.12 * vnoise(q * 30.0));
#elif PAT == 8
    float brushed = vnoise(vec2(ang * 80.0, r * 3.0));
    float panel = smoothstep(0.48, 0.495, abs(fract(ang * 3.8197) - 0.5));
    mult = (0.88 + brushed * 0.2) * (1.0 - panel * 0.3);
#elif PAT == 9
    float grain = sin((r + fbm(q * 1.7) * 0.35) * 42.0) * 0.5 + 0.5;
    mult = 0.86 + grain * 0.2;
#elif PAT == 10
    vec3 v = voronoi(q * 2.0);
    mult = 0.75 + v.y * 0.5;
    em = smoothstep(0.05, 0.0, v.x);
#elif PAT == 11
    float seam = smoothstep(0.47, 0.49, abs(fract(r * 2.0) - 0.5));
    mult = (0.97 + 0.05 * vnoise(q * 40.0)) * (1.0 - seam * 0.15);
#elif PAT == 12
    float plank = smoothstep(0.47, 0.495, abs(fract(ang * 4.77) - 0.5));
    mult = (1.0 - plank * 0.22) * (0.94 + 0.1 * vnoise(vec2(ang * 30.0, r * 2.0)));
    ov = uAccent; ovA = smoothstep(0.05, 0.0, abs(r - 1.15 - 0.08 * sin(ang * 9.0)) - 0.05);
#endif
    oA = vec4(ov, ovA);
    oB = vec4(clamp(mult * 0.5, 0.0, 1.0), em, phase, crack);
}
"""

    // ---------------------------------------------------------------- depth-only (shadow map)
    val DEPTH_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uModel;
uniform mat4 uLightVP;
void main() { gl_Position = uLightVP * uModel * vec4(aPos, 1.0); }
"""
    val DEPTH_FS = """#version 300 es
precision mediump float;
out vec4 o;
void main() { o = vec4(1.0); }
"""

    // ---------------------------------------------------------------- ball / coins
    val BALL_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
uniform mat4 uModel;
uniform mat4 uViewProj;
uniform mat4 uLightVP;
uniform mat3 uObjRot;
out vec3 vWorld;
out vec3 vNormal;
out vec3 vObj;
out vec3 vObjN;
out vec4 vShadow;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vNormal = normalize(mat3(uModel) * aNormal);
    vObj = uObjRot * aPos;
    vObjN = uObjRot * aNormal;
    vShadow = uLightVP * w;
    gl_Position = uViewProj * w;
}
"""

    val BALL_FS = """#version 300 es
precision highp float;
$LIGHTING
$NOISE
in vec3 vWorld;
in vec3 vNormal;
in vec3 vObj;
in vec3 vObjN;
in vec4 vShadow;
uniform int uSkin;
uniform vec3 uC1;
uniform vec3 uC2;
uniform vec3 uC3;
uniform float uMetal;
uniform float uEmis;
uniform float uBlaze;
uniform float uFlash;
uniform float uForceAlpha;
out vec4 o;

vec3 hue(float h) { return clamp(abs(fract(h + vec3(0.0, 0.6667, 0.3333)) * 6.0 - 3.0) - 1.0, 0.0, 1.0); }

void main() {
    vec3 N = normalize(vNormal);
    vec3 n = normalize(vObjN);
    vec3 V = normalize(uCamPos - vWorld);
    float sh = shadowAt(vShadow);
    vec3 base = uC1;
    vec3 emis = vec3(0.0);
    float bloom = 0.0;
    float gloss = 64.0;
    float specK = 1.0;
    float metal = uMetal;
    float lat = n.y;
    float lon = atan(n.z, n.x);

    if (uSkin == 1) {               // stripes
        base = mix(uC1, uC2, step(0.5, fract(lat * 2.5 + 0.25)));
    } else if (uSkin == 2) {        // candy swirl
        base = mix(uC1, uC2, step(0.5, fract(lon / 6.2831 * 3.0 + lat * 1.6)));
    } else if (uSkin == 3) {        // polka dots
        vec2 g = vec2(lon * 2.2, lat * 4.0);
        vec2 f = fract(g) - 0.5;
        base = mix(uC1, uC2, smoothstep(0.24, 0.2, length(f)));
    } else if (uSkin == 4) {        // galaxy
        float nb = fbm(vec2(lon * 2.0, lat * 3.0) + uTime * 0.05);
        base = mix(uC1, uC2, nb) + uC3 * smoothstep(0.6, 0.9, nb) * 0.5;
        float star = step(0.985, hash21(floor(vec2(lon * 40.0, lat * 40.0))));
        emis += vec3(star) * (0.6 + 0.4 * sin(uTime * 4.0 + lon * 10.0));
        bloom = star * 0.8;
        gloss = 90.0;
    } else if (uSkin == 5) {        // chrome / gold mirror
        metal = 1.0; gloss = 140.0;
    } else if (uSkin == 6) {        // lava core
        vec3 v = voronoi(vec2(lon * 2.5, lat * 3.0));
        float vein = smoothstep(0.12, 0.0, v.x);
        float flow = 0.7 + 0.3 * sin(uTime * 3.0 + v.y * 9.0);
        base = uC1 * (0.6 + v.z * 0.4);
        emis += uC2 * vein * flow * 1.4;
        bloom = vein * 0.9;
        gloss = 24.0; specK = 0.4;
    } else if (uSkin == 7) {        // frost ice
        float f = pow(1.0 - max(dot(N, V), 0.0), 2.0);
        base = mix(uC1, uC2, f) + vec3(fbm(vec2(lon * 3.0, lat * 3.0)) * 0.15);
        gloss = 120.0; specK = 1.4;
    } else if (uSkin == 8) {        // disco mirror tiles
        vec2 g = vec2(lon * 5.0, lat * 7.0);
        vec2 id = floor(g);
        float tile = hash21(id);
        float gap = smoothstep(0.42, 0.47, max(abs(fract(g.x) - 0.5), abs(fract(g.y) - 0.5)));
        base = mix(uC1 * (0.6 + tile * 0.6), vec3(0.15), gap);
        float sparkle = step(0.93, fract(tile * 7.0 + uTime * 0.8));
        emis += vec3(sparkle) * 0.9;
        bloom = sparkle * 0.7;
        metal = 0.8; gloss = 120.0;
    } else if (uSkin == 9) {        // ringed planet bands
        float b = fbm(vec2(lat * 8.0, lon * 0.3));
        base = mix(uC1, uC2, smoothstep(0.3, 0.7, sin(lat * 14.0 + b * 3.0) * 0.5 + 0.5));
    } else if (uSkin == 10 || uSkin == 19) {   // friendly faces (eyes + smile)
        vec2 p = vObj.xy / 1.0;
        float front = smoothstep(0.0, 0.3, vObj.z);
        float eyeL = length((p - vec2(-0.32, 0.22)) * vec2(1.0, uSkin == 19 ? 3.5 : 0.8));
        float eyeR = length((p - vec2(0.32, 0.22)) * vec2(1.0, uSkin == 19 ? 3.5 : 0.8));
        float eyes = smoothstep(0.13, 0.1, min(eyeL, eyeR));
        float mouthD = abs(length(p - vec2(0.0, 0.08)) - 0.42);
        float mouth = smoothstep(0.06, 0.035, mouthD) * step(p.y, -0.12) * step(abs(p.x), 0.34);
        float cheeks = smoothstep(0.14, 0.0, length(p - vec2(-0.5, -0.08))) + smoothstep(0.14, 0.0, length(p - vec2(0.5, -0.08)));
        base = mix(uC1, uC3, cheeks * 0.45 * front);
        base = mix(base, uC2, max(eyes, mouth) * front);
        gloss = 70.0;
    } else if (uSkin == 11) {       // checker
        vec2 g = vec2(lon * 2.546, lat * 4.0);
        float c = mod(floor(g.x) + floor(g.y), 2.0);
        base = mix(uC1, uC2, c);
    } else if (uSkin == 12) {       // moon craters
        vec3 v = voronoi(vec2(lon * 2.0, lat * 2.5));
        float crater = smoothstep(0.35, 0.15, v.x + v.y * 0.3);
        base = uC1 * (0.8 + fbm(vec2(lon, lat) * 4.0) * 0.35) * (1.0 - crater * 0.2);
        gloss = 16.0; specK = 0.25;
    } else if (uSkin == 13) {       // sun
        float t = fbm(vec2(lon * 3.0 + uTime * 0.4, lat * 3.0 - uTime * 0.3));
        base = mix(uC1, uC2, t);
        emis += mix(uC1, uC2, t) * 0.9;
        bloom = 0.9;
        specK = 0.2;
    } else if (uSkin == 14) {       // glazed donut
        float glaze = smoothstep(-0.05, 0.08, lat + 0.12 * sin(lon * 5.0));
        float has = step(0.7, hash21(floor(vec2(lon * 9.0, lat * 12.0)))) * glaze;
        vec3 spr = hue(hash21(floor(vec2(lon * 9.0, lat * 12.0)) + 0.3));
        base = mix(uC2, uC1, glaze);
        base = mix(base, spr, has * 0.9);
        gloss = 50.0;
    } else if (uSkin == 15) {       // neon wire globe
        float gx = smoothstep(0.44, 0.48, abs(fract(lon / 6.2831 * 12.0) - 0.5));
        float gy = smoothstep(0.44, 0.48, abs(fract(lat * 4.0) - 0.5));
        float w = max(gx, gy);
        base = uC1;
        emis += uC2 * w * 1.6;
        bloom = w;
    } else if (uSkin == 16) {       // marble
        float m = sin(lat * 6.0 + fbm(vec2(lon, lat) * 3.0) * 6.0);
        base = mix(uC1, uC2, smoothstep(0.85, 1.0, abs(m)));
        gloss = 110.0; specK = 1.2;
    } else if (uSkin == 17) {       // rainbow
        base = hue(lat * 0.5 + uTime * 0.15) * 0.9 + 0.1;
        gloss = 80.0;
    } else if (uSkin == 18) {       // pearl
        float f = 1.0 - max(dot(N, V), 0.0);
        base = mix(uC1, hue(f * 0.8 + 0.5) * 0.4 + 0.65, f * 0.8);
        gloss = 100.0; specK = 1.3;
    } else if (uSkin == 30) {       // coin
        float r = length(vObj.xz);
        float face = step(0.5, abs(n.y));
        float ring = smoothstep(0.012, 0.0, abs(r - 0.105)) * face;
        float ang = atan(vObj.z, vObj.x);
        float star = smoothstep(0.012, 0.0, r - (0.045 + 0.025 * cos(ang * 5.0))) * face;
        base = uC1 * (1.0 - ring * 0.35) * (1.0 + star * 0.25);
        metal = 0.9; gloss = 90.0;
        emis += uC1 * 0.12;
        bloom = 0.25;
    }

    vec3 col;
    vec3 R = reflect(-V, N);
    vec3 env = mix(uAmbGround * 1.3, uAmbSky * 1.5, R.y * 0.5 + 0.5) + uLightColor * pow(max(dot(R, uLightDir), 0.0), 24.0) * 1.5;
    vec3 dielectric = lightIt(base, N, vWorld, gloss, specK, sh, 1.0, 0.5);
    vec3 metallic = base * env * mix(0.6, 1.0, sh) + uLightColor * pow(max(dot(normalize(uLightDir + V), N), 0.0), gloss) * 1.2;
    col = mix(dielectric, metallic, metal);
    col += emis + base * uEmis;
    bloom = max(bloom, uEmis);

    // Blaze: fiery overlay with flickering emission.
    if (uBlaze > 0.0) {
        float flick = fbm(vec2(lon * 3.0, lat * 3.0 - uTime * 6.0));
        vec3 fire = mix(vec3(1.0, 0.35, 0.05), vec3(1.0, 0.85, 0.3), flick);
        col = mix(col, fire * 1.3, uBlaze * 0.8);
        bloom = max(bloom, uBlaze);
    }
    col += vec3(uFlash);
    o = vec4(col, uForceAlpha > 0.5 ? 1.0 : bloom);
}
"""

    // ---------------------------------------------------------------- fullscreen helpers
    val FULLSCREEN_VS = """#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
    vUv = p * 0.5 + 0.5;
    gl_Position = vec4(p, 0.0, 1.0);
}
"""

    // ---------------------------------------------------------------- sky + parallax silhouettes
    val SKY_FS = """#version 300 es
precision highp float;
in vec2 vUv;
uniform vec3 uSkyTop;
uniform vec3 uSkyBottom;
uniform vec3 uGlow;
uniform vec2 uSun;
uniform float uTime;
uniform float uAspect;
uniform float uPan;
uniform float uDepth;
uniform vec3 uFar;
uniform vec3 uMid;
uniform vec3 uNear;
uniform vec3 uFogColor;
uniform float uStars;
uniform float uClouds;
uniform float uSunSize;
uniform vec3 uLights;
uniform float uAA;
uniform sampler2D uProfile;
uniform sampler2D uNoise;
out vec4 o;

float h21(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }

void main() {
    vec2 uv = vUv;
    float t = uv.y;
    vec3 col = mix(uSkyBottom, uSkyTop, smoothstep(0.0, 1.0, t));
    col = mix(col, col * 0.82, uDepth * 0.4);
    vec2 d = (uv - uSun) * vec2(uAspect, 1.0);
    float sd = length(d);
    col += uGlow * (smoothstep(0.7, 0.0, sd) * 0.45 + smoothstep(uSunSize, uSunSize * 0.9, sd) * 0.9);
    if (uStars > 0.0 && t > 0.25) {
        vec2 g = uv * vec2(uAspect, 1.0) * 70.0 + vec2(uPan * 40.0, 0.0);
        float h = h21(floor(g));
        float s = step(0.992, h) * smoothstep(0.35, 0.0, length(fract(g) - 0.5)) * (0.6 + 0.4 * sin(uTime * 3.0 + h * 50.0));
        col += vec3(s) * uStars * smoothstep(0.25, 0.7, t);
    }
    if (uClouds > 0.0 && t > 0.3) {
        float c = texture(uNoise, vec2(uv.x * uAspect * 0.9 + uPan * 1.5 + uTime * 0.004, uv.y * 1.1)).r * 0.65
                + texture(uNoise, vec2(uv.x * uAspect * 2.3 + uPan * 2.0, uv.y * 2.6)).r * 0.35;
        col = mix(col, mix(col, vec3(1.0), 0.55), smoothstep(0.52, 0.78, c) * uClouds * smoothstep(0.3, 0.9, t));
    }
    float x = uv.x - 0.5;
    if (t < 0.62) {
        float uf = x * 0.28 + uPan * 0.5;
        vec4 pf = texture(uProfile, vec2(uf, 0.5));
        float hFar = 0.17 + 0.36 * pf.r;
        float a = smoothstep(hFar + uAA, hFar - uAA, t);
        col = mix(col, uFar, 0.85 * a);
        float win = pf.a * step(0.55, h21(vec2(floor(t * 70.0), floor(uf * 256.0)))) * step(t, hFar - 0.015);
        col += uLights * win * 0.35 * a;
        if (t < 0.42) {
            float um = x * 0.4 + uPan * 1.0 + 0.37;
            vec4 pm = texture(uProfile, vec2(um, 0.5));
            float hMid = 0.10 + 0.27 * pm.g;
            a = smoothstep(hMid + uAA, hMid - uAA, t);
            col = mix(col, mix(uMid, col, 0.12), a);
            win = pm.a * step(0.6, h21(vec2(floor(t * 60.0), floor(um * 256.0)))) * step(t, hMid - 0.015);
            col += uLights * win * 0.5 * a;
            if (t < 0.24) {
                float un = x * 0.55 + uPan * 2.0 + 0.71;
                vec4 pn = texture(uProfile, vec2(un, 0.5));
                float hNear = 0.03 + 0.17 * pn.b;
                a = smoothstep(hNear + uAA, hNear - uAA, t);
                col = mix(col, uNear, a);
            }
        }
    }
    col = mix(col, uFogColor, smoothstep(0.35, 0.0, t) * 0.35);
    o = vec4(col, 0.0);
}
"""

    // ---------------------------------------------------------------- instanced particles
    val PARTICLE_VS = """#version 300 es
layout(location = 0) in vec3 aCorner;
layout(location = 2) in vec4 iPosSize;
layout(location = 3) in vec4 iColor;
layout(location = 4) in vec2 iRotTile;
uniform mat4 uViewProj;
uniform vec3 uCamRight;
uniform vec3 uCamUp;
out vec2 vUv;
out vec4 vColor;
void main() {
    float c = cos(iRotTile.x), s = sin(iRotTile.x);
    vec2 p = vec2(c * aCorner.x - s * aCorner.y, s * aCorner.x + c * aCorner.y) * iPosSize.w;
    vec3 w = iPosSize.xyz + uCamRight * p.x + uCamUp * p.y;
    gl_Position = uViewProj * vec4(w, 1.0);
    float tile = iRotTile.y;
    vec2 cell = vec2(mod(tile, 4.0), floor(tile / 4.0));
    vUv = (cell + vec2(aCorner.x + 0.5, 0.5 - aCorner.y)) / 4.0;
    vColor = iColor;
}
"""
    val PARTICLE_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
in vec4 vColor;
uniform sampler2D uAtlas;
uniform float uBloom;
out vec4 o;
void main() {
    vec4 t = texture(uAtlas, vUv);
    t.rgb /= max(t.a, 0.004);
    o = vec4(vColor.rgb * t.rgb, vColor.a * t.a);
    if (o.a < 0.01) discard;
}
"""

    // ---------------------------------------------------------------- splat decals on ring tops
    val DECAL_VS = """#version 300 es
layout(location = 0) in vec3 aCorner;
layout(location = 2) in vec4 iPos;     // localX, localZ, size, rotation
layout(location = 3) in vec4 iColor;
layout(location = 4) in vec2 iTile;    // tile, age01
uniform mat4 uModel;
uniform mat4 uViewProj;
out vec2 vUv;
out vec2 vLocal;
out vec4 vColor;
out float vAge;
void main() {
    float c = cos(iPos.w), s = sin(iPos.w);
    vec2 p = vec2(c * aCorner.x - s * aCorner.y, s * aCorner.x + c * aCorner.y) * iPos.z;
    vec3 local = vec3(iPos.x + p.x, 0.006, iPos.y + p.y);
    vLocal = local.xz;
    gl_Position = uViewProj * uModel * vec4(local, 1.0);
    float tile = iTile.x;
    vec2 cell = vec2(mod(tile, 4.0), floor(tile / 4.0));
    vUv = (cell + vec2(aCorner.x + 0.5, 0.5 - aCorner.y)) / vec2(4.0, 2.0);
    vColor = iColor;
    vAge = iTile.y;
}
"""
    val DECAL_FS = """#version 300 es
precision highp float;
in vec2 vUv;
in vec2 vLocal;
in vec4 vColor;
in float vAge;
uniform sampler2D uAtlas;
uniform sampler2D uMask;
uniform float uRow;
uniform vec3 uLightTint;
uniform float uRotDeg;
out vec4 o;
void main() {
    float r = length(vLocal);
    if (r < 0.6 || r > 1.76) discard;
    float ang = degrees(atan(vLocal.y, vLocal.x));
    ang = mod(ang, 360.0);
    float solid = texture(uMask, vec2(ang / 360.0, (uRow + 0.5) / 64.0)).r;
    if (solid < 0.5) discard;
    vec4 t = texture(uAtlas, vUv);
    t.rgb /= max(t.a, 0.004);
    float a = t.a * vColor.a;
    if (a < 0.02) discard;
    vec3 c = vColor.rgb * mix(0.72, 1.08, t.r) * uLightTint;
    o = vec4(c, a);
}
"""

    // ---------------------------------------------------------------- trail ribbon
    val TRAIL_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aColor;
uniform mat4 uViewProj;
out vec4 vColor;
out float vU;
void main() {
    vColor = aColor;
    gl_Position = uViewProj * vec4(aPos, 1.0);
}
"""
    val TRAIL_FS = """#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 o;
void main() { o = vColor; }
"""

    // ---------------------------------------------------------------- post-processing
    val BRIGHT_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uTexel;
out vec4 o;
void main() {
    vec4 a = texture(uTex, vUv + uTexel * vec2(-0.5, -0.5));
    vec4 b = texture(uTex, vUv + uTexel * vec2(0.5, -0.5));
    vec4 c = texture(uTex, vUv + uTexel * vec2(-0.5, 0.5));
    vec4 d = texture(uTex, vUv + uTexel * vec2(0.5, 0.5));
    vec3 col = (a.rgb * a.a + b.rgb * b.a + c.rgb * c.a + d.rgb * d.a) * 0.25;
    o = vec4(col, 1.0);
}
"""
    val BLUR_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uDir;
out vec4 o;
void main() {
    vec3 c = texture(uTex, vUv).rgb * 0.2270270270;
    c += texture(uTex, vUv + uDir * 1.3846153846).rgb * 0.3162162162;
    c += texture(uTex, vUv - uDir * 1.3846153846).rgb * 0.3162162162;
    c += texture(uTex, vUv + uDir * 3.2307692308).rgb * 0.0702702703;
    c += texture(uTex, vUv - uDir * 3.2307692308).rgb * 0.0702702703;
    o = vec4(c, 1.0);
}
"""
    val COPY_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
out vec4 o;
void main() { o = vec4(texture(uTex, vUv).rgb, 0.0); }
"""

    val COMPOSITE_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uScene;
uniform vec2 uPx;
uniform float uFxaa;
uniform sampler2D uBloomTex;
uniform float uBloom;
uniform float uVignette;
uniform float uSaturation;
uniform float uFlash;
uniform vec3 uFlashColor;
uniform float uChroma;
out vec4 o;
vec3 fxaa(vec2 uv) {
    const vec3 L = vec3(0.299, 0.587, 0.114);
    vec3 rgbNW = texture(uScene, uv + vec2(-1.0, -1.0) * uPx).rgb;
    vec3 rgbNE = texture(uScene, uv + vec2(1.0, -1.0) * uPx).rgb;
    vec3 rgbSW = texture(uScene, uv + vec2(-1.0, 1.0) * uPx).rgb;
    vec3 rgbSE = texture(uScene, uv + vec2(1.0, 1.0) * uPx).rgb;
    vec3 rgbM = texture(uScene, uv).rgb;
    float lNW = dot(rgbNW, L), lNE = dot(rgbNE, L), lSW = dot(rgbSW, L), lSE = dot(rgbSE, L), lM = dot(rgbM, L);
    float lMin = min(lM, min(min(lNW, lNE), min(lSW, lSE)));
    float lMax = max(lM, max(max(lNW, lNE), max(lSW, lSE)));
    if (lMax - lMin < max(0.04, lMax * 0.12)) return rgbM;
    vec2 dir = vec2(-((lNW + lNE) - (lSW + lSE)), (lNW + lSW) - (lNE + lSE));
    float reduce = max((lNW + lNE + lSW + lSE) * 0.03125, 1.0 / 128.0);
    float rcp = 1.0 / (min(abs(dir.x), abs(dir.y)) + reduce);
    dir = clamp(dir * rcp, vec2(-8.0), vec2(8.0)) * uPx;
    vec3 a = 0.5 * (texture(uScene, uv + dir * (1.0 / 3.0 - 0.5)).rgb + texture(uScene, uv + dir * (2.0 / 3.0 - 0.5)).rgb);
    vec3 b = a * 0.5 + 0.25 * (texture(uScene, uv - dir * 0.5).rgb + texture(uScene, uv + dir * 0.5).rgb);
    float lb = dot(b, L);
    return (lb < lMin || lb > lMax) ? a : b;
}
void main() {
    vec3 col;
    if (uChroma > 0.001) {
        vec2 dir = (vUv - 0.5) * uChroma;
        col.r = texture(uScene, vUv + dir).r;
        col.g = texture(uScene, vUv).g;
        col.b = texture(uScene, vUv - dir).b;
    } else if (uFxaa > 0.5) {
        col = fxaa(vUv);
    } else {
        col = texture(uScene, vUv).rgb;
    }
    col += texture(uBloomTex, vUv).rgb * uBloom;
    float l = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(vec3(l), col, uSaturation);
    vec2 d = vUv - 0.5;
    float v = 1.0 - dot(d, d) * uVignette;
    col *= v;
    col = mix(col, uFlashColor, uFlash);
    // Soft shoulder to avoid harsh clipping.
    col = col / (1.0 + max(col - 1.0, 0.0) * 0.6);
    o = vec4(col, 1.0);
}
"""
}
