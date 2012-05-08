/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.frames;

public class WindowUpdateFrame extends ControlFrame
{
    private final int streamId;
    private final int windowDelta;

    public WindowUpdateFrame(short version, int streamId, int windowDelta)
    {
        super(version, ControlFrameType.WINDOW_UPDATE, (byte)0);
        this.streamId = streamId;
        this.windowDelta = windowDelta;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public int getWindowDelta()
    {
        return windowDelta;
    }

    @Override
    public String toString()
    {
        return String.format("%s stream=%d delta=%d", super.toString(), getStreamId(), getWindowDelta());
    }
}
