package dev.xmbreborn.render

/**
 * The Earth from orbit: procedural continents, ice and oceans, drifting clouds, a soft terminator
 * with city lights on the night side, and a thin blue atmosphere glowing at the limb. The
 * firmware's earth scene has an atmosphere, clouds, a sun and HDR exposure; none of its textures
 * or tables are used here. The planet fills the lower part of the screen, under the bar.
 */
class Earth : ShaderScene(ShaderScene.COMMON + FS) {
    private companion object {
        const val FS = """
            // Whole turns per hour, so nothing jumps when the hourly clock wraps.
            const float SPIN = 6.2831853 * 6.0 / 3600.0;
            const float CLOUD_SPIN = 6.2831853 * 9.0 / 3600.0;

            float fbm3(vec3 p) {
                float s = 0.0, a = 0.5;
                for (int i = 0; i < 5; i++) { s += a * noise3(p); p = p * 2.02 + vec3(3.1, 1.7, 5.3); a *= 0.5; }
                return s;
            }
            vec3 spinY(vec3 p, float a) {
                float c = cos(a), s = sin(a);
                return vec3(c * p.x + s * p.z, p.y, -s * p.x + c * p.z);
            }

            void main() {
                vec2 uv = (vUv * 2.0 - 1.0) * vec2(uAspect, 1.0);
                vec3 ro = vec3(0.0, 0.15, 1.75);
                vec3 ta = vec3(0.0, 0.95, 0.0);
                vec3 fw = normalize(ta - ro);
                vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
                vec3 up = cross(fw, rt);
                vec3 rd = normalize(fw * 1.3 + rt * uv.x + up * uv.y);
                vec3 sun = normalize(vec3(-0.55, 0.5, 0.65));

                // Space: faint stars and a hint of the theme's colour.
                float s = hash3(floor(rd * 420.0));
                vec3 col = uColors[4] * 0.12 + vec3(0.0, 0.0, 0.015) + vec3(step(0.9982, s) * (s - 0.9982) * 450.0);

                float b = dot(ro, rd);
                float c = dot(ro, ro) - 1.0;
                float h = b * b - c;
                if (h > 0.0) {
                    float t = -b - sqrt(h);
                    vec3 n = normalize(ro + rd * t);
                    vec3 q = spinY(n, uTime * SPIN);
                    float land = fbm3(q * 1.9);
                    float isLand = smoothstep(0.515, 0.535, land);
                    float lat = abs(q.y);
                    vec3 ocean = mix(vec3(0.01, 0.04, 0.13), vec3(0.02, 0.10, 0.26), smoothstep(0.35, 0.52, land));
                    vec3 ground = mix(vec3(0.08, 0.20, 0.06), vec3(0.40, 0.33, 0.20),
                                      smoothstep(0.56, 0.68, land + (1.0 - lat) * 0.06));
                    vec3 surf = mix(ocean, ground, isLand);
                    float ice = smoothstep(0.80, 0.88, lat + (land - 0.5) * 0.35);
                    surf = mix(surf, vec3(0.85, 0.88, 0.92), ice);

                    float diff = dot(n, sun);
                    float day = smoothstep(-0.08, 0.3, diff);
                    col = surf * (0.03 + 1.1 * max(diff, 0.0));
                    vec3 hv = normalize(sun - rd);
                    col += vec3(1.0, 0.9, 0.7) * pow(max(dot(n, hv), 0.0), 160.0) * (1.0 - isLand) * (1.0 - ice) * 0.35 * day;
                    // City lights where it's night.
                    float city = smoothstep(0.82, 0.9, noise3(q * 70.0)) * smoothstep(0.55, 0.6, land) * (1.0 - ice);
                    col += vec3(1.0, 0.68, 0.32) * city * (1.0 - smoothstep(-0.2, 0.05, diff)) * 0.7;

                    vec3 cq = spinY(n, uTime * CLOUD_SPIN);
                    float cloud = smoothstep(0.52, 0.78, fbm3(cq * 2.7 + vec3(0.0, 4.0, 0.0)));
                    col = mix(col, vec3(1.0) * (0.04 + 1.05 * max(diff, 0.0)), cloud * 0.85);

                    float rim = pow(1.0 - max(dot(n, -rd), 0.0), 3.0);
                    col += vec3(0.25, 0.5, 1.0) * rim * (0.08 + 1.2 * max(diff + 0.15, 0.0));
                } else if (b < 0.0) {
                    // The atmosphere's glow just past the limb, brightest on the sunlit side.
                    float dmin = sqrt(max(dot(ro, ro) - b * b, 0.0));
                    vec3 cp = ro - rd * b;
                    float sunSide = clamp(dot(normalize(cp), sun) * 0.6 + 0.5, 0.0, 1.0);
                    col += vec3(0.3, 0.55, 1.0) * exp(-(dmin - 1.0) * 30.0) * sunSide * 0.9;
                }
                fragColor = vec4(col * (0.6 + 0.4 * uLight), 1.0);
            }
        """
    }
}
