//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test {@link AnnotatedEndpointScanner} against various valid, simple, 1 method {@link ClientEndpoint} annotated classes with valid signatures.
 */
public class ClientAnnotatedEndpointScannerGoodSignaturesTest
{

    private static ClientContainer container = new ClientContainer();

    public static Stream<Arguments> scenarios() throws Exception
    {
        Field fOpen = findFieldRef(AnnotatedEndpointMetadata.class, "onOpen");
        Field fClose = findFieldRef(AnnotatedEndpointMetadata.class, "onClose");
        Field fError = findFieldRef(AnnotatedEndpointMetadata.class, "onError");
        Field fText = findFieldRef(AnnotatedEndpointMetadata.class, "onText");
        Field fBinary = findFieldRef(AnnotatedEndpointMetadata.class, "onBinary");
        Field fBinaryStream = findFieldRef(AnnotatedEndpointMetadata.class, "onBinaryStream");
        Field fPong = findFieldRef(AnnotatedEndpointMetadata.class, "onPong");

        List<Scenario> data = new ArrayList<>();

        // -- Open Events
        data.add(new Scenario(BasicOpenSocket.class, fOpen));
        data.add(new Scenario(BasicOpenSessionSocket.class, fOpen, Session.class));
        // -- Close Events
        data.add(new Scenario(CloseSocket.class, fClose));
        data.add(new Scenario(CloseReasonSocket.class, fClose, CloseReason.class));
        data.add(new Scenario(CloseReasonSessionSocket.class, fClose, CloseReason.class, Session.class));
        data.add(new Scenario(CloseSessionReasonSocket.class, fClose, Session.class, CloseReason.class));
        // -- Error Events
        data.add(new Scenario(BasicErrorSocket.class, fError));
        data.add(new Scenario(BasicErrorSessionSocket.class, fError, Session.class));
        data.add(new Scenario(BasicErrorSessionThrowableSocket.class, fError, Session.class, Throwable.class));
        data.add(new Scenario(BasicErrorThrowableSocket.class, fError, Throwable.class));
        data.add(new Scenario(BasicErrorThrowableSessionSocket.class, fError, Throwable.class, Session.class));
        // -- Text Events
        data.add(new Scenario(BasicTextMessageStringSocket.class, fText, String.class));
        // -- Binary Events
        data.add(new Scenario(BasicBinaryMessageByteBufferSocket.class, fBinary, ByteBuffer.class));
        // -- Pong Events
        data.add(new Scenario(BasicPongMessageSocket.class, fPong, PongMessage.class));
        // -- InputStream Events
        data.add(new Scenario(BasicInputStreamSocket.class, fBinaryStream, InputStream.class));
        data.add(new Scenario(BasicInputStreamWithThrowableSocket.class, fBinaryStream, InputStream.class));

        // TODO: validate return types

        return data.stream().map(Arguments::of);
    }

    private static Field findFieldRef(Class<?> clazz, String fldName) throws Exception
    {
        return clazz.getField(fldName);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testScanBasic(Scenario scenario) throws Exception
    {
        AnnotatedClientEndpointMetadata metadata = new AnnotatedClientEndpointMetadata(container, scenario.pojo);
        AnnotatedEndpointScanner<ClientEndpoint, ClientEndpointConfig> scanner = new AnnotatedEndpointScanner<>(metadata);
        scanner.scan();

        assertThat("Metadata", metadata, notNullValue());

        JsrCallable cm = (JsrCallable)scenario.metadataField.get(metadata);
        assertThat(scenario.metadataField.toString(), cm, notNullValue());
        int len = scenario.expectedParameters.length;
        for (int i = 0; i < len; i++)
        {
            Class<?> expectedParam = scenario.expectedParameters[i];
            Class<?> actualParam = cm.getParamTypes()[i];

            assertTrue(actualParam.equals(expectedParam), "Parameter[" + i + "] - expected:[" + expectedParam + "], actual:[" + actualParam + "]");
        }
    }

    public static class Scenario
    {
        // The websocket pojo to test against
        Class<?> pojo;
        // The JsrAnnotatedMetadata field that should be populated
        Field metadataField;
        // The expected parameters for the Callable found by the scanner
        Class<?>[] expectedParameters;

        public Scenario(Class<?> pojo, Field metadataField, Class<?>... expectedParams)
        {
            this.pojo = pojo;
            this.metadataField = metadataField;
            this.expectedParameters = expectedParams;
        }

        @Override
        public String toString()
        {
            return this.pojo.getSimpleName();
        }
    }
}
