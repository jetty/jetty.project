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
package org.eclipse.jetty.websocket.server.helper;

import static org.hamcrest.Matchers.*;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.parser.Parser;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.junit.Assert;

public class FrameParseCapture implements Parser.Listener
{
    private static final Logger LOG = Log.getLogger(FrameParseCapture.class);
    private List<WebSocketFrame> frames = new ArrayList<>();
    private List<WebSocketException> errors = new ArrayList<>();

    public void assertHasErrors(Class<? extends WebSocketException> errorType, int expectedCount)
    {
        Assert.assertThat(errorType.getSimpleName(),getErrorCount(errorType),is(expectedCount));
    }

    public void assertHasFrame(Class<? extends WebSocketFrame> frameType)
    {
        Assert.assertThat(frameType.getSimpleName(),getFrameCount(frameType),greaterThanOrEqualTo(1));
    }

    public void assertHasFrame(Class<? extends WebSocketFrame> frameType, int expectedCount)
    {
        Assert.assertThat(frameType.getSimpleName(),getFrameCount(frameType),is(expectedCount));
    }

    public void assertHasNoFrames()
    {
        Assert.assertThat("Has no frames",frames.size(),is(0));
    }

    public void assertNoErrors()
    {
        Assert.assertThat("Has no errors",errors.size(),is(0));
    }

    public int getErrorCount(Class<? extends WebSocketException> errorType)
    {
        int count = 0;
        for (WebSocketException error : errors)
        {
            if (errorType.isInstance(error))
            {
                count++;
            }
        }
        return count;
    }

    public List<WebSocketException> getErrors()
    {
        return errors;
    }

    public int getFrameCount(Class<? extends WebSocketFrame> frameType)
    {
        int count = 0;
        for (WebSocketFrame frame : frames)
        {
            if (frameType.isInstance(frame))
            {
                count++;
            }
        }
        return count;
    }

    public List<WebSocketFrame> getFrames()
    {
        return frames;
    }

    @Override
    public void onFrame(WebSocketFrame frame)
    {
        frames.add(frame);
    }

    @Override
    public void onWebSocketException(WebSocketException e)
    {
        LOG.warn(e);
        errors.add(e);
    }
}
