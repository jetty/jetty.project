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

package org.eclipse.jetty.ee10.websocket.jakarta.common.util;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.websocket.core.internal.util.InvokerUtils;
import org.eclipse.jetty.websocket.core.internal.util.ReflectUtils;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@SuppressWarnings("Duplicates")
public class InvokerUtilsTest
{
    public static class Simple
    {
        public String onMessage(String msg)
        {
            return String.format("onMessage(%s)", msg);
        }
    }

    public static class KeyValue
    {
        public String onEntry(String key, int value)
        {
            return String.format("onEntry(%s, %d)", key, value);
        }

        public String onEntry(int value, String key)
        {
            return String.format("onEntry(%d, %s)", value, key);
        }
    }

    public static class A
    {
        private final String id;

        public A(String id)
        {
            this.id = id;
        }

        public String toString()
        {
            return String.format("A:%s", id);
        }
    }

    public static class B
    {
        private final int val;

        public B(int val)
        {
            this.val = val;
        }

        public String toString()
        {
            return String.format("B:%d", val);
        }
    }

    @SuppressWarnings("unused")
    public static class SampleSignatures
    {
        public String sigEmpty()
        {
            return "sigEmpty<>";
        }

        public String sigStr(String str)
        {
            return String.format("sigStr<%s>", str);
        }

        public String sigStrFile(String str, File foo)
        {
            return String.format("sigStrFile<%s,%s>", str, q(foo));
        }

        public String sigFileStr(File foo, String str)
        {
            return String.format("sigFileStr<%s,%s>", q(foo), str);
        }

        public String sigFileStrFin(File foo, String str, @Name("fin") boolean fin)
        {
            return String.format("sigFileStrFin<%s,%s,%b>", q(foo), q(str), fin);
        }

        public String sigByteArray(byte[] buf, @Name("offset") int offset, @Name("length") int len)
        {
            return String.format("sigByteArray<%s,%d,%d>", buf == null ? "<null>" : ("[" + buf.length + "]"), offset, len);
        }

        public String sigObjectArgs(A a, B b)
        {
            return String.format("sigObjectArgs<%s,%s>", q(a), q(b));
        }

        private String q(Object obj)
        {
            if (obj == null)
                return "<null>";
            else
                return obj.toString();
        }
    }

    public static class NamedParams
    {
        public String onMessage(@Name("fruit") String fruit, @Name("color") String color, @Name("cost") int cents)
        {
            return String.format("onMessage(%s, %s, %d)", fruit, color, cents);
        }
    }

    public static Method findMethodByName(Object obj, String name)
    {
        for (Method method : obj.getClass().getMethods())
        {
            if (method.getName().equals(name))
            {
                return method;
            }
        }
        throw new AssertionError("Unable to find method: " + name);
    }

    private static MethodHandles.Lookup lookup = MethodHandles.lookup();
    
    @Test
    public void testSimpleInvoker() throws Throwable
    {
        Method method = ReflectUtils.findMethod(Simple.class, "onMessage", String.class);
        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, Simple.class, method, new InvokerUtils.Arg(String.class));

        Simple simple = new Simple();
        String result = (String)methodHandle.invoke(simple, "Hello World");
        assertThat("Message invoked", result, is("onMessage(Hello World)"));
    }

    @Test
    public void testKeyValueBackwards() throws Throwable
    {
        Method method2 = ReflectUtils.findMethod(KeyValue.class, "onEntry", int.class, String.class);

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(String.class),
            new InvokerUtils.Arg(int.class)
        };

        MethodHandle methodHandle2 = InvokerUtils.mutatedInvoker(lookup, KeyValue.class, method2, callingArgs);

        KeyValue obj = new KeyValue();
        String result = (String)methodHandle2.invoke(obj, "Year", 1972);
        assertThat("First invoke", result, is("onEntry(1972, Year)"));
    }

    @Test
    public void testKeyValueNormal() throws Throwable
    {
        Method method1 = ReflectUtils.findMethod(KeyValue.class, "onEntry", String.class, int.class);

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(String.class),
            new InvokerUtils.Arg(int.class)
        };

        MethodHandle methodHandle1 = InvokerUtils.mutatedInvoker(lookup, KeyValue.class, method1, callingArgs);

        KeyValue obj = new KeyValue();
        String result = (String)methodHandle1.invoke(obj, "Age", 45);
        assertThat("First invoke", result, is("onEntry(Age, 45)"));
    }

    @Test
    public void testKeyValueExtraArgsAtEnd() throws Throwable
    {
        Method method1 = ReflectUtils.findMethod(KeyValue.class, "onEntry", String.class, int.class);

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(String.class),
            new InvokerUtils.Arg(int.class),
            new InvokerUtils.Arg(Boolean.class)
        };

        MethodHandle methodHandle1 = InvokerUtils.mutatedInvoker(lookup, KeyValue.class, method1, callingArgs);

        KeyValue obj = new KeyValue();
        String result = (String)methodHandle1.invoke(obj, "Age", 45, Boolean.TRUE);
        assertThat("First invoke", result, is("onEntry(Age, 45)"));
    }

    @Test
    public void testKeyValueExtraArgsInMiddle() throws Throwable
    {
        Method method1 = ReflectUtils.findMethod(KeyValue.class, "onEntry", String.class, int.class);

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(String.class),
            new InvokerUtils.Arg(Long.class),
            new InvokerUtils.Arg(int.class)
        };

        MethodHandle methodHandle1 = InvokerUtils.mutatedInvoker(lookup, KeyValue.class, method1, callingArgs);

        KeyValue obj = new KeyValue();
        String result = (String)methodHandle1.invoke(obj, "Year", 888888L, 2017);
        assertThat("First invoke", result, is("onEntry(Year, 2017)"));
    }

    @Test
    public void testKeyValueExtraArgsAtStart() throws Throwable
    {
        Method method1 = ReflectUtils.findMethod(KeyValue.class, "onEntry", String.class, int.class);

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(Simple.class),
            new InvokerUtils.Arg(String.class),
            new InvokerUtils.Arg(int.class)
        };

        MethodHandle methodHandle1 = InvokerUtils.mutatedInvoker(lookup, KeyValue.class, method1, callingArgs);

        KeyValue obj = new KeyValue();
        String result = (String)methodHandle1.invoke(obj, new Simple(), "Count", 1776);
        assertThat("First invoke", result, is("onEntry(Count, 1776)"));
    }

    @Test
    public void testKeyValueExtraArgsMixed() throws Throwable
    {
        Method method1 = ReflectUtils.findMethod(KeyValue.class, "onEntry", String.class, int.class);

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(Simple.class),
            new InvokerUtils.Arg(String.class),
            new InvokerUtils.Arg(Boolean.class),
            new InvokerUtils.Arg(int.class),
            new InvokerUtils.Arg(Long.class)
        };

        MethodHandle methodHandle1 = InvokerUtils.mutatedInvoker(lookup, KeyValue.class, method1, callingArgs);

        KeyValue obj = new KeyValue();
        String result = (String)methodHandle1.invoke(obj, new Simple(), "Amount", Boolean.TRUE, 200, 9999L);
        assertThat("First invoke", result, is("onEntry(Amount, 200)"));
    }

    @Test
    public void testNamedAllParams() throws Throwable
    {
        Method method = ReflectUtils.findMethod(NamedParams.class, "onMessage", String.class, String.class, int.class);

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(String.class, "fruit"),
            new InvokerUtils.Arg(String.class, "color"),
            new InvokerUtils.Arg(int.class, "cost")
        };

        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, NamedParams.class, method, new NameParamIdentifier(), null, callingArgs);

        NamedParams obj = new NamedParams();
        String result = (String)methodHandle.invoke(obj, "Apple", "Red", 10);
        assertThat("Result", result, is("onMessage(Apple, Red, 10)"));
    }

    @Test
    public void testNamedAllParamsMixed() throws Throwable
    {
        Method method = ReflectUtils.findMethod(NamedParams.class, "onMessage", String.class, String.class, int.class);

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(int.class, "cost"),
            new InvokerUtils.Arg(String.class, "fruit"),
            new InvokerUtils.Arg(String.class, "color")
        };

        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, NamedParams.class, method, new NameParamIdentifier(), null, callingArgs);

        NamedParams obj = new NamedParams();
        String result = (String)methodHandle.invoke(obj, 20, "Banana", "Yellow");
        assertThat("Result", result, is("onMessage(Banana, Yellow, 20)"));
    }

    @Test
    public void testEmptyCallNone() throws Throwable
    {
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigEmpty");

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{};

        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, SampleSignatures.class, method, callingArgs);
        String result = (String)methodHandle.invoke(samples);
        assertThat("Result", result, is("sigEmpty<>"));
    }

    @Test
    public void testEmptyCallFile() throws Throwable
    {
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigEmpty");

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(File.class)
        };

        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, SampleSignatures.class, method, callingArgs);
        String result = (String)methodHandle.invoke(samples, new File("bogus"));
        assertThat("Result", result, is("sigEmpty<>"));
    }

    @Test
    public void testEmptyCallNullFile() throws Throwable
    {
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigEmpty");

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(File.class)
        };

        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, SampleSignatures.class, method, callingArgs);
        String result = (String)methodHandle.invoke(samples, null);
        assertThat("Result", result, is("sigEmpty<>"));
    }

    @Test
    public void testStringCallString() throws Throwable
    {
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigStr");

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(String.class)
        };

        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, SampleSignatures.class, method, callingArgs);
        String result = (String)methodHandle.invoke(samples, "Hello");
        assertThat("Result", result, is("sigStr<Hello>"));
    }

    @Test
    public void testStringCallFileString() throws Throwable
    {
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigStr");

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(File.class),
            new InvokerUtils.Arg(String.class)
        };

        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, SampleSignatures.class, method, callingArgs);
        String result = (String)methodHandle.invoke(samples, new File("bogus"), "Hiya");
        assertThat("Result", result, is("sigStr<Hiya>"));
    }

    @Test
    public void testStringCallStringFile() throws Throwable
    {
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigStr");

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(String.class),
            new InvokerUtils.Arg(File.class)
        };

        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, SampleSignatures.class, method, callingArgs);
        String result = (String)methodHandle.invoke(samples, "Greetings", new File("bogus"));
        assertThat("Result", result, is("sigStr<Greetings>"));
    }

    @Test
    public void testStringFileCallStringFile() throws Throwable
    {
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigStrFile");

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(String.class),
            new InvokerUtils.Arg(File.class)
        };

        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, SampleSignatures.class, method, callingArgs);
        String result = (String)methodHandle.invoke(samples, "Name", new File("bogus1"));
        assertThat("Result", result, is("sigStrFile<Name,bogus1>"));
    }

    @Test
    public void testStringFileCallFileString() throws Throwable
    {
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigStrFile");

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(File.class),
            new InvokerUtils.Arg(String.class)
        };

        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, SampleSignatures.class, method, callingArgs);
        String result = (String)methodHandle.invoke(samples, new File("bogus2"), "Alt");
        assertThat("Result", result, is("sigStrFile<Alt,bogus2>"));
    }

    @Test
    public void testFileStringCallStringFile() throws Throwable
    {
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigFileStr");

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(String.class),
            new InvokerUtils.Arg(File.class)
        };

        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, SampleSignatures.class, method, callingArgs);
        String result = (String)methodHandle.invoke(samples, "Bob", new File("bogus3"));
        assertThat("Result", result, is("sigFileStr<bogus3,Bob>"));
    }

    @Test
    public void testFileStringCallFileString() throws Throwable
    {
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigFileStr");

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(File.class),
            new InvokerUtils.Arg(String.class)
        };

        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, SampleSignatures.class, method, callingArgs);
        String result = (String)methodHandle.invoke(samples, new File("bogus4"), "Dobalina");
        assertThat("Result", result, is("sigFileStr<bogus4,Dobalina>"));
    }

    @Test
    public void testFileStringFinCallFileStringBoolTag() throws Throwable
    {
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigFileStrFin");

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(File.class),
            new InvokerUtils.Arg(String.class),
            new InvokerUtils.Arg(boolean.class, "fin")
        };

        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, SampleSignatures.class, method, new NameParamIdentifier(), null, callingArgs);
        String result = (String)methodHandle.invoke(samples, new File("foo"), "bar", true);
        assertThat("Result", result, is("sigFileStrFin<foo,bar,true>"));
    }

    @Test
    public void testFileStringFinCallFileStringBool() throws Throwable
    {
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigFileStrFin");

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(File.class),
            new InvokerUtils.Arg(String.class),
            new InvokerUtils.Arg(boolean.class)
        };

        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, SampleSignatures.class, method, callingArgs);
        String result = (String)methodHandle.invoke(samples, new File("baz"), "flem", false);
        assertThat("Result", result, is("sigFileStrFin<baz,flem,false>"));
    }

    @Test
    public void testFileStringFinCallBoolTagFileString() throws Throwable
    {
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigFileStrFin");

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(boolean.class, "fin"),
            new InvokerUtils.Arg(File.class),
            new InvokerUtils.Arg(String.class)
        };

        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, SampleSignatures.class, method, new NameParamIdentifier(), null, callingArgs);
        String result = (String)methodHandle.invoke(samples, false, new File("foo"), "bar");
        assertThat("Result", result, is("sigFileStrFin<foo,bar,false>"));
    }

    @Test
    public void testFileStringFinCallBoolFileString() throws Throwable
    {
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigFileStrFin");

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(boolean.class),
            new InvokerUtils.Arg(File.class),
            new InvokerUtils.Arg(String.class)
        };

        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, SampleSignatures.class, method, callingArgs);
        String result = (String)methodHandle.invoke(samples, true, new File("foo"), "bar");
        assertThat("Result", result, is("sigFileStrFin<foo,bar,true>"));
    }

    @Test
    public void testFileStringFinCallBoolTagNullString() throws Throwable
    {
        SampleSignatures samples = new SampleSignatures();
        Method method = findMethodByName(samples, "sigFileStrFin");

        InvokerUtils.Arg[] callingArgs = new InvokerUtils.Arg[]{
            new InvokerUtils.Arg(boolean.class, "fin"),
            new InvokerUtils.Arg(File.class),
            new InvokerUtils.Arg(String.class)
        };

        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, SampleSignatures.class, method, new NameParamIdentifier(), null, callingArgs);
        String result = (String)methodHandle.invoke(samples, true, null, "bar");
        assertThat("Result", result, is("sigFileStrFin<<null>,bar,true>"));
    }
}
