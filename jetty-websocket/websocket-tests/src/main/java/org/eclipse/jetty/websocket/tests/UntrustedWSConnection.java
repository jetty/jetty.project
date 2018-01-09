//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.SharedBlockingCallback;
import org.eclipse.jetty.util.SharedBlockingCallback.Blocker;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.core.io.WebSocketCoreConnection;

/**
 * A wrapper for {@link WebSocketCoreConnection} to
 * allow for untrusted behaviors, such as arbitrary writes and flushes on network.
 */
public class UntrustedWSConnection
{
    private static final Logger LOG = Log.getLogger(UntrustedWSConnection.class);
    private final WebSocketCoreConnection internalConnection;
    private final SharedBlockingCallback writeBlocker;
    
    public UntrustedWSConnection(WebSocketCoreConnection connection)
    {
        this.internalConnection = connection;
        this.writeBlocker = new SharedBlockingCallback();
    }
    
    /**
     * Perform write flush.
     */
    public void flush() throws IOException
    {
        internalConnection.getEndPoint().flush();
    }
    
    /**
     * Forward a frame to the {@link org.eclipse.jetty.websocket.api.extensions.OutgoingFrames} handler
     * @param frame the frame to send out
     */
    public void write(Frame frame) throws Exception
    {
        BlockerFrameCallback blocker = new BlockerFrameCallback();
        this.internalConnection.outgoingFrame(frame, blocker, BatchMode.OFF);
        blocker.block();
    }
    
    /**
     * Write arbitrary bytes out the active connection.
     *
     * @param buf the buffer to write
     * @throws IOException if unable to write
     */
    public void writeRaw(ByteBuffer buf) throws IOException
    {
        if(LOG.isDebugEnabled())
        {
            LOG.debug("{} writeRaw({})", this.internalConnection.getPolicy().getBehavior(), BufferUtil.toDetailString(buf));
        }
        
        try(Blocker blocker = writeBlocker.acquire())
        {
            internalConnection.getEndPoint().write(blocker, buf);
            blocker.block();
        }
    }
    
    /**
     * Write arbitrary bytes out the active connection.
     *
     * @param buf the buffer to write
     * @param numBytes the number of bytes from the buffer to write
     * @throws IOException if unable to write
     */
    public void writeRaw(ByteBuffer buf, int numBytes) throws IOException
    {
        if(LOG.isDebugEnabled())
        {
            LOG.debug("{} writeRaw({}, numBytes={})", this.internalConnection.getPolicy().getBehavior(), BufferUtil.toDetailString(buf), numBytes);
        }
        
        try(Blocker blocker = writeBlocker.acquire())
        {
            ByteBuffer slice = buf.slice();
            
            int writeLen = Math.min(buf.remaining(), numBytes);
            slice.limit(writeLen);
            internalConnection.getEndPoint().write(blocker, slice);
            blocker.block();
            buf.position(buf.position() + writeLen);
        }
    }
    
    /**
     * Write arbitrary String out the active connection.
     *
     * @param str the string, converted to UTF8 bytes, then written
     * @throws IOException if unable to write
     */
    public void writeRaw(String str) throws IOException
    {
        LOG.debug("write((String)[{}]){}{})",str.length(),'\n',str);
        writeRaw(BufferUtil.toBuffer(str, StandardCharsets.UTF_8));
    }
    
    /**
     * Write arbitrary buffer out the active connection, slowly.
     *
     * @param buf the buffer to write
     * @param segmentSize the segment size to write, with a {@link #flush()} after each segment
     * @throws IOException if unable to write
     */
    public void writeRawSlowly(ByteBuffer buf, int segmentSize) throws IOException
    {
        while (buf.remaining() > 0)
        {
            writeRaw(buf,segmentSize);
            flush();
        }
    }
    
    public boolean isOpen()
    {
        return internalConnection.getEndPoint().isOpen();
    }
}
