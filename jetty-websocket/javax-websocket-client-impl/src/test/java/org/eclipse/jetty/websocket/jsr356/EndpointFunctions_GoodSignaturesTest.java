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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.websocket.CloseReason;
import javax.websocket.PongMessage;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.jsr356.EndpointFunctions.Role;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.BasicBinaryMessageByteBufferSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.BasicErrorSessionSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.BasicErrorSessionThrowableSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.BasicErrorSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.BasicErrorThrowableSessionSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.BasicErrorThrowableSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.BasicInputStreamSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.BasicInputStreamWithThrowableSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.BasicOpenSessionSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.BasicOpenSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.BasicPongMessageSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.BasicTextMessageStringSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.close.CloseReasonSessionSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.close.CloseReasonSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.close.CloseSessionReasonSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.close.CloseSocket;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class EndpointFunctions_GoodSignaturesTest
{
    static class ExpectedMethod
    {
        String methodName;
        Class<?> params[];
    }

    static class ActualMethod
    {
        EndpointFunctions.ArgRole argRole;
        Method method;
    }

    public static class Case
    {
        public static Case add(List<Case[]> data, Class<?> pojo)
        {
            Case test = new Case(pojo);
            data.add(new Case[]{test});
            return test;
        }

        // The websocket pojo to test against
        final Class<?> pojo;
        // The expected roles found, along with select methods that should
        // have been identified
        Map<Role, ExpectedMethod> expectedRoles;

        public Case(Class<?> pojo)
        {
            this.pojo = pojo;
            this.expectedRoles = new HashMap<>();
        }

        public void addExpected(Role role, String methodName, Class<?>... params)
        {
            ExpectedMethod expected = new ExpectedMethod();
            expected.methodName = methodName;
            expected.params = params;
            expectedRoles.put(role, expected);
        }

        @Override
        public String toString()
        {
            return String.format("%s [%d roles]", pojo.getSimpleName(), expectedRoles.size());
        }
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Case[]> data() throws Exception
    {
        List<Case[]> data = new ArrayList<>();

        Case.add(data, BasicOpenSocket.class)
                .addExpected(Role.OPEN, "onOpen");
        Case.add(data, BasicOpenSessionSocket.class)
                .addExpected(Role.OPEN, "onOpen", Session.class);

        Case.add(data, CloseSocket.class)
                .addExpected(Role.CLOSE, "onClose");
        Case.add(data, CloseReasonSocket.class)
                .addExpected(Role.CLOSE, "onClose", CloseReason.class);
        Case.add(data, CloseReasonSessionSocket.class)
                .addExpected(Role.CLOSE, "onClose", CloseReason.class, Session.class);
        Case.add(data, CloseSessionReasonSocket.class)
                .addExpected(Role.CLOSE, "onClose", Session.class, CloseReason.class);

        Case.add(data, BasicErrorSocket.class)
                .addExpected(Role.ERROR, "onError");
        Case.add(data, BasicErrorSessionSocket.class)
                .addExpected(Role.ERROR, "onError", Session.class);
        Case.add(data, BasicErrorSessionThrowableSocket.class)
                .addExpected(Role.ERROR, "onError", Session.class, Throwable.class);
        Case.add(data, BasicErrorThrowableSocket.class)
                .addExpected(Role.ERROR, "onError", Throwable.class);
        Case.add(data, BasicErrorThrowableSessionSocket.class)
                .addExpected(Role.ERROR, "onError", Throwable.class, Session.class);

        Case.add(data, BasicTextMessageStringSocket.class)
                .addExpected(Role.TEXT, "onText", String.class);

        Case.add(data, BasicBinaryMessageByteBufferSocket.class)
                .addExpected(Role.BINARY, "onBinary", ByteBuffer.class);

        Case.add(data, BasicPongMessageSocket.class)
                .addExpected(Role.PONG, "onPong", PongMessage.class);

        Case.add(data, BasicInputStreamSocket.class)
                .addExpected(Role.BINARY_STREAM, "onBinary", InputStream.class);
        Case.add(data, BasicInputStreamWithThrowableSocket.class)
                .addExpected(Role.BINARY_STREAM, "onBinary", InputStream.class);

        return data;
    }

    @Parameterized.Parameter(0)
    public Case testcase;

    @Test
    public void testFoundRoles()
    {
        EndpointFunctions functions = new EndpointFunctions();

        // Walk all methods and see what is found
        Map<Role, ActualMethod> actualMap = new HashMap<>();

        for (Method method : testcase.pojo.getDeclaredMethods())
        {
            EndpointFunctions.ArgRole argRole = functions.findArgRole(method);
            if (argRole != null)
            {
                ActualMethod actualMethod = new ActualMethod();
                actualMethod.argRole = argRole;
                actualMethod.method = method;

                actualMap.put(argRole.role, actualMethod);
            }
        }

        // Ensure that actual matches found
        for (Map.Entry<Role, ExpectedMethod> expected : testcase.expectedRoles.entrySet())
        {
            // Expected
            Role expectedRole = expected.getKey();
            ExpectedMethod expectedMethod = expected.getValue();

            // Actual
            ActualMethod actual = actualMap.get(expectedRole);
            assertThat("Role", actual.argRole.role, is(expectedRole));
            assertThat("Method.name", actual.method.getName(), is(expectedMethod.methodName));

            // validate parameters
            Class<?> actualParams[] = actual.method.getParameterTypes();
            Class<?> expectedParams[] = expectedMethod.params;

            assertThat("param count", actualParams.length, is(expectedParams.length));
            for (int i = 0; i < actualParams.length; i++)
            {
                assertThat("Param[" + i + "]", actualParams[i], equalTo(expectedParams[i]));
            }
        }
    }
}
