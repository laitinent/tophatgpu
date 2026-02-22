#version 310 es
precision highp float;

// Original grayscale image
uniform sampler2D original;

// Image after morphological opening (erosion followed by dilation)
uniform sampler2D opened;

in vec2 vTexCoord;
out vec4 fragColor;

/**
 * Performs the Top-Hat transformation: Original - Opened.
 * This operation extracts small elements and details from an image.
 */
void main() {
    // Sample values from the two textures
    float o = texture(original, vTexCoord).r;
    float open = texture(opened, vTexCoord).r;

    // Calculate the difference
    float result = o - open;

    // Output result as a grayscale color
    fragColor = vec4(result, result, result, 1.0);
}
