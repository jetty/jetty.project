package org.eclipse.jetty.websocket;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.masks.FixedMasker;
import org.eclipse.jetty.websocket.masks.RandomMasker;
import org.eclipse.jetty.websocket.parser.FrameParseCapture;
import org.eclipse.jetty.websocket.parser.Parser;
import org.junit.Assert;
import org.junit.Test;

public class GeneratorParserRoundtripTest
{
    @Test
    public void testParserAndGenerator() throws Exception
    {
        // Debug.enableDebugLogging(Generator.class);
        // Debug.enableDebugLogging(TextFrameGenerator.class);
        // Debug.enableDebugLogging(Parser.class);
        // Debug.enableDebugLogging(TextPayloadParser.class);

        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        StandardByteBufferPool bufferPool = new StandardByteBufferPool();
        Generator gen = new Generator(policy);
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);

        String message = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

        ByteBuffer out = bufferPool.acquire(policy.getBufferSize(),false);
        try
        {
            // Generate Buffer
            BufferUtil.flipToFill(out);
            gen.generate(out,new TextFrame(message));

            // Parse Buffer
            BufferUtil.flipToFlush(out,0);
            parser.parse(out);
        }
        finally
        {
            bufferPool.release(out);
        }

        // Validate
        capture.assertNoErrors();
        capture.assertHasFrame(TextFrame.class,1);

        TextFrame txt = (TextFrame)capture.getFrames().get(0);
        Assert.assertThat("Text parsed",txt.getPayloadUTF8(),is(message));
    }

    @Test
    public void testParserAndGeneratorMasked() throws Exception
    {
        // Debug.enableDebugLogging(Generator.class);
        // Debug.enableDebugLogging(TextFrameGenerator.class);
        // Debug.enableDebugLogging(Parser.class);
        // Debug.enableDebugLogging(TextPayloadParser.class);

        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        policy.setMasker(new RandomMasker());

        StandardByteBufferPool bufferPool = new StandardByteBufferPool();
        Generator gen = new Generator(policy);
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);

        String message = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

        ByteBuffer out = bufferPool.acquire(policy.getBufferSize(),false);
        try
        {
            // Setup Frame
            TextFrame txt = new TextFrame(message);

            // Add masking
            byte mask[] = new byte[4];
            new FixedMasker().genMask(mask);
            txt.setMask(mask);

            // Generate Buffer
            BufferUtil.flipToFill(out);
            gen.generate(out,txt);

            // Parse Buffer
            BufferUtil.flipToFlush(out,0);
            parser.parse(out);
        }
        finally
        {
            bufferPool.release(out);
        }

        // Validate
        capture.assertNoErrors();
        capture.assertHasFrame(TextFrame.class,1);

        TextFrame txt = (TextFrame)capture.getFrames().get(0);
        Assert.assertTrue("Text.isMasked",txt.isMasked());
        Assert.assertThat("Text parsed",txt.getPayloadUTF8(),is(message));
    }
}
