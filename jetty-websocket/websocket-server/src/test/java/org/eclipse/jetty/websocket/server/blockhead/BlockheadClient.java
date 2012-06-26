package org.eclipse.jetty.websocket.server.blockhead;

import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingDeque;

import javax.net.ssl.HttpsURLConnection;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.parser.Parser;
import org.junit.Assert;

/**
 * A simple websocket client for performing unit tests with.
 * <p>
 * This client will use {@link HttpURLConnection} and {@link HttpsURLConnection} with standard blocking calls to perform websocket requests.
 * <p>
 * This client is intended to be performant or follow the websocket spec religiously. In fact, being able to deviate from the websocket spec at will is desired
 * for this client to operate properly for the unit testing within this module.
 */
public class BlockheadClient implements Parser.Listener
{
    private static final Logger LOG = Log.getLogger(BlockheadClient.class);
    private final URI destHttpURI;
    private final URI destWebsocketURI;
    private final ByteBufferPool bufferPool;
    private final WebSocketPolicy policy;
    private final Generator generator;
    private final Parser parser;
    private final LinkedBlockingDeque<BaseFrame> incomingFrameQueue;

    public BlockheadClient(URI destWebsocketURI) throws URISyntaxException
    {
        Assert.assertThat("Websocket URI scheme",destWebsocketURI.getScheme(),anyOf(is("ws"),is("wss")));
        this.destWebsocketURI = destWebsocketURI;
        String scheme = "http";
        if (destWebsocketURI.getScheme().equals("wss"))
        {
            scheme = "https";
        }
        this.destHttpURI = new URI(scheme,destWebsocketURI.getSchemeSpecificPart(),destWebsocketURI.getFragment());

        policy = WebSocketPolicy.newClientPolicy();
        bufferPool = new StandardByteBufferPool(policy.getBufferSize());
        generator = new Generator(policy);
        parser = new Parser(policy);
        parser.addListener(this);

        incomingFrameQueue = new LinkedBlockingDeque<>();
    }

    public void connect() throws IOException
    {

    }

    public URI getHttpURI()
    {
        return destHttpURI;
    }

    public URI getWebsocketURI()
    {
        return destWebsocketURI;
    }

    @Override
    public void onFrame(BaseFrame frame)
    {
        if (!incomingFrameQueue.offerLast(frame))
        {
            throw new RuntimeException("Unable to queue incoming frame: " + frame);
        }
    }

    @Override
    public void onWebSocketException(WebSocketException e)
    {
        LOG.warn(e);
    }

    public void write(BaseFrame frame)
    {
        ByteBuffer buf = bufferPool.acquire(policy.getBufferSize(),false);
        try
        {
            generator.generate(buf,frame);
            // TODO write to Socket
        }
        finally
        {
            bufferPool.release(buf);
        }
    }
}
