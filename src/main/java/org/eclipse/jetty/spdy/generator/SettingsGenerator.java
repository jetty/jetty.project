package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;
import java.util.Map;

import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.api.SettingsInfo;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.SettingsFrame;

public class SettingsGenerator extends ControlFrameGenerator
{
    @Override
    public ByteBuffer generate(ControlFrame frame) throws StreamException
    {
        SettingsFrame settings = (SettingsFrame)frame;

        Map<SettingsInfo.Key, Integer> pairs = settings.getSettings();
        int size = pairs.size();
        int frameBodyLength = 4 + 8 * size;
        int totalLength = ControlFrame.HEADER_LENGTH + frameBodyLength;
        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        generateControlFrameHeader(settings, frameBodyLength, buffer);

        buffer.putInt(size);

        for (Map.Entry<SettingsInfo.Key, Integer> entry : pairs.entrySet())
        {
            buffer.putInt(entry.getKey().getKey());
            buffer.putInt(entry.getValue());
        }

        buffer.flip();
        return buffer;
    }
}
