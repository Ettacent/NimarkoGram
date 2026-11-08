/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include "display_list.h"

#include <cstdint>
#include <functional>
#include <vector>

namespace twlite {

struct LayerKey {
    float t = 0;
    Affine matrix;
    float opacity = 1;
};

struct LayerTrack {
    double start = 0;
    double duration = 0;
    bool repeat = false;
    std::vector<LayerKey> keys;
};

struct Layer {
    int64_t id = 0;
    Rect bounds;
    uint64_t content_version = 0;
    DisplayList content;
    float raster_scale = 1;
    bool has_clip = false;
    Rect clip;
    Radii clip_radii;
    uint64_t track_version = 0;
    LayerTrack track;
    int64_t parent = 0;
};

struct Composition {
    std::vector<Layer> layers;
    uint64_t version = 0;
};

std::vector<LayerKey> resume_track(const LayerTrack& track, double elapsed, double& duration);

std::string to_json(const Composition& composition);

uint64_t paint_digest(const DisplayList& list);

struct PaintSummary {
    struct Entry {
        uint64_t key = 0;
        Rect ink;
        int canvas = 0;
        bool backdrop = false;
    };
    std::vector<Entry> entries;
};
PaintSummary summarize(const DisplayList& list);

size_t drop_inert_clips(DisplayList& list);

bool damage_between(const PaintSummary& before, const PaintSummary& after, const std::function<bool(int canvas)>& canvas_changed, std::vector<Rect>& out);

bool bake_track(const std::function<bool(double t, LayerKey& key)>& sample, const Rect& box, std::vector<LayerKey>& keys);

}
