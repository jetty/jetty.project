package org.eclipse.jetty.websocket;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.generator.TextFrameGenerator;
import org.eclipse.jetty.websocket.masks.RandomMasker;
import org.eclipse.jetty.websocket.parser.FrameParseCapture;
import org.eclipse.jetty.websocket.parser.Parser;
import org.eclipse.jetty.websocket.parser.TextPayloadParser;
import org.junit.Assert;
import org.junit.Test;

public class GeneratorParserRoundtripTest
{
    @Test
    public void testParserAndGenerator() throws Exception
    {
        Debug.enableDebugLogging(Generator.class);
        Debug.enableDebugLogging(TextFrameGenerator.class);
        Debug.enableDebugLogging(Parser.class);
        Debug.enableDebugLogging(TextPayloadParser.class);

        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        StandardByteBufferPool bufferPool = new StandardByteBufferPool();
        Generator gen = new Generator(bufferPool,policy);
        Parser parser = new Parser(policy);

        // Generate Buffer
        String message = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";
        ByteBuffer out = gen.generate(new TextFrame(message));
        Debug.dumpState(out);

        // Parse Buffer
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        out.flip();
        parser.parse(out);

        // Validate
        capture.assertNoErrors();
        capture.assertHasFrame(TextFrame.class,1);

        TextFrame txt = (TextFrame)capture.getFrames().get(0);
        Assert.assertThat("Text parsed",txt.getData().toString(),is(message));
    }

    @Test
    public void testParserAndGeneratorMasked() throws Exception
    {
        Debug.enableDebugLogging(Generator.class);
        Debug.enableDebugLogging(TextFrameGenerator.class);
        Debug.enableDebugLogging(Parser.class);
        Debug.enableDebugLogging(TextPayloadParser.class);

        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        policy.setMasker(new RandomMasker());

        StandardByteBufferPool bufferPool = new StandardByteBufferPool();
        Generator gen = new Generator(bufferPool,policy);
        Parser parser = new Parser(policy);

        // Generate Buffer
        String message = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";
        ByteBuffer out = gen.generate(new TextFrame(message));
        Debug.dumpState(out);

        // Parse Buffer
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        out.flip();
        parser.parse(out);

        // Validate
        capture.assertNoErrors();
        capture.assertHasFrame(TextFrame.class,1);

        TextFrame txt = (TextFrame)capture.getFrames().get(0);
        Assert.assertTrue("Text.isMasked",txt.isMasked());
        Assert.assertThat("Text parsed",txt.getData().toString(),is(message));
    }
}
