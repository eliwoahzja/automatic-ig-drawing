package dev.autosketch.mobile;

import static org.junit.Assert.*;
import java.util.ArrayList;
import org.junit.Test;

public class AiSceneTest {
    @Test public void regionsStayInsideUnitSquare() {
        AiScene.Region region = new AiScene.Region(-.3f, .85f, .5f, .8f, "hello");
        assertEquals(0f, region.x, .00001f);
        assertEquals(.85f, region.y, .00001f);
        assertTrue(region.x + region.width <= 1.0001f);
        assertTrue(region.y + region.height <= 1.0001f);
    }

    @Test public void wordsKeepDetectedOrder() {
        ArrayList<AiScene.Region> words = new ArrayList<>();
        words.add(new AiScene.Region(.1f, .1f, .2f, .1f, "Goodnight"));
        words.add(new AiScene.Region(.1f, .3f, .2f, .1f, "Sleepwell"));
        AiScene scene = new AiScene(new ArrayList<>(), words, "");
        assertEquals("Goodnight\nSleepwell", scene.recognizedWords());
    }
}
