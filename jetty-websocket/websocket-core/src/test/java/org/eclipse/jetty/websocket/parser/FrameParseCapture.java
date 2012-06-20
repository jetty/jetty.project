package org.eclipse.jetty.websocket.parser;

import static org.hamcrest.Matchers.*;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.junit.Assert;

public class FrameParseCapture implements Parser.Listener
{
    private List<BaseFrame> frames = new ArrayList<>();
    private List<WebSocketException> errors = new ArrayList<>();

    public void assertHasErrors(Class<? extends WebSocketException> errorType, int expectedCount)
    {
        Assert.assertThat(errorType.getSimpleName(),getErrorCount(errorType),is(expectedCount));
    }

    public void assertHasFrame(Class<? extends BaseFrame> frameType)
    {
        Assert.assertThat(frameType.getSimpleName(),getFrameCount(frameType),greaterThanOrEqualTo(1));
    }

    public void assertHasFrame(Class<? extends BaseFrame> frameType, int expectedCount)
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
        for(WebSocketException error: errors) {
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

    public int getFrameCount(Class<? extends BaseFrame> frameType)
    {
        int count = 0;
        for(BaseFrame frame: frames) {
            if (frameType.isInstance(frame))
            {
                count++;
            }
        }
        return count;
    }

    public List<BaseFrame> getFrames()
    {
        return frames;
    }

    @Override
    public void onFrame(BaseFrame frame)
    {
        frames.add(frame);
    }

    @Override
    public void onWebSocketException(WebSocketException e)
    {
        errors.add(e);
    }
}
