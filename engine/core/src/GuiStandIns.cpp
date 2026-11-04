// libslic3r references a few functions that the desktop app defines in its GUI layer (which is not built here).
// They are provided here unchanged, the way OrcaSlicer's own headless tools (profile validator, tests) do.

// The header-only nanosvg implementation (desktop: slic3r/GUI/BitmapCache.cpp), used by libslic3r's SVG import.
// Defined before any other include: libslic3r headers include nanosvg.h without the macro.
#define NANOSVG_IMPLEMENTATION
#include "nanosvg/nanosvg.h"
#define NANOSVGRAST_IMPLEMENTATION
#include "nanosvg/nanosvgrast.h"

#include <algorithm>
#include <cmath>

// From slic3r/Utils/ColorSpaceConvert.cpp (which includes wxWidgets), used by FlushVolCalc to derive purge volumes
// from filament colours. The input r, g, b values are in [0, 1]; h is in [0, 360], s and v in [0, 1].
void RGB2HSV(float r, float g, float b, float *h, float *s, float *v)
{
    float Cmax  = std::max(std::max(r, g), b);
    float Cmin  = std::min(std::min(r, g), b);
    float delta = Cmax - Cmin;

    if (std::abs(delta) < 0.001) {
        *h = 0.f;
    } else if (Cmax == r) {
        *h = 60.f * fmod((g - b) / delta, 6.f);
    } else if (Cmax == g) {
        *h = 60.f * ((b - r) / delta + 2);
    } else {
        *h = 60.f * ((r - g) / delta + 4);
    }

    if (std::abs(Cmax) < 0.001) {
        *s = 0.f;
    } else {
        *s = delta / Cmax;
    }

    *v = Cmax;
}
