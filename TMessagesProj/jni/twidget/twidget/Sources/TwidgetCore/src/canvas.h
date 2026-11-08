/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include "backend.h"
#include "canvas_ops.h"
#include "twidget/json.h"

#include <cstdint>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

namespace twlite {

class CanvasHost {
public:
    virtual ~CanvasHost() = default;
    virtual int canvas_font(const std::string& css, FontMetrics& metrics) = 0;
    virtual float canvas_text_width(int font, const std::string& text) = 0;
    virtual bool canvas_source(int64_t node, int& image, int& canvas, float& w, float& h) = 0;
    virtual int64_t canvas_allowance(int id) = 0;
};

class Canvas {
public:
    static constexpr int MAX_SIDE = 8192;
    static constexpr int64_t MAX_PIXELS = 4096 * 4096;

    Canvas(int id, int width, int height, CanvasHost& host);

    int id() const { return id_; }
    int width() const { return width_; }
    int height() const { return height_; }
    int64_t pixels() const { return int64_t(width_) * height_; }

    void run(const twidget::Json& cmds, std::vector<CanvasOp>& out);
    void resize(int width, int height, std::vector<CanvasOp>& out);
    twidget::Json call(const std::string& method, const twidget::Json& args) const;

private:
    struct Matrix {
        double a = 1, b = 0, c = 0, d = 1, e = 0, f = 0;
        void apply(double x, double y, float& ox, float& oy) const {
            ox = float(a * x + c * y + e);
            oy = float(b * x + d * y + f);
        }
        Matrix then(const Matrix& m) const;
        bool invert(Matrix& out) const;
    };
    struct Paint {
        bool gradient = false;
        Color color{0, 0, 0, 255};
        int id = 0;
    };
    struct State {
        Matrix matrix;
        Paint fill, stroke;
        std::string font = "10px sans-serif";
        std::string align = "start", baseline = "alphabetic";
        std::vector<float> dash;
        float dash_offset = 0;
        float line_width = 1;
        int line_cap = 0, line_join = 0;
        float miter_limit = 10;
        float alpha = 1;
        Composite composite = Composite::SourceOver;
        Color shadow_color{0, 0, 0, 0};
        float shadow_blur = 0, shadow_x = 0, shadow_y = 0;
        bool smoothing = true;
        std::shared_ptr<const std::vector<CanvasClip>> clips;
    };
    struct Gradient {
        std::string kind;
        std::vector<float> args;
        std::vector<Stop> stops;
    };
    struct Builder {
        Geometry geometry;
        bool open = false;
        bool has_point = false;
        float sx = 0, sy = 0, cx = 0, cy = 0;
    };
    struct Value;

    void call_method(const std::string& method, const std::vector<Value>& args, std::vector<CanvasOp>& out);
    void path_method(Builder& path, const Matrix& m, const std::string& method, const std::vector<Value>& args) const;
    void set(const std::string& property, const Value& value);
    CanvasOp op(CanvasOp::Kind kind) const;
    bool paint_of(const Paint& paint, CanvasPaint& out) const;
    void fill(const Geometry& path, bool evenodd, std::vector<CanvasOp>& out);
    void stroke(const Geometry& path, std::vector<CanvasOp>& out);
    void text(const std::string& s, double x, double y, const Value* max_width, bool stroke, std::vector<CanvasOp>& out);
    void image(const std::vector<Value>& args, std::vector<CanvasOp>& out);
    Geometry transformed(const Geometry& path, const Matrix& m) const;
    Geometry rect_geometry(double x, double y, double w, double h) const;

    int id_;
    int width_, height_;
    CanvasHost& host_;
    State state_;
    std::vector<State> stack_;
    Builder path_;
    std::map<int, Gradient> gradients_;
    bool color_of(const std::string& text, Color& out);
public:
    void trim() { colors_.clear(); }
private:
    std::unordered_map<std::string, std::optional<Color>> colors_;
    std::map<int, Builder> paths_;
};

void append_svg_path(const std::string& d, Geometry& out);

bool geometry_contains(const Geometry& g, double x, double y, bool evenodd);

}
