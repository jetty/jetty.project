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

package org.eclipse.jetty.websocket.jsr356.endpoints;

import static org.hamcrest.Matchers.notNullValue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.PongMessage;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointMetadata;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointScanner;
import org.eclipse.jetty.websocket.jsr356.annotations.JsrCallable;
import org.eclipse.jetty.websocket.jsr356.client.AnnotatedClientEndpointMetadata;
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
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Test {@link AnnotatedEndpointScanner} against various valid, simple, 1 method {@link ClientEndpoint} annotated classes with valid signatures.
 */
@RunWith(Parameterized.class)
public class ClientAnnotatedEndpointScanner_GoodSignaturesTest
{
    public static class Case
    {
        public static void add(List<Case[]> data, Class<?> pojo, Field metadataField, Class<?>... expectedParams)
        {
            data.add(new Case[]
            { new Case(pojo,metadataField,expectedParams) });
        }

        // The websocket pojo to test against
        Class<?> pojo;
        // The JsrAnnotatedMetadata field that should be populated
        Field metadataField;
        // The expected parameters for the Callable found by the scanner
        Class<?> expectedParameters[];

        public Case(Class<?> pojo, Field metadataField, Class<?>... expectedParams)
        {
            this.pojo = pojo;
            this.metadataField = metadataField;
            this.expectedParameters = expectedParams;
        }
    }

    private static ClientContainer container = new ClientContainer();

    @Parameters
    public static Collection<Case[]> data() throws Exception
    {
        List<Case[]> data = new ArrayList<>();
        Field fOpen = findFieldRef(AnnotatedEndpointMetadata.class,"onOpen");
        Field fClose = findFieldRef(AnnotatedEndpointMetadata.class,"onClose");
        Field fError = findFieldRef(AnnotatedEndpointMetadata.class,"onError");
        Field fText = findFieldRef(AnnotatedEndpointMetadata.class,"onText");
        Field fBinary = findFieldRef(AnnotatedEndpointMetadata.class,"onBinary");
        Field fBinaryStream = findFieldRef(AnnotatedEndpointMetadata.class,"onBinaryStream");
        Field fPong = findFieldRef(AnnotatedEndpointMetadata.class,"onPong");

        // @formatter:off
        // -- Open Events
        Case.add(data, BasicOpenSocket.class, fOpen);
        Case.add(data, BasicOpenSessionSocket.class, fOpen, Session.class);
        // -- Close Events
        Case.add(data, CloseSocket.class, fClose);
        Case.add(data, CloseReasonSocket.class, fClose, CloseReason.class);
        Case.add(data, CloseReasonSessionSocket.class, fClose, CloseReason.class, Session.class);
        Case.add(data, CloseSessionReasonSocket.class, fClose, Session.class, CloseReason.class);
        // -- Error Events
        Case.add(data, BasicErrorSocket.class, fError);
        Case.add(data, BasicErrorSessionSocket.class, fError, Session.class);
        Case.add(data, BasicErrorSessionThrowableSocket.class, fError, Session.class, Throwable.class);
        Case.add(data, BasicErrorThrowableSocket.class, fError, Throwable.class);
        Case.add(data, BasicErrorThrowableSessionSocket.class, fError, Throwable.class, Session.class);
        // -- Text Events
        Case.add(data, BasicTextMessageStringSocket.class, fText, String.class);
        // -- Binary Events
        Case.add(data, BasicBinaryMessageByteBufferSocket.class, fBinary, ByteBuffer.class);
        // -- Pong Events
        Case.add(data, BasicPongMessageSocket.class, fPong, PongMessage.class);
        // -- InputStream Events
        Case.add(data, BasicInputStreamSocket.class, fBinaryStream, InputStream.class);
        Case.add(data, BasicInputStreamWithThrowableSocket.class, fBinaryStream, InputStream.class);
        // @formatter:on

        // TODO: validate return types

        return data;
    }

    private static Field findFieldRef(Class<?> clazz, String fldName) throws Exception
    {
        return clazz.getField(fldName);
    }

    private Case testcase;

    public ClientAnnotatedEndpointScanner_GoodSignaturesTest(Case testcase)
    {
        this.testcase = testcase;
    }

    @Test
    public void testScan_Basic() throws Exception
    {
        AnnotatedClientEndpointMetadata metadata = new AnnotatedClientEndpointMetadata(container,testcase.pojo);
        AnnotatedEndpointScanner<ClientEndpoint, ClientEndpointConfig> scanner = new AnnotatedEndpointScanner<>(metadata);
        scanner.scan();

        Assert.assertThat("Metadata",metadata,notNullValue());

        JsrCallable cm = (JsrCallable)testcase.metadataField.get(metadata);
        Assert.assertThat(testcase.metadataField.toString(),cm,notNullValue());
        int len = testcase.expectedParameters.length;
        for (int i = 0; i < len; i++)
        {
            Class<?> expectedParam = testcase.expectedParameters[i];
            Class<?> actualParam = cm.getParamTypes()[i];

            Assert.assertTrue("Parameter[" + i + "] - expected:[" + expectedParam + "], actual:[" + actualParam + "]",actualParam.equals(expectedParam));
        }
    }
}
