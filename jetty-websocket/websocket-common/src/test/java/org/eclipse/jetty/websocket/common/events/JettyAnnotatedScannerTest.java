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

package org.eclipse.jetty.websocket.common.events;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.common.annotations.BadBinarySignatureSocket;
import org.eclipse.jetty.websocket.common.annotations.BadDuplicateBinarySocket;
import org.eclipse.jetty.websocket.common.annotations.BadDuplicateFrameSocket;
import org.eclipse.jetty.websocket.common.annotations.BadTextSignatureSocket;
import org.eclipse.jetty.websocket.common.annotations.FrameSocket;
import org.eclipse.jetty.websocket.common.annotations.MyEchoBinarySocket;
import org.eclipse.jetty.websocket.common.annotations.MyEchoSocket;
import org.eclipse.jetty.websocket.common.annotations.MyStatelessEchoSocket;
import org.eclipse.jetty.websocket.common.annotations.NoopSocket;
import org.eclipse.jetty.websocket.common.events.annotated.CallableMethod;
import org.junit.Assert;
import org.junit.Test;

import examples.AnnotatedBinaryArraySocket;
import examples.AnnotatedBinaryStreamSocket;
import examples.AnnotatedTextSocket;
import examples.AnnotatedTextStreamSocket;

public class JettyAnnotatedScannerTest
{
    private void assertHasEventMethod(String message, CallableMethod actual)
    {
        Assert.assertThat(message + " CallableMethod",actual,notNullValue());

        Assert.assertThat(message + " CallableMethod.pojo",actual.getPojo(),notNullValue());
        Assert.assertThat(message + " CallableMethod.method",actual.getMethod(),notNullValue());
    }

    private void assertNoEventMethod(String message, CallableMethod actual)
    {
        Assert.assertThat(message + " CallableMethod",actual,nullValue());
    }

    /**
     * Test Case for bad declaration (duplicate OnWebSocketBinary declarations)
     */
    @Test
    public void testAnnotatedBadDuplicateBinarySocket()
    {
        JettyAnnotatedScanner impl = new JettyAnnotatedScanner();
        try
        {
            // Should toss exception
            impl.scan(BadDuplicateBinarySocket.class);
            Assert.fail("Should have thrown " + InvalidWebSocketException.class);
        }
        catch (InvalidWebSocketException e)
        {
            // Validate that we have clear error message to the developer
            Assert.assertThat(e.getMessage(),containsString("Duplicate @OnWebSocketMessage declaration"));
        }
    }

    /**
     * Test Case for bad declaration (duplicate frame type methods)
     */
    @Test
    public void testAnnotatedBadDuplicateFrameSocket()
    {
        JettyAnnotatedScanner impl = new JettyAnnotatedScanner();
        try
        {
            // Should toss exception
            impl.scan(BadDuplicateFrameSocket.class);
            Assert.fail("Should have thrown " + InvalidWebSocketException.class);
        }
        catch (InvalidWebSocketException e)
        {
            // Validate that we have clear error message to the developer
            Assert.assertThat(e.getMessage(),containsString("Duplicate @OnWebSocketFrame"));
        }
    }

    /**
     * Test Case for bad declaration a method with a non-void return type
     */
    @Test
    public void testAnnotatedBadSignature_NonVoidReturn()
    {
        JettyAnnotatedScanner impl = new JettyAnnotatedScanner();
        try
        {
            // Should toss exception
            impl.scan(BadBinarySignatureSocket.class);
            Assert.fail("Should have thrown " + InvalidWebSocketException.class);
        }
        catch (InvalidWebSocketException e)
        {
            // Validate that we have clear error message to the developer
            Assert.assertThat(e.getMessage(),containsString("must be void"));
        }
    }

    /**
     * Test Case for bad declaration a method with a public static method
     */
    @Test
    public void testAnnotatedBadSignature_Static()
    {
        JettyAnnotatedScanner impl = new JettyAnnotatedScanner();
        try
        {
            // Should toss exception
            impl.scan(BadTextSignatureSocket.class);
            Assert.fail("Should have thrown " + InvalidWebSocketException.class);
        }
        catch (InvalidWebSocketException e)
        {
            // Validate that we have clear error message to the developer
            Assert.assertThat(e.getMessage(),containsString("may not be static"));
        }
    }

    /**
     * Test Case for socket for binary array messages
     */
    @Test
    public void testAnnotatedBinaryArraySocket()
    {
        JettyAnnotatedScanner impl = new JettyAnnotatedScanner();
        JettyAnnotatedMetadata metadata = impl.scan(AnnotatedBinaryArraySocket.class);

        String classId = AnnotatedBinaryArraySocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,metadata,notNullValue());

        assertHasEventMethod(classId + ".onBinary",metadata.onBinary);
        assertHasEventMethod(classId + ".onClose",metadata.onClose);
        assertHasEventMethod(classId + ".onConnect",metadata.onConnect);
        assertNoEventMethod(classId + ".onException",metadata.onError);
        assertNoEventMethod(classId + ".onText",metadata.onText);
        assertNoEventMethod(classId + ".onFrame",metadata.onFrame);

        Assert.assertFalse(classId + ".onBinary.isSessionAware",metadata.onBinary.isSessionAware());
        Assert.assertFalse(classId + ".onBinary.isStreaming",metadata.onBinary.isStreaming());
    }

    /**
     * Test Case for socket for binary stream messages
     */
    @Test
    public void testAnnotatedBinaryStreamSocket()
    {
        JettyAnnotatedScanner impl = new JettyAnnotatedScanner();
        JettyAnnotatedMetadata metadata = impl.scan(AnnotatedBinaryStreamSocket.class);

        String classId = AnnotatedBinaryStreamSocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,metadata,notNullValue());

        assertHasEventMethod(classId + ".onBinary",metadata.onBinary);
        assertHasEventMethod(classId + ".onClose",metadata.onClose);
        assertHasEventMethod(classId + ".onConnect",metadata.onConnect);
        assertNoEventMethod(classId + ".onException",metadata.onError);
        assertNoEventMethod(classId + ".onText",metadata.onText);
        assertNoEventMethod(classId + ".onFrame",metadata.onFrame);

        Assert.assertFalse(classId + ".onBinary.isSessionAware",metadata.onBinary.isSessionAware());
        Assert.assertTrue(classId + ".onBinary.isStreaming",metadata.onBinary.isStreaming());
    }

    /**
     * Test Case for no exceptions and 4 methods (3 methods from parent)
     */
    @Test
    public void testAnnotatedMyEchoBinarySocket()
    {
        JettyAnnotatedScanner impl = new JettyAnnotatedScanner();
        JettyAnnotatedMetadata metadata = impl.scan(MyEchoBinarySocket.class);

        String classId = MyEchoBinarySocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,metadata,notNullValue());

        assertHasEventMethod(classId + ".onBinary",metadata.onBinary);
        assertHasEventMethod(classId + ".onClose",metadata.onClose);
        assertHasEventMethod(classId + ".onConnect",metadata.onConnect);
        assertNoEventMethod(classId + ".onException",metadata.onError);
        assertHasEventMethod(classId + ".onText",metadata.onText);
        assertNoEventMethod(classId + ".onFrame",metadata.onFrame);
    }

    /**
     * Test Case for no exceptions and 3 methods
     */
    @Test
    public void testAnnotatedMyEchoSocket()
    {
        JettyAnnotatedScanner impl = new JettyAnnotatedScanner();
        JettyAnnotatedMetadata metadata = impl.scan(MyEchoSocket.class);

        String classId = MyEchoSocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,metadata,notNullValue());

        assertNoEventMethod(classId + ".onBinary",metadata.onBinary);
        assertHasEventMethod(classId + ".onClose",metadata.onClose);
        assertHasEventMethod(classId + ".onConnect",metadata.onConnect);
        assertNoEventMethod(classId + ".onException",metadata.onError);
        assertHasEventMethod(classId + ".onText",metadata.onText);
        assertNoEventMethod(classId + ".onFrame",metadata.onFrame);
    }

    /**
     * Test Case for annotated for text messages w/connection param
     */
    @Test
    public void testAnnotatedMyStatelessEchoSocket()
    {
        JettyAnnotatedScanner impl = new JettyAnnotatedScanner();
        JettyAnnotatedMetadata metadata = impl.scan(MyStatelessEchoSocket.class);

        String classId = MyStatelessEchoSocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,metadata,notNullValue());

        assertNoEventMethod(classId + ".onBinary",metadata.onBinary);
        assertNoEventMethod(classId + ".onClose",metadata.onClose);
        assertNoEventMethod(classId + ".onConnect",metadata.onConnect);
        assertNoEventMethod(classId + ".onException",metadata.onError);
        assertHasEventMethod(classId + ".onText",metadata.onText);
        assertNoEventMethod(classId + ".onFrame",metadata.onFrame);

        Assert.assertTrue(classId + ".onText.isSessionAware",metadata.onText.isSessionAware());
        Assert.assertFalse(classId + ".onText.isStreaming",metadata.onText.isStreaming());
    }

    /**
     * Test Case for no exceptions and no methods
     */
    @Test
    public void testAnnotatedNoop()
    {
        JettyAnnotatedScanner impl = new JettyAnnotatedScanner();
        JettyAnnotatedMetadata metadata = impl.scan(NoopSocket.class);

        String classId = NoopSocket.class.getSimpleName();

        Assert.assertThat("Methods for " + classId,metadata,notNullValue());

        assertNoEventMethod(classId + ".onBinary",metadata.onBinary);
        assertNoEventMethod(classId + ".onClose",metadata.onClose);
        assertNoEventMethod(classId + ".onConnect",metadata.onConnect);
        assertNoEventMethod(classId + ".onException",metadata.onError);
        assertNoEventMethod(classId + ".onText",metadata.onText);
        assertNoEventMethod(classId + ".onFrame",metadata.onFrame);
    }

    /**
     * Test Case for no exceptions and 1 methods
     */
    @Test
    public void testAnnotatedOnFrame()
    {
        JettyAnnotatedScanner impl = new JettyAnnotatedScanner();
        JettyAnnotatedMetadata metadata = impl.scan(FrameSocket.class);

        String classId = FrameSocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,metadata,notNullValue());

        assertNoEventMethod(classId + ".onBinary",metadata.onBinary);
        assertNoEventMethod(classId + ".onClose",metadata.onClose);
        assertNoEventMethod(classId + ".onConnect",metadata.onConnect);
        assertNoEventMethod(classId + ".onException",metadata.onError);
        assertNoEventMethod(classId + ".onText",metadata.onText);
        assertHasEventMethod(classId + ".onFrame",metadata.onFrame);
    }

    /**
     * Test Case for socket for simple text messages
     */
    @Test
    public void testAnnotatedTextSocket()
    {
        JettyAnnotatedScanner impl = new JettyAnnotatedScanner();
        JettyAnnotatedMetadata metadata = impl.scan(AnnotatedTextSocket.class);

        String classId = AnnotatedTextSocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,metadata,notNullValue());

        assertNoEventMethod(classId + ".onBinary",metadata.onBinary);
        assertHasEventMethod(classId + ".onClose",metadata.onClose);
        assertHasEventMethod(classId + ".onConnect",metadata.onConnect);
        assertHasEventMethod(classId + ".onException",metadata.onError);
        assertHasEventMethod(classId + ".onText",metadata.onText);
        assertNoEventMethod(classId + ".onFrame",metadata.onFrame);

        Assert.assertFalse(classId + ".onText.isSessionAware",metadata.onText.isSessionAware());
        Assert.assertFalse(classId + ".onText.isStreaming",metadata.onText.isStreaming());
    }

    /**
     * Test Case for socket for text stream messages
     */
    @Test
    public void testAnnotatedTextStreamSocket()
    {
        JettyAnnotatedScanner impl = new JettyAnnotatedScanner();
        JettyAnnotatedMetadata metadata = impl.scan(AnnotatedTextStreamSocket.class);

        String classId = AnnotatedTextStreamSocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,metadata,notNullValue());

        assertNoEventMethod(classId + ".onBinary",metadata.onBinary);
        assertHasEventMethod(classId + ".onClose",metadata.onClose);
        assertHasEventMethod(classId + ".onConnect",metadata.onConnect);
        assertNoEventMethod(classId + ".onException",metadata.onError);
        assertHasEventMethod(classId + ".onText",metadata.onText);
        assertNoEventMethod(classId + ".onFrame",metadata.onFrame);

        Assert.assertFalse(classId + ".onText.isSessionAware",metadata.onText.isSessionAware());
        Assert.assertTrue(classId + ".onText.isStreaming",metadata.onText.isStreaming());
    }
}
