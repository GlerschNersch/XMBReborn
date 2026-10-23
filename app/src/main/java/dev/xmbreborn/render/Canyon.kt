package dev.xmbreborn.render

/**
 * A slow flight down a winding canyon in the month palette's colours: cliffs fading into fog of
 * the gradient's centre colour, sky from the gradient itself, and a glowing guide line floating
 * over the valley floor ahead. The firmware's canyon scene has the same parts (camera, terrain,
 * a line ahead, fog, colour grading); this one is raymarched over our own procedural terrain.
 */
class Canyon : ShaderScene(ShaderScene.COMMON + FS) {
    private companion object {
        const val FS = """
            const float SPEED = 5.0;
            const float FAR = 140.0;
            const float LINE_Y = 0.6;

            float fbm(vec2 p) {
                float s = 0.0, a = 0.5;
                for (int i = 0; i < 4; i++) { s += a * noise2(p); p = p * 2.03 + vec2(1.7, 9.2); a *= 0.5; }
                return s;
            }
            // The valley's centre wanders from side to side.
            float centre(float z) { return 7.0 * sin(z * 0.031) + 3.0 * sin(z * 0.013 + 1.3); }
            float height(vec2 xz) {
                float d = abs(xz.x - centre(xz.y));
                float walls = 16.0 * smoothstep(2.5, 15.0, d);
                float rough = fbm(xz * 0.08) * 7.0 * (0.25 + smoothstep(2.0, 12.0, d));
                return walls + rough - 2.5;
            }

            void main() {
                vec2 uv = (vUv * 2.0 - 1.0) * vec2(uAspect, 1.0);
                float z0 = uTime * SPEED;
                vec3 ro = vec3(centre(z0), 2.6, z0);
                vec3 ta = vec3(centre(z0 + 14.0), 1.2, z0 + 14.0);
                vec3 fw = normalize(ta - ro);
                vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
                vec3 up = cross(fw, rt);
                vec3 rd = normalize(fw * 1.4 + rt * uv.x + up * uv.y);

                float t = 0.3;
                bool hit = false;
                for (int i = 0; i < 80; i++) {
                    vec3 p = ro + rd * t;
                    float h = p.y - height(p.xz);
                    if (h < 0.004 * t) { hit = true; break; }
                    t += max(0.04, h * 0.45);
                    if (t > FAR) break;
                }

                vec3 sky = palette(vUv) * 1.1 + uColors[4] * 0.25 * (1.0 - vUv.y);
                vec3 fog = uColors[4] * 1.25 + 0.05;
                vec3 col = sky;
                if (hit) {
                    vec3 p = ro + rd * t;
                    vec2 e = vec2(0.12, 0.0);
                    vec3 n = normalize(vec3(height(p.xz - e.xy) - height(p.xz + e.xy), 2.0 * e.x,
                                            height(p.xz - e.yx) - height(p.xz + e.yx)));
                    vec3 sun = normalize(vec3(-0.5, 0.75, 0.45));
                    float diff = clamp(dot(n, sun), 0.0, 1.0);
                    float steep = 1.0 - n.y;
                    // Rock layers on the cliffs, a paler floor.
                    vec3 rock = mix(uColors[2] * 0.7 + 0.04, uColors[4] * 1.3 + 0.06,
                                    0.5 + 0.5 * sin(p.y * 2.3 + fbm(p.xz * 0.3) * 3.0));
                    // Sand ripples on the floor, so the near ground has some texture.
                    vec3 floorCol = (uColors[4] * 0.8 + 0.05) * (0.75 + 0.35 * fbm(p.xz * vec2(0.6, 0.15)));
                    vec3 base = mix(floorCol, rock, smoothstep(0.15, 0.5, steep));
                    col = base * (0.3 + 0.95 * diff);
                    col = mix(col, fog, 1.0 - exp(-t * 0.022));
                }

                // The guide line: where the ray crosses the line's height, how near that is to the
                // valley's centre, faded in ahead of the camera and out into the distance.
                if (rd.y < 0.0) {
                    float tl = (LINE_Y - ro.y) / rd.y;
                    if (!hit || tl < t) {
                        vec3 q = ro + rd * tl;
                        float d = abs(q.x - centre(q.z));
                        float ahead = q.z - z0;
                        float span = smoothstep(9.0, 20.0, ahead) * (1.0 - smoothstep(50.0, 110.0, ahead));
                        // Thinner than its glow, which widens with distance less than the core does.
                        float glow = exp(-d * d * 32.0) * 0.6 + exp(-d * 3.0) * 0.12;
                        col += (vec3(0.5) + fog * 0.5) * glow * span;
                    }
                }

                float vignette = 1.0 - 0.5 * dot(vUv - 0.5, vUv - 0.5);
                fragColor = vec4(col * vignette * (0.55 + 0.45 * uLight), 1.0);
            }
        """
    }
}
