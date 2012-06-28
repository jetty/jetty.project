package org.eclipse.jetty.websocket.annotations;

import static org.hamcrest.Matchers.*;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.junit.Assert;
import org.junit.Test;

public class EventMethodsCacheTest
{
    private void assertHasEventMethod(String message, EventMethod actual)
    {
        Assert.assertThat(message + "Event method should have been discovered",actual,notNullValue());
    }

    private void assertNoEventMethod(String message, EventMethod actual)
    {
        Assert.assertThat(message + "Event method should have been NOOP",actual,nullValue());
    }

    /**
     * Test Case for bad declaration (duplicate OnWebSocketBinary declarations)
     */
    @Test
    public void testDiscoverBadDuplicateBinarySocket()
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
            Assert.assertThat(e.getMessage(),containsString("Duplicate @OnWebSocketBinary declaration"));
        }
    }

    /**
     * Test Case for bad declaration (duplicate frame type methods)
     */
    @Test
    public void testDiscoverBadDuplicateFrameSocket()
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
            Assert.assertThat(e.getMessage(),containsString("Duplicate Frame Type"));
        }
    }

    /**
     * Test Case for bad declaration a method with a non-void return type
     */
    @Test
    public void testDiscoverBadSignature_NonVoidReturn()
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
    public void testDiscoverBadSignature_Static()
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
     * Test Case for no exceptions and 3 methods
     */
    @Test
    public void testDiscoverMyEchoSocket()
    {
        EventMethodsCache cache = new EventMethodsCache();
        EventMethods methods = cache.getMethods(MyEchoSocket.class);

        Assert.assertThat("EventMethods for MyEchoSocket",methods,notNullValue());

        assertNoEventMethod("MyEchoSocket.onBinary",methods.onBinary);
        assertHasEventMethod("MyEchoSocket.onClose",methods.onClose);
        assertHasEventMethod("MyEchoSocket.onConnect",methods.onConnect);
        assertNoEventMethod("MyEchoSocket.onException",methods.onException);
        assertHasEventMethod("MyEchoSocket.onText",methods.onText);

        Assert.assertThat("MyEchoSocket.getOnFrames()",methods.getOnFrames().size(),is(0));
    }

    /**
     * Test Case for no exceptions and 1 method
     */
    @Test
    public void testDiscoverMyStatelessEchoSocket()
    {
        EventMethodsCache cache = new EventMethodsCache();
        EventMethods methods = cache.getMethods(MyStatelessEchoSocket.class);

        Assert.assertThat("EventMethods for MyStatelessEchoSocket",methods,notNullValue());

        assertNoEventMethod("MyStatelessEchoSocket.onBinary",methods.onBinary);
        assertNoEventMethod("MyStatelessEchoSocket.onClose",methods.onClose);
        assertNoEventMethod("MyStatelessEchoSocket.onConnect",methods.onConnect);
        assertNoEventMethod("MyStatelessEchoSocket.onException",methods.onException);
        assertHasEventMethod("MyStatelessEchoSocket.onText",methods.onText);

        Assert.assertThat("MyEchoSocket.getOnFrames()",methods.getOnFrames().size(),is(0));
    }

    /**
     * Test Case for no exceptions and no methods
     */
    @Test
    public void testDiscoverNoop()
    {
        EventMethodsCache cache = new EventMethodsCache();
        EventMethods methods = cache.getMethods(NoopSocket.class);

        Assert.assertThat("Methods for NoopSocket",methods,notNullValue());

        assertNoEventMethod("NoopSocket.onBinary",methods.onBinary);
        assertNoEventMethod("NoopSocket.onClose",methods.onClose);
        assertNoEventMethod("NoopSocket.onConnect",methods.onConnect);
        assertNoEventMethod("NoopSocket.onException",methods.onException);
        assertNoEventMethod("NoopSocket.onText",methods.onText);

        Assert.assertThat("MyEchoSocket.getOnFrames()",methods.getOnFrames().size(),is(0));
    }

    /**
     * Test Case for bad declaration (duplicate OnWebSocketBinary declarations)
     */
    @Test
    public void testDiscoverNotASocket()
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
     * Test Case for no exceptions and 3 methods
     */
    @Test
    public void testDiscoverOnFrame()
    {
        EventMethodsCache cache = new EventMethodsCache();
        EventMethods methods = cache.getMethods(FrameSocket.class);

        Assert.assertThat("EventMethods for MyEchoSocket",methods,notNullValue());

        assertNoEventMethod("MyEchoSocket.onBinary",methods.onBinary);
        assertNoEventMethod("MyEchoSocket.onClose",methods.onClose);
        assertNoEventMethod("MyEchoSocket.onConnect",methods.onConnect);
        assertNoEventMethod("MyEchoSocket.onException",methods.onException);
        assertNoEventMethod("MyEchoSocket.onText",methods.onText);

        Assert.assertThat("MyEchoSocket.getOnFrames()",methods.getOnFrames().size(),is(2));
        assertHasEventMethod("MyEchoSocket.onFrame(BaseFrame)",methods.getOnFrame(BaseFrame.class));
        assertHasEventMethod("MyEchoSocket.onFrame(BaseFrame)",methods.getOnFrame(TextFrame.class));
    }
}
