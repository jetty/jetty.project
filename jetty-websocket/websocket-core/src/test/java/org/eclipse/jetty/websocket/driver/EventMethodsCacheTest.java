// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.driver;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import org.eclipse.jetty.websocket.annotations.BadBinarySignatureSocket;
import org.eclipse.jetty.websocket.annotations.BadDuplicateBinarySocket;
import org.eclipse.jetty.websocket.annotations.BadDuplicateFrameSocket;
import org.eclipse.jetty.websocket.annotations.BadTextSignatureSocket;
import org.eclipse.jetty.websocket.annotations.FrameSocket;
import org.eclipse.jetty.websocket.annotations.MyEchoBinarySocket;
import org.eclipse.jetty.websocket.annotations.MyEchoSocket;
import org.eclipse.jetty.websocket.annotations.MyStatelessEchoSocket;
import org.eclipse.jetty.websocket.annotations.NoopSocket;
import org.eclipse.jetty.websocket.annotations.NotASocket;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.examples.AdapterConnectCloseSocket;
import org.eclipse.jetty.websocket.examples.AnnotatedBinaryArraySocket;
import org.eclipse.jetty.websocket.examples.AnnotatedBinaryStreamSocket;
import org.eclipse.jetty.websocket.examples.AnnotatedTextSocket;
import org.eclipse.jetty.websocket.examples.AnnotatedTextStreamSocket;
import org.eclipse.jetty.websocket.examples.ListenerBasicSocket;
import org.junit.Assert;
import org.junit.Test;

public class EventMethodsCacheTest
{
    private void assertHasEventMethod(String message, EventMethod actual)
    {
        Assert.assertThat(message + " EventMethod",actual,notNullValue());

        Assert.assertThat(message + " EventMethod.pojo",actual.pojo,notNullValue());
        Assert.assertThat(message + " EventMethod.method",actual.method,notNullValue());
    }

    private void assertNoEventMethod(String message, EventMethod actual)
    {
        Assert.assertThat(message + "Event method",actual,nullValue());
    }

    /**
     * Test Case for no exceptions and 5 methods (extends WebSocketAdapter)
     */
    @Test
    public void testAdapterConnectCloseSocket()
    {
        EventMethodsCache cache = new EventMethodsCache();
        EventMethods methods = cache.getMethods(AdapterConnectCloseSocket.class);

        String classId = AdapterConnectCloseSocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,methods,notNullValue());

        // Directly Declared
        assertHasEventMethod(classId + ".onClose",methods.onClose);
        assertHasEventMethod(classId + ".onConnect",methods.onConnect);

        // From WebSocketAdapter
        assertHasEventMethod(classId + ".onBinary",methods.onBinary);
        assertHasEventMethod(classId + ".onException",methods.onException);
        assertHasEventMethod(classId + ".onText",methods.onText);

        // Advanced, only available from @OnWebSocketFrame annotation
        assertNoEventMethod(classId + ".onFrame",methods.onFrame);
    }

    /**
     * Test Case for bad declaration (duplicate OnWebSocketBinary declarations)
     */
    @Test
    public void testAnnotatedBadDuplicateBinarySocket()
    {
        EventMethodsCache cache = new EventMethodsCache();
        try
        {
            // Should toss exception
            cache.getMethods(BadDuplicateBinarySocket.class);
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
        EventMethodsCache cache = new EventMethodsCache();
        try
        {
            // Should toss exception
            cache.getMethods(BadDuplicateFrameSocket.class);
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
        EventMethodsCache cache = new EventMethodsCache();
        try
        {
            // Should toss exception
            cache.getMethods(BadBinarySignatureSocket.class);
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
        EventMethodsCache cache = new EventMethodsCache();
        try
        {
            // Should toss exception
            cache.getMethods(BadTextSignatureSocket.class);
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
        EventMethodsCache cache = new EventMethodsCache();
        EventMethods methods = cache.getMethods(AnnotatedBinaryArraySocket.class);

        String classId = AnnotatedBinaryArraySocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,methods,notNullValue());

        assertHasEventMethod(classId + ".onBinary",methods.onBinary);
        assertHasEventMethod(classId + ".onClose",methods.onClose);
        assertHasEventMethod(classId + ".onConnect",methods.onConnect);
        assertNoEventMethod(classId + ".onException",methods.onException);
        assertNoEventMethod(classId + ".onText",methods.onText);
        assertNoEventMethod(classId + ".onFrame",methods.onFrame);

        Assert.assertFalse(classId + ".onBinary.hasConnection",methods.onBinary.isHasConnection());
        Assert.assertFalse(classId + ".onBinary.isStreaming",methods.onBinary.isStreaming());
    }

    /**
     * Test Case for socket for binary stream messages
     */
    @Test
    public void testAnnotatedBinaryStreamSocket()
    {
        EventMethodsCache cache = new EventMethodsCache();
        EventMethods methods = cache.getMethods(AnnotatedBinaryStreamSocket.class);

        String classId = AnnotatedBinaryStreamSocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,methods,notNullValue());

        assertHasEventMethod(classId + ".onBinary",methods.onBinary);
        assertHasEventMethod(classId + ".onClose",methods.onClose);
        assertHasEventMethod(classId + ".onConnect",methods.onConnect);
        assertNoEventMethod(classId + ".onException",methods.onException);
        assertNoEventMethod(classId + ".onText",methods.onText);
        assertNoEventMethod(classId + ".onFrame",methods.onFrame);

        Assert.assertFalse(classId + ".onBinary.hasConnection",methods.onBinary.isHasConnection());
        Assert.assertTrue(classId + ".onBinary.isStreaming",methods.onBinary.isStreaming());
    }

    /**
     * Test Case for no exceptions and 4 methods (3 methods from parent)
     */
    @Test
    public void testAnnotatedMyEchoBinarySocket()
    {
        EventMethodsCache cache = new EventMethodsCache();
        EventMethods methods = cache.getMethods(MyEchoBinarySocket.class);

        String classId = MyEchoBinarySocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,methods,notNullValue());

        assertHasEventMethod(classId + ".onBinary",methods.onBinary);
        assertHasEventMethod(classId + ".onClose",methods.onClose);
        assertHasEventMethod(classId + ".onConnect",methods.onConnect);
        assertNoEventMethod(classId + ".onException",methods.onException);
        assertHasEventMethod(classId + ".onText",methods.onText);
        assertNoEventMethod(classId + ".onFrame",methods.onFrame);
    }

    /**
     * Test Case for no exceptions and 3 methods
     */
    @Test
    public void testAnnotatedMyEchoSocket()
    {
        EventMethodsCache cache = new EventMethodsCache();
        EventMethods methods = cache.getMethods(MyEchoSocket.class);

        String classId = MyEchoSocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,methods,notNullValue());

        assertNoEventMethod(classId + ".onBinary",methods.onBinary);
        assertHasEventMethod(classId + ".onClose",methods.onClose);
        assertHasEventMethod(classId + ".onConnect",methods.onConnect);
        assertNoEventMethod(classId + ".onException",methods.onException);
        assertHasEventMethod(classId + ".onText",methods.onText);
        assertNoEventMethod(classId + ".onFrame",methods.onFrame);
    }

    /**
     * Test Case for annotated for text messages w/connection param
     */
    @Test
    public void testAnnotatedMyStatelessEchoSocket()
    {
        EventMethodsCache cache = new EventMethodsCache();
        EventMethods methods = cache.getMethods(MyStatelessEchoSocket.class);

        String classId = MyStatelessEchoSocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,methods,notNullValue());

        assertNoEventMethod(classId + ".onBinary",methods.onBinary);
        assertNoEventMethod(classId + ".onClose",methods.onClose);
        assertNoEventMethod(classId + ".onConnect",methods.onConnect);
        assertNoEventMethod(classId + ".onException",methods.onException);
        assertHasEventMethod(classId + ".onText",methods.onText);
        assertNoEventMethod(classId + ".onFrame",methods.onFrame);

        Assert.assertTrue(classId + ".onText.hasConnection",methods.onText.isHasConnection());
        Assert.assertFalse(classId + ".onText.isStreaming",methods.onText.isStreaming());
    }

    /**
     * Test Case for no exceptions and no methods
     */
    @Test
    public void testAnnotatedNoop()
    {
        EventMethodsCache cache = new EventMethodsCache();
        EventMethods methods = cache.getMethods(NoopSocket.class);

        String classId = NoopSocket.class.getSimpleName();

        Assert.assertThat("Methods for " + classId,methods,notNullValue());

        assertNoEventMethod(classId + ".onBinary",methods.onBinary);
        assertNoEventMethod(classId + ".onClose",methods.onClose);
        assertNoEventMethod(classId + ".onConnect",methods.onConnect);
        assertNoEventMethod(classId + ".onException",methods.onException);
        assertNoEventMethod(classId + ".onText",methods.onText);
        assertNoEventMethod(classId + ".onFrame",methods.onFrame);
    }

    /**
     * Test Case for no exceptions and 1 methods
     */
    @Test
    public void testAnnotatedOnFrame()
    {
        EventMethodsCache cache = new EventMethodsCache();
        EventMethods methods = cache.getMethods(FrameSocket.class);

        String classId = FrameSocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,methods,notNullValue());

        assertNoEventMethod(classId + ".onBinary",methods.onBinary);
        assertNoEventMethod(classId + ".onClose",methods.onClose);
        assertNoEventMethod(classId + ".onConnect",methods.onConnect);
        assertNoEventMethod(classId + ".onException",methods.onException);
        assertNoEventMethod(classId + ".onText",methods.onText);
        assertHasEventMethod(classId + ".onFrame",methods.onFrame);
    }

    /**
     * Test Case for socket for simple text messages
     */
    @Test
    public void testAnnotatedTextSocket()
    {
        EventMethodsCache cache = new EventMethodsCache();
        EventMethods methods = cache.getMethods(AnnotatedTextSocket.class);

        String classId = AnnotatedTextSocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,methods,notNullValue());

        assertNoEventMethod(classId + ".onBinary",methods.onBinary);
        assertHasEventMethod(classId + ".onClose",methods.onClose);
        assertHasEventMethod(classId + ".onConnect",methods.onConnect);
        assertNoEventMethod(classId + ".onException",methods.onException);
        assertHasEventMethod(classId + ".onText",methods.onText);
        assertNoEventMethod(classId + ".onFrame",methods.onFrame);

        Assert.assertFalse(classId + ".onText.hasConnection",methods.onText.isHasConnection());
        Assert.assertFalse(classId + ".onText.isStreaming",methods.onText.isStreaming());
    }

    /**
     * Test Case for socket for text stream messages
     */
    @Test
    public void testAnnotatedTextStreamSocket()
    {
        EventMethodsCache cache = new EventMethodsCache();
        EventMethods methods = cache.getMethods(AnnotatedTextStreamSocket.class);

        String classId = AnnotatedTextStreamSocket.class.getSimpleName();

        Assert.assertThat("EventMethods for " + classId,methods,notNullValue());

        assertNoEventMethod(classId + ".onBinary",methods.onBinary);
        assertHasEventMethod(classId + ".onClose",methods.onClose);
        assertHasEventMethod(classId + ".onConnect",methods.onConnect);
        assertNoEventMethod(classId + ".onException",methods.onException);
        assertHasEventMethod(classId + ".onText",methods.onText);
        assertNoEventMethod(classId + ".onFrame",methods.onFrame);

        Assert.assertFalse(classId + ".onText.hasConnection",methods.onText.isHasConnection());
        Assert.assertTrue(classId + ".onText.isStreaming",methods.onText.isStreaming());
    }

    /**
     * Test Case for bad declaration (duplicate OnWebSocketBinary declarations)
     */
    @Test
    public void testBadNotASocket()
    {
        EventMethodsCache cache = new EventMethodsCache();
        try
        {
            // Should toss exception
            cache.getMethods(NotASocket.class);
        }
        catch (InvalidWebSocketException e)
        {
            // Validate that we have clear error message to the developer
            Assert.assertThat(e.getMessage(),allOf(containsString(WebSocketListener.class.getSimpleName()),containsString(WebSocket.class.getSimpleName())));
        }
    }

    /**
     * Test Case for no exceptions and 5 methods (implement WebSocketListener)
     */
    @Test
    public void testListenerBasicSocket()
    {
        EventMethodsCache cache = new EventMethodsCache();
        EventMethods methods = cache.getMethods(ListenerBasicSocket.class);

        String classId = AdapterConnectCloseSocket.class.getSimpleName();

        Assert.assertThat("ListenerBasicSocket for " + classId,methods,notNullValue());

        assertHasEventMethod(classId + ".onClose",methods.onClose);
        assertHasEventMethod(classId + ".onConnect",methods.onConnect);
        assertHasEventMethod(classId + ".onBinary",methods.onBinary);
        assertHasEventMethod(classId + ".onException",methods.onException);
        assertHasEventMethod(classId + ".onText",methods.onText);
        assertNoEventMethod(classId + ".onFrame",methods.onFrame);
    }
}
