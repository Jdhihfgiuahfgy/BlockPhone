#version 330

// Фильтр камеры BlockPhone. Та же формула продублирована на CPU в ImageOps.applyFilter —
// при изменении меняйте обе.

uniform sampler2D InSampler;

layout(std140) uniform FilterConfig {
    vec4 RowR;   // xyz — коэффициенты r,g,b; w — смещение
    vec4 RowG;
    vec4 RowB;
    vec4 Params; // x — насыщенность, y — контраст, z — виньетка
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec3 c = texture(InSampler, texCoord).rgb;
    vec3 m = vec3(dot(RowR.xyz, c) + RowR.w, dot(RowG.xyz, c) + RowG.w, dot(RowB.xyz, c) + RowB.w);
    float lum = dot(m, vec3(0.299, 0.587, 0.114));
    m = mix(vec3(lum), m, Params.x);
    m = (m - 0.5) * Params.y + 0.5;
    float d = distance(texCoord, vec2(0.5)) * 1.41421;
    m *= 1.0 - Params.z * smoothstep(0.35, 1.0, d);
    fragColor = vec4(clamp(m, 0.0, 1.0), 1.0);
}
