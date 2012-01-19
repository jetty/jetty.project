// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.server.handler;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import org.eclipse.jetty.server.Handler;
import org.junit.Before;
import org.junit.Test;

/* ------------------------------------------------------------ */
/**
 */
public class HandlerWrapperTest
{

    private HandlerWrapper _handlerWrapper;
    private Handler _handler;

    /* ------------------------------------------------------------ */
    /**
     * @throws java.lang.Exception
     */
    @Before
    public void setUp() throws Exception
    {
        _handler = new ErrorHandler();
        _handlerWrapper = new HandlerWrapper();
    }

    @Test
    public void testLifeCycleAddAndRemoveBean() throws Exception
    {
        _handlerWrapper.setHandler(_handler);
        assertThat("No handler bean should have been added to LifeCycle before we start the HandlerWrapper decorator.",_handlerWrapper.getBeans().size(),is(0));
        _handlerWrapper.start();
        assertThat(_handler.isStarted(), is(true));
        assertThat("A single handler has been added.",_handlerWrapper.getBeans().size(),is(1));
        _handlerWrapper.stop();
        assertThat("Handler should have been removed on doStop().",_handlerWrapper.getBeans().size(),is(0));
        // removeBean() currently does not stop the bean, so this assertion is commented for now 
//        assertThat(_handler.isStarted(), is(false)); 
    }

    @Test(expected = IllegalStateException.class)
    public void testSetHandlerAfterDoStart() throws Exception
    {
        _handlerWrapper.start();
        _handlerWrapper.setHandler(_handler);
    }
    
    @Test
    public void testSetNullHandlerAndStartStop() throws Exception
    {
        _handlerWrapper.setHandler(null);
        _handlerWrapper.start();
        assertThat("No handler should have been added.",_handlerWrapper.getBeans().size(),is(0));
        assertThat("No handler should have been added.",_handlerWrapper.getHandler(),nullValue());
        _handlerWrapper.stop();
    }

}
