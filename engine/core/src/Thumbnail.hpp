#pragma once

#include "libslic3r/GCode/ThumbnailData.hpp"

#include <string>
#include <vector>

namespace Slic3r {
class Model;
}

// Renders the model for the G-code thumbnails (the picture the printer shows) without OpenGL:
// an isometric view, shaded, in the filament colours, on a transparent background.
Slic3r::ThumbnailsList render_thumbnails(const Slic3r::Model &model, const std::vector<std::string> &filament_colours,
                                         const Slic3r::ThumbnailsParams &params);
