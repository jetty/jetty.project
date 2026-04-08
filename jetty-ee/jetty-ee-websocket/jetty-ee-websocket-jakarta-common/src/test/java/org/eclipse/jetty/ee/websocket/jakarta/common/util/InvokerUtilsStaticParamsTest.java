//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee.websocket.jakarta.common.util;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import org.eclipse.jetty.ee.websocket.jakarta.common.JakartaWebSocketFrameHandlerFactory;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.util.InvokerUtils;
import org.eclipse.jetty.websocket.core.util.MethodHolder;
import org.eclipse.jetty.websocket.core.util.ReflectUtils;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.ee.websocket.jakarta.common.JakartaWebSocketFrameHandlerFactory.bindTemplateVariables;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;

public class InvokerUtilsStaticParamsTest
{
    @SuppressWarnings("unused")
    public static class Foo
    {
        public String onFruit(@Name("fruit") String fruit)
        {
            return String.format("onFruit('%s')", (fruit == null) ? "null" : fruit);
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

    private MethodHolder getMethodHolder(Method method, String[] namedVariables, InvokerUtils.Arg... args)
    {
        MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, Foo.class, method, new NameParamIdentifier(), namedVariables, args);
        return MethodHolder.from(methodHandle);
    }

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
        MethodHolder methodHolder = getMethodHolder(method, namedVariables);

        // Some point later an actual instance is needed, which has static named parameters
        Map<String, String> templateValues = new HashMap<>();
        templateValues.put("fruit", "pear");

        // Bind the static values, in same order as declared
        methodHolder = bindTemplateVariables(methodHolder, namedVariables, templateValues);

        // Assign an instance to call.
        Foo foo = new Foo();
        methodHolder = methodHolder.bindTo(foo);

        // Call method against instance
        String result = (String)methodHolder.invoke();
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
        MethodHolder methodHolder = getMethodHolder(method, namedVariables);

        // Some point later an actual instance is needed, which has static named parameters
        Map<String, String> templateValues = new HashMap<>();
        templateValues.put("count", "2222");

        // Bind the static values for the variables, in same order as the variables were declared
        methodHolder = bindTemplateVariables(methodHolder, namedVariables, templateValues);

        // Assign an instance to call.
        Foo foo = new Foo();
        methodHolder = methodHolder.bindTo(foo);

        // Call method against instance
        String result = (String)methodHolder.invoke();
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
        MethodHolder methodHolder = getMethodHolder(method, namedVariables, ARG_LABEL);

        // Some point later an actual instance is needed, which has static named parameters
        Map<String, String> templateValues = new HashMap<>();
        templateValues.put("count", "444");

        // Bind the static values for the variables, in same order as the variables were declared
        methodHolder = bindTemplateVariables(methodHolder, namedVariables, templateValues);

        // Assign an instance to call.
        Foo foo = new Foo();
        methodHolder = methodHolder.bindTo(foo);

        // Call method against instance
        String result = (String)methodHolder.invoke("cherry");
        assertThat("Result", result, is("onLabeledCount('cherry', 444)"));
    }

    @Test
    public void testNonExistentParams() throws Throwable
    {
        class PathParamEndpoint
        {
            private String pathParam;
            private String nonExistent1;
            private String nonExistent2;
            private String nonExistent3;

            @OnOpen
            public void onOpen(@PathParam("non-existent1") String nonExistent1,
                               EndpointConfig config,
                               @PathParam("non-existent2") String nonExistent2,
                               @PathParam("param1") String pathParam,
                               Session session,
                               @PathParam("non-existent3") String nonExistent3
            )
            {
                this.pathParam = pathParam;
                this.nonExistent1 = nonExistent1;
                this.nonExistent2 = nonExistent2;
                this.nonExistent3 = nonExistent3;
            }
        }

        Method method = ReflectUtils.findAnnotatedMethod(PathParamEndpoint.class, OnOpen.class);

        // Declared Variable Names
        final String[] namedVariables = new String[]{
            "param1",
            "param2",
            "param3"
        };

        // Some point later an actual instance is needed, which has static named parameters
        Map<String, String> templateValues = new HashMap<>();
        templateValues.put("param1", "value1");
        templateValues.put("param2", "value2");
        templateValues.put("param3", "value3");

        // Bind the static values, in same order as declared
        final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class);
        final InvokerUtils.Arg ENDPOINT_CONFIG = new InvokerUtils.Arg(EndpointConfig.class);
        MethodHolder methodHolder = MethodHolder.from(InvokerUtils.mutatedInvoker(lookup, PathParamEndpoint.class, method, new PathParamIdentifier(), namedVariables, SESSION, ENDPOINT_CONFIG));

        methodHolder = bindTemplateVariables(methodHolder, namedVariables, templateValues);

        // Assign an instance to call.
        PathParamEndpoint foo = new PathParamEndpoint();
        methodHolder = methodHolder.bindTo(foo).bindTo(null).bindTo(null);

        // Call method against instance
        methodHolder.invoke();
        assertThat(foo.pathParam, is("value1"));
        assertNull(foo.nonExistent1);
        assertNull(foo.nonExistent2);
        assertNull(foo.nonExistent3);
    }

    @SuppressWarnings("unused")
    public static class PathParamIdentifier implements InvokerUtils.ParamIdentifier
    {
        @Override
        public InvokerUtils.Arg getParamArg(Method method, Class<?> paramType, int idx)
        {
            Annotation[] annos = method.getParameterAnnotations()[idx];
            if (annos != null || (annos.length > 0))
            {
                for (Annotation anno : annos)
                {
                    if (anno.annotationType().equals(PathParam.class))
                    {
                        if (!String.class.isAssignableFrom(paramType))
                            throw new InvalidSignatureException("Unsupported PathParam Type: " + paramType);
                        PathParam pathParam = (PathParam)anno;
                        return new InvokerUtils.Arg(paramType, pathParam.value());
                    }
                }
            }
            return new InvokerUtils.Arg(paramType);
        }
    }
}
