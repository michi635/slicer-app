// slicercore: the slicing engine of the app, a small command-line program around OrcaSlicer's libslic3r.
//
// The app runs it as a separate process and talks to it through files and stdout lines:
//   slicercore version
//   slicercore describe <resources-dir> <data-dir> [<printer> [<process>]]
//   slicercore arrange  <resources-dir> <data-dir> <job.json>
//   slicercore slice    <resources-dir> <data-dir> <job.json> <out.gcode>
//
// stdout protocol, one message per line:
//   PROGRESS <percent> <text>
//   RESULT <json>            (always the last line; {"ok":false,"error":...} on failure)
// Diagnostics go to stderr.
//
// <data-dir>/system holds the vendor profiles (Anycubic, OrcaFilamentLibrary, ...), exactly as OrcaSlicer keeps them.
// Preset resolution, the filament merge for multi-material (ACE) and the slicing itself are OrcaSlicer's own code;
// this program only does what the desktop GUI does around them (plate, bed type, flush volumes, prime tower).

#include "libslic3r/libslic3r.h"
#include "libslic3r_version.h"
#include "libslic3r/AppConfig.hpp"
#include "libslic3r/Arrange.hpp"
#include "libslic3r/BoundingBox.hpp"
#include "libslic3r/Exception.hpp"
#include "libslic3r/FlushVolCalc.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/GCode/GCodeStatistics.hpp"
#include "libslic3r/GCode/WipeTower.hpp"
#include "libslic3r/GCode/WipeTowerEstimate.hpp"
#include "libslic3r/Geometry.hpp"
#include "libslic3r/LocalesUtils.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/ModelArrange.hpp"
#include "libslic3r/Preset.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Utils.hpp"

#include <nlohmann/json.hpp>

#include <algorithm>
#include <cfloat>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <exception>
#include <fstream>
#include <iostream>
#include <map>
#include <memory>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

using namespace Slic3r;
using json = nlohmann::json;

namespace {

// ---------------------------------------------------------------------------------------------------------------------
// Output

void print_line(const std::string &tag, const std::string &payload)
{
    std::cout << tag << ' ' << payload << '\n' << std::flush;
}

void progress(int percent, const std::string &text)
{
    print_line("PROGRESS", std::to_string(percent) + " " + text);
}

void result(const json &j)
{
    print_line("RESULT", j.dump());
}

struct UserError : std::runtime_error
{
    using std::runtime_error::runtime_error;
};

// The settings the app shows. Everything else stays as the presets define it.
const std::vector<std::string> &ui_keys()
{
    static const std::vector<std::string> keys = {
        "layer_height",
        "sparse_infill_density", "sparse_infill_pattern", "infill_combination",
        "wall_loops",
        "enable_support", "support_type", "support_on_build_plate_only",
        "support_filament", "support_interface_filament",
        "brim_type", "skirt_loops",
        "spiral_mode",
        "thick_bridges", "enable_extra_bridge_layer",
        "enable_prime_tower", "prime_tower_width",
    };
    return keys;
}

// ---------------------------------------------------------------------------------------------------------------------
// Presets

struct Engine
{
    std::unique_ptr<AppConfig>    app_config;
    std::unique_ptr<PresetBundle> bundle;

    void load(const std::string &resources, const std::string &data)
    {
        set_resources_dir(resources);
        set_data_dir(data);
        app_config = std::make_unique<AppConfig>();
        bundle     = std::make_unique<PresetBundle>();
        std::string errors;
        bundle->load_presets(*app_config, ForwardCompatibilitySubstitutionRule::EnableSilent, PresetBundle::PresetPreferences(), &errors, false);
        if (!errors.empty())
            std::cerr << "preset warnings: " << errors << std::endl;
        if (bundle->printers.size() == 0)
            throw UserError("Keine Druckerprofile gefunden (" + data + "/system).");
    }

    void select_printer(const std::string &printer)
    {
        if (printer.empty())
            return;
        if (!bundle->printers.select_preset_by_name(printer, true) || bundle->printers.get_selected_preset_name() != printer)
            throw UserError("Druckerprofil nicht gefunden: " + printer);
        // Like the GUI after a printer change: pick a compatible process and filament if the current ones are not.
        bundle->update_compatible(PresetSelectCompatibleType::Always, PresetSelectCompatibleType::Always);
    }

    void select_process(const std::string &process)
    {
        if (process.empty())
            return;
        if (!bundle->prints.select_preset_by_name(process, true) || bundle->prints.get_selected_preset_name() != process)
            throw UserError("Qualitätsprofil nicht gefunden: " + process);
    }

    void select_filaments(const std::vector<std::string> &names)
    {
        if (names.empty())
            return;
        for (const std::string &name : names)
            if (bundle->filaments.find_preset(name, false) == nullptr)
                throw UserError("Filamentprofil nicht gefunden: " + name);
        bundle->filaments.select_preset_by_name(names.front(), true);
        bundle->filament_presets = names;
        bundle->update_multi_material_filament_presets();
    }
};

std::string first_string(const DynamicPrintConfig &config, const std::string &key)
{
    if (const auto *opt = config.option<ConfigOptionStrings>(key); opt != nullptr && !opt->values.empty())
        return opt->values.front();
    if (const auto *opt = config.option<ConfigOptionString>(key); opt != nullptr)
        return opt->value;
    return {};
}

BoundingBoxf bed_box(const DynamicPrintConfig &config)
{
    const auto *area = config.option<ConfigOptionPoints>("printable_area");
    if (area == nullptr || area->values.empty())
        throw UserError("Druckerprofil ohne Bauplatte (printable_area).");
    return BoundingBoxf(area->values);
}

json option_choices(const std::string &key)
{
    const ConfigOptionDef *def = print_config_def.get(key);
    json out = json::object();
    if (def == nullptr)
        return out;
    out["label"] = def->label;
    switch (def->type) {
    case coEnum: out["type"] = "enum"; break;
    case coBool: out["type"] = "bool"; break;
    case coInt: out["type"] = "int"; break;
    case coFloat: out["type"] = "float"; break;
    case coPercent: out["type"] = "percent"; break;
    default: out["type"] = "string"; break;
    }
    if (def->type == coEnum) {
        json values = json::array();
        for (size_t i = 0; i < def->enum_values.size(); ++i)
            values.push_back({{"value", def->enum_values[i]}, {"label", i < def->enum_labels.size() ? def->enum_labels[i] : def->enum_values[i]}});
        out["choices"] = values;
    }
    if (def->min > -FLT_MAX)
        out["min"] = def->min;
    if (def->max < FLT_MAX)
        out["max"] = def->max;
    return out;
}

json describe(Engine &engine, const std::string &printer, const std::string &process)
{
    PresetBundle &bundle = *engine.bundle;
    json out;
    out["ok"]      = true;
    out["version"] = SoftFever_VERSION;

    json printers = json::array();
    for (const Preset &p : bundle.printers) {
        if (!p.is_system || p.is_default)
            continue;
        BoundingBoxf bed = bed_box(p.config);
        json item;
        item["name"]    = p.name;
        item["model"]   = p.config.opt_string("printer_model");
        item["bed"]     = {bed.min.x(), bed.min.y(), bed.max.x(), bed.max.y()};
        item["height"]  = p.config.opt_float("printable_height");
        item["nozzle"]  = p.config.option<ConfigOptionFloats>("nozzle_diameter")->values.front();
        item["default_process"] = p.config.opt_string("default_print_profile");
        if (const auto *fil = p.config.option<ConfigOptionStrings>("default_filament_profile"))
            item["default_filaments"] = fil->values;
        printers.push_back(item);
    }
    out["printers"] = printers;

    if (printer.empty())
        return out;
    engine.select_printer(printer);
    engine.select_process(process);

    json processes = json::array();
    for (const Preset &p : bundle.prints) {
        if (!p.is_compatible || p.is_default || !p.is_system)
            continue;
        processes.push_back({{"name", p.name}, {"layer_height", p.config.opt_float("layer_height")}});
    }
    out["processes"] = processes;

    json filaments = json::array();
    for (const Preset &f : bundle.filaments) {
        if (!f.is_compatible || f.is_default || !f.is_system)
            continue;
        filaments.push_back({{"name", f.name},
                             {"vendor", first_string(f.config, "filament_vendor")},
                             {"type", first_string(f.config, "filament_type")},
                             {"color", first_string(f.config, "default_filament_colour")},
                             {"temperature", f.config.option<ConfigOptionInts>("nozzle_temperature")->values.front()}});
    }
    out["filaments"] = filaments;

    out["selected"] = {{"printer", bundle.printers.get_selected_preset_name()}, {"process", bundle.prints.get_selected_preset_name()}};

    DynamicPrintConfig full = bundle.full_config(false);
    json values = json::object();
    json options = json::object();
    for (const std::string &key : ui_keys()) {
        if (full.has(key))
            values[key] = full.opt_serialize(key);
        options[key] = option_choices(key);
    }
    out["values"]  = values;
    out["options"] = options;
    return out;
}

// ---------------------------------------------------------------------------------------------------------------------
// Job

struct JobObject
{
    std::string path;
    std::string name;
    double      x = 0., y = 0.;
    Vec3d       rotation_deg = Vec3d::Zero();
    Vec3d       scale        = Vec3d::Ones();
    int         filament     = 1;
};

struct Job
{
    std::string                        printer, process;
    std::vector<std::string>           filaments;
    std::vector<std::string>           colors;
    std::map<std::string, std::string> overrides;
    std::string                        bed_type;
    std::vector<JobObject>             objects;
    bool                               arrange = false;
};

std::string as_setting(const json &v)
{
    if (v.is_string())
        return v.get<std::string>();
    if (v.is_boolean())
        return v.get<bool>() ? "1" : "0";
    if (v.is_number_integer())
        return std::to_string(v.get<long long>());
    if (v.is_number())
        return float_to_string_decimal_point(v.get<double>());
    return v.dump();
}

Vec3d as_vec3(const json &v, const Vec3d &fallback)
{
    if (v.is_array() && v.size() == 3)
        return Vec3d(v[0].get<double>(), v[1].get<double>(), v[2].get<double>());
    if (v.is_number())
        return Vec3d(v.get<double>(), v.get<double>(), v.get<double>());
    return fallback;
}

Job read_job(const std::string &path)
{
    std::ifstream in(path);
    if (!in)
        throw UserError("Auftrag nicht lesbar: " + path);
    json j = json::parse(in);
    Job  job;
    job.printer = j.value("printer", "");
    job.process = j.value("process", "");
    for (const json &f : j.value("filaments", json::array())) {
        job.filaments.push_back(f.value("preset", ""));
        job.colors.push_back(f.value("color", "#FFFFFF"));
    }
    const json overrides = j.value("overrides", json::object());
    for (auto it = overrides.begin(); it != overrides.end(); ++it)
        job.overrides[it.key()] = as_setting(it.value());
    job.bed_type = j.value("bed_type", "");
    job.arrange  = j.value("arrange", false);
    for (const json &o : j.value("objects", json::array())) {
        JobObject obj;
        obj.path         = o.value("path", "");
        obj.name         = o.value("name", "");
        obj.x            = o.value("x", 0.);
        obj.y            = o.value("y", 0.);
        obj.rotation_deg = as_vec3(o.value("rotation", json()), Vec3d::Zero());
        obj.scale        = as_vec3(o.value("scale", json()), Vec3d::Ones());
        obj.filament     = o.value("filament", 1);
        job.objects.push_back(obj);
    }
    if (job.printer.empty())
        throw UserError("Kein Drucker gewählt.");
    if (job.objects.empty())
        throw UserError("Keine Objekte auf der Platte.");
    return job;
}

// Minimum flush volume per filament, as the GUI computes it (Plater.cpp get_min_flush_volumes).
std::vector<int> min_flush_volumes(const DynamicPrintConfig &config, size_t nozzle_id)
{
    const auto *nozzle_volume_opt = config.option<ConfigOptionFloatsNullable>("nozzle_volume");
    const int   nozzle_volume     = nozzle_volume_opt ? int(nozzle_volume_opt->get_at(nozzle_id)) : 0;
    const auto *level_opt         = config.option<ConfigOptionInt>("enable_long_retraction_when_cut");
    const int   machine_level     = level_opt ? level_opt->value : 0;
    const auto *activated_opt     = config.option<ConfigOptionBools>("long_retractions_when_cut");
    const bool  machine_activated = activated_opt && nozzle_id < activated_opt->values.size() && activated_opt->values[nozzle_id] == 1;

    const size_t        count = config.option<ConfigOptionFloats>("filament_diameter")->values.size();
    std::vector<double> filament_cut(count, 18.), printer_cut(std::max<size_t>(count, nozzle_id + 1), 18.);
    std::vector<unsigned char> filament_long(count, 0);
    if (const auto *o = config.option<ConfigOptionFloats>("filament_retraction_distances_when_cut"))
        filament_cut = o->values;
    if (const auto *o = config.option<ConfigOptionFloats>("retraction_distances_when_cut"))
        printer_cut = o->values;
    if (const auto *o = config.option<ConfigOptionBools>("filament_long_retractions_when_cut"))
        filament_long = o->values;
    filament_cut.resize(count, 18.);
    filament_long.resize(count, 0);
    printer_cut.resize(std::max<size_t>(printer_cut.size(), nozzle_id + 1), 18.);

    std::vector<int> out;
    for (size_t i = 0; i < count; ++i) {
        int retract = machine_level && machine_activated ? int(printer_cut[nozzle_id]) : 0;
        if (filament_long[i] == 0)
            retract = 0;
        else if (filament_long[i] == 1 && machine_level == LongRectrationLevel::EnableFilament)
            retract = std::isnan(filament_cut[i]) ? int(printer_cut[nozzle_id]) : int(filament_cut[i]);
        out.push_back(nozzle_volume - int(PI * 1.75 * 1.75 / 4 * retract));
    }
    return out;
}

bool parse_rgb(const std::string &text, unsigned char rgba[4])
{
    rgba[0] = rgba[1] = rgba[2] = 255;
    rgba[3]                     = 255;
    std::string s = text;
    if (!s.empty() && s[0] == '#')
        s.erase(0, 1);
    if (s.size() != 6 && s.size() != 8)
        return false;
    for (size_t i = 0; i < s.size() / 2; ++i)
        rgba[i] = (unsigned char) std::stoi(s.substr(2 * i, 2), nullptr, 16);
    return true;
}

// Flush (purge) volumes between the filaments from their colours, as the GUI does when colours change.
void compute_flush_volumes(DynamicPrintConfig &config, const std::vector<std::string> &colors)
{
    const size_t n = colors.size();
    if (n < 2)
        return;
    const size_t nozzles = config.option<ConfigOptionFloats>("nozzle_diameter")->values.size();

    std::vector<int> dataset(nozzles, 0);
    if (const auto *o = config.option<ConfigOptionIntsNullable>("nozzle_flush_dataset"); o != nullptr && !o->values.empty())
        for (size_t z = 0; z < nozzles; ++z)
            dataset[z] = o->get_at(z);

    std::vector<unsigned char> is_support(n, 0);
    if (const auto *o = config.option<ConfigOptionBools>("filament_is_support"))
        for (size_t i = 0; i < n && i < o->values.size(); ++i)
            is_support[i] = o->values[i];

    std::vector<double> matrix(n * n * nozzles, 0.);
    for (size_t z = 0; z < nozzles; ++z) {
        const std::vector<int> min_volumes = min_flush_volumes(config, z);
        for (size_t from = 0; from < n; ++from) {
            unsigned char a[4];
            parse_rgb(colors[from], a);
            for (size_t to = 0; to < n; ++to) {
                double volume = 0.;
                if (from != to) {
                    if (is_support[to]) {
                        volume = g_flush_volume_to_support;
                    } else {
                        unsigned char b[4];
                        parse_rgb(colors[to], b);
                        FlushVolCalculator calc(from < min_volumes.size() ? min_volumes[from] : 0, g_max_flush_volume, dataset[z]);
                        volume = calc.calc_flush_vol(a[3], a[0], a[1], a[2], b[3], b[0], b[1], b[2]);
                        if (is_support[from])
                            volume = std::max<double>(volume, g_min_flush_volume_from_support);
                    }
                }
                matrix[z * n * n + from * n + to] = volume;
            }
        }
    }
    config.option<ConfigOptionFloats>("flush_volumes_matrix", true)->values = matrix;
    auto &mult = config.option<ConfigOptionFloats>("flush_multiplier", true)->values;
    mult.resize(nozzles, 1.);
}

// Vase mode only works with these settings; the desktop GUI asks and then sets exactly these.
void apply_spiral_mode(DynamicPrintConfig &config)
{
    if (!config.opt_bool("spiral_mode"))
        return;
    config.set_key_value("wall_loops", new ConfigOptionInt(1));
    config.set_key_value("top_shell_layers", new ConfigOptionInt(0));
    config.set_key_value("sparse_infill_density", new ConfigOptionPercent(0));
    config.set_key_value("enable_support", new ConfigOptionBool(false));
    config.set_key_value("enforce_support_layers", new ConfigOptionInt(0));
    config.set_key_value("detect_thin_wall", new ConfigOptionBool(false));
    config.set_key_value("overhang_reverse", new ConfigOptionBool(false));
    config.set_key_value("timelapse_type", new ConfigOptionEnum<TimelapseType>(tlTraditional));
    if (config.has("enable_wrapping_detection"))
        config.set_key_value("enable_wrapping_detection", new ConfigOptionBool(false));
}

DynamicPrintConfig build_config(Engine &engine, const Job &job)
{
    engine.select_printer(job.printer);
    engine.select_process(job.process);
    engine.select_filaments(job.filaments);

    PresetBundle      &bundle = *engine.bundle;
    DynamicPrintConfig config = bundle.full_config(false);

    // Filament colours (the ACE slots) replace the presets' defaults.
    if (!job.colors.empty()) {
        std::vector<std::string> colors = job.colors;
        colors.resize(bundle.filament_presets.size(), colors.back());
        config.option<ConfigOptionStrings>("filament_colour", true)->values = colors;
        if (config.has("filament_multi_colour"))
            config.option<ConfigOptionStrings>("filament_multi_colour", true)->values = colors;
    }

    // The user's settings from the app.
    ConfigSubstitutionContext ctx(ForwardCompatibilitySubstitutionRule::Disable);
    for (const auto &[key, value] : job.overrides) {
        if (print_config_def.get(key) == nullptr)
            throw UserError("Unbekannte Einstellung: " + key);
        try {
            config.set_deserialize(key, value, ctx);
        } catch (const std::exception &ex) {
            throw UserError("Ungültiger Wert für " + key + ": " + value);
        }
    }
    apply_spiral_mode(config);

    // Plate type: the printer's default unless the job names one.
    if (!job.bed_type.empty())
        config.set_deserialize("curr_bed_type", job.bed_type, ctx);
    else
        config.set_key_value("curr_bed_type", new ConfigOptionEnum<BedType>(bundle.printers.get_edited_preset().get_default_bed_type(&bundle)));

    compute_flush_volumes(config, config.option<ConfigOptionStrings>("filament_colour")->values);
    return config;
}

Model load_model(const Job &job, int filament_count)
{
    Model model;
    for (size_t i = 0; i < job.objects.size(); ++i) {
        const JobObject &o = job.objects[i];
        Model loaded;
        try {
            loaded = Model::read_from_file(o.path, nullptr, nullptr, LoadStrategy::AddDefaultInstances);
        } catch (const std::exception &ex) {
            throw UserError("Datei nicht lesbar: " + (o.name.empty() ? o.path : o.name) + " (" + ex.what() + ")");
        }
        if (loaded.objects.empty())
            throw UserError("Datei enthält kein Modell: " + (o.name.empty() ? o.path : o.name));
        for (ModelObject *src : loaded.objects) {
            ModelObject *obj = model.add_object(*src);
            if (!o.name.empty())
                obj->name = o.name;
            if (obj->instances.empty())
                obj->add_instance();
            obj->center_around_origin();
            ModelInstance *inst = obj->instances.front();
            inst->set_rotation(Vec3d(Geometry::deg2rad(o.rotation_deg.x()), Geometry::deg2rad(o.rotation_deg.y()), Geometry::deg2rad(o.rotation_deg.z())));
            inst->set_scaling_factor(o.scale);
            inst->set_offset(Vec3d(o.x, o.y, 0.));
            obj->ensure_on_bed();
            obj->config.set_key_value("extruder", new ConfigOptionInt(std::clamp(o.filament, 1, std::max(1, filament_count))));
        }
    }
    return model;
}

std::vector<unsigned int> used_filaments(const Model &model, const DynamicPrintConfig &config)
{
    std::set<unsigned int> ids;
    for (const ModelObject *obj : model.objects) {
        const ConfigOption *opt = obj->config.option("extruder");
        ids.insert(opt ? std::max(1, opt->getInt()) - 1 : 0);
    }
    if (config.opt_bool("enable_support")) {
        if (int f = config.opt_int("support_filament"); f > 0)
            ids.insert(f - 1);
        if (int f = config.opt_int("support_interface_filament"); f > 0)
            ids.insert(f - 1);
    }
    return std::vector<unsigned int>(ids.begin(), ids.end());
}

double max_object_height(const Model &model)
{
    double h = 0.;
    for (const ModelObject *obj : model.objects)
        for (size_t i = 0; i < obj->instances.size(); ++i)
            h = std::max(h, obj->instance_bounding_box(i).size().z());
    return h;
}

struct Tower
{
    bool   present = false;
    double x = 0., y = 0., width = 0., depth = 0., brim = 0.;
};

// The prime tower for ACE filament changes, placed where the desktop GUI places it by default.
Tower place_prime_tower(DynamicPrintConfig &config, const Model &model)
{
    Tower tower;
    const std::vector<unsigned int> filaments = used_filaments(model, config);
    if (!config.opt_bool("enable_prime_tower") || filaments.size() < 2)
        return tower;

    const WipeTowerFootprint footprint = estimate_wipe_tower_footprint(config, resolve_wipe_tower_type(config), filaments,
                                                                       config.opt_float("layer_height"), max_object_height(model));
    if (footprint.depth <= 0.)
        return tower;
    tower.present = true;
    tower.width   = footprint.width;
    tower.depth   = footprint.depth;
    tower.brim    = footprint.brim_width;

    const BoundingBoxf bed    = bed_box(config);
    const double       margin = WIPE_TOWER_AUTO_MARGIN + footprint.brim_width;
    bool               i3     = false;
    if (const auto *structure = config.option<ConfigOptionEnum<PrinterStructure>>("printer_structure"))
        i3 = structure->value == PrinterStructure::psI3;
    double x = i3 ? 0. : 165.;
    double y = 250.;
    if (x + margin + tower.width > bed.max.x())
        x = bed.max.x() - tower.width - margin;
    else if (x < margin + bed.min.x())
        x = margin + bed.min.x();
    if (y + margin + tower.depth > bed.max.y())
        y = bed.max.y() - tower.depth - margin;
    else if (y < margin)
        y = margin;
    tower.x = x;
    tower.y = y;

    config.option<ConfigOptionFloats>("wipe_tower_x", true)->values = {x};
    config.option<ConfigOptionFloats>("wipe_tower_y", true)->values = {y};
    return tower;
}

ExPolygon rectangle(double x, double y, double w, double h)
{
    Polygon p;
    p.points = {Point::new_scale(x, y), Point::new_scale(x + w, y), Point::new_scale(x + w, y + h), Point::new_scale(x, y + h)};
    return ExPolygon(p);
}

// Arrange all objects on the plate, keeping the prime tower's corner free.
void arrange(Model &model, const DynamicPrintConfig &config, const Tower &tower)
{
    const BoundingBoxf bed = bed_box(config);
    BoundingBox        bed_scaled(Point::new_scale(bed.min.x(), bed.min.y()), Point::new_scale(bed.max.x(), bed.max.y()));

    arrangement::ArrangeParams params;
    params.min_obj_distance = scaled(6.);
    params.allow_rotations  = false;
    params.printable_height = config.opt_float("printable_height");

    arrangement::ArrangePolygons excludes;
    if (tower.present) {
        arrangement::ArrangePolygon wt;
        const double m  = tower.brim + 2.;
        wt.poly         = rectangle(tower.x - m, tower.y - m, tower.width + 2 * m, tower.depth + 2 * m);
        wt.bed_idx      = 0;
        wt.is_wipe_tower = true;
        excludes.push_back(wt);
    }

    ModelInstancePtrs instances;
    arrangement::ArrangePolygons items = get_arrange_polys(model, instances);
    arrangement::arrange(items, excludes, bed_scaled, params);
    for (const auto &item : items)
        if (item.bed_idx != 0)
            throw UserError("Nicht alle Objekte passen auf die Bauplatte.");
    apply_arrange_polys(items, instances, [](arrangement::ArrangePolygon &) {});
    for (ModelObject *obj : model.objects)
        obj->ensure_on_bed();
}

json object_positions(const Model &model)
{
    json out = json::array();
    for (const ModelObject *obj : model.objects) {
        const Vec3d off = obj->instances.front()->get_offset();
        BoundingBoxf3 bb = obj->instance_bounding_box(0);
        out.push_back({{"name", obj->name}, {"x", off.x()}, {"y", off.y()},
                       {"size", {bb.size().x(), bb.size().y(), bb.size().z()}},
                       {"bbox", {bb.min.x(), bb.min.y(), bb.max.x(), bb.max.y()}}});
    }
    return out;
}

json tower_json(const Tower &t)
{
    if (!t.present)
        return nullptr;
    return {{"x", t.x}, {"y", t.y}, {"width", t.width}, {"depth", t.depth}, {"brim", t.brim}};
}

json run_arrange(Engine &engine, const Job &job)
{
    progress(5, "Profile laden");
    DynamicPrintConfig config = build_config(engine, job);
    Model              model  = load_model(job, int(engine.bundle->filament_presets.size()));
    Tower              tower  = place_prime_tower(config, model);
    arrange(model, config, tower);
    return {{"ok", true}, {"objects", object_positions(model)}, {"prime_tower", tower_json(tower)}};
}

json run_slice(Engine &engine, const Job &job, const std::string &out_path)
{
    progress(2, "Profile vorbereiten");
    DynamicPrintConfig config = build_config(engine, job);

    progress(4, "Modelle laden");
    const int filament_count = int(engine.bundle->filament_presets.size());
    Model     model          = load_model(job, filament_count);
    Tower     tower          = place_prime_tower(config, model);
    if (job.arrange)
        arrange(model, config, tower);

    // Objects completely outside the plate are not sliced; tell the user instead of producing an empty print.
    const BoundingBoxf bed = bed_box(config);
    for (const ModelObject *obj : model.objects) {
        BoundingBoxf3 bb = obj->instance_bounding_box(0);
        if (bb.min.x() < bed.min.x() - EPSILON || bb.min.y() < bed.min.y() - EPSILON || bb.max.x() > bed.max.x() + EPSILON ||
            bb.max.y() > bed.max.y() + EPSILON || bb.size().z() > config.opt_float("printable_height") + EPSILON)
            throw UserError("„" + obj->name + "“ ragt über die Bauplatte hinaus.");
    }

    Print print;
    print.set_plate_origin(Vec3d::Zero());
    print.apply(model, config);
    print.is_BBL_printer() = false;
    print.set_check_multi_filaments_compatibility(true);

    std::vector<StringObjectException> warnings;
    StringObjectException              err = print.validate(&warnings);
    if (!err.string.empty())
        throw UserError(err.string);
    if (print.empty())
        throw UserError("Nichts zu slicen: kein Objekt liegt vollständig auf der Bauplatte.");

    print.set_status_callback([](const PrintBase::SlicingStatus &s) {
        if (s.percent >= 0)
            progress(std::clamp(5 + s.percent * 90 / 100, 5, 95), s.text);
    });

    const PrintConfig &print_config = print.config();
    Model::setExtruderParams(config, filament_count);
    Model::setPrintSpeedTable(config, print_config);

    print.process();
    progress(96, "G-code schreiben");
    GCodeProcessorResult gcode_result;
    print.export_gcode(out_path, &gcode_result, nullptr);

    // Full statistics (per layer and object) go to a file next to the G-code; the reply carries the summary.
    const std::string statistics_path = gcode_statistics_path(out_path);
    export_gcode_statistics(gcode_result, statistics_path);
    const json statistics = json::parse(gcode_statistics_json(gcode_result));

    json out;
    out["ok"]          = true;
    out["gcode"]       = out_path;
    out["statistics"]  = statistics_path;
    out["summary"]     = statistics["summary"];
    out["objects"]     = object_positions(model);
    out["prime_tower"] = tower_json(tower);
    json w = json::array();
    for (const auto &warning : warnings)
        w.push_back(warning.string);
    out["warnings"] = w;
    progress(100, "Fertig");
    return out;
}

int usage()
{
    std::cerr << "usage:\n"
                 "  slicercore version\n"
                 "  slicercore describe <resources> <data> [<printer> [<process>]]\n"
                 "  slicercore arrange  <resources> <data> <job.json>\n"
                 "  slicercore slice    <resources> <data> <job.json> <out.gcode>\n";
    return 2;
}

} // namespace

int main(int argc, char **argv)
{
    std::vector<std::string> args(argv + 1, argv + argc);
    if (args.empty())
        return usage();
    const std::string cmd = args[0];
    if (cmd == "version") {
        result({{"ok", true}, {"version", SoftFever_VERSION}, {"engine", "OrcaSlicer"}});
        return 0;
    }
    if ((cmd == "describe" && args.size() < 3) || (cmd == "arrange" && args.size() < 4) || (cmd == "slice" && args.size() < 5))
        return usage();

    set_logging_level(1); // errors only; libslic3r logs a lot at info level
    try {
        Engine engine;
        engine.load(args[1], args[2]);
        json out;
        if (cmd == "describe")
            out = describe(engine, args.size() > 3 ? args[3] : "", args.size() > 4 ? args[4] : "");
        else if (cmd == "arrange")
            out = run_arrange(engine, read_job(args[3]));
        else if (cmd == "slice")
            out = run_slice(engine, read_job(args[3]), args[4]);
        else
            return usage();
        result(out);
        return 0;
    } catch (const UserError &ex) {
        result({{"ok", false}, {"error", ex.what()}});
    } catch (const SlicingError &ex) {
        result({{"ok", false}, {"error", ex.what()}});
    } catch (const std::exception &ex) {
        result({{"ok", false}, {"error", std::string("Interner Fehler: ") + ex.what()}});
    }
    return 1;
}
