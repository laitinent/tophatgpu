#version 300 es
precision highp float;

uniform sampler2D uTexture;
uniform vec2 texelSize;

in vec2 vTexCoord;
out vec4 fragColor;

void main() {
    float minVal = 1.0;

    for (int x = -7; x <= 7; x++) {
        for (int y = -7; y <= 7; y++) {
            vec2 offset = vec2(float(x), float(y)) * texelSize;
            float val = texture(uTexture, vTexCoord + offset).r;
            minVal = min(minVal, val);
        }
    }

    fragColor = vec4(minVal, minVal, minVal, 1.0);
}
