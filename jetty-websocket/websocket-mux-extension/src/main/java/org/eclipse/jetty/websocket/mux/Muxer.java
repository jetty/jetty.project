//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.mux;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.frames.ControlFrame;
import org.eclipse.jetty.websocket.mux.add.MuxAddClient;
import org.eclipse.jetty.websocket.mux.add.MuxAddServer;
import org.eclipse.jetty.websocket.mux.op.MuxAddChannelRequest;
import org.eclipse.jetty.websocket.mux.op.MuxAddChannelResponse;
import org.eclipse.jetty.websocket.mux.op.MuxDropChannel;
import org.eclipse.jetty.websocket.mux.op.MuxFlowControl;
import org.eclipse.jetty.websocket.mux.op.MuxNewChannelSlot;

/**
 * Muxer responsible for managing sub-channels.
 * <p>
 * Maintains a 1 (incoming and outgoing mux encapsulated frames) to many (per-channel incoming/outgoing standard websocket frames) relationship, along with
 * routing of {@link MuxControlBlock} events.
 * <p>
 * Control Channel events (channel ID == 0) are handled by the Muxer.
 */
public class Muxer implements IncomingFrames, MuxParser.Listener
{
    private static final int CONTROL_CHANNEL_ID = 0;

    private static final Logger LOG = Log.getLogger(Muxer.class);

    /**
     * Map of sub-channels, key is the channel Id.
     */
    private Map<Long, MuxChannel> channels = new HashMap<Long, MuxChannel>();

    private final WebSocketPolicy policy;
    private final LogicalConnection physicalConnection;
    private InetSocketAddress remoteAddress;
    /** Parsing frames destined for sub-channels */
    private MuxParser parser;
    /** Generating frames destined for physical connection */
    private MuxGenerator generator;
    private MuxAddServer addServer;
    private MuxAddClient addClient;
    /** The original request headers, used for delta encoded AddChannelRequest blocks */
    private UpgradeRequest physicalRequestHeaders;
    /** The original response headers, used for delta encoded AddChannelResponse blocks */
    private UpgradeResponse physicalResponseHeaders;

    public Muxer(final LogicalConnection connection)
    {
        this.physicalConnection = connection;
        this.policy = connection.getPolicy().clonePolicy();
        this.parser = new MuxParser();
        this.parser.setEvents(this);
        this.generator = new MuxGenerator();
    }

    public MuxAddClient getAddClient()
    {
        return addClient;
    }

    public MuxAddServer getAddServer()
    {
        return addServer;
    }

    public MuxChannel getChannel(long channelId, boolean create)
    {
        if (channelId == CONTROL_CHANNEL_ID)
        {
            throw new MuxPhysicalConnectionException(MuxDropChannel.Reason.UNKNOWN_MUX_CONTROL_BLOCK,"Invalid Channel ID");
        }

        MuxChannel channel = channels.get(channelId);
        if (channel == null)
        {
            if (create)
            {
                channel = new MuxChannel(channelId,this);
                channels.put(channelId,channel);
            }
            else
            {
                throw new MuxPhysicalConnectionException(MuxDropChannel.Reason.UNKNOWN_MUX_CONTROL_BLOCK,"Unknown Channel ID");
            }
        }
        return channel;
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    /**
     * Get the remote address of the physical connection.
     * 
     * @return the remote address of the physical connection
     */
    public InetSocketAddress getRemoteAddress()
    {
        return this.remoteAddress;
    }

    /**
     * Incoming parser errors
     */
    @Override
    public void incomingError(Throwable e)
    {
        MuxDropChannel.Reason reason = MuxDropChannel.Reason.PHYSICAL_CONNECTION_FAILED;
        String phrase = String.format("%s: %s", e.getClass().getName(), e.getMessage());
        mustFailPhysicalConnection(new MuxPhysicalConnectionException(reason,phrase));
    }

    /**
     * Incoming mux encapsulated frames.
     */
    @Override
    public void incomingFrame(Frame frame)
    {
        parser.parse(frame);
    }

    /**
     * Is the muxer and the physical connection still open?
     * 
     * @return true if open
     */
    public boolean isOpen()
    {
        return physicalConnection.isOpen();
    }

    public String mergeHeaders(List<String> physicalHeaders, String deltaHeaders)
    {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * Per spec, the physical connection must be failed.
     * <p>
     * <a href="https://tools.ietf.org/html/draft-ietf-hybi-websocket-multiplexing-08#section-18">Section 18. Fail the Physical Connection.</a>
     * 
     * <blockquote> To _Fail the Physical Connection_, an endpoint MUST send a DropChannel multiplex control block with objective channel ID of 0 and drop
     * reason code in the range of 2000-2999, and then _Fail the WebSocket Connection_ on the physical connection with status code of 1011. </blockquote>
     */
    private void mustFailPhysicalConnection(MuxPhysicalConnectionException muxe)
    {
        // TODO: stop muxer from receiving incoming sub-channel traffic.

        MuxDropChannel drop = muxe.getMuxDropChannel();
        LOG.warn(muxe);
        try
        {
            generator.generate(null,drop);
        }
        catch (IOException ioe)
        {
            LOG.warn("Unable to send mux DropChannel",ioe);
        }

        String reason = "Mux[MUST FAIL]" + drop.getPhrase();
        reason = StringUtil.truncate(reason,ControlFrame.MAX_CONTROL_PAYLOAD);
        this.physicalConnection.close(StatusCode.SERVER_ERROR,reason);

        // TODO: trigger abnormal close for all sub-channels.
    }

    /**
     * Incoming mux control block, destined for the control channel (id 0)
     */
    @Override
    public void onMuxAddChannelRequest(MuxAddChannelRequest request)
    {
        if (policy.getBehavior() == WebSocketBehavior.CLIENT)
        {
            throw new MuxPhysicalConnectionException(MuxDropChannel.Reason.UNKNOWN_MUX_CONTROL_BLOCK,"AddChannelRequest not allowed per spec");
        }

        if (request.getRsv() != 0)
        {
            throw new MuxPhysicalConnectionException(MuxDropChannel.Reason.UNKNOWN_REQUEST_ENCODING,"RSV Not allowed to be set");
        }

        // Pre-allocate channel.
        long channelId = request.getChannelId();
        MuxChannel channel = getChannel(channelId, true);

        // submit to upgrade handshake process
        try
        {
            switch (request.getEncoding())
            {
                case MuxAddChannelRequest.IDENTITY_ENCODING:
                {
                    UpgradeRequest idenReq = MuxRequest.parse(request.getHandshake());
                    addServer.handshake(this,channel,idenReq);
                    break;
                }
                case MuxAddChannelRequest.DELTA_ENCODING:
                {
                    UpgradeRequest baseReq = addServer.getPhysicalHandshakeRequest();
                    UpgradeRequest deltaReq = MuxRequest.parse(request.getHandshake());
                    UpgradeRequest mergedReq = MuxRequest.merge(baseReq,deltaReq);

                    addServer.handshake(this,channel,mergedReq);
                    break;
                }
                default:
                {
                    throw new MuxPhysicalConnectionException(MuxDropChannel.Reason.BAD_REQUEST,"Unrecognized request encoding");
                }
            }
        }
        catch (MuxPhysicalConnectionException e)
        {
            throw e;
        }
        catch (Throwable t)
        {
            throw new MuxPhysicalConnectionException(MuxDropChannel.Reason.BAD_REQUEST,"Unable to parse request",t);
        }
    }

    /**
     * Incoming mux control block, destined for the control channel (id 0)
     */
    @Override
    public void onMuxAddChannelResponse(MuxAddChannelResponse response)
    {
        if (policy.getBehavior() == WebSocketBehavior.SERVER)
        {
            throw new MuxPhysicalConnectionException(MuxDropChannel.Reason.UNKNOWN_MUX_CONTROL_BLOCK,"AddChannelResponse not allowed per spec");
        }

        if (response.getRsv() != 0)
        {
            throw new MuxPhysicalConnectionException(MuxDropChannel.Reason.UNKNOWN_RESPONSE_ENCODING,"RSV Not allowed to be set");
        }

        // Process channel
        long channelId = response.getChannelId();
        MuxChannel channel = getChannel(channelId,false);

        // Process Response headers
        try
        {
            // Parse Response

            // TODO: Sec-WebSocket-Accept header
            // TODO: Sec-WebSocket-Extensions header
            // TODO: Setup extensions
            // TODO: Setup sessions

            // Trigger channel open
            channel.onOpen();
        }
        catch (Throwable t)
        {
            throw new MuxPhysicalConnectionException(MuxDropChannel.Reason.BAD_RESPONSE,"Unable to parse response",t);
        }
    }

    /**
     * Incoming mux control block, destined for the control channel (id 0)
     */
    @Override
    public void onMuxDropChannel(MuxDropChannel drop)
    {
        // Process channel
        long channelId = drop.getChannelId();
        MuxChannel channel = getChannel(channelId,false);

        String reason = "Mux " + drop.toString();
        reason = StringUtil.truncate(reason,(ControlFrame.MAX_CONTROL_PAYLOAD - 2));
        channel.close(StatusCode.PROTOCOL,reason);
        // TODO: set channel to inactive?
    }

    /**
     * Incoming mux-unwrapped frames, destined for a sub-channel
     */
    @Override
    public void onMuxedFrame(MuxedFrame frame)
    {
        MuxChannel subchannel = channels.get(frame.getChannelId());
        subchannel.incomingFrame(frame);
    }

    @Override
    public void onMuxException(MuxException e)
    {
        if (e instanceof MuxPhysicalConnectionException)
        {
            mustFailPhysicalConnection((MuxPhysicalConnectionException)e);
        }

        LOG.warn(e);
        // TODO: handle other (non physical) mux exceptions how?
    }

    /**
     * Incoming mux control block, destined for the control channel (id 0)
     */
    @Override
    public void onMuxFlowControl(MuxFlowControl flow)
    {
        if (flow.getSendQuotaSize() > 0x7F_FF_FF_FF_FF_FF_FF_FFL)
        {
            throw new MuxPhysicalConnectionException(MuxDropChannel.Reason.SEND_QUOTA_OVERFLOW,"Send Quota Overflow");
        }

        // Process channel
        long channelId = flow.getChannelId();
        MuxChannel channel = getChannel(channelId,false);

        // TODO: set channel quota
    }

    /**
     * Incoming mux control block, destined for the control channel (id 0)
     */
    @Override
    public void onMuxNewChannelSlot(MuxNewChannelSlot slot)
    {
        if (policy.getBehavior() == WebSocketBehavior.SERVER)
        {
            throw new MuxPhysicalConnectionException(MuxDropChannel.Reason.UNKNOWN_MUX_CONTROL_BLOCK,"NewChannelSlot not allowed per spec");
        }

        if (slot.isFallback())
        {
            if (slot.getNumberOfSlots() == 0)
            {
                throw new MuxPhysicalConnectionException(MuxDropChannel.Reason.UNKNOWN_MUX_CONTROL_BLOCK,"Cannot have 0 number of slots during fallback");
            }
            if (slot.getInitialSendQuota() == 0)
            {
                throw new MuxPhysicalConnectionException(MuxDropChannel.Reason.UNKNOWN_MUX_CONTROL_BLOCK,"Cannot have 0 initial send quota during fallback");
            }
        }

        // TODO: handle channel slot
    }

    /**
     * Outgoing frame, without mux encapsulated payload.
     */
    public void output(long channelId, Frame frame, WriteCallback callback)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("output({}, {})",channelId,frame,callback);
        }
        generator.generate(channelId,frame,callback);
    }

    /**
     * Write an OP out the physical connection.
     * 
     * @param op
     *            the mux operation to write
     * @throws IOException
     */
    public void output(MuxControlBlock op) throws IOException
    {
        generator.generate(null,op);
    }

    public void setAddClient(MuxAddClient addClient)
    {
        this.addClient = addClient;
    }

    public void setAddServer(MuxAddServer addServer)
    {
        this.addServer = addServer;
    }

    public void setOutgoingFramesHandler(OutgoingFrames outgoing)
    {
        this.generator.setOutgoing(outgoing);
    }

    /**
     * Set the remote address of the physical connection.
     * <p>
     * This address made available to sub-channels.
     * 
     * @param remoteAddress
     *            the remote address
     */
    public void setRemoteAddress(InetSocketAddress remoteAddress)
    {
        this.remoteAddress = remoteAddress;
    }

    @Override
    public String toString()
    {
        return String.format("Muxer[subChannels.size=%d]",channels.size());
    }
}
