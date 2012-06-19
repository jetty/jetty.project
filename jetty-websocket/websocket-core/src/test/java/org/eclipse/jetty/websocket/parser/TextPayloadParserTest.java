package org.eclipse.jetty.websocket.parser;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.Debug;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.junit.Assert;
import org.junit.Test;

public class TextPayloadParserTest
{
    private final byte[] mask = new byte[]
            { 0x00, (byte)0xF0, 0x0F, (byte)0xFF };

    @Test
    public void testShortMaskedText() throws Exception
    {
        Debug.enableDebugLogging(Parser.class);
        Debug.enableDebugLogging(TextPayloadParser.class);
        String expectedText = "Hello World";

        ByteBuffer buf = ByteBuffer.allocate(24);
        buf.put((byte)0x81);
        buf.put((byte)(0x80 | expectedText.length()));
        writeMask(buf);
        writeMasked(buf,expectedText.getBytes(StringUtil.__UTF8));
        buf.flip();

        Parser parser = new Parser();
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(TextFrame.class,1);
        TextFrame txt = (TextFrame)capture.getFrames().get(0);
        Assert.assertThat("TextFrame.data",txt.getData().toString(),is("Hello World"));
    }

    private void writeMask(ByteBuffer buf)
    {
        buf.put(mask,0,mask.length);
    }

    private void writeMasked(ByteBuffer buf, byte[] bytes)
    {
        int len = bytes.length;
        for (int i = 0; i < len; i++)
        {
            buf.put((byte)(bytes[i] ^ mask[i % 4]));
        }
    }
}
