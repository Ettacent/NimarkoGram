/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include "animations.h"
#include "backend.h"
#include "canvas.h"
#include "compositor.h"
#include "display_list.h"
#include "twidget/gesture_policy.h"
#include "twidget/json.h"

#include <array>
#include <cstdint>
#include <deque>
#include <limits>
#include <map>
#include <memory>
#include <optional>
#include <set>
#include <string>
#include <tuple>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace twlite {

class Container;

struct BuildStats {
    double serialize_ms = 0, parse_ms = 0;
    int passes = 0;
    size_t html_bytes = 0;
    int builds = 0;
    int patched = 0;
    int throttled = 0;
    int in_place = 0;
    int scripted = 0;
    int reused = 0;
};

class Renderer : private CanvasHost {
public:
    Renderer(double width, double dpr, bool dark, Backend& backend);
    ~Renderer();
    Renderer(const Renderer&) = delete;
    Renderer& operator=(const Renderer&) = delete;

    std::vector<twidget::Json> receive(const twidget::Json& msg);
    void set_width(double width, std::vector<twidget::Json>& out);
    void set_max_height(double height, std::vector<twidget::Json>& out);
    void set_theme(bool dark, std::vector<twidget::Json>& out);
    std::vector<twidget::Json> touch(int action, double x, double y, int pointer, bool primary, bool mouse = false, int modifiers = 0, int button = 0);
    std::vector<twidget::Json> hover(int action, double x, double y, int modifiers = 0);
    std::string cursor() const;
    std::vector<twidget::Json> wheel(double x, double y, double dx, double dy, int mode, int modifiers = 0);
    std::vector<twidget::Json> key(const std::string& type, const std::string& key, const std::string& code, bool repeat, int modifiers);

    struct EditorSpec {
        int64_t id = 0;
        uint64_t generation = 0;
        bool multiline = false;
        Rect frame;
        bool has_clip = false;
        Rect clip;
        int font = 0;
        std::string font_family;
        float font_size = 16;
        int font_weight = 400;
        bool italic = false;
        Color color;
        int align = 0;
        std::string text, placeholder;
        std::string input_type;
        int max_length = -1;
        bool read_only = false;
        int selection_start = -1, selection_end = -1;
    };
    const EditorSpec* editor() const { return editing_.id ? &editing_ : nullptr; }
    std::string editor_json() const;
    std::vector<twidget::Json> edit(uint64_t generation, const std::string& text, const std::string& input_type = "insertText");
    std::vector<twidget::Json> end_editing();
    bool has_selection() const;
    std::string selected_text() const;
    bool clear_selection();
    const std::string& master_css() const;
    const std::vector<std::pair<std::string, std::string>>& theme_variables() const { return theme_vars_; }
    std::string theme_variables_json() const;
    std::string selectable_text_json() const;
    std::vector<twidget::Json> tick();
    bool wants_tick() const;
    double next_tick_in() const;

    void set_compositing(bool enabled);
    const Composition& composition() const { return composition_; }
    static double clock() { return now_s(); }
    std::vector<twidget::Json> image_ready(int id, float w, float h);

    struct ResizeDiff {
        double old_height = 0, new_height = 0;
        double unchanged_top = 0, unchanged_bottom = 0;
        bool has_pointer = false;
        double pointer_from = 0, pointer_to = 0;
        bool keeps_bottom = false;
    };
    void set_resize_tracking(bool enabled, bool prefers_bottom = false);
    const ResizeDiff* resize_diff() const { return has_resize_ ? &resize_ : nullptr; }

    double width() const { return width_; }
    int viewport_height() const { return vp_height_; }
    double dpr() const { return dpr_; }
    uint32_t px_width() const;
    uint32_t px_height() const;
    bool dirty() const { return dirty_; }
    DisplayList paint();
    bool damage(std::vector<Rect>& out) const;
    void trim();
    void charge(double seconds);
    std::string damage_json() const;
    const BuildStats& stats() const { return stats_; }
    void set_patching(bool enabled) { patching_ = enabled; }
    uint64_t layouts() const;
    const std::string& last_html() {
        if (stale_) settle(pending_events_);
        if (html_stale_) {
            html_ = serialize();
            html_stale_ = false;
        }
        return html_;
    }

private:
    friend class Container;
    struct Node;
    struct Touch {
        double x0, y0, x, y;
        int64_t target;
        Rect target_rect;
        bool moved;
        bool primary;
        int pointer;
        bool mouse;
        int modifiers;
        int button = 0;
        bool held = false;
        int64_t capture = 0;
        double sent_x = x0, sent_y = y0;
        bool finger = false;
    };
    struct Input {
        double x = 0, y = 0;
        int pointer = 0;
        bool primary = true;
        bool mouse = false;
        int button = 0;
        int buttons = 0;
        int modifiers = 0;
        int detail = 0;
        int64_t related = 0;
        double movement_x = 0, movement_y = 0;
    };

    void apply_op(const twidget::Json& op, std::vector<twidget::Json>& out);
    void apply_ops(const std::vector<twidget::Json>& ops, std::vector<twidget::Json>& out);
    Node* node(int64_t id) const;
    void adopt(int64_t id, Node* n, const twidget::Json& attrs);
    void detach(Node* n);
    void forget(Node* n);
    std::string serialize() const;
    void serialize_node(const Node* n, std::string& out) const;
    void rebuild(bool patch = false);
    void settle(std::vector<twidget::Json>& out);
    bool throttled() const;
    bool frame_due() const;
    struct Meter {
        Renderer& r;
        double start;
        explicit Meter(Renderer& renderer) : r(renderer), start(r.meter_depth_++ ? 0 : now_s()) {}
        ~Meter() {
            if (--r.meter_depth_ == 0) r.charge(now_s() - start);
        }
    };
    int meter_depth_ = 0;
    std::deque<std::pair<double, double>> charges_;
    double first_charge_ = -1;
    double throttled_until_ = 0;
    void report_size(std::vector<twidget::Json>& out);
    void note_resize();
    twidget::Json answer(const twidget::Json& q);
    bool match_media(const std::string& query) const;
    int64_t widget_id_at(double x, double y, Rect* placement) const;
    twidget::WidgetGestureClaim gesture_claim(int64_t target) const;
    double arbiter_x(double x) const { return rtl_ ? width_ - x : x; }
    std::optional<double> hold_deadline() const;
    void check_hold(std::vector<twidget::Json>& out);
    void settle_gesture(const twidget::GestureVerdict& v, int pointer, std::vector<twidget::Json>& out);
    void bubble_takes(int pointer, const twidget::GestureVerdict& v, std::vector<twidget::Json>& out);
    void set_capture(Touch& t, int64_t id, std::vector<twidget::Json>& out);
    Rect box_of(int64_t id) const;
    void emit_event(const char* type, int64_t target, const Rect& target_rect, const Touch& t, int action, std::vector<twidget::Json>& out);
    twidget::Json event_json(const char* type, int64_t target, const Rect& target_rect, const Input& in) const;
    void load_images(std::vector<twidget::Json>& out);
    void image_event(const char* type, int64_t id, float w, float h, std::vector<twidget::Json>& out);
    static twidget::Json event_message(twidget::Json ev, bool trusted = true);
    void dispatch(twidget::Json ev, std::vector<twidget::Json>& out, bool trusted = true);
    void flush_moves(std::vector<twidget::Json>& out);
    void flush_pending(std::vector<twidget::Json>& out);
    void report_resizes(std::vector<twidget::Json>& out);
    void toggle_details(int64_t clicked, std::vector<twidget::Json>& out);
    bool listened(const char* type) const;
    void send_event(const char* type, int64_t target, const Input& in, std::vector<twidget::Json>& out);
    int64_t text_control(int64_t id) const;
    std::string control_value(const Node* n) const;
    void focus(int64_t id, std::vector<twidget::Json>& out);
    void blur(std::vector<twidget::Json>& out);
    void form_event(const char* type, int64_t id, std::vector<twidget::Json>& out, const char* input_type = nullptr);
    void refresh_editor();
    EditorSpec editing_;
    std::string editing_initial_;
    uint64_t editor_generation_ = 0;
    bool is_ancestor(int64_t ancestor, int64_t id) const;
    std::vector<int64_t> chain(int64_t id) const;
    void write_style(const Node* n, std::string& out) const;
    static double now_s();
    void build_document(bool patch = false);
    struct PatchLog {
        bool valid = true;
        std::map<const Node*, std::set<std::string>> attributes;
        std::set<const Node*> texts;
    };
    bool attached(const Node* n) const;
    void note_attribute(const Node* n, const std::string& name);
    void note_children(const Node* parent, const Node* child);
    bool patch_document();
    void animate();
    void apply_overrides(StyleOverrides sampled);
    void queue_animation_events();
    struct Promotion {
        Rect box;
        Rect reach;
        float raster_scale = 1;
        bool has_clip = false;
        Rect clip;
        Radii clip_radii;
        uint64_t signature = 0;
        uint64_t track_version = 0;
        LayerTrack track;
        size_t order = 0;
        bool scripted = false;
        Declared base;
        bool shimmer = false;
        DisplayList tile;
        uint64_t tile_version = 0;
        bool svg = false;
        bool canvas = false;
        int64_t parent = 0;
        bool found_clip = false;
        Rect found_clip_rect;
    };
    void update_composition(double now);
    bool scripted_only(const twidget::Json::Array& ops, std::vector<int64_t>& touched) const;
    void note_svg(const twidget::Json::Array& ops);
    std::unordered_map<int64_t, int> svg_streaks_;
    std::set<int64_t> svg_layers_;
    std::unordered_map<int, uint64_t> canvas_draws_;
    std::set<int64_t> canvas_layers_;
    uint64_t content_version(const DisplayList& content) const;
    void note_scripted(const twidget::Json::Array& ops);
    bool apply_scripted(const std::vector<int64_t>& ids);
    bool scripted_key(int64_t id, const Promotion& p, LayerKey& out) const;
    bool still_track(int64_t id, Promotion& p, double now);
    bool reuse_paint() const;
    struct ScriptedHit {
        int count = 0;
        uint64_t batch = 0;
        double at = 0;
    };
    std::unordered_map<int64_t, ScriptedHit> scripted_hits_;
    uint64_t ops_batch_ = 0;
    std::set<int64_t> scripted_candidates_;
    bool scripted_pending_ = false;
    bool composition_only_ = false;
    DisplayList painted_list_;
    bool painted_list_valid_ = false;
    std::tuple<const void*, uint32_t, uint64_t> painted_key_{};
    void strip_layers(StyleOverrides& sampled) const;
    Canvas* canvas_for(Node* n);
    void run_canvas(Canvas& canvas, const twidget::Json& cmds);
    void forget_canvas(int64_t id);
    int canvas_font(const std::string& css, FontMetrics& metrics) override;
    twidget::Json measure_text(const std::string& font, const std::string& text);
    float canvas_text_width(int font, const std::string& text) override;
    bool canvas_source(int64_t node, int& image, int& canvas, float& w, float& h) override;
    int64_t canvas_allowance(int id) override;
    void freeze(const std::string& reason, bool fatal);
    bool still() const { return frozen_ || paint_stopped_; }

    void count_paint();
    void scan_boxes();
    void recount_boxes(const std::vector<int64_t>& ids);
    std::optional<float> layer_opacity(int64_t id) const;
    bool sync_motion(double now);
    void stop_paint(const paint::Verdict& verdict);

    struct TextPoint {
        int64_t owner = 0;
        size_t offset = 0;
        bool operator==(const TextPoint& o) const { return owner == o.owner && offset == o.offset; }
    };
    struct TextRun {
        int64_t owner = 0;
        size_t offset = 0;
        std::string text;
        Rect box;
        uintptr_t font = 0;
        int block = 0;
        bool selectable = false;
        bool line_break = false;
        mutable std::unordered_map<size_t, float> advances;
        mutable Affine shown;
        mutable int64_t layer = 0;
        mutable bool drawn = false;
    };
    const std::vector<TextRun>& text_runs() const;
    bool locate(const TextPoint& p, const std::vector<TextRun>& runs, size_t& run, size_t& offset) const;
    bool point_at(double x, double y, const std::vector<TextRun>& runs, TextPoint& out) const;
    float run_advance(const TextRun& run, size_t bytes) const;
    bool selection_range(const std::vector<TextRun>& runs, size_t& first, size_t& first_offset, size_t& last, size_t& last_offset) const;
    bool starts_selection(int64_t target) const;
    void paint_selection(DisplayList& list, int64_t layer, bool tint) const;
    bool selectable_text() const;
    bool run_shown(const TextRun& r, double now, std::unordered_map<int64_t, std::optional<Affine>>& layers, Affine& out) const;
    bool layer_shown(int64_t id, double now, Affine& shown, float& opacity) const;
    void selection_changed();
    void restyled_by_input();
    bool apply_theme(const twidget::Json& theme);
    bool apply_safe_area(const twidget::Json& inset);
    void apply_root_variables();

    std::unique_ptr<Container> container_;
    Backend& backend_;
    double width_;
    double dpr_;
    bool dark_;
    int vp_height_;
    double doc_height_ = 0;
    bool dirty_ = true;
    bool document_changed_ = false;
    bool stale_ = false;
    double last_input_ = -1e9;
    DisplayList paint_document();
    PaintSummary painted_;
    bool painted_valid_ = false;
    std::pair<uint32_t, uint32_t> painted_size_{0, 0};
    std::vector<Rect> damage_;
    bool damage_valid_ = false;
    std::unordered_set<int> canvases_drawn_;
    double max_height_ = 600;
    struct Edges {
        float top, bottom;
    };
    bool track_resize_ = false, prefers_bottom_ = false;
    std::unordered_map<int64_t, Edges> edges_;
    double edges_height_ = 0;
    uint64_t edges_layouts_ = 0;
    bool has_resize_ = false;
    ResizeDiff resize_;
    double resize_at_ = -1e9;
    uint64_t resize_serial_ = 0, resize_reported_ = 0;
    int64_t press_id_ = 0;
    double press_y_ = 0, press_at_ = -1e9;
    PatchLog patch_log_;
    std::unordered_set<int64_t> touched_;
    bool touched_all_ = true;
    uint64_t painted_layouts_ = 0;
    bool patching_ = true;
    mutable std::string html_;
    mutable bool html_stale_ = false;
    BuildStats stats_;
    std::vector<std::string> pending_violations_;

    std::unordered_map<int64_t, std::unique_ptr<Node>> nodes_;
    std::unordered_map<int64_t, Node*> aliases_;
    std::unique_ptr<Node> html_node_, head_node_, body_node_;
    std::vector<int64_t> pending_images_;
    std::unordered_map<int, std::vector<int64_t>> waiting_images_;
    std::unordered_set<std::string> interest_;
    std::vector<std::pair<std::string, std::string>> host_theme_vars_, safe_area_vars_;
    std::vector<std::pair<std::string, std::string>> theme_vars_;
    bool theme_received_ = false;
    struct {
        TextPoint anchor, focus;
        int pointer = -1;
    } selection_;
    mutable std::tuple<const void*, uint32_t, uint64_t, uint64_t> runs_key_{};
    mutable std::vector<TextRun> runs_;
    mutable std::pair<const void*, uint64_t> selectable_key_{};
    mutable bool selectable_ = false;
    std::unordered_set<std::string> prevent_;
    bool frozen_ = false;
    std::string frozen_reason_;
    bool paint_on_ = true;
    bool paint_reports_ = false;
    std::pair<int, int> paint_reported_{-1, -1};
    bool paint_scan_ = true;
    bool paint_stopped_ = false;
    paint::PaintCounter<int64_t> paint_;
    uint64_t motion_hash_ = 0;
    bool motion_stale_ = true;
    std::vector<Animator::Motion> motions_;
    struct CountedMotion {
        int64_t key = 0;
        std::string id;
        paint::BoxPaint paint;
    };
    std::vector<CountedMotion> motion_counted_;
    bool frozen_fatal_ = false;
    std::array<double, 4> last_size_{};
    uint64_t declared_generation_ = 0;
    std::unordered_map<int, Touch> touches_;
    int64_t hover_ = 0;
    double hover_x_ = 0, hover_y_ = 0;
    int click_count_ = 0;
    twidget::GestureArbiter arbiter_;
    int arbiter_pointer_ = -1;
    bool bubble_ = false, bubble_touch_ = false, rtl_ = false;
    std::vector<std::pair<std::string, twidget::Json>> pending_moves_;
    std::vector<twidget::Json> pending_events_;
    std::map<int64_t, std::pair<float, float>> resize_watch_;

    Animator animator_;
    StyleOverrides overrides_;
    std::string sheets_key_;
    bool animating_ = false;

    bool compositing_ = false;
    std::map<int64_t, Promotion> promoted_;
    std::unordered_set<int64_t> layer_ids_;
    Composition composition_;
    double wake_at_ = std::numeric_limits<double>::infinity();
    uint64_t composition_key_ = 0;
    uint64_t next_version_ = 1;

    std::unordered_map<int64_t, std::unique_ptr<Canvas>> canvases_;
    std::unordered_map<std::string, std::pair<int, FontMetrics>> canvas_fonts_;
    std::string binary_;
};

std::string rewrite_color_scheme(const std::string& css, bool dark);

}
