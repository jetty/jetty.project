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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import jakarta.websocket.Session;
import org.eclipse.jetty.ee10.websocket.jakarta.common.JakartaWebSocketFrameHandlerFactory;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.websocket.core.internal.util.InvokerUtils;
import org.eclipse.jetty.websocket.core.internal.util.ReflectUtils;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class InvokerUtilsStaticParamsTest
{
    @SuppressWarnings("unused")
    public static class Foo
    {
        public String onFruit(@Name("fruit") String fruit)
        {
            return String.format("onFruit('%s')", fruit);
        }

        public String onCount(@Name("count") int count)
        {
            return String.format("onCount(%d)", count);
        }

        public String onLabeledCount(String label, @Name("count") int count)
        {
            return String.format("onLabeledCount('%s', %d)", label, count);
        }

        public String onColorMessage(Session session, String message, @Name("color") String color)
        {
            return String.format("onColorMessage(%s, '%s', '%s')", color);
        }
    }
    
    private static MethodHandles.Lookup lookup = MethodHandles.lookup();

    @Test
    public void testOnlyParamString() throws Throwable
    {
        Method method = ReflectUtils.findMethod(Foo.class, "onFruit", String.class);

        // Declared Variable Names
        final String[] namedVariables = new String[]{
            "fruit"
        };

        // Raw Calling Args - none specified

        // Get basic method handle (without a instance to call against) - this is what the metadata stores
        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, Foo.class, method, new NameParamIdentifier(), namedVariables);

        // Some point later an actual instance is needed, which has static named parameters
        Map<String, String> templateValues = new HashMap<>();
        templateValues.put("fruit", "pear");

        // Bind the static values, in same order as declared
        methodHandle = JakartaWebSocketFrameHandlerFactory.bindTemplateVariables(methodHandle, namedVariables, templateValues);

        // Assign an instance to call.
        Foo foo = new Foo();
        methodHandle = methodHandle.bindTo(foo);

        // Call method against instance
        String result = (String)methodHandle.invoke();
        assertThat("Result", result, is("onFruit('pear')"));
    }

    @Test
    public void testOnlyParamInt() throws Throwable
    {
        Method method = ReflectUtils.findMethod(Foo.class, "onCount", int.class);

        // Declared Variable Names - as seen in url-template-pattern
        final String[] namedVariables = new String[]{
            "count"
        };

        // Get basic method handle (without a instance to call against) - this is what the metadata stores
        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, Foo.class, method, new NameParamIdentifier(), namedVariables);

        // Some point later an actual instance is needed, which has static named parameters
        Map<String, String> templateValues = new HashMap<>();
        templateValues.put("count", "2222");

        // Bind the static values for the variables, in same order as the variables were declared
        methodHandle = JakartaWebSocketFrameHandlerFactory.bindTemplateVariables(methodHandle, namedVariables, templateValues);

        // Assign an instance to call.
        Foo foo = new Foo();
        methodHandle = methodHandle.bindTo(foo);

        // Call method against instance
        String result = (String)methodHandle.invoke();
        assertThat("Result", result, is("onCount(2222)"));
    }

    @Test
    public void testLabeledParamStringInt() throws Throwable
    {
        Method method = ReflectUtils.findMethod(Foo.class, "onLabeledCount", String.class, int.class);

        // Declared Variable Names - as seen in url-template-pattern
        final String[] namedVariables = new String[]{
            "count"
        };

        final InvokerUtils.Arg ARG_LABEL = new InvokerUtils.Arg(String.class).required();

        // Get basic method handle (without a instance to call against) - this is what the metadata stores
        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, Foo.class, method, new NameParamIdentifier(), namedVariables, ARG_LABEL);

        // Some point later an actual instance is needed, which has static named parameters
        Map<String, String> templateValues = new HashMap<>();
        templateValues.put("count", "444");

        // Bind the static values for the variables, in same order as the variables were declared
        methodHandle = JakartaWebSocketFrameHandlerFactory.bindTemplateVariables(methodHandle, namedVariables, templateValues);

        // Assign an instance to call.
        Foo foo = new Foo();
        methodHandle = methodHandle.bindTo(foo);

        // Call method against instance
        String result = (String)methodHandle.invoke("cherry");
        assertThat("Result", result, is("onLabeledCount('cherry', 444)"));
    }
}
