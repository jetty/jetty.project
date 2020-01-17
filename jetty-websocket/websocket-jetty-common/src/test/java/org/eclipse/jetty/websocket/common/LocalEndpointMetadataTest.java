//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.common;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.common.endpoints.annotated.AnnotatedBinaryArraySocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.AnnotatedBinaryStreamSocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.AnnotatedTextSocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.AnnotatedTextStreamSocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.BadBinarySignatureSocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.BadDuplicateBinarySocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.BadDuplicateFrameSocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.BadTextSignatureSocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.FrameSocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.MyEchoBinarySocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.MyEchoSocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.MyStatelessEchoSocket;
import org.eclipse.jetty.websocket.common.endpoints.annotated.NoopSocket;
import org.eclipse.jetty.websocket.common.endpoints.listeners.ListenerBasicSocket;
import org.eclipse.jetty.websocket.common.endpoints.listeners.ListenerFrameSocket;
import org.eclipse.jetty.websocket.common.message.ByteArrayMessageSink;
import org.eclipse.jetty.websocket.common.message.InputStreamMessageSink;
import org.eclipse.jetty.websocket.common.message.ReaderMessageSink;
import org.eclipse.jetty.websocket.common.message.StringMessageSink;
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

    private JettyWebSocketFrameHandlerFactory endpointFactory = new JettyWebSocketFrameHandlerFactory(container);

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
        Exception e = assertThrows(InvalidWebSocketException.class, () -> createMetadata(BadDuplicateBinarySocket.class));
        assertThat(e.getMessage(), allOf(containsString("Cannot replace previously assigned"), containsString("BINARY Handler")));
    }

    /**
     * Test Case for bad declaration (duplicate frame type methods)
     */
    @Test
    public void testAnnotatedBadDuplicateFrameSocket() throws Exception
    {
        // Should toss exception
        Exception e = assertThrows(InvalidWebSocketException.class, () -> createMetadata(BadDuplicateFrameSocket.class));
        assertThat(e.getMessage(), containsString("Duplicate @OnWebSocketFrame"));
    }

    /**
     * Test Case for bad declaration a method with a non-void return type
     */
    @Test
    public void testAnnotatedBadSignatureNonVoidReturn() throws Exception
    {
        // Should toss exception
        Exception e = assertThrows(InvalidWebSocketException.class, () -> createMetadata(BadBinarySignatureSocket.class));
        assertThat(e.getMessage(), containsString("must be void"));
    }

    /**
     * Test Case for bad declaration a method with a public static method
     */
    @Test
    public void testAnnotatedBadSignatureStatic() throws Exception
    {
        // Should toss exception
        Exception e = assertThrows(InvalidWebSocketException.class, () -> createMetadata(BadTextSignatureSocket.class));
        assertThat(e.getMessage(), containsString("must not be static"));
    }

    /**
     * Test Case for socket for binary array messages
     */
    @Test
    public void testAnnotatedBinaryArraySocket() throws Exception
    {
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(AnnotatedBinaryArraySocket.class);

        String classId = AnnotatedBinaryArraySocket.class.getSimpleName();

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
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(AnnotatedBinaryStreamSocket.class);

        String classId = AnnotatedBinaryStreamSocket.class.getSimpleName();

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
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(MyEchoBinarySocket.class);

        String classId = MyEchoBinarySocket.class.getSimpleName();

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
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(MyEchoSocket.class);

        String classId = MyEchoSocket.class.getSimpleName();

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
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(MyStatelessEchoSocket.class);

        String classId = MyStatelessEchoSocket.class.getSimpleName();

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
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(NoopSocket.class);

        String classId = NoopSocket.class.getSimpleName();

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
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(FrameSocket.class);

        String classId = FrameSocket.class.getSimpleName();

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
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(AnnotatedTextSocket.class);

        String classId = AnnotatedTextSocket.class.getSimpleName();

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
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(AnnotatedTextStreamSocket.class);

        String classId = AnnotatedTextStreamSocket.class.getSimpleName();

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
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(ListenerBasicSocket.class);

        String classId = ListenerBasicSocket.class.getSimpleName();

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
        JettyWebSocketFrameHandlerMetadata metadata = createMetadata(ListenerFrameSocket.class);

        String classId = ListenerFrameSocket.class.getSimpleName();

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
