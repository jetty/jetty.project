package org.eclipse.jetty.websocket.annotations;

import static org.hamcrest.Matchers.*;

import org.junit.Assert;
import org.junit.Test;

public class EventMethodsCacheTest
{
    private void assertHasEventMethod(String message, EventMethod actual)
    {
        Assert.assertNotSame(message + "Event method should have been discovered",actual,EventMethod.NOOP);
    }

    private void assertNoEventMethod(String message, EventMethod actual)
    {
        Assert.assertEquals(message + "Event method should have been NOOP",actual,EventMethod.NOOP);
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
        assertNoEventMethod("MyEchoSocket.onFrame",methods.onFrame);
        assertHasEventMethod("MyEchoSocket.onText",methods.onText);
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
        assertNoEventMethod("MyStatelessEchoSocket.onFrame",methods.onFrame);
        assertHasEventMethod("MyStatelessEchoSocket.onText",methods.onText);
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
        assertNoEventMethod("NoopSocket.onConnect", methods.onConnect);
        assertNoEventMethod("NoopSocket.onException",methods.onException);
        assertNoEventMethod("NoopSocket.onFrame",methods.onFrame);
        assertNoEventMethod("NoopSocket.onText",methods.onText);
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
        assertHasEventMethod("MyEchoSocket.onFrame",methods.onFrame);
        assertNoEventMethod("MyEchoSocket.onText",methods.onText);
    }
}
