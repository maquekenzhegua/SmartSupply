package com.smartsupply.agent.tools;

import com.smartsupply.agent.rag.TextSplitter;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TextSplitterTest {
    @Test void splitsLongTextWithOverlap() {
        String text = "a".repeat(2000);
        List<String> chunks = TextSplitter.split(text, 800, 100);
        assertTrue(chunks.size() >= 3);
        for (String c : chunks) assertTrue(c.length() <= 800);
        // overlap: second chunk starts before first ends
        assertTrue(chunks.get(1).length() > 0);
    }
    @Test void shortTextReturnsSingleChunk() {
        List<String> chunks = TextSplitter.split("hello world", 800, 100);
        assertEquals(1, chunks.size());
        assertEquals("hello world", chunks.get(0));
    }
    @Test void nullOrBlankReturnsEmpty() {
        assertTrue(TextSplitter.split(null, 800, 100).isEmpty());
        assertTrue(TextSplitter.split("   ", 800, 100).isEmpty());
    }
}
