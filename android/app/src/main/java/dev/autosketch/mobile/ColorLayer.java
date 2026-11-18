package dev.autosketch.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ColorLayer {
    public final int color;
    public final String name;
    public final List<float[]> strokes;

    public ColorLayer(int color, String name, List<float[]> strokes) {
        this.color = color;
        this.name = name;
        this.strokes = Collections.unmodifiableList(new ArrayList<>(strokes));
    }
}
