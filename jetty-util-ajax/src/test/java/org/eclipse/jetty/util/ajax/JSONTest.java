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

import java.io.IOException;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.eclipse.jetty.util.DateCache;
import org.eclipse.jetty.util.ajax.JSON.Output;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JSONTest
{
    // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck

    private JSON json;
    private String test = "\n\n\n\t\t    " +
        "// ignore this ,a [ \" \n" +
        "/* and this \n" +
        "/* and * // this \n" +
        "*/" +
        "{ " +
        "\"onehundred\" : 100  ," +
        "\"small\":-0.2," +
        "\"name\" : \"fred\"  ," +
        "\"empty\" : {}  ," +
        "\"map\" : {\"a\":-1.0e2}  ," +
        "\"array\" : [\"a\",-1.0e2,[],null,true,false]  ," +
        "\"w0\":{\"class\":\"org.eclipse.jetty.util.ajax.JSONTest$Woggle\",\"name\":\"woggle0\",\"nested\":{\"class\":\"org.eclipse.jetty.util.ajax.JSONTest$Woggle\",\"name\":\"woggle1\",\"nested\":null,\"number\":-101},\"number\":100}," +
        "\"NaN\": NaN," +
        "\"undefined\": undefined," +
        "}";

    @BeforeEach
    public void resetJSON()
    {
        json = new JSON();
    }

    @Test
    public void testToString()
    {
        json.addConvertor(Gadget.class, new JSONObjectConvertor(false));
        Map<String, Object> map = new HashMap<>();
        Map<String, Object> obj6 = new HashMap<>();
        Map<String, Object> obj7 = new HashMap<>();

        Woggle w0 = new Woggle();
        Woggle w1 = new Woggle();

        w0.name = "woggle0";
        w0.nested = w1;
        w0.number = 100;
        w1.name = "woggle1";
        w1.nested = null;
        w1.number = -101;

        map.put("n1", null);
        map.put("n2", 2);
        map.put("n3", -0.00000000003);
        map.put("n4", "4\n\r\t\"4");
        map.put("n5", new Object[]{"a", 'b', 3, new String[]{}, null, Boolean.TRUE, Boolean.FALSE});
        map.put("n6", obj6);
        map.put("n7", obj7);
        map.put("n8", new int[]{1, 2, 3, 4});
        map.put("n9", new JSON.Literal("[{},  [],  {}]"));
        map.put("w0", w0);

        obj7.put("x", "value");

        String s = json.toJSON(map);
        assertTrue(s.contains("\"n1\":null"));
        assertTrue(s.contains("\"n2\":2"));
        assertTrue(s.contains("\"n3\":-3.0E-11"));
        assertTrue(s.contains("\"n4\":\"4\\n"));
        assertTrue(s.contains("\"n5\":[\"a\",\"b\","));
        assertTrue(s.contains("\"n6\":{}"));
        assertTrue(s.contains("\"n7\":{\"x\":\"value\"}"));
        assertTrue(s.contains("\"n8\":[1,2,3,4]"));
        assertTrue(s.contains("\"n9\":[{},  [],  {}]"));
        assertTrue(s.contains("\"w0\":{\"class\":\"org.eclipse.jetty.util.ajax.JSONTest$Woggle\",\"name\":\"woggle0\",\"nested\":{\"class\":\"org.eclipse.jetty.util.ajax.JSONTest$Woggle\",\"name\":\"woggle1\",\"nested\":null,\"number\":-101},\"number\":100}"));

        Gadget gadget = new Gadget();
        gadget.setModulated(true);
        gadget.setShields(42);
        gadget.setWoggles(new Woggle[]{w0, w1});

        s = json.toJSON(new Gadget[]{gadget});
        assertThat(s, startsWith("["));
        assertThat(s, containsString("\"modulated\":" + gadget.isModulated()));
        assertThat(s, containsString("\"shields\":" + gadget.getShields()));
        assertThat(s, containsString("\"name\":\"woggle0\""));
        assertThat(s, containsString("\"name\":\"woggle1\""));
    }

    @Test
    public void testParse()
    {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>)json.fromJSON(test);
        assertEquals((long)100, map.get("onehundred"));
        assertEquals("fred", map.get("name"));
        assertEquals(-0.2, map.get("small"));
        assertTrue(map.get("array").getClass().isArray());
        assertTrue(map.get("w0") instanceof Woggle);
        assertTrue(((Woggle)map.get("w0")).nested instanceof Woggle);
        assertEquals(-101, ((Woggle)((Woggle)map.get("w0")).nested).number);
        assertTrue(map.containsKey("NaN"));
        assertNull(map.get("NaN"));
        assertTrue(map.containsKey("undefined"));
        assertNull(map.get("undefined"));
    }

    @Test
    public void testToStringLineFeed()
    {
        Map<String, String> map = new HashMap<>();
        map.put("str", "line\nfeed");
        String jsonStr = json.toJSON(map);
        assertThat(jsonStr, is("{\"str\":\"line\\nfeed\"}"));
    }

    @Test
    public void testToStringTab()
    {
        Map<String, String> map = new HashMap<>();
        map.put("str", "tab\tchar");
        String jsonStr = json.toJSON(map);
        assertThat(jsonStr, is("{\"str\":\"tab\\tchar\"}"));
    }

    @Test
    public void testToStringBel()
    {
        Map<String, String> map = new HashMap<>();
        map.put("str", "ascii\u0007bel");
        String jsonStr = json.toJSON(map);
        assertThat(jsonStr, is("{\"str\":\"ascii\\u0007bel\"}"));
    }

    @Test
    public void testToStringUtf8()
    {
        Map<String, String> map = new HashMap<>();
        map.put("str", "japanese: 桟橋");
        String jsonStr = json.toJSON(map);
        assertThat(jsonStr, is("{\"str\":\"japanese: 桟橋\"}"));
    }

    @Test
    public void testToJsonUtf8Encoded()
    {
        JSON jsonUnicode = new JSON()
        {
            @Override
            protected void escapeUnicode(Appendable buffer, char c) throws IOException
            {
                buffer.append(String.format("\\u%04x", (int)c));
            }
        };

        Map<String, String> map = new HashMap<>();
        map.put("str", "japanese: 桟橋");
        String jsonStr = jsonUnicode.toJSON(map);
        assertThat(jsonStr, is("{\"str\":\"japanese: \\u685f\\u6a4b\"}"));
    }

    @Test
    public void testParseUtf8JsonEncoded()
    {
        String jsonStr = "{\"str\": \"japanese: \\u685f\\u6a4b\"}";
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>)json.fromJSON(jsonStr);
        assertThat(map.get("str"), is("japanese: 桟橋"));
    }

    @Test
    public void testParseUtf8JavaEncoded()
    {
        String jsonStr = "{\"str\": \"japanese: \u685f\u6a4b\"}";
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>)json.fromJSON(jsonStr);
        assertThat(map.get("str"), is("japanese: 桟橋"));
    }

    @Test
    public void testParseUtf8Raw()
    {
        String jsonStr = "{\"str\": \"japanese: 桟橋\"}";
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>)json.fromJSON(jsonStr);
        assertThat(map.get("str"), is("japanese: 桟橋"));
    }

    @Test
    public void testParseReader()
    {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>)json.fromJSON(test);

        assertEquals((long)100, map.get("onehundred"));
        assertEquals("fred", map.get("name"));
        assertTrue(map.get("array").getClass().isArray());
        assertTrue(map.get("w0") instanceof Woggle);
        assertTrue(((Woggle)map.get("w0")).nested instanceof Woggle);
    }

    @Test
    public void testStripComment()
    {
        String test = "\n\n\n\t\t    " +
            "// ignore this ,a [ \" \n" +
            "/* " +
            "{ " +
            "\"onehundred\" : 100  ," +
            "\"name\" : \"fred\"  ," +
            "\"empty\" : {}  ," +
            "\"map\" : {\"a\":-1.0e2}  ," +
            "\"array\" : [\"a\",-1.0e2,[],null,true,false]  ," +
            "} */";

        Object o = json.fromJSON(test);
        assertNull(o);
        o = json.parse(new JSON.StringSource(test), true);
        assertTrue(o instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>)o;
        assertEquals("fred", map.get("name"));
    }

    @Test
    public void testQuote()
    {
        String test = "\"abc123|\\\"|\\\\|\\/|\\b|\\f|\\n|\\r|\\t|\\uaaaa|\"";
        String result = (String)json.fromJSON(test);
        assertEquals("abc123|\"|\\|/|\b|\f|\n|\r|\t|\uaaaa|", result);
    }

    @Test
    public void testBigDecimal()
    {
        Object obj = json.fromJSON("1.0E7");
        assertTrue(obj instanceof Double);
        BigDecimal bd = BigDecimal.valueOf(10000000d);
        String string = json.toJSON(new Object[]{bd});
        obj = Array.get(json.fromJSON(string), 0);
        assertTrue(obj instanceof Double);
    }

    @Test
    public void testZeroByte()
    {
        String withzero = "\u0000";
        assertEquals("\"\\u0000\"", json.toJSON(withzero));
    }

    public static class Gadget
    {
        private boolean modulated;
        private long shields;
        private Woggle[] woggles;

        // Getters and setters required by the POJO convertor.

        /**
         * @return the modulated
         */
        public boolean isModulated()
        {
            return modulated;
        }

        /**
         * @param modulated the modulated to set
         */
        public void setModulated(boolean modulated)
        {
            this.modulated = modulated;
        }

        /**
         * @return the shields
         */
        public long getShields()
        {
            return shields;
        }

        /**
         * @param shields the shields to set
         */
        public void setShields(long shields)
        {
            this.shields = shields;
        }

        /**
         * @return the woggles
         */
        public Woggle[] getWoggles()
        {
            return woggles;
        }

        /**
         * @param woggles the woggles to set
         */
        public void setWoggles(Woggle[] woggles)
        {
            this.woggles = woggles;
        }
    }

    @Test
    public void testConvertor()
    {
        // test case#1 - force timezone to GMT
        JSON json = new JSON();
        json.addConvertor(Date.class, new JSONDateConvertor("MM/dd/yyyy HH:mm:ss zzz", TimeZone.getTimeZone("GMT"), false));
        json.addConvertor(Object.class, new JSONObjectConvertor());

        Woggle w0 = new Woggle();
        Gizmo g0 = new Gizmo();

        w0.setName("woggle0");
        w0.setNested(g0);
        w0.setNumber(100);
        g0.setName("woggle1");
        g0.setNested(null);
        g0.setNumber(-101);
        g0.setTested(true);

        Map<String, Object> map = new HashMap<>();
        Date dummyDate = new Date(1);
        map.put("date", dummyDate);
        map.put("w0", w0);

        StringBuffer buf = new StringBuffer();
        json.append(buf, map);
        String js = buf.toString();

        assertTrue(js.contains("\"date\":\"01/01/1970 00:00:00 GMT\""));
        assertTrue(js.contains("org.eclipse.jetty.util.ajax.JSONTest$Woggle"));
        assertFalse(js.contains("org.eclipse.jetty.util.ajax.JSONTest$Gizmo"));
        assertTrue(js.contains("\"tested\":true"));

        // test case#3
        TimeZone tzone = TimeZone.getTimeZone("JST");
        String format = "EEE MMMMM dd HH:mm:ss zzz yyyy";

        Locale l = new Locale("ja", "JP");
        json.addConvertor(Date.class, new JSONDateConvertor(format, tzone, false, l));
        buf = new StringBuffer();
        json.append(buf, map);
        js = buf.toString();
        assertTrue(js.contains(" 01 09:00:00 JST 1970\""));
        assertTrue(js.contains("org.eclipse.jetty.util.ajax.JSONTest$Woggle"));
        assertFalse(js.contains("org.eclipse.jetty.util.ajax.JSONTest$Gizmo"));
        assertTrue(js.contains("\"tested\":true"));

        // test case#4
        json.addConvertor(Date.class, new JSONDateConvertor(true));
        w0.nested = null;
        buf = new StringBuffer();
        json.append(buf, map);
        js = buf.toString();
        assertFalse(js.contains("\"date\":\"Thu Jan 01 00:00:00 GMT 1970\""));
        assertTrue(js.contains("org.eclipse.jetty.util.ajax.JSONTest$Woggle"));
        assertFalse(js.contains("org.eclipse.jetty.util.ajax.JSONTest$Gizmo"));

        @SuppressWarnings("unchecked")
        Map<String, Object> map1 = (Map<String, Object>)json.fromJSON(js);

        assertTrue(map1.get("date") instanceof Date);
        assertTrue(map1.get("w0") instanceof Woggle);
    }

    @Test
    public void testEnumConvertor()
    {
        JSON json = new JSON();
        Locale l = new Locale("en", "US");
        json.addConvertor(Date.class, new JSONDateConvertor(DateCache.DEFAULT_FORMAT, TimeZone.getTimeZone("GMT"), false, l));
        json.addConvertor(Enum.class, new JSONEnumConvertor(false));
        json.addConvertor(Object.class, new JSONObjectConvertor());

        Woggle w0 = new Woggle();
        Gizmo g0 = new Gizmo();

        w0.setName("woggle0");
        w0.setNested(g0);
        w0.setNumber(100);
        w0.setOther(Color.Blue);
        g0.setName("woggle1");
        g0.setNested(null);
        g0.setNumber(-101);
        g0.setTested(true);
        g0.setOther(Color.Green);

        Map<String, Object> map = new HashMap<>();
        map.put("date", new Date(1));
        map.put("w0", w0);
        map.put("g0", g0);

        StringBuffer buf = new StringBuffer();
        json.append(buf, map);
        String js = buf.toString();

        assertTrue(js.contains("\"date\":\"Thu Jan 01 00:00:00 GMT 1970\""));
        assertTrue(js.contains("org.eclipse.jetty.util.ajax.JSONTest$Woggle"));
        assertFalse(js.contains("org.eclipse.jetty.util.ajax.JSONTest$Gizmo"));
        assertTrue(js.contains("\"tested\":true"));
        assertTrue(js.contains("\"Green\""));
        assertFalse(js.contains("\"Blue\""));

        json.addConvertor(Date.class, new JSONDateConvertor(DateCache.DEFAULT_FORMAT, TimeZone.getTimeZone("GMT"), true, l));
        json.addConvertor(Enum.class, new JSONEnumConvertor(false));
        w0.nested = null;
        buf = new StringBuffer();
        json.append(buf, map);
        js = buf.toString();
        assertFalse(js.contains("\"date\":\"Thu Jan 01 00:00:00 GMT 1970\""));
        assertTrue(js.contains("org.eclipse.jetty.util.ajax.JSONTest$Woggle"));
        assertFalse(js.contains("org.eclipse.jetty.util.ajax.JSONTest$Gizmo"));

        @SuppressWarnings("unchecked")
        Map<String, Object> map2 = (Map<String, Object>)json.fromJSON(js);

        assertTrue(map2.get("date") instanceof Date);
        assertTrue(map2.get("w0") instanceof Woggle);
        assertNull(((Woggle)map2.get("w0")).other);
        @SuppressWarnings("unchecked")
        Map<String, Object> map3 = (Map<String, Object>)map2.get("g0");
        assertEquals(Color.Green.toString(), map3.get("other"));

        json.addConvertor(Date.class, new JSONDateConvertor(DateCache.DEFAULT_FORMAT, TimeZone.getTimeZone("GMT"), true, l));
        json.addConvertor(Enum.class, new JSONEnumConvertor(true));
        buf = new StringBuffer();
        json.append(buf, map);
        js = buf.toString();
        @SuppressWarnings("unchecked")
        Map<String, Object> map4 = (Map<String, Object>)json.fromJSON(js);

        assertTrue(map4.get("date") instanceof Date);
        assertTrue(map4.get("w0") instanceof Woggle);
        assertNull(((Woggle)map4.get("w0")).other);
        @SuppressWarnings("unchecked")
        Map<String, Object> map5 = (Map<String, Object>)map4.get("g0");
        Object o = map5.get("other");
        assertEquals(Color.Green, o);
    }

    public static class Gizmo
    {
        String name;
        Gizmo nested;
        long number;
        boolean tested;
        Object other;

        // Getters and setters required by the POJO convertor.

        public String getName()
        {
            return name;
        }

        public void setName(String name)
        {
            this.name = name;
        }

        public Gizmo getNested()
        {
            return nested;
        }

        public void setNested(Gizmo nested)
        {
            this.nested = nested;
        }

        public long getNumber()
        {
            return number;
        }

        public void setNumber(long number)
        {
            this.number = number;
        }

        public boolean isTested()
        {
            return tested;
        }

        public void setTested(boolean tested)
        {
            this.tested = tested;
        }

        public Object getOther()
        {
            return other;
        }

        public void setOther(Object other)
        {
            this.other = other;
        }
    }

    public static class Woggle extends Gizmo implements JSON.Convertible
    {
        public Woggle()
        {
        }

        @Override
        public void fromJSON(Map<String, Object> object)
        {
            name = (String)object.get("name");
            nested = (Gizmo)object.get("nested");
            number = ((Number)object.get("number")).intValue();
        }

        @Override
        public void toJSON(Output out)
        {
            out.addClass(Woggle.class);
            out.add("name", name);
            out.add("nested", nested);
            out.add("number", number);
        }

        @Override
        public String toString()
        {
            return name + "<<" + nested + ">>" + number;
        }
    }
}
