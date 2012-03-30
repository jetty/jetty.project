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


package org.eclipse.jetty.spdy;

import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.*;

import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.frames.SynStreamFrame;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;


/* ------------------------------------------------------------ */
/**
 */
@RunWith(MockitoJUnitRunner.class)
public class StandardStreamTest
{

    @Mock private ISession session;
    @Mock private SynStreamFrame synStreamFrame;
    
    /**
     * Test method for {@link org.eclipse.jetty.spdy.StandardStream#syn(org.eclipse.jetty.spdy.api.SynInfo)}.
     */
    @Test
    public void testSyn()
    {
        Stream stream = new StandardStream(synStreamFrame,session,0,null);
        when(synStreamFrame.isClose()).thenReturn(false);
        SynInfo synInfo = new SynInfo(false);
        stream.syn(synInfo);
        PushSynInfo expectedPushSynInfo = new PushSynInfo(stream.getId(),synInfo);
        verify(session).syn(eq(expectedPushSynInfo),any(StreamFrameListener.class));
    }
    
    @Test(expected=IllegalStateException.class)
    public void testSynOnClosedStream(){
        IStream stream = new StandardStream(synStreamFrame,session,0,null);
        stream.updateCloseState(true);
        stream.updateCloseState(true);
        assertThat("stream expected to be closed",stream.isClosed(),is(true));
        stream.syn(new SynInfo(false));
    }

}
