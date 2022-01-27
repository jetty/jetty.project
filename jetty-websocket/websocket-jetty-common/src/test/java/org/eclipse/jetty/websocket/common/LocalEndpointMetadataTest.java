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

package org.eclipse.jetty.websocket.common;

import org.eclipse.jetty.websocket.api.exceptions.InvalidWebSocketException;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.exception.DuplicateAnnotationException;
import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.internal.messages.ByteArrayMessageSink;
import org.eclipse.jetty.websocket.core.internal.messages.InputStreamMessageSink;
import org.eclipse.jetty.websocket.core.internal.messages.ReaderMessageSink;
import org.eclipse.jetty.websocket.core.internal.messages.StringMessageSink;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class LocalEndpointMetadataTest
{
    public static final Matcher<Object> EXISTS = notNullValue();
    public static DummyContainer container;

    @BeforeAll
    public static void startContainer() throws Exception
    {
        container = new DummyContainer();
        container.start();
    }

    @AfterAll
    public static void stopContainer() throws Exception
    {
        container.stop();
    }

    private final WebSocketComponents components = new WebSocketComponents();
    private final JettyWebSocketFrameHandlerFactory endpointFactory = new JettyWebSocketFrameHandlerFactory(container, components);

    private JettyWebSocketFrameHandlerMetadata createMetadata(Class<?> endpointClass)
    {
        return endpointFactory.createMetadata(endpointClass);
    }

    /**
     * Test Case for bad declaration (duplicate OnWebSocketBinary declarations)
     */
    @Test
    public void testAnnotatedBadDuplicateBinarySocket() throws Exception
    {
        // Should toss exception
        Exception e = assertThrows(InvalidWebSocketException.class, () -> createMetadata(EndPoints.BadDuplicateBinarySocket.class));
        assertThat(e.getMessage(), allOf(containsString("Cannot replace previously assigned"), containsString("BINARY Handler")));
    }

    /**
     * Test Case for bad declaration (duplicate frame type methods)
     */
    @Test
    public void testAnnotatedBadDuplicateFrameSocket() throws Exception
    {
        // Should toss exception
        Exception e = assertThrows(DuplicateAnnotationException.class, () -> createMetadata(EndPoints.BadDuplicateFrameSocket.class));
        assertThat(e.getMessage(), containsString("Duplicate @OnWebSocketFrame"));
    }

    /**
     * Test Case for bad declaration a method with a non-void return type
     */
    @Test
    public void testAnnotatedBadSignatureNonVoidReturn() throws Exception
    {
        // Should toss exception
        Exception e = assertThrows(InvalidSignatureException.class, () -> createMetadata(EndPoints.BadBinarySignatureSocket.class));
        assertThat(e.getMessage(), containsString("must be void"));
    }

    /**
     * Test Case for bad declaration a method with a public static method
     */
    @Test
    public void testAnnotatedBadSignatureStatic() throws Exception
    {
        // Should toss exception
        Exception e = assertThrows(InvalidSignatureException.class, () -> createMetadata(EndPoints.BadTextSignatureSocket.class));
        assertThat(e.getMessage(), containsString("must not be static"));
    }

    /**
     * Test Case for socket for binary array messages
     */
    @Test
    public void testAnnotatedBinaryArraySocket() throws Exception
    {
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(EndPoints.AnnotatedBinaryArraySocket.class);

        String classId = EndPoints.AnnotatedBinaryArraySocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), EXISTS);
        assertThat(classId + ".binarySink", metadata.getBinarySink(), equalTo(ByteArrayMessageSink.class));

        assertThat(classId + ".textHandle", metadata.getTextHandle(), nullValue());
        assertThat(classId + ".textSink", metadata.getTextSink(), nullValue());

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), EXISTS);
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), EXISTS);
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), nullValue());

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), nullValue());
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }

    /**
     * Test Case for socket for binary stream messages
     */
    @Test
    public void testAnnotatedBinaryStreamSocket() throws Exception
    {
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(EndPoints.AnnotatedBinaryStreamSocket.class);

        String classId = EndPoints.AnnotatedBinaryStreamSocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), EXISTS);
        assertThat(classId + ".binarySink", metadata.getBinarySink(), equalTo(InputStreamMessageSink.class));

        assertThat(classId + ".textHandle", metadata.getTextHandle(), nullValue());
        assertThat(classId + ".textSink", metadata.getTextSink(), nullValue());

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), EXISTS);
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), EXISTS);
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), nullValue());

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), nullValue());
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }

    /**
     * Test Case for no exceptions and 4 methods (3 methods from parent)
     */
    @Test
    public void testAnnotatedMyEchoBinarySocket() throws Exception
    {
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(EndPoints.MyEchoBinarySocket.class);

        String classId = EndPoints.MyEchoBinarySocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), EXISTS);
        assertThat(classId + ".binarySink", metadata.getBinarySink(), equalTo(ByteArrayMessageSink.class));

        assertThat(classId + ".textHandle", metadata.getTextHandle(), EXISTS);
        assertThat(classId + ".textSink", metadata.getTextSink(), equalTo(StringMessageSink.class));

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), EXISTS);
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), EXISTS);
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), nullValue());

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), nullValue());
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }

    /**
     * Test Case for no exceptions and 3 methods
     */
    @Test
    public void testAnnotatedMyEchoSocket() throws Exception
    {
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(EndPoints.MyEchoSocket.class);

        String classId = EndPoints.MyEchoSocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), nullValue());
        assertThat(classId + ".binarySink", metadata.getBinarySink(), nullValue());

        assertThat(classId + ".textHandle", metadata.getTextHandle(), EXISTS);
        assertThat(classId + ".textSink", metadata.getTextSink(), equalTo(StringMessageSink.class));

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), EXISTS);
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), EXISTS);
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), nullValue());

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), nullValue());
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }

    /**
     * Test Case for annotated for text messages w/connection param
     */
    @Test
    public void testAnnotatedMyStatelessEchoSocket() throws Exception
    {
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(EndPoints.MyStatelessEchoSocket.class);

        String classId = EndPoints.MyStatelessEchoSocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), nullValue());
        assertThat(classId + ".binarySink", metadata.getBinarySink(), nullValue());

        assertThat(classId + ".textHandle", metadata.getTextHandle(), EXISTS);
        assertThat(classId + ".textSink", metadata.getTextSink(), equalTo(StringMessageSink.class));

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), nullValue());
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), nullValue());
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), nullValue());

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), nullValue());
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }

    /**
     * Test Case for no exceptions and no methods
     */
    @Test
    public void testAnnotatedNoop() throws Exception
    {
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(EndPoints.NoopSocket.class);

        String classId = EndPoints.NoopSocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), nullValue());
        assertThat(classId + ".binarySink", metadata.getBinarySink(), nullValue());

        assertThat(classId + ".textHandle", metadata.getTextHandle(), nullValue());
        assertThat(classId + ".textSink", metadata.getTextSink(), nullValue());

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), nullValue());
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), nullValue());
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), nullValue());

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), nullValue());
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }

    /**
     * Test Case for no exceptions and 1 methods
     */
    @Test
    public void testAnnotatedOnFrame() throws Exception
    {
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(EndPoints.FrameSocket.class);

        String classId = EndPoints.FrameSocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), nullValue());
        assertThat(classId + ".binarySink", metadata.getBinarySink(), nullValue());

        assertThat(classId + ".textHandle", metadata.getTextHandle(), nullValue());
        assertThat(classId + ".textSink", metadata.getTextSink(), nullValue());

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), nullValue());
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), nullValue());
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), nullValue());

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), EXISTS);
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }

    /**
     * Test Case for socket for simple text messages
     */
    @Test
    public void testAnnotatedTextSocket() throws Exception
    {
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(EndPoints.AnnotatedTextSocket.class);

        String classId = EndPoints.AnnotatedTextSocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), nullValue());
        assertThat(classId + ".binarySink", metadata.getBinarySink(), nullValue());

        assertThat(classId + ".textHandle", metadata.getTextHandle(), EXISTS);
        assertThat(classId + ".textSink", metadata.getTextSink(), equalTo(StringMessageSink.class));

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), EXISTS);
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), EXISTS);
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), EXISTS);

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), nullValue());
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }

    /**
     * Test Case for socket for text stream messages
     */
    @Test
    public void testAnnotatedTextStreamSocket() throws Exception
    {
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(EndPoints.AnnotatedTextStreamSocket.class);

        String classId = EndPoints.AnnotatedTextStreamSocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), nullValue());
        assertThat(classId + ".binarySink", metadata.getBinarySink(), nullValue());

        assertThat(classId + ".textHandle", metadata.getTextHandle(), EXISTS);
        assertThat(classId + ".textSink", metadata.getTextSink(), equalTo(ReaderMessageSink.class));

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), EXISTS);
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), EXISTS);
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), nullValue());

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), nullValue());
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }

    /**
     * Test Case for socket using {@link org.eclipse.jetty.websocket.api.WebSocketListener}
     */
    @Test
    public void testListenerBasicSocket()
    {
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(EndPoints.ListenerBasicSocket.class);

        String classId = EndPoints.ListenerBasicSocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), EXISTS);
        assertThat(classId + ".binarySink", metadata.getBinarySink(), equalTo(ByteArrayMessageSink.class));

        assertThat(classId + ".textHandle", metadata.getTextHandle(), EXISTS);
        assertThat(classId + ".textSink", metadata.getTextSink(), equalTo(StringMessageSink.class));

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), EXISTS);
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), EXISTS);
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), EXISTS);

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), nullValue());
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }

    /**
     * Test Case for socket using {@link org.eclipse.jetty.websocket.api.WebSocketFrameListener}
     */
    @Test
    public void testListenerFrameSocket() throws Exception
    {
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(EndPoints.ListenerFrameSocket.class);

        String classId = EndPoints.ListenerFrameSocket.class.getSimpleName();

        assertThat(classId + ".binaryHandle", metadata.getBinaryHandle(), nullValue());
        assertThat(classId + ".binarySink", metadata.getBinarySink(), nullValue());

        assertThat(classId + ".textHandle", metadata.getTextHandle(), nullValue());
        assertThat(classId + ".textSink", metadata.getTextSink(), nullValue());

        assertThat(classId + ".openHandle", metadata.getOpenHandle(), EXISTS);
        assertThat(classId + ".closeHandle", metadata.getCloseHandle(), EXISTS);
        assertThat(classId + ".errorHandle", metadata.getErrorHandle(), EXISTS);

        assertThat(classId + ".frameHandle", metadata.getFrameHandle(), EXISTS);
        assertThat(classId + ".pingHandle", metadata.getPingHandle(), nullValue());
        assertThat(classId + ".pongHandle", metadata.getPongHandle(), nullValue());
    }
}
