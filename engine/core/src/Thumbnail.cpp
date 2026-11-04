#include "Thumbnail.hpp"

#include "libslic3r/Model.hpp"
#include "libslic3r/TriangleMesh.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>

using namespace Slic3r;

namespace {

struct Rgb
{
    float r, g, b;
};

Rgb parse_colour(const std::string &text)
{
    std::string s = text;
    if (!s.empty() && s[0] == '#')
        s.erase(0, 1);
    if (s.size() < 6)
        return {0.85f, 0.85f, 0.85f};
    auto hex = [&](size_t i) { return float(std::stoi(s.substr(i, 2), nullptr, 16)) / 255.f; };
    try {
        return {hex(0), hex(2), hex(4)};
    } catch (...) {
        return {0.85f, 0.85f, 0.85f};
    }
}

struct Triangle
{
    Vec3f a, b, c; // view space: x right, y up, z towards the viewer
    Rgb   colour;
};

} // namespace

ThumbnailsList render_thumbnails(const Model &model, const std::vector<std::string> &filament_colours, const ThumbnailsParams &params)
{
    // View: from the front right, 35 degrees above, like the default thumbnail of the desktop slicer.
    const float yaw = float(-M_PI / 4.), pitch = float(M_PI / 180. * 55.);
    const Eigen::Matrix3f view = (Eigen::AngleAxisf(-pitch, Eigen::Vector3f::UnitX()) * Eigen::AngleAxisf(yaw, Eigen::Vector3f::UnitZ())).toRotationMatrix();
    const Vec3f light = Vec3f(-0.3f, 0.5f, 1.f).normalized();

    std::vector<Triangle> tris;
    for (const ModelObject *obj : model.objects) {
        int extruder = 1;
        if (const ConfigOption *opt = obj->config.option("extruder"))
            extruder = std::max(1, opt->getInt());
        const Rgb colour = extruder - 1 < int(filament_colours.size()) ? parse_colour(filament_colours[extruder - 1]) : Rgb{0.85f, 0.85f, 0.85f};
        for (const ModelInstance *inst : obj->instances) {
            for (const ModelVolume *vol : obj->volumes) {
                if (!vol->is_model_part())
                    continue;
                const Transform3d   m   = inst->get_matrix() * vol->get_matrix();
                const indexed_triangle_set &its = vol->mesh().its;
                for (const stl_triangle_vertex_indices &f : its.indices) {
                    Vec3f p[3];
                    for (int k = 0; k < 3; ++k)
                        p[k] = view * (m * its.vertices[f[k]].cast<double>()).cast<float>();
                    tris.push_back({p[0], p[1], p[2], colour});
                }
            }
        }
    }

    ThumbnailsList out;
    if (tris.empty())
        return out;

    Vec3f lo = Vec3f::Constant(std::numeric_limits<float>::max()), hi = -lo;
    for (const Triangle &t : tris)
        for (const Vec3f &p : {t.a, t.b, t.c}) {
            lo = lo.cwiseMin(p);
            hi = hi.cwiseMax(p);
        }

    for (const Vec2d &size : params.sizes) {
        const int w = std::max(1, int(size.x())), h = std::max(1, int(size.y()));
        ThumbnailData data;
        data.set(w, h);
        std::fill(data.pixels.begin(), data.pixels.end(), 0);
        std::vector<float> depth(size_t(w) * h, -std::numeric_limits<float>::max());

        const float margin = 0.06f;
        const float scale  = std::min(w * (1 - 2 * margin) / std::max(1e-3f, hi.x() - lo.x()), h * (1 - 2 * margin) / std::max(1e-3f, hi.y() - lo.y()));
        const float ox     = (w - (hi.x() - lo.x()) * scale) / 2 - lo.x() * scale;
        const float oy     = (h - (hi.y() - lo.y()) * scale) / 2 - lo.y() * scale;

        for (const Triangle &t : tris) {
            Vec3f n = (t.b - t.a).cross(t.c - t.a);
            const float len = n.norm();
            if (len <= 0.f)
                continue;
            n /= len;
            // Pixel coordinates; row 0 is the bottom row, as OrcaSlicer's PNG encoder expects (OpenGL order).
            const Vec2f a(t.a.x() * scale + ox, t.a.y() * scale + oy), b(t.b.x() * scale + ox, t.b.y() * scale + oy),
                c(t.c.x() * scale + ox, t.c.y() * scale + oy);
            const float area = (b - a).x() * (c - a).y() - (b - a).y() * (c - a).x();
            if (std::abs(area) < 1e-12f)
                continue;
            const float shade = 0.35f + 0.65f * std::abs(n.dot(light));
            const unsigned char r = (unsigned char) std::clamp(t.colour.r * shade * 255.f, 0.f, 255.f);
            const unsigned char g = (unsigned char) std::clamp(t.colour.g * shade * 255.f, 0.f, 255.f);
            const unsigned char bl = (unsigned char) std::clamp(t.colour.b * shade * 255.f, 0.f, 255.f);
            const int x0 = std::max(0, int(std::floor(std::min({a.x(), b.x(), c.x()}))));
            const int x1 = std::min(w - 1, int(std::ceil(std::max({a.x(), b.x(), c.x()}))));
            const int y0 = std::max(0, int(std::floor(std::min({a.y(), b.y(), c.y()}))));
            const int y1 = std::min(h - 1, int(std::ceil(std::max({a.y(), b.y(), c.y()}))));
            for (int y = y0; y <= y1; ++y)
                for (int x = x0; x <= x1; ++x) {
                    const Vec2f p(x + 0.5f, y + 0.5f);
                    const float w0 = ((b - p).x() * (c - p).y() - (b - p).y() * (c - p).x()) / area;
                    const float w1 = ((c - p).x() * (a - p).y() - (c - p).y() * (a - p).x()) / area;
                    const float w2 = 1.f - w0 - w1;
                    if (w0 < 0 || w1 < 0 || w2 < 0)
                        continue;
                    const float z = w0 * t.a.z() + w1 * t.b.z() + w2 * t.c.z();
                    float &d = depth[size_t(y) * w + x];
                    if (z <= d)
                        continue;
                    d = z;
                    unsigned char *px = &data.pixels[(size_t(y) * w + x) * 4];
                    px[0] = r;
                    px[1] = g;
                    px[2] = bl;
                    px[3] = 255;
                }
        }
        if (!params.transparent_background)
            for (size_t i = 0; i < data.pixels.size(); i += 4)
                if (data.pixels[i + 3] == 0) {
                    data.pixels[i] = data.pixels[i + 1] = data.pixels[i + 2] = 40;
                    data.pixels[i + 3] = 255;
                }
        out.push_back(std::move(data));
    }
    return out;
}
