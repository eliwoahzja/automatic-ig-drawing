package dev.autosketch.mobile;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class GeminiPlanTest {

    private GeminiPlan parse(String json, int existing) throws Exception {
        return GeminiPlan.parse(new JSONObject(json), existing, 30, 40);
    }

    @Test public void convertsYxThousandGridIntoUnitXy() throws Exception {
        GeminiPlan plan = parse("{\"summary\":\"duck\",\"texts\":[],\"strokes\":"
                + "[[[100,200],[300,400],[500,600]]]}", 0);
        assertEquals(1, plan.strokes.size());
        float[] s = plan.strokes.get(0);
        assertEquals(.2f, s[0], 1e-5f);   // x from the second number
        assertEquals(.1f, s[1], 1e-5f);   // y from the first number
        assertEquals(.6f, s[4], 1e-5f);
    }

    @Test public void penLiftsSplitStrokes() throws Exception {
        GeminiPlan plan = parse("{\"summary\":\"\",\"texts\":[],\"strokes\":"
                + "[[[100,100],[120,120],[900,900],[920,920]]]}", 0);
        assertEquals(2, plan.strokes.size());
    }

    @Test public void readsLetteringBoxes() throws Exception {
        GeminiPlan plan = parse("{\"summary\":\"\",\"strokes\":[],\"texts\":"
                + "[{\"text\":\"Sleepwell!\",\"box\":[400,500,500,900]}]}", 0);
        assertEquals(1, plan.lettering.size());
        AiScene.Region r = plan.lettering.get(0);
        assertEquals("Sleepwell!", r.text);
        assertEquals(.5f, r.x, 1e-5f);
        assertEquals(.4f, r.y, 1e-5f);
        assertEquals(.4f, r.width, 1e-5f);
        assertEquals(.1f, r.height, 1e-5f);
    }

    @Test public void rejectsOutOfRangeAndMassRemovals() throws Exception {
        GeminiPlan fine = parse("{\"summary\":\"\",\"texts\":[],\"strokes\":[],\"remove\":[1,2,99,-4]}", 10);
        assertEquals(2, fine.remove.size());
        GeminiPlan wipe = parse("{\"summary\":\"\",\"texts\":[],\"strokes\":[],"
                + "\"remove\":[0,1,2,3,4,5,6,7]}", 10);
        assertTrue(wipe.remove.isEmpty());
    }

    @Test public void ignoresTinyOrBlankLettering() throws Exception {
        GeminiPlan plan = parse("{\"summary\":\"\",\"strokes\":[],\"texts\":["
                + "{\"text\":\" \",\"box\":[0,0,100,100]},"
                + "{\"text\":\"ok\",\"box\":[0,0,5,5]}]}", 0);
        assertTrue(plan.lettering.isEmpty());
    }
    @Test public void rejectsVerticesOutsideTheImageInsteadOfClamping() throws Exception {
        GeminiPlan plan = parse("{\"summary\":\"\",\"texts\":[],\"strokes\":"
                + "[[[100,100],[200,200],[1200,900],[300,300]]]}", 0);
        assertEquals(1, plan.strokes.size());
        assertEquals(6, plan.strokes.get(0).length);
        for (float value : plan.strokes.get(0)) {
            assertTrue(value >= 0f && value <= 1f);
        }
    }

}
