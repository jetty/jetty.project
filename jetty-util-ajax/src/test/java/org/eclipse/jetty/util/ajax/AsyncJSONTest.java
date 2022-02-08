//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.ajax;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AsyncJSONTest
{
    private static AsyncJSON newAsyncJSON()
    {
        AsyncJSON.Factory factory = new AsyncJSON.Factory();
        factory.setDetailedParseException(true);
        return factory.newAsyncJSON();
    }

    @ParameterizedTest
    @ValueSource(strings = {"|", "}", "]", "{]", "[}", "+", ".", "{} []"})
    public void testParseInvalidJSON(String json)
    {
        byte[] bytes = json.getBytes(UTF_8);
        AsyncJSON parser = newAsyncJSON();

        // Parse the whole input.
        assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes));
        assertTrue(parser.isEmpty());

        // Parse byte by byte.
        assertThrows(IllegalArgumentException.class, () ->
        {
            for (byte b : bytes)
            {
                parser.parse(new byte[]{b});
            }
        });
        assertTrue(parser.isEmpty());
    }

    @ParameterizedTest(name = "[{index}] ''{0}'' -> ''{1}''")
    @MethodSource("validStrings")
    public void testParseString(String string, String expected)
    {
        String json = "\"${value}\"".replace("${value}", string);
        byte[] bytes = json.getBytes(UTF_8);
        AsyncJSON parser = newAsyncJSON();

        // Parse the whole input.
        assertTrue(parser.parse(bytes));
        assertEquals(expected, parser.complete());
        assertTrue(parser.isEmpty());

        // Parse byte by byte.
        for (int i = 0; i < bytes.length; ++i)
        {
            byte b = bytes[i];
            if (i == bytes.length - 1)
                assertTrue(parser.parse(new byte[]{b}));
            else
                assertFalse(parser.parse(new byte[]{b}));
        }
        assertEquals(expected, parser.complete());
        assertTrue(parser.isEmpty());
    }

    public static List<Object[]> validStrings()
    {
        List<Object[]> result = new ArrayList<>();
        result.add(new Object[]{"", ""});
        result.add(new Object[]{" \t\r\n", " \t\r\n"});
        result.add(new Object[]{"\u20AC", "\u20AC"}); // euro symbol
        result.add(new Object[]{"\\u20AC", "\u20AC"}); // euro symbol
        result.add(new Object[]{"/foo", "/foo"});
        result.add(new Object[]{"123E+01", "123E+01"});
        result.add(new Object[]{"A\\u20AC/foo\\t\\n", "A\u20AC/foo\t\n"});  // euro symbol
        result.add(new Object[]{" ABC ", " ABC "});
        return result;
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\u", "\\u0", "\\x"})
    public void testParseInvalidString(String value)
    {
        String json = "\"${value}\"".replace("${value}", value);
        byte[] bytes = json.getBytes(UTF_8);
        AsyncJSON parser = newAsyncJSON();

        // Parse the whole input.
        assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes));
        assertTrue(parser.isEmpty());

        // Parse byte by byte.
        assertThrows(IllegalArgumentException.class, () ->
        {
            for (byte b : bytes)
            {
                parser.parse(new byte[]{b});
            }
        });
        assertTrue(parser.isEmpty());
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @MethodSource("validArrays")
    public void testParseArray(String json, List<?> expected)
    {
        byte[] bytes = json.getBytes(UTF_8);
        AsyncJSON parser = newAsyncJSON();

        // Parse the whole input.
        assertTrue(parser.parse(bytes));
        assertEquals(expected, parser.complete());
        assertTrue(parser.isEmpty());
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        assertTrue(parser.parse(buffer));
        assertFalse(buffer.hasRemaining());
        assertEquals(expected, parser.complete());
        assertTrue(parser.isEmpty());

        // Parse byte by byte.
        for (byte b : bytes)
        {
            parser.parse(new byte[]{b});
        }
        assertEquals(expected, parser.complete());
        assertTrue(parser.isEmpty());
    }

    public static List<Object[]> validArrays()
    {
        List<Object[]> result = new ArrayList<>();

        List<Object> expected = Collections.emptyList();
        result.add(new Object[]{"[]", expected});
        result.add(new Object[]{"[] \n", expected});

        expected = new ArrayList<>();
        expected.add(Collections.emptyList());
        result.add(new Object[]{"[[]]", expected});

        expected = new ArrayList<>();
        expected.add("first");
        expected.add(5D);
        expected.add(null);
        expected.add(true);
        expected.add(false);
        expected.add(new HashMap<>());
        HashMap<String, Object> last = new HashMap<>();
        last.put("a", new ArrayList<>());
        expected.add(last);
        result.add(new Object[]{"[\"first\", 5E+0, null, true, false, {}, {\"a\":[]}]", expected});

        return result;
    }

    @ParameterizedTest
    @ValueSource(strings = {"[", "]", "[[,]", " [ 1,2,[ "})
    public void testParseInvalidArray(String json)
    {
        byte[] bytes = json.getBytes(UTF_8);
        AsyncJSON parser = newAsyncJSON();

        // Parse the whole input.
        assertThrows(IllegalArgumentException.class, () ->
        {
            parser.parse(bytes);
            parser.complete();
        });
        assertTrue(parser.isEmpty());

        // Parse byte by byte.
        assertThrows(IllegalArgumentException.class, () ->
        {
            for (byte b : bytes)
            {
                parser.parse(new byte[]{b});
            }
            parser.complete();
        });
        assertTrue(parser.isEmpty());
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @MethodSource("validObjects")
    public void testParseObject(String json, Object expected)
    {
        byte[] bytes = json.getBytes(UTF_8);
        AsyncJSON parser = newAsyncJSON();

        // Parse the whole input.
        assertTrue(parser.parse(bytes));
        assertEquals(expected, parser.complete());
        assertTrue(parser.isEmpty());

        // Parse byte by byte.
        for (int i = 0; i < bytes.length; ++i)
        {
            byte b = bytes[i];
            if (i == bytes.length - 1)
            {
                assertTrue(parser.parse(new byte[]{b}));
            }
            else
            {
                assertFalse(parser.parse(new byte[]{b}));
            }
        }
        assertEquals(expected, parser.complete());
        assertTrue(parser.isEmpty());
    }

    public static List<Object[]> validObjects()
    {
        List<Object[]> result = new ArrayList<>();

        HashMap<String, Object> expected = new HashMap<>();
        result.add(new Object[]{"{}", expected});

        expected = new HashMap<>();
        expected.put("", 0L);
        result.add(new Object[]{"{\"\":0}", expected});

        expected = new HashMap<>();
        expected.put("name", "value");
        result.add(new Object[]{"{ \"name\": \"value\" }", expected});

        expected = new HashMap<>();
        expected.put("name", null);
        expected.put("valid", true);
        expected.put("secure", false);
        expected.put("value", 42L);
        result.add(new Object[]{
            "{, \"name\": null, \"valid\": true\n , \"secure\": false\r\n,\n \"value\":42, }", expected
        });

        return result;
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "}", "{{,}", "{:\"s\"}", "{[]:0}", "{1:0}", " {\": 0} ", "{\"a: \"b\"}"})
    public void testParseInvalidObject(String json)
    {
        byte[] bytes = json.getBytes(UTF_8);
        AsyncJSON parser = newAsyncJSON();

        // Parse the whole input.
        assertThrows(IllegalArgumentException.class, () ->
        {
            parser.parse(bytes);
            parser.complete();
        });
        assertTrue(parser.isEmpty());

        // Parse byte by byte.
        assertThrows(IllegalArgumentException.class, () ->
        {
            for (byte b : bytes)
            {
                parser.parse(new byte[]{b});
            }
            parser.complete();
        });
        assertTrue(parser.isEmpty());
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @MethodSource("validNumbers")
    public void testParseNumber(String json, Number expected)
    {
        byte[] bytes = json.getBytes(UTF_8);
        AsyncJSON parser = newAsyncJSON();

        // Parse the whole input.
        parser.parse(bytes);
        assertEquals(expected, parser.complete());
        assertTrue(parser.isEmpty());
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        parser.parse(buffer);
        assertEquals(expected, parser.complete());
        assertFalse(buffer.hasRemaining());
        assertTrue(parser.isEmpty());

        // Parse byte by byte.
        for (byte b : bytes)
        {
            parser.parse(new byte[]{b});
        }
        assertEquals(expected, parser.complete());
        assertTrue(parser.isEmpty());
    }

    public static List<Object[]> validNumbers()
    {
        List<Object[]> result = new ArrayList<>();

        result.add(new Object[]{"0", 0L});
        result.add(new Object[]{"-0", -0L});
        result.add(new Object[]{"13\n", 13L});
        result.add(new Object[]{"-42", -42L});
        result.add(new Object[]{"123.456", 123.456D});
        result.add(new Object[]{"-234.567", -234.567D});
        result.add(new Object[]{"9e0", 9D});
        result.add(new Object[]{"8E+1\t", 80D});
        result.add(new Object[]{"-7E-2 ", -0.07D});
        result.add(new Object[]{"70.5E-1", 7.05D});

        return result;
    }

    @ParameterizedTest
    @ValueSource(strings = {"--", "--1", ".5", "e0", "1a1", "3-7", "1+2", "1e0e1", "1.2.3"})
    public void testParseInvalidNumber(String json)
    {
        byte[] bytes = json.getBytes(UTF_8);
        AsyncJSON parser = newAsyncJSON();

        // Parse the whole input.
        assertThrows(IllegalArgumentException.class, () ->
        {
            parser.parse(bytes);
            parser.complete();
        });
        assertTrue(parser.isEmpty());

        // Parse byte by byte.
        assertThrows(IllegalArgumentException.class, () ->
        {
            for (byte b : bytes)
            {
                parser.parse(new byte[]{b});
            }
            parser.complete();
        });
        assertTrue(parser.isEmpty());
    }

    @Test
    public void testParseObjectWithConvertor()
    {
        AsyncJSON.Factory factory = new AsyncJSON.Factory();
        CustomConvertor convertor = new CustomConvertor();
        factory.putConvertor(CustomConvertor.class.getName(), convertor);

        String json = "{" +
            "\"f1\": {\"class\":\"" + CustomConvertible.class.getName() + "\", \"field\": \"value\"}," +
            "\"f2\": {\"class\":\"" + CustomConvertor.class.getName() + "\"}" +
            "}";

        AsyncJSON parser = factory.newAsyncJSON();
        assertTrue(parser.parse(UTF_8.encode(json)));
        Map<String, Object> result = parser.complete();

        Object value1 = result.get("f1");
        assertTrue(value1 instanceof CustomConvertible);
        assertEquals("value", ((CustomConvertible)value1).field);
        Object value2 = result.get("f2");
        assertTrue(value2 instanceof CustomConvertor.Custom);

        assertSame(convertor, factory.removeConvertor(CustomConvertor.class.getName()));
        assertTrue(parser.parse(UTF_8.encode(json)));
        result = parser.complete();

        value1 = result.get("f1");
        assertTrue(value1 instanceof CustomConvertible);
        assertEquals("value", ((CustomConvertible)value1).field);
        value2 = result.get("f2");
        assertTrue(value2 instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map2 = (Map<String, Object>)value2;
        assertEquals(CustomConvertor.class.getName(), map2.get("class"));
    }

    public static class CustomConvertible implements JSON.Convertible
    {
        private Object field;

        @Override
        public void toJSON(JSON.Output out)
        {
        }

        @Override
        public void fromJSON(Map<String, Object> map)
        {
            this.field = map.get("field");
        }
    }

    public static class CustomConvertor implements JSON.Convertor
    {
        @Override
        public void toJSON(Object obj, JSON.Output out)
        {
        }

        @Override
        public Object fromJSON(Map<String, Object> map)
        {
            return new Custom();
        }

        public static class Custom
        {
        }
    }

    @Test
    public void testContext()
    {
        AsyncJSON.Factory factory = new AsyncJSON.Factory()
        {
            @Override
            public AsyncJSON newAsyncJSON()
            {
                return new AsyncJSON(this)
                {
                    @Override
                    protected Map<String, Object> newObject(Context context)
                    {
                        if (context.depth() == 1)
                        {
                            return new CustomMap();
                        }
                        return super.newObject(context);
                    }
                };
            }
        };
        AsyncJSON parser = factory.newAsyncJSON();

        String json = "[{" +
            "\"channel\": \"/meta/handshake\"," +
            "\"version\": \"1.0\"," +
            "\"supportedConnectionTypes\": [\"long-polling\"]," +
            "\"advice\": {\"timeout\": 0}" +
            "}]";

        assertTrue(parser.parse(UTF_8.encode(json)));
        List<CustomMap> messages = parser.complete();

        for (CustomMap message : messages)
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> advice = (Map<String, Object>)message.get("advice");
            assertFalse(advice instanceof CustomMap);
        }
    }

    public static class CustomMap extends HashMap<String, Object>
    {
    }

    @Test
    public void testCaching()
    {
        AsyncJSON.Factory factory = new AsyncJSON.Factory();
        String foo = "foo";
        factory.cache(foo);
        AsyncJSON parser = factory.newAsyncJSON();

        String json = "{\"foo\": [\"foo\", \"foo\"]}";
        parser.parse(UTF_8.encode(json));
        Map<String, Object> object = parser.complete();

        Map.Entry<String, Object> entry = object.entrySet().iterator().next();
        assertSame(foo, entry.getKey());
        @SuppressWarnings("unchecked")
        List<String> array = (List<String>)entry.getValue();
        for (String item : array)
        {
            assertSame(foo, item);
        }
    }

    @Test
    public void testEncodedCaching()
    {
        AsyncJSON.Factory factory = new AsyncJSON.Factory();
        assertFalse(factory.cache("yèck"));
        String foo = "foo\\yuck";
        assertTrue(factory.cache(foo));
        AsyncJSON parser = factory.newAsyncJSON();

        String json = "{\"foo\\\\yuck\": [\"foo\\\\yuck\", \"foo\\\\yuck\"]}";
        parser.parse(UTF_8.encode(json));
        Map<String, Object> object = parser.complete();

        Map.Entry<String, Object> entry = object.entrySet().iterator().next();
        assertSame(foo, entry.getKey());
        @SuppressWarnings("unchecked")
        List<String> array = (List<String>)entry.getValue();
        for (String item : array)
        {
            assertSame(foo, item);
        }
    }

    @Test
    public void testArrayConverter()
    {
        // Test root arrays.
        testArrayConverter("[1]", Function.identity());

        // Test non-root arrays.
        testArrayConverter("{\"array\": [1]}", object ->
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>)object;
            return map.get("array");
        });
    }

    private void testArrayConverter(String json, Function<Object, Object> extractor)
    {
        AsyncJSON.Factory factory = new AsyncJSON.Factory();
        AsyncJSON async = factory.newAsyncJSON();
        JSON sync = new JSON();

        async.parse(UTF_8.encode(json));
        Object result = extractor.apply(async.complete());
        // AsyncJSON historically defaults to list.
        assertThat(result, Matchers.instanceOf(List.class));
        // JSON historically defaults to array.
        result = extractor.apply(sync.parse(new JSON.StringSource(json)));
        assertNotNull(result);
        assertTrue(result.getClass().isArray(), json + " -> " + result);

        // Configure AsyncJSON to return arrays.
        factory.setArrayConverter(List::toArray);
        async.parse(UTF_8.encode(json));
        result = extractor.apply(async.complete());
        assertNotNull(result);
        assertTrue(result.getClass().isArray(), json + " -> " + result);

        // Configure JSON to return lists.
        sync.setArrayConverter(list -> list);
        result = extractor.apply(sync.parse(new JSON.StringSource(json)));
        assertThat(result, Matchers.instanceOf(List.class));
    }
}
