# Top-Hat GPU - Real-Time Morphological Analysis

Top-Hat GPU is a high-performance Android application demonstrating real-time morphological image processing using OpenGL ES 3.2 Compute Shaders. It extracts small features and textures from camera streams using the Top-Hat transformation, followed by automated thresholding and connected components labeling.

## Features

- **GPU-Accelerated Pipeline**: Every step from grayscale conversion to final labeling runs entirely on the GPU using GLES 3.2 and Compute Shaders.
- **Top-Hat Transformation**:
    - **White Top-Hat**: (Original - Opened) Extracts bright features smaller than the kernel.
    - **Black Top-Hat**: (Closed - Original) Extracts dark features smaller than the kernel.
- **Adaptive Thresholding**: Real-time Otsu's method optimized via GPU downsampling to maintain 60 FPS.
- **Connected Components Labeling (CCL)**: GPU-parallel label propagation identifies individual "blobs" in the image.
- **GPU-Native Label Counting**: High-speed unique object counting using Shader Storage Buffer Objects (SSBOs) and atomic operations.
- **Real-Time Visualization**: Vibrant HSV-based coloring for labeled components.

## Usage

1. **Kernel Size (Original, x2, x4)**: Adjust the size of the structural element to extract features of different scales.
2. **Sub ON/OFF**: Toggle the final subtraction pass to see either the morphological operation (Opening/Closing) or the Top-Hat result.
3. **Opening/Closing**: Switch between extracting bright features (Opening) or dark features (Closing).
4. **Mode Cycle**:
    - **None**: View the grayscale Top-Hat result.
    - **Threshold**: View the binary mask after Otsu's thresholding.
    - **Labeling**: View color-coded connected components and the live object count.

## Technical Details

- **Graphics API**: OpenGL ES 3.2
- **Shaders**:
    - Fragment Shaders: Erosion, Dilation, Subtraction, Thresholding, OES conversion.
    - Compute Shaders: Label Initialization, Label Propagation, GPU Counting, Flag Clearing.
- **Performance**: Optimized for 1080p @ 60 FPS on modern devices like the Pixel 7a.
- **Platform**: Android (tested on API 35+).

## License

MIT License - feel free to use this in your own GPU computer vision projects.

## Thanks
ChatGPT, Gemini / Android Studio