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

package org.eclipse.jetty.websocket.jsr356.server;

import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;
import javax.websocket.CloseReason;
import javax.websocket.PongMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointScanner;
import org.eclipse.jetty.websocket.jsr356.annotations.JsrCallable;
import org.eclipse.jetty.websocket.jsr356.server.samples.BasicBinaryMessageByteBufferSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.BasicCloseReasonSessionSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.BasicCloseReasonSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.BasicCloseSessionReasonSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.BasicCloseSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.BasicErrorSessionSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.BasicErrorSessionThrowableSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.BasicErrorSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.BasicErrorThrowableSessionSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.BasicErrorThrowableSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.BasicOpenSessionSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.BasicOpenSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.BasicPongMessageSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.BasicTextMessageStringSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.StatelessTextMessageStringSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.beans.DateTextSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.primitives.BooleanObjectTextSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.primitives.BooleanTextSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.primitives.ByteObjectTextSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.primitives.ByteTextSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.primitives.CharTextSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.primitives.CharacterObjectTextSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.primitives.DoubleObjectTextSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.primitives.DoubleTextSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.primitives.FloatObjectTextSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.primitives.FloatTextSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.primitives.IntTextSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.primitives.IntegerObjectTextSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.primitives.ShortObjectTextSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.primitives.ShortTextSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.streaming.ReaderParamSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.streaming.StringReturnReaderParamSocket;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test {@link AnnotatedEndpointScanner} against various simple, 1 method {@link ServerEndpoint} annotated classes with valid signatures.
 */
public class ServerAnnotatedEndpointScannerGoodSignaturesTest
{
    public static Stream<Arguments> scenarios() throws Exception
    {

        Field fOpen = findFieldRef(AnnotatedServerEndpointMetadata.class, "onOpen");
        Field fClose = findFieldRef(AnnotatedServerEndpointMetadata.class, "onClose");
        Field fError = findFieldRef(AnnotatedServerEndpointMetadata.class, "onError");
        Field fText = findFieldRef(AnnotatedServerEndpointMetadata.class, "onText");
        Field fTextStream = findFieldRef(AnnotatedServerEndpointMetadata.class, "onTextStream");
        Field fBinary = findFieldRef(AnnotatedServerEndpointMetadata.class, "onBinary");
        @SuppressWarnings("unused")
        Field fBinaryStream = findFieldRef(AnnotatedServerEndpointMetadata.class, "onBinaryStream");
        Field fPong = findFieldRef(AnnotatedServerEndpointMetadata.class, "onPong");

        List<Scenario> data = new ArrayList<>();
        // -- Open Events
        data.add(new Scenario(BasicOpenSocket.class, fOpen));
        data.add(new Scenario(BasicOpenSessionSocket.class, fOpen, Session.class));
        // -- Close Events
        data.add(new Scenario(BasicCloseSocket.class, fClose));
        data.add(new Scenario(BasicCloseReasonSocket.class, fClose, CloseReason.class));
        data.add(new Scenario(BasicCloseReasonSessionSocket.class, fClose, CloseReason.class, Session.class));
        data.add(new Scenario(BasicCloseSessionReasonSocket.class, fClose, Session.class, CloseReason.class));
        // -- Error Events
        data.add(new Scenario(BasicErrorSocket.class, fError));
        data.add(new Scenario(BasicErrorSessionSocket.class, fError, Session.class));
        data.add(new Scenario(BasicErrorSessionThrowableSocket.class, fError, Session.class, Throwable.class));
        data.add(new Scenario(BasicErrorThrowableSocket.class, fError, Throwable.class));
        data.add(new Scenario(BasicErrorThrowableSessionSocket.class, fError, Throwable.class, Session.class));
        // -- Text Events
        data.add(new Scenario(BasicTextMessageStringSocket.class, fText, String.class));
        data.add(new Scenario(StatelessTextMessageStringSocket.class, fText, Session.class, String.class));
        // -- Primitives
        data.add(new Scenario(BooleanTextSocket.class, fText, Boolean.TYPE));
        data.add(new Scenario(BooleanObjectTextSocket.class, fText, Boolean.class));
        data.add(new Scenario(ByteTextSocket.class, fText, Byte.TYPE));
        data.add(new Scenario(ByteObjectTextSocket.class, fText, Byte.class));
        data.add(new Scenario(CharTextSocket.class, fText, Character.TYPE));
        data.add(new Scenario(CharacterObjectTextSocket.class, fText, Character.class));
        data.add(new Scenario(DoubleTextSocket.class, fText, Double.TYPE));
        data.add(new Scenario(DoubleObjectTextSocket.class, fText, Double.class));
        data.add(new Scenario(FloatTextSocket.class, fText, Float.TYPE));
        data.add(new Scenario(FloatObjectTextSocket.class, fText, Float.class));
        data.add(new Scenario(IntTextSocket.class, fText, Integer.TYPE));
        data.add(new Scenario(IntegerObjectTextSocket.class, fText, Integer.class));
        data.add(new Scenario(ShortTextSocket.class, fText, Short.TYPE));
        data.add(new Scenario(ShortObjectTextSocket.class, fText, Short.class));
        // -- Beans
        data.add(new Scenario(DateTextSocket.class, fText, Date.class));
        // -- Reader Events
        data.add(new Scenario(ReaderParamSocket.class, fTextStream, Reader.class, String.class));
        data.add(new Scenario(StringReturnReaderParamSocket.class, fTextStream, Reader.class, String.class));
        // -- Binary Events
        data.add(new Scenario(BasicBinaryMessageByteBufferSocket.class, fBinary, ByteBuffer.class));
        // -- Pong Events
        data.add(new Scenario(BasicPongMessageSocket.class, fPong, PongMessage.class));
        // @formatter:on

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
        WebSocketContainerScope container = new SimpleContainerScope(WebSocketPolicy.newClientPolicy());
        AnnotatedServerEndpointMetadata metadata = new AnnotatedServerEndpointMetadata(container, scenario.pojo, null);
        AnnotatedEndpointScanner<ServerEndpoint, ServerEndpointConfig> scanner = new AnnotatedEndpointScanner<>(metadata);
        scanner.scan();

        assertThat("Metadata", metadata, notNullValue());

        JsrCallable method = (JsrCallable)scenario.metadataField.get(metadata);
        assertThat(scenario.metadataField.toString(), method, notNullValue());
        int len = scenario.expectedParameters.length;
        for (int i = 0; i < len; i++)
        {
            Class<?> expectedParam = scenario.expectedParameters[i];
            Class<?> actualParam = method.getParamTypes()[i];

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
