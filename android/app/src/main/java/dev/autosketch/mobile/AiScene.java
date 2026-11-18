package dev.autosketch.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AiScene {
    public static final class Region {
        public final float x, y, width, height;
        public final String text;
        public Region(float x, float y, float width, float height, String text) {
            this.x = clamp(x);
            this.y = clamp(y);
            this.width = Math.max(0f, Math.min(1f - this.x, width));
            this.height = Math.max(0f, Math.min(1f - this.y, height));
            this.text = text == null ? "" : text.trim();
        }
        public float centerX() { return x + width * .5f; }
        public float centerY() { return y + height * .5f; }
    }

    public final List<Region> objects;
    public final List<Region> lettering;
    public final String notice;

    public AiScene(List<Region> objects, List<Region> lettering, String notice) {
        this.objects = Collections.unmodifiableList(new ArrayList<>(objects));
        this.lettering = Collections.unmodifiableList(new ArrayList<>(lettering));
        this.notice = notice == null ? "" : notice;
    }

    public static AiScene empty() {
        return new AiScene(new ArrayList<>(), new ArrayList<>(), "Nothing recognized");
    }

    public String recognizedWords() {
        StringBuilder out = new StringBuilder();
        for (Region item : lettering) {
            if (item.text.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(item.text);
        }
        return out.toString();
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
