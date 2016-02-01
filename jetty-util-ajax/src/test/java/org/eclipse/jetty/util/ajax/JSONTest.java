//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util.ajax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.StringReader;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.eclipse.jetty.util.DateCache;
import org.eclipse.jetty.util.ajax.JSON.Output;
import org.junit.BeforeClass;
import org.junit.Test;


public class JSONTest
{
    String test="\n\n\n\t\t    "+
    "// ignore this ,a [ \" \n"+
    "/* and this \n" +
    "/* and * // this \n" +
    "*/" +
    "{ "+
    "\"onehundred\" : 100  ,"+
    "\"small\":-0.2,"+
    "\"name\" : \"fred\"  ," +
    "\"empty\" : {}  ," +
    "\"map\" : {\"a\":-1.0e2}  ," +
    "\"array\" : [\"a\",-1.0e2,[],null,true,false]  ," +
    "\"w0\":{\"class\":\"org.eclipse.jetty.util.ajax.JSONTest$Woggle\",\"name\":\"woggle0\",\"nested\":{\"class\":\"org.eclipse.jetty.util.ajax.JSONTest$Woggle\",\"name\":\"woggle1\",\"nested\":null,\"number\":-101},\"number\":100}," +
    "\"NaN\": NaN," +
    "\"undefined\": undefined," +
    "}";

    @BeforeClass
    public static void setUp() throws Exception
    {
        JSON.registerConvertor(Gadget.class,new JSONObjectConvertor(false));
    }

    @Test
    public void testToString()
    {
        HashMap map = new HashMap();
        HashMap obj6 = new HashMap();
        HashMap obj7 = new HashMap();

        Woggle w0 = new Woggle();
        Woggle w1 = new Woggle();

        w0.name="woggle0";
        w0.nested=w1;
        w0.number=100;
        w1.name="woggle1";
        w1.nested=null;
        w1.number=-101;

        map.put("n1",null);
        map.put("n2",new Integer(2));
        map.put("n3",new Double(-0.00000000003));
        map.put("n4","4\n\r\t\"4");
        map.put("n5",new Object[]{"a",new Character('b'),new Integer(3),new String[]{},null,Boolean.TRUE,Boolean.FALSE});
        map.put("n6",obj6);
        map.put("n7",obj7);
        map.put("n8",new int[]{1,2,3,4});
        map.put("n9",new JSON.Literal("[{},  [],  {}]"));
        map.put("w0",w0);

        obj7.put("x","value");


        String s = JSON.toString(map);
        assertTrue(s.indexOf("\"n1\":null")>=0);
        assertTrue(s.indexOf("\"n2\":2")>=0);
        assertTrue(s.indexOf("\"n3\":-3.0E-11")>=0);
        assertTrue(s.indexOf("\"n4\":\"4\\n")>=0);
        assertTrue(s.indexOf("\"n5\":[\"a\",\"b\",")>=0);
        assertTrue(s.indexOf("\"n6\":{}")>=0);
        assertTrue(s.indexOf("\"n7\":{\"x\":\"value\"}")>=0);
        assertTrue(s.indexOf("\"n8\":[1,2,3,4]")>=0);
        assertTrue(s.indexOf("\"n9\":[{},  [],  {}]")>=0);
        assertTrue(s.indexOf("\"w0\":{\"class\":\"org.eclipse.jetty.util.ajax.JSONTest$Woggle\",\"name\":\"woggle0\",\"nested\":{\"class\":\"org.eclipse.jetty.util.ajax.JSONTest$Woggle\",\"name\":\"woggle1\",\"nested\":null,\"number\":-101},\"number\":100}")>=0);

        Gadget gadget = new Gadget();
        gadget.setShields(42);
        gadget.setWoggles(new Woggle[]{w0,w1});

        s = JSON.toString(new Gadget[]{gadget});
        assertTrue(s.startsWith("["));
        assertTrue(s.indexOf("\"modulated\":false")>=0);
        assertTrue(s.indexOf("\"shields\":42")>=0);
        assertTrue(s.indexOf("\"name\":\"woggle0\"")>=0);
        assertTrue(s.indexOf("\"name\":\"woggle1\"")>=0);
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testParse()
    {
        Map map = (Map)JSON.parse(test);
        assertEquals(new Long(100),map.get("onehundred"));
        assertEquals("fred",map.get("name"));
        assertEquals(-0.2,map.get("small"));
        assertTrue(map.get("array").getClass().isArray());
        assertTrue(map.get("w0") instanceof Woggle);
        assertTrue(((Woggle)map.get("w0")).nested instanceof Woggle);
        assertEquals(-101,((Woggle)((Woggle)map.get("w0")).nested).number);
        assertTrue(map.containsKey("NaN"));
        assertEquals(null,map.get("NaN"));
        assertTrue(map.containsKey("undefined"));
        assertEquals(null,map.get("undefined"));

        test="{\"data\":{\"source\":\"15831407eqdaawf7\",\"widgetId\":\"Magnet_8\"},\"channel\":\"/magnets/moveStart\",\"connectionId\":null,\"clientId\":\"15831407eqdaawf7\"}";
        map = (Map)JSON.parse(test);
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testParseReader() throws Exception
    {
        Map map = (Map)JSON.parse(new StringReader(test));

        assertEquals(new Long(100),map.get("onehundred"));
        assertEquals("fred",map.get("name"));
        assertTrue(map.get("array").getClass().isArray());
        assertTrue(map.get("w0") instanceof Woggle);
        assertTrue(((Woggle)map.get("w0")).nested instanceof Woggle);

        test="{\"data\":{\"source\":\"15831407eqdaawf7\",\"widgetId\":\"Magnet_8\"},\"channel\":\"/magnets/moveStart\",\"connectionId\":null,\"clientId\":\"15831407eqdaawf7\"}";
        map = (Map)JSON.parse(test);
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testStripComment()
    {
        String test="\n\n\n\t\t    "+
        "// ignore this ,a [ \" \n"+
        "/* "+
        "{ "+
        "\"onehundred\" : 100  ,"+
        "\"name\" : \"fred\"  ," +
        "\"empty\" : {}  ," +
        "\"map\" : {\"a\":-1.0e2}  ," +
        "\"array\" : [\"a\",-1.0e2,[],null,true,false]  ," +
        "} */";

        Object o = JSON.parse(test,false);
        assertTrue(o==null);
        o = JSON.parse(test,true);
        assertTrue(o instanceof Map);
        assertEquals("fred",((Map)o).get("name"));

    }

    /* ------------------------------------------------------------ */
    @Test
    public void testQuote()
    {
        String test="\"abc123|\\\"|\\\\|\\/|\\b|\\f|\\n|\\r|\\t|\\uaaaa|\"";

        String result = (String)JSON.parse(test,false);
        assertEquals("abc123|\"|\\|/|\b|\f|\n|\r|\t|\uaaaa|",result);
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testBigDecimal()
    {
        Object obj = JSON.parse("1.0E7");
        assertTrue(obj instanceof Double);
        BigDecimal bd = BigDecimal.valueOf(10000000d);
        String string = JSON.toString(new Object[]{bd});
        obj = Array.get(JSON.parse(string),0);
        assertTrue(obj instanceof Double);
    }


    /* ------------------------------------------------------------ */
    @Test
    public void testZeroByte()
    {
        String withzero="\u0000";
        JSON.toString(withzero);
    }

    /* ------------------------------------------------------------ */
    public static class Gadget
    {
        private boolean modulated;
        private long shields;
        private Woggle[] woggles;
        /* ------------------------------------------------------------ */
        /**
         * @return the modulated
         */
        public boolean isModulated()
        {
            return modulated;
        }
        /* ------------------------------------------------------------ */
        /**
         * @param modulated the modulated to set
         */
        public void setModulated(boolean modulated)
        {
            this.modulated=modulated;
        }
        /* ------------------------------------------------------------ */
        /**
         * @return the shields
         */
        public long getShields()
        {
            return shields;
        }
        /* ------------------------------------------------------------ */
        /**
         * @param shields the shields to set
         */
        public void setShields(long shields)
        {
            this.shields=shields;
        }
        /* ------------------------------------------------------------ */
        /**
         * @return the woggles
         */
        public Woggle[] getWoggles()
        {
            return woggles;
        }
        /* ------------------------------------------------------------ */
        /**
         * @param woggles the woggles to set
         */
        public void setWoggles(Woggle[] woggles)
        {
            this.woggles=woggles;
        }
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testConvertor()
    {
        // test case#1 - force timezone to GMT
        JSON json = new JSON();
        json.addConvertor(Date.class, new JSONDateConvertor("MM/dd/yyyy HH:mm:ss zzz", TimeZone.getTimeZone("GMT"),false));
        json.addConvertor(Object.class,new JSONObjectConvertor());

        Woggle w0 = new Woggle();
        Gizmo g0 = new Gizmo();

        w0.name="woggle0";
        w0.nested=g0;
        w0.number=100;
        g0.name="woggle1";
        g0.nested=null;
        g0.number=-101;
        g0.tested=true;

        HashMap map = new HashMap();
        Date dummyDate = new Date(1);
        map.put("date", dummyDate);
        map.put("w0",w0);

        StringBuffer buf = new StringBuffer();
        json.append(buf,map);
        String js=buf.toString();

        assertTrue(js.indexOf("\"date\":\"01/01/1970 00:00:00 GMT\"")>=0);
        assertTrue(js.indexOf("org.eclipse.jetty.util.ajax.JSONTest$Woggle")>=0);
        assertTrue(js.indexOf("org.eclipse.jetty.util.ajax.JSONTest$Gizmo")<0);
        assertTrue(js.indexOf("\"tested\":true")>=0);

        // test case#3
        TimeZone tzone = TimeZone.getTimeZone("JST");
        String tzone3Letter = tzone.getDisplayName(false, TimeZone.SHORT);
        String format = "EEE MMMMM dd HH:mm:ss zzz yyyy";

        Locale l = new Locale("ja", "JP");
        if (l!=null)
        {
            json.addConvertor(Date.class, new JSONDateConvertor(format, tzone, false, l));
            buf = new StringBuffer();
            json.append(buf,map);
            js=buf.toString();
            //assertTrue(js.indexOf("\"date\":\"\u6728 1\u6708 01 09:00:00 JST 1970\"")>=0);
            assertTrue(js.indexOf(" 01 09:00:00 JST 1970\"")>=0);
            assertTrue(js.indexOf("org.eclipse.jetty.util.ajax.JSONTest$Woggle")>=0);
            assertTrue(js.indexOf("org.eclipse.jetty.util.ajax.JSONTest$Gizmo")<0);
            assertTrue(js.indexOf("\"tested\":true")>=0);
        }

        // test case#4
        json.addConvertor(Date.class,new JSONDateConvertor(true));
        w0.nested=null;
        buf = new StringBuffer();
        json.append(buf,map);
        js=buf.toString();
        assertTrue(js.indexOf("\"date\":\"Thu Jan 01 00:00:00 GMT 1970\"")<0);
        assertTrue(js.indexOf("org.eclipse.jetty.util.ajax.JSONTest$Woggle")>=0);
        assertTrue(js.indexOf("org.eclipse.jetty.util.ajax.JSONTest$Gizmo")<0);

        map=(HashMap)json.parse(new JSON.StringSource(js));

        assertTrue(map.get("date") instanceof Date);
        assertTrue(map.get("w0") instanceof Woggle);
    }

    enum Color { Red, Green, Blue };

    @Test
    public void testEnumConvertor()
    {
        JSON json = new JSON();
        Locale l = new Locale("en", "US");
        json.addConvertor(Date.class,new JSONDateConvertor(DateCache.DEFAULT_FORMAT,TimeZone.getTimeZone("GMT"),false,l));
        json.addConvertor(Enum.class,new JSONEnumConvertor(false));
        json.addConvertor(Object.class,new JSONObjectConvertor());

        Woggle w0 = new Woggle();
        Gizmo g0 = new Gizmo();

        w0.name="woggle0";
        w0.nested=g0;
        w0.number=100;
        w0.other=Color.Blue;
        g0.name="woggle1";
        g0.nested=null;
        g0.number=-101;
        g0.tested=true;
        g0.other=Color.Green;

        HashMap map = new HashMap();
        map.put("date",new Date(1));
        map.put("w0",w0);
        map.put("g0",g0);

        StringBuffer buf = new StringBuffer();
        json.append((Appendable)buf,map);
        String js=buf.toString();

        assertTrue(js.indexOf("\"date\":\"Thu Jan 01 00:00:00 GMT 1970\"")>=0);
        assertTrue(js.indexOf("org.eclipse.jetty.util.ajax.JSONTest$Woggle")>=0);
        assertTrue(js.indexOf("org.eclipse.jetty.util.ajax.JSONTest$Gizmo")<0);
        assertTrue(js.indexOf("\"tested\":true")>=0);
        assertTrue(js.indexOf("\"Green\"")>=0);
        assertTrue(js.indexOf("\"Blue\"")<0);

        json.addConvertor(Date.class,new JSONDateConvertor(DateCache.DEFAULT_FORMAT,TimeZone.getTimeZone("GMT"),true,l));
        json.addConvertor(Enum.class,new JSONEnumConvertor(false));
        w0.nested=null;
        buf = new StringBuffer();
        json.append((Appendable)buf,map);
        js=buf.toString();
        assertTrue(js.indexOf("\"date\":\"Thu Jan 01 00:00:00 GMT 1970\"")<0);
        assertTrue(js.indexOf("org.eclipse.jetty.util.ajax.JSONTest$Woggle")>=0);
        assertTrue(js.indexOf("org.eclipse.jetty.util.ajax.JSONTest$Gizmo")<0);

        Map map2=(HashMap)json.parse(new JSON.StringSource(js));

        assertTrue(map2.get("date") instanceof Date);
        assertTrue(map2.get("w0") instanceof Woggle);
        assertEquals(null, ((Woggle)map2.get("w0")).getOther() );
        assertEquals(Color.Green.toString(), ((Map)map2.get("g0")).get("other"));



        json.addConvertor(Date.class,new JSONDateConvertor(DateCache.DEFAULT_FORMAT,TimeZone.getTimeZone("GMT"),true,l));
        json.addConvertor(Enum.class,new JSONEnumConvertor(true));
        buf = new StringBuffer();
        json.append((Appendable)buf,map);
        js=buf.toString();
        map2=(HashMap)json.parse(new JSON.StringSource(js));

        assertTrue(map2.get("date") instanceof Date);
        assertTrue(map2.get("w0") instanceof Woggle);
        assertEquals(null, ((Woggle)map2.get("w0")).getOther() );
        Object o=((Map)map2.get("g0")).get("other");
        assertEquals(Color.Green, o);

    }

    /* ------------------------------------------------------------ */
    public static class Gizmo
    {
        String name;
        Gizmo nested;
        long number;
        boolean tested;
        Object other;

        public String getName()
        {
            return name;
        }
        public Gizmo getNested()
        {
            return nested;
        }
        public long getNumber()
        {
            return number;
        }
        public boolean isTested()
        {
            return tested;
        }
        public Object getOther()
        {
            return other;
        }
    }

    /* ------------------------------------------------------------ */
    public static class Woggle extends Gizmo implements JSON.Convertible
    {

        public Woggle()
        {
        }

        public void fromJSON(Map object)
        {
            name=(String)object.get("name");
            nested=(Gizmo)object.get("nested");
            number=((Number)object.get("number")).intValue();
        }

        public void toJSON(Output out)
        {
            out.addClass(Woggle.class);
            out.add("name",name);
            out.add("nested",nested);
            out.add("number",number);
        }

        public String toString()
        {
            return name+"<<"+nested+">>"+number;
        }

    }
}
