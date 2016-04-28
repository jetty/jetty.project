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

package org.eclipse.jetty.websocket.jsr356;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;

import org.eclipse.jetty.websocket.jsr356.EndpointFunctions.Role;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.InvalidCloseIntSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.InvalidErrorErrorSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.InvalidErrorExceptionSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.InvalidErrorIntSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.InvalidOpenCloseReasonSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.InvalidOpenIntSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.InvalidOpenSessionIntSocket;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class EndpointFunctions_BadSignaturesTest
{
    public static class Case
    {
        public static Case add(List<Case[]> data, Class<?> pojo, Class<? extends Annotation> methodAnnotation, Role role)
        {
            Case test = new Case(pojo, methodAnnotation, role);
            data.add(new Case[]{test});
            return test;
        }

        // The websocket pojo to test against
        final Class<?> pojo;
        final Class<? extends Annotation> methodAnnotation;
        final Role role;

        public Case(Class<?> pojo, Class<? extends Annotation> methodAnnotation, Role role)
        {
            this.pojo = pojo;
            this.methodAnnotation = methodAnnotation;
            this.role = role;
        }

        @Override
        public String toString()
        {
            return String.format("%s @%s", pojo.getSimpleName(), methodAnnotation.getSimpleName());
        }
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Case[]> data() throws Exception
    {
        List<Case[]> data = new ArrayList<>();

        Case.add(data, InvalidCloseIntSocket.class, OnClose.class, Role.CLOSE);
        Case.add(data, InvalidErrorErrorSocket.class, OnError.class, Role.ERROR);
        Case.add(data, InvalidErrorExceptionSocket.class, OnError.class, Role.ERROR);
        Case.add(data, InvalidErrorIntSocket.class, OnError.class, Role.ERROR);
        Case.add(data, InvalidOpenCloseReasonSocket.class, OnOpen.class, Role.OPEN);
        Case.add(data, InvalidOpenIntSocket.class, OnOpen.class, Role.OPEN);
        Case.add(data, InvalidOpenSessionIntSocket.class, OnOpen.class, Role.OPEN);

        // TODO: invalid return types
        // TODO: static methods
        // TODO: private or protected methods
        // TODO: abstract methods

        return data;
    }

    @Parameterized.Parameter(0)
    public Case testcase;

    @Test
    public void testInvalidSignature()
    {
        EndpointFunctions functions = new EndpointFunctions();

        Method foundMethod = null;

        for (Method method : testcase.pojo.getDeclaredMethods())
        {
            if (method.getAnnotation(testcase.methodAnnotation) != null)
            {
                foundMethod = method;
                break;
            }
        }

        assertThat("Found Method with @" + testcase.methodAnnotation.getSimpleName(), foundMethod, notNullValue());

        try
        {
            EndpointFunctions.ArgRole argRole = functions.getArgRole(foundMethod, testcase.methodAnnotation, testcase.role);
            fail("Expected " + InvalidSignatureException.class + " with message that references " + testcase.methodAnnotation.getSimpleName() + " annotation");
        }
        catch (InvalidSignatureException e)
        {
            assertThat("Message", e.getMessage(), containsString(testcase.methodAnnotation.getSimpleName()));
        }
    }
}
