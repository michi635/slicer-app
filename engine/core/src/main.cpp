// slicercore entry point (first build check: links libslic3r and loads the system presets).
#include "libslic3r/libslic3r.h"
#include "libslic3r_version.h"
#include "libslic3r/Utils.hpp"
#include "libslic3r/AppConfig.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/Print.hpp"

#include <cstdio>
#include <cstring>
#include <string>

using namespace Slic3r;

int main(int argc, char **argv)
{
    if (argc >= 2 && std::strcmp(argv[1], "--version") == 0) {
        std::printf("slicercore (OrcaSlicer %s)\n", SoftFever_VERSION);
        return 0;
    }
    if (argc >= 4 && std::strcmp(argv[1], "--list") == 0) {
        set_resources_dir(argv[2]);
        set_data_dir(argv[3]);
        AppConfig app_config;
        PresetBundle bundle;
        std::string errors;
        bundle.load_presets(app_config, ForwardCompatibilitySubstitutionRule::EnableSilent, PresetBundle::PresetPreferences(), &errors, true);
        std::printf("printers %zu, processes %zu, filaments %zu\n", bundle.printers.size(), bundle.prints.size(), bundle.filaments.size());
        if (!errors.empty())
            std::printf("errors: %s\n", errors.c_str());
        Model model;
        Print print;
        (void) model;
        (void) print;
        return 0;
    }
    std::fprintf(stderr, "usage: slicercore --version | --list <resources> <datadir>\n");
    return 2;
}
