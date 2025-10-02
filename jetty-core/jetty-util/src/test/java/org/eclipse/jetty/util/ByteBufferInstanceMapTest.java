package org.eclipse.jetty.util;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteBufferInstanceMapTest
{
    @Test
    void testPutGetArrayIdentity()
    {
        ByteBufferInstanceMap<String> map = new ByteBufferInstanceMap<>();

        byte[] a1 = new byte[0];
        byte[] a2 = new byte[0];

        ByteBuffer k1 = ByteBuffer.wrap(a1);
        ByteBuffer k1b = ByteBuffer.wrap(a1); // same backing array
        ByteBuffer k2 = ByteBuffer.wrap(a2);  // different backing array

        assertNull(map.put(k1, "one"));
        assertEquals(1, map.size());

        // same array → overwrite
        assertEquals("one", map.put(k1b, "uno"));
        assertEquals(1, map.size());
        assertEquals("uno", map.get(k1));
        assertEquals("uno", map.get(k1b));

        // different array → new entry
        assertNull(map.put(k2, "two"));
        assertEquals(2, map.size());
        assertEquals("two", map.get(k2));
    }

    @Test
    void testConveniencePutReturnsKey()
    {
        ByteBufferInstanceMap<Integer> map = new ByteBufferInstanceMap<>();

        ByteBuffer k1 = map.put(42);
        assertNotNull(k1);
        assertTrue(k1.hasArray());
        assertEquals(1, map.size());
        assertEquals(42, map.get(k1));

        ByteBuffer k2 = map.put(7);
        assertNotSame(k1, k2); // different array instance each time
        assertEquals(2, map.size());
        assertEquals(7, map.get(k2));
    }

    @Test
    void testPositionLimitIgnored()
    {
        ByteBufferInstanceMap<String> map = new ByteBufferInstanceMap<>();

        byte[] arr = new byte[16];
        ByteBuffer base = ByteBuffer.wrap(arr);

        // Store with one view
        base.position(5).limit(10);
        map.put(base, "value");

        // Lookup with different views on the same array
        ByteBuffer view1 = ByteBuffer.wrap(arr).position(0).limit(16).slice(); // still backed by same arr
        // NOTE: slice() creates a new buffer whose hasArray() is true and array() is the same arr
        assertEquals("value", map.get(view1));

        ByteBuffer view2 = ByteBuffer.wrap(arr);
        assertTrue(map.containsKey(view2));
        assertEquals("value", map.remove(view2));
        assertFalse(map.containsKey(view2));
        assertEquals(0, map.size());
    }

    @Test
    void testDirectAndReadOnlyBehavior()
    {
        ByteBufferInstanceMap<String> map = new ByteBufferInstanceMap<>();

        // direct buffer
        ByteBuffer direct = ByteBuffer.allocateDirect(0);
        assertThrows(IllegalArgumentException.class, () -> map.put(direct, "x"));
        assertNull(map.get(direct));
        assertNull(map.remove(direct));
        assertFalse(map.containsKey(direct));

        // read-only heap buffer
        ByteBuffer heap = ByteBuffer.wrap(new byte[0]).asReadOnlyBuffer();
        assertThrows(IllegalArgumentException.class, () -> map.put(heap, "x"));
        assertNull(map.get(heap));
        assertNull(map.remove(heap));
        assertFalse(map.containsKey(heap));
    }

    @Test
    void testViewsAndIteratorRemove()
    {
        ByteBufferInstanceMap<String> map = new ByteBufferInstanceMap<>();
        ByteBuffer k1 = map.put("a");
        ByteBuffer k2 = map.put("b");
        ByteBuffer k3 = map.put("c");

        assertEquals(3, map.size());
        assertEquals(3, map.keySet().size());
        assertEquals(3, map.values().size());
        assertEquals(3, map.entrySet().size());

        // values iterator remove
        Iterator<String> vi = map.values().iterator();
        assertTrue(vi.hasNext());
        assertNotNull(vi.next());
        vi.remove(); // removes one entry
        assertEquals(2, map.size());
        assertEquals(2, map.keySet().size());
        assertEquals(2, map.values().size());
        assertEquals(2, map.entrySet().size());

        // keySet iterator remove
        Iterator<ByteBuffer> ki = map.keySet().iterator();
        assertTrue(ki.hasNext());
        ByteBuffer removedKey = ki.next();
        ki.remove();
        assertEquals(1, map.size());
        assertFalse(map.containsKey(removedKey));

        // entrySet iterator remove
        Iterator<Map.Entry<ByteBuffer, String>> ei = map.entrySet().iterator();
        assertTrue(ei.hasNext());
        Map.Entry<ByteBuffer, String> e = ei.next();
        ei.remove();
        assertEquals(0, map.size());
        assertTrue(map.isEmpty());
    }

    @Test
    void testEntrySetKeysAreOriginalInstances()
    {
        ByteBufferInstanceMap<String> map = new ByteBufferInstanceMap<>();
        ByteBuffer k = ByteBuffer.wrap(new byte[0]);
        map.put(k, "v");

        Map.Entry<ByteBuffer, String> entry = map.entrySet().iterator().next();
        assertSame(k, entry.getKey());
        assertEquals("v", entry.getValue());

        // setValue updates map
        assertEquals("v", entry.setValue("v2"));
        assertEquals("v2", map.get(k));
    }

    @Test
    void testPutAll()
    {
        ByteBufferInstanceMap<String> m1 = new ByteBufferInstanceMap<>();
        ByteBufferInstanceMap<String> m2 = new ByteBufferInstanceMap<>();

        byte[] a = new byte[0];
        byte[] b = new byte[0];
        m1.put(ByteBuffer.wrap(a), "A1");
        m1.put(ByteBuffer.wrap(b), "B1");

        // Same arrays, different ByteBuffer instances in m2
        m2.put(ByteBuffer.wrap(a), "A2");
        m2.put(ByteBuffer.wrap(b), "B2");

        // Put all from m2 into m1 → overwrite by array identity
        m1.putAll(m2);
        assertEquals(2, m1.size());
        assertEquals("A2", m1.get(ByteBuffer.wrap(a)));
        assertEquals("B2", m1.get(ByteBuffer.wrap(b)));
    }

    @Test
    void testClear()
    {
        ByteBufferInstanceMap<Integer> map = new ByteBufferInstanceMap<>();
        map.put(1);
        map.put(2);
        assertFalse(map.isEmpty());
        map.clear();
        assertTrue(map.isEmpty());
        assertEquals(0, map.size());
    }
}

