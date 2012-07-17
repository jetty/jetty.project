package org.eclipse.jetty.websocket.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.driver.WebSocketEventDriver;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

public class WebSocketSession implements WebSocketConnection, IncomingFrames, OutgoingFrames
{
    private static final Logger LOG = Log.getLogger(WebSocketSession.class);
    private final RawConnection connection;
    private final WebSocketPolicy policy;
    private final String subprotocol;
    private final WebSocketEventDriver websocket;
    private OutgoingFrames outgoing;

    public WebSocketSession(WebSocketEventDriver websocket, RawConnection connection, WebSocketPolicy policy, String subprotocol)
    {
        super();
        this.websocket = websocket;
        this.connection = connection;
        this.policy = policy;
        this.subprotocol = subprotocol;
    }

    @Override
    public void close() throws IOException
    {
        connection.close();
    }

    @Override
    public void close(int statusCode, String reason) throws IOException
    {
        connection.close(statusCode,reason);
    }

    public IncomingFrames getIncoming()
    {
        return websocket;
    }

    public OutgoingFrames getOutgoing()
    {
        return outgoing;
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return connection.getRemoteAddress();
    }

    @Override
    public String getSubProtocol()
    {
        return subprotocol;
    }

    @Override
    public void incoming(WebSocketException e)
    {
        // pass on incoming to websocket itself
        websocket.incoming(e);
    }

    @Override
    public void incoming(WebSocketFrame frame)
    {
        // pass on incoming to websocket itself
        websocket.incoming(frame);
    }

    @Override
    public boolean isOpen()
    {
        return connection.isOpen();
    }

    @Override
    public <C> void output(C context, Callback<C> callback, WebSocketFrame frame)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("output({},{},{})",context,callback,frame);
        }
        // forward on to chain
        outgoing.output(context,callback,frame);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <C> void ping(C context, Callback<C> callback, byte[] payload) throws IOException
    {
        WebSocketFrame frame = new WebSocketFrame(OpCode.PING).setPayload(payload);
        frame.setFin(true);
        output(context,callback,frame);
    }

    public void setOutgoing(OutgoingFrames outgoing)
    {
        this.outgoing = outgoing;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <C> void write(C context, Callback<C> callback, byte[] buf, int offset, int len) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("write(context,{},byte[],{},{})",callback,offset,len);
        }
        WebSocketFrame frame = WebSocketFrame.binary().setPayload(buf,offset,len);
        frame.setFin(true);
        output(context,callback,frame);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <C> void write(C context, Callback<C> callback, ByteBuffer buffer) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("write(context,{},ByteBuffer->{})",callback,BufferUtil.toDetailString(buffer));
        }
        WebSocketFrame frame = WebSocketFrame.binary().setPayload(buffer);
        frame.setFin(true);
        output(context,callback,frame);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <C> void write(C context, Callback<C> callback, String message) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("write(context,{},message.length:{})",callback,message.length());
        }
        WebSocketFrame frame = WebSocketFrame.text(message);
        frame.setFin(true);
        output(context,callback,frame);
    }
}
