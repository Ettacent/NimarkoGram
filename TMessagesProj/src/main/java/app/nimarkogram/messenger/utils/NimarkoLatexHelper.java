package app.nimarkogram.messenger.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.util.LruCache;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.CodeHighlighting;
import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.TextStyleSpan;
import org.telegram.ui.Components.URLSpanMono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.nimarkogram.messenger.NimarkoConfig;
import ru.noties.jlatexmath.JLatexMathAndroid;
import ru.noties.jlatexmath.JLatexMathDrawable;

public class NimarkoLatexHelper {

    private static final Pattern DISPLAY_MATH = Pattern.compile("(?<!\\\\)\\$\\$(.+?)(?<!\\\\)\\$\\$", Pattern.DOTALL);
    private static final Pattern INLINE_MATH = Pattern.compile("(?<![\\\\$])\\$(?!\\$|\\s)([^$\\r\\n]+?)(?<!\\s)\\$(?!\\$)");
    private static final Pattern PURE_NUMBER = Pattern.compile("^[\\d,.\\s]+$");
    private static final Pattern LATEX_COMMAND = Pattern.compile("\\\\(?:[a-zA-Z]+|[^a-zA-Z\\s])");
    private static final Pattern WORD = Pattern.compile("\\p{L}+");
    private static final Pattern KNOWN_MATH_WORD = Pattern.compile(
            "(?i)^(?:sin|cos|tan|cot|sec|csc|sinh|cosh|tanh|log|ln|exp|lim|max|min|mod|gcd|lcm|det|dim|ker|arg)$");
    private static final Pattern MATH_OPERATOR = Pattern.compile("[=+\\-*/^_<>|&]");
    private static final Pattern MATH_SYMBOL = Pattern.compile("[±×÷·⋅√∞∑∏∫∂∇∀∃∈∉⊂⊃∪∩≤≥≠≈≡∼∝→←⇒⇐⇔]");
    private static final Pattern COMPACT_NUMBER_VARIABLE = Pattern.compile("(?:\\d[A-Za-z]|[A-Za-z]\\d)");

    private static final int MAX_FORMULAS = 20;
    private static final int MAX_FORMULA_LENGTH = 2000;
    private static final float MIN_TEXT_SIZE_RATIO = 0.7f;
    private static final int MAX_BITMAP_DIMENSION = 4096;
    private static final long MAX_BITMAP_PIXELS = 2_000_000L;

    private static final Object RENDER_LOCK = new Object();
    private static final LruCache<String, Bitmap> RENDER_CACHE = new LruCache<>(48);
    private static volatile boolean latexInitialized;
    private static volatile boolean latexUnavailable;

    public static CharSequence processLatex(CharSequence text, float textSize, int maxTextWidth, boolean preview) {
        if (!NimarkoConfig.latexRenderingEnabled) {
            removeLatexSpans(text);
            return text;
        }
        if (text == null || text.length() < 3) return text;

        String raw = text.toString();
        if (!raw.contains("$")) return text;

        SpannableStringBuilder ssb;
        if (text instanceof SpannableStringBuilder) {
            ssb = (SpannableStringBuilder) text;
            
            LatexSpan[] existing = ssb.getSpans(0, ssb.length(), LatexSpan.class);
            if (existing.length > 0) {
                int expectedWidth = maxTextWidth > 0 ? maxTextWidth : getFallbackWidth();
                boolean reusable = true;
                for (LatexSpan span : existing) {
                    if (!span.matches(textSize, expectedWidth, preview)) { reusable = false; break; }
                }
                if (reusable) return ssb;
                removeLatexSpans(ssb);
            }
        } else {
            ssb = new SpannableStringBuilder(text);
        }

        List<int[]> codeRanges = getCodeRanges(ssb);

        List<LatexMatch> matches = findMatches(raw, codeRanges);

        if (matches.isEmpty()) return text;

        int maxW = maxTextWidth > 0 ? maxTextWidth : getFallbackWidth();

        for (LatexMatch m : matches) {
            try {
                float size = m.display ? textSize * 1.2f : textSize;
                Drawable drawable = renderFormula(m.formula, size, maxW);
                if (drawable == null) continue;

                LatexSpan span = new LatexSpan(drawable, m.display, preview, textSize, maxW);
                ssb.setSpan(span, m.start, m.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (OutOfMemoryError oom) {
                FileLog.e("NimarkoLatex: OOM: " + m.formula.substring(0, Math.min(50, m.formula.length())));
            } catch (Exception e) {
                FileLog.e("NimarkoLatex: span fail: " + m.formula);
            }
        }

        return ssb;
    }

    public static String cleanForPreview(String text) {
        if (!NimarkoConfig.latexRenderingEnabled || text == null || !text.contains("$")) return text;

        List<LatexMatch> matches = findMatches(text, Collections.emptyList());
        if (matches.isEmpty()) return text;

        StringBuilder result = new StringBuilder(text);
        for (int i = matches.size() - 1; i >= 0; i--) {
            LatexMatch match = matches.get(i);
            result.replace(match.start, match.end, latexToUnicode(match.formula));
        }
        return result.toString();
    }

    private static void removeLatexSpans(CharSequence text) {
        if (!(text instanceof Spannable)) return;
        Spannable spannable = (Spannable) text;
        LatexSpan[] spans = spannable.getSpans(0, spannable.length(), LatexSpan.class);
        for (LatexSpan span : spans) {
            spannable.removeSpan(span);
        }
    }

    private static final Pattern FRAC_PATTERN = Pattern.compile("\\\\frac\\{([^}]*)\\}\\{([^}]*)\\}");
    private static final Pattern SQRT_PATTERN = Pattern.compile("\\\\sqrt\\{([^}]*)\\}");
    private static final Pattern TEXT_PATTERN = Pattern.compile("\\\\(?:text|mathrm|mathit|mathbf|operatorname)\\{([^}]*)\\}");
    private static final Pattern LEFT_RIGHT = Pattern.compile("\\\\(?:left|right|big|Big|bigg|Bigg)\\s*");
    private static final Pattern COMMAND_PATTERN = Pattern.compile("\\\\[a-zA-Z]+");

    private static String latexToUnicode(String s) {
        if (s == null || !s.contains("\\")) return s;
        s = FRAC_PATTERN.matcher(s).replaceAll("($1/$2)");
        s = SQRT_PATTERN.matcher(s).replaceAll("√($1)");
        s = TEXT_PATTERN.matcher(s).replaceAll("$1");
        s = LEFT_RIGHT.matcher(s).replaceAll("");
        String[][] greekMap = {
            {"\\alpha","α"},{"\\beta","β"},{"\\gamma","γ"},{"\\delta","δ"},
            {"\\epsilon","ε"},{"\\zeta","ζ"},{"\\eta","η"},{"\\theta","θ"},
            {"\\iota","ι"},{"\\kappa","κ"},{"\\lambda","λ"},{"\\mu","μ"},
            {"\\nu","ν"},{"\\xi","ξ"},{"\\pi","π"},{"\\rho","ρ"},
            {"\\sigma","σ"},{"\\tau","τ"},{"\\phi","φ"},{"\\chi","χ"},
            {"\\psi","ψ"},{"\\omega","ω"},
            {"\\Gamma","Γ"},{"\\Delta","Δ"},{"\\Theta","Θ"},{"\\Lambda","Λ"},
            {"\\Xi","Ξ"},{"\\Pi","Π"},{"\\Sigma","Σ"},{"\\Phi","Φ"},
            {"\\Psi","Ψ"},{"\\Omega","Ω"},
            {"\\infty","∞"},{"\\sum","Σ"},{"\\prod","Π"},{"\\int","∫"},
            {"\\partial","∂"},{"\\nabla","∇"},{"\\forall","∀"},{"\\exists","∃"},
            {"\\in","∈"},{"\\notin","∉"},{"\\subset","⊂"},{"\\supset","⊃"},
            {"\\cup","∪"},{"\\cap","∩"},{"\\pm","±"},{"\\mp","∓"},
            {"\\times","×"},{"\\div","÷"},{"\\cdot","·"},{"\\circ","∘"},
            {"\\leq","≤"},{"\\geq","≥"},{"\\neq","≠"},{"\\approx","≈"},
            {"\\equiv","≡"},{"\\sim","∼"},{"\\propto","∝"},
            {"\\to","→"},{"\\rightarrow","→"},{"\\leftarrow","←"},
            {"\\Rightarrow","⇒"},{"\\Leftarrow","⇐"},{"\\iff","⇔"},
            {"\\ldots","…"},{"\\cdots","⋯"},{"\\dots","…"},
            {"\\langle","⟨"},{"\\rangle","⟩"},
        };
        for (String[] pair : greekMap) {
            if (s.contains(pair[0])) {
                s = s.replace(pair[0], pair[1]);
            }
        }
        s = COMMAND_PATTERN.matcher(s).replaceAll("");
        s = s.replace("{", "").replace("}", "");
        s = s.replace("  ", " ").trim();
        return s;
    }

    private static Drawable renderFormula(String formula, float baseSize, int maxWidth) {
        String cacheKey = formula + '|' + Float.floatToIntBits(baseSize) + '|' + maxWidth;
        synchronized (RENDER_CACHE) {
            Bitmap cached = RENDER_CACHE.get(cacheKey);
            if (cached != null && !cached.isRecycled()) {
                BitmapDrawable result = new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), cached);
                result.setBounds(0, 0, cached.getWidth(), cached.getHeight());
                return result;
            }
        }
        float minSize = baseSize * MIN_TEXT_SIZE_RATIO;

        JLatexMathDrawable rendered = buildDrawable(formula, baseSize);
        if (rendered == null) return null;

        int w = rendered.getIntrinsicWidth();
        int h = rendered.getIntrinsicHeight();
        if (w <= 0 || h <= 0) return null;

        if (w > maxWidth && maxWidth > 0) {
            float needed = baseSize * ((float) maxWidth / w);
            float adjusted = Math.max(needed, minSize);
            rendered = buildDrawable(formula, adjusted);
            if (rendered == null) return null;
            w = rendered.getIntrinsicWidth();
            h = rendered.getIntrinsicHeight();
            if (w <= 0 || h <= 0) return null;
        }

        float scale = 1f;
        if (maxWidth > 0 && w > maxWidth) scale = Math.min(scale, (float) maxWidth / w);
        if (w > MAX_BITMAP_DIMENSION) scale = Math.min(scale, (float) MAX_BITMAP_DIMENSION / w);
        if (h > MAX_BITMAP_DIMENSION) scale = Math.min(scale, (float) MAX_BITMAP_DIMENSION / h);
        long pixels = (long) w * (long) h;
        if (pixels > MAX_BITMAP_PIXELS) {
            scale = Math.min(scale, (float) Math.sqrt((double) MAX_BITMAP_PIXELS / pixels));
        }
        int fitW = Math.max(1, Math.round(w * scale));
        int fitH = Math.max(1, Math.round(h * scale));
        Bitmap bmp = Bitmap.createBitmap(fitW, fitH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        if (scale != 1f) c.scale(scale, scale);
        rendered.setBounds(0, 0, w, h);
        rendered.draw(c);

        synchronized (RENDER_CACHE) { RENDER_CACHE.put(cacheKey, bmp); }

        BitmapDrawable drawable = new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), bmp);
        drawable.setBounds(0, 0, bmp.getWidth(), bmp.getHeight());
        return drawable;
    }

    private static JLatexMathDrawable buildDrawable(String formula, float size) {
        if (!ensureLatexInitialized()) return null;
        try {
            synchronized (RENDER_LOCK) {
                return JLatexMathDrawable.builder(formula)
                        .textSize(size)
                        .color(0xFF000000)
                        .padding(2)
                        .build();
            }
        } catch (LinkageError e) {
            latexUnavailable = true;
            FileLog.e("NimarkoLatex: renderer unavailable", e);
            return null;
        } catch (RuntimeException e) {
            FileLog.e("NimarkoLatex: formula render failed", e);
            return null;
        }
    }

    private static boolean ensureLatexInitialized() {
        if (latexInitialized) return true;
        if (latexUnavailable) return false;
        synchronized (RENDER_LOCK) {
            if (latexInitialized) return true;
            if (latexUnavailable) return false;
            try {
                if (ApplicationLoader.applicationContext == null) return false;
                JLatexMathAndroid.init(ApplicationLoader.applicationContext);
                latexInitialized = true;
                return true;
            } catch (LinkageError e) {
                latexUnavailable = true;
                FileLog.e("NimarkoLatex: initialization failed", e);
                return false;
            } catch (RuntimeException e) {
                FileLog.e("NimarkoLatex: initialization deferred", e);
                return false;
            }
        }
    }

    private static int getFallbackWidth() {
        try {
            int screenWidth = AndroidUtilities.isTablet()
                    ? AndroidUtilities.getMinTabletSide()
                    : AndroidUtilities.displaySize.x;
            return Math.max(screenWidth - AndroidUtilities.dp(140), 200);
        } catch (Exception e) {
            return 600;
        }
    }

    private static List<int[]> getCodeRanges(Spanned spanned) {
        List<int[]> ranges = new ArrayList<>();
        try {
            CodeHighlighting.Span[] codeSpans = spanned.getSpans(0, spanned.length(), CodeHighlighting.Span.class);
            if (codeSpans != null) {
                for (CodeHighlighting.Span s : codeSpans) {
                    ranges.add(new int[]{spanned.getSpanStart(s), spanned.getSpanEnd(s)});
                }
            }
        } catch (Exception ignored) {}
        try {
            URLSpanMono[] monoSpans = spanned.getSpans(0, spanned.length(), URLSpanMono.class);
            if (monoSpans != null) {
                for (URLSpanMono s : monoSpans) {
                    ranges.add(new int[]{spanned.getSpanStart(s), spanned.getSpanEnd(s)});
                }
            }
        } catch (Exception ignored) {}
        try {
            TextStyleSpan[] styleSpans = spanned.getSpans(0, spanned.length(), TextStyleSpan.class);
            if (styleSpans != null) {
                for (TextStyleSpan s : styleSpans) {
                    if (s.getTextStyleRun() != null && (s.getTextStyleRun().flags & TextStyleSpan.FLAG_STYLE_MONO) != 0) {
                        ranges.add(new int[]{spanned.getSpanStart(s), spanned.getSpanEnd(s)});
                    }
                }
            }
        } catch (Exception ignored) {}
        return ranges;
    }

    private static boolean overlapsCode(int start, int end, List<int[]> codeRanges) {
        for (int[] r : codeRanges) {
            if (start < r[1] && end > r[0]) return true;
        }
        return false;
    }

    private static List<LatexMatch> findMatches(String text, List<int[]> codeRanges) {
        List<LatexMatch> matches = new ArrayList<>();
        collectMatches(matches, text, DISPLAY_MATH, true, codeRanges);
        collectMatches(matches, text, INLINE_MATH, false, codeRanges);
        if (matches.isEmpty()) return matches;

        Collections.sort(matches, (a, b) -> Integer.compare(a.start, b.start));
        List<LatexMatch> filtered = new ArrayList<>();
        int lastEnd = -1;
        for (LatexMatch match : matches) {
            if (match.start >= lastEnd) {
                filtered.add(match);
                lastEnd = match.end;
            }
            if (filtered.size() >= MAX_FORMULAS) break;
        }
        return filtered;
    }

    private static boolean isLikelyMath(String formula, boolean display) {
        if (!hasBalancedBraces(formula)) return false;
        if (display) return true;
        if (PURE_NUMBER.matcher(formula).matches()) return false;

        String lower = formula.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("://") || lower.contains("www.")) return false;
        if (LATEX_COMMAND.matcher(formula).find()) return true;

        Matcher words = WORD.matcher(formula);
        int wordCount = 0;
        int singleLetterCount = 0;
        boolean hasKnownMathWord = false;
        while (words.find()) {
            String word = words.group();
            int length = word.codePointCount(0, word.length());
            wordCount++;
            if (length == 1) {
                singleLetterCount++;
            } else if (KNOWN_MATH_WORD.matcher(word).matches()) {
                hasKnownMathWord = true;
            } else {
                
                if (length > 2 || !isAsciiLetters(word)) return false;
            }
        }

        boolean hasOperator = MATH_OPERATOR.matcher(formula).find();
        boolean hasMathSymbol = MATH_SYMBOL.matcher(formula).find();
        boolean hasDigit = false;
        for (int i = 0; i < formula.length(); i++) {
            if (Character.isDigit(formula.charAt(i))) {
                hasDigit = true;
                break;
            }
        }
        boolean allSingleLetterVariables = wordCount > 0 && wordCount == singleLetterCount;
        boolean hasCompactNumberVariable = COMPACT_NUMBER_VARIABLE.matcher(formula).find();
        if (!hasMathSymbol && wordCount == 0 && !hasDigit) return false;
        return hasOperator || hasMathSymbol || hasKnownMathWord
                || allSingleLetterVariables || hasCompactNumberVariable;
    }

    private static boolean isAsciiLetters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 0x7f || !Character.isLetter(c)) return false;
        }
        return true;
    }

    private static boolean hasBalancedBraces(String formula) {
        int depth = 0;
        boolean escaped = false;
        for (int i = 0; i < formula.length(); i++) {
            char c = formula.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth < 0) {
                return false;
            }
        }
        return depth == 0;
    }

    private static void collectMatches(List<LatexMatch> out, String text, Pattern pattern,
                                       boolean display, List<int[]> codeRanges) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            if (overlapsCode(m.start(), m.end(), codeRanges)) continue;
            String formula = m.group(1);
            if (formula == null || formula.trim().isEmpty()) continue;
            String trimmed = formula.trim();
            if (trimmed.length() > MAX_FORMULA_LENGTH) continue;
            if (!isLikelyMath(trimmed, display)) continue;
            out.add(new LatexMatch(m.start(), m.end(), trimmed, display));
        }
    }

    private static class LatexMatch {
        final int start, end;
        final String formula;
        final boolean display;

        LatexMatch(int start, int end, String formula, boolean display) {
            this.start = start;
            this.end = end;
            this.formula = formula;
            this.display = display;
        }
    }

    public static class LatexSpan extends ReplacementSpan {
        private final Drawable drawable;
        private int lastColor = 0;
        private final boolean display;
        private final boolean preview;
        private final float sourceTextSize;
        private final int sourceMaxWidth;

        public LatexSpan(Drawable drawable, boolean display, boolean preview, float sourceTextSize, int sourceMaxWidth) {
            this.drawable = drawable;
            this.display = display;
            this.preview = preview;
            this.sourceTextSize = sourceTextSize;
            this.sourceMaxWidth = sourceMaxWidth;
        }

        boolean matches(float textSize, int maxWidth, boolean previewMode) {
            return Math.abs(sourceTextSize - textSize) < 0.5f
                    && sourceMaxWidth == maxWidth && preview == previewMode;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (drawable == null) return (int) paint.measureText(text, start, end);
            Rect bounds = drawable.getBounds();
            if (fm != null) {
                int imgHeight = bounds.height();
                int txtHeight = fm.descent - fm.ascent;
                if (!preview && (display || imgHeight > txtHeight)) {
                    int half = (imgHeight - txtHeight) / 2;
                    fm.ascent -= half;
                    fm.top -= half;
                    fm.descent += half;
                    fm.bottom += half;
                }
            }
            if (preview) {
                int imgH = bounds.height();
                int txtH = paint.getFontMetricsInt().descent - paint.getFontMetricsInt().ascent;
                if (imgH > txtH && imgH > 0) {
                    return (int) (bounds.width() * ((float) txtH / imgH));
                }
            }
            return bounds.width();
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, Paint paint) {
            if (drawable == null) {
                canvas.drawText(text, start, end, x, y, paint);
                return;
            }
            int color = paint.getColor();
            if (color != lastColor) {
                lastColor = color;
                drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            }
            canvas.save();
            int imgHeight = drawable.getBounds().height();
            int lineHeight = bottom - top;
            if (preview && imgHeight > lineHeight && lineHeight > 0) {
                float scale = (float) lineHeight / imgHeight;
                canvas.translate(x, top);
                canvas.scale(scale, scale);
            } else {
                int transY = top + (lineHeight - imgHeight) / 2;
                canvas.translate(x, transY);
            }
            drawable.draw(canvas);
            canvas.restore();
        }
    }
}
