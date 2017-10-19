//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.core.io.WebSocketConnection;

/**
 * The Core WebSocket Session.
 *
 */
public class WebSocketChannel extends ContainerLifeCycle implements IncomingFrames, OutgoingFrames
{
    private Logger LOG = Log.getLogger(this.getClass());

    private final WebSocketChannelState state = new WebSocketChannelState();
    private final WebSocketPolicy policy;
    private final FrameHandler handler;
    private final ExtensionStack extensionStack;
    private final String subprotocol;

    private WebSocketConnection connection;

    public WebSocketChannel(FrameHandler handler,
    		WebSocketPolicy policy,
    		ExtensionStack extensionStack,
    		String subprotocol)
    {
        this.handler = handler;
        this.policy = policy;
        this.extensionStack = extensionStack;
        this.subprotocol = subprotocol;
        addBean(extensionStack,true);
        extensionStack.setNextIncoming(new IncomingState());
        extensionStack.setNextOutgoing(new OutgoingState());
        addBean(handler, true);
        handler.setWebSocketChannel(this);
    }

    public ExtensionStack getExtensionStack()
    {
        return extensionStack;
    }

    public FrameHandler getHandler()
    {
        return handler;
    }

    public String getSubprotocol()
    {
        return subprotocol;
    }
    
    public Object getAttachment()
    {
        return null; // TODO
    }
    
    public void setWebSocketConnection(WebSocketConnection connection)
    {
        this.connection = connection;
    }

    public void close(int statusCode, String reason, Callback callback)
    {
        // TODO guard for multiple closes?

        outgoingFrame(new CloseFrame().setPayload(statusCode, reason), callback, BatchMode.OFF);
    }

    public void close(CloseStatus closeStatus, Callback callback)
    {
        close(closeStatus.getCode(), closeStatus.getReason(), callback);
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    /**
     * Process an Error event seen by the Session and/or Connection
     *
     * @param cause the cause
     */
    public void processError(Throwable cause)
    {
        // Forward Errors to Local WebSocket EndPoint
        handler.onError(cause);

        if (cause instanceof Utf8Appendable.NotUtf8Exception)
        {
            close(WebSocketConstants.BAD_PAYLOAD, cause.getMessage(), Callback.NOOP);
        }
        else if (cause instanceof SocketTimeoutException)
        {
            // A path often seen in Windows
            close(WebSocketConstants.SHUTDOWN, cause.getMessage(), Callback.NOOP);
        }
        else if (cause instanceof IOException)
        {
            close(WebSocketConstants.PROTOCOL, cause.getMessage(), Callback.NOOP);
        }
        else if (cause instanceof SocketException)
        {
            // A path unique to Unix
            close(WebSocketConstants.SHUTDOWN, cause.getMessage(), Callback.NOOP);
        }
        else if (cause instanceof CloseException)
        {
            CloseException ce = (CloseException) cause;
            Callback callback = Callback.NOOP;

            // Force disconnect for protocol breaking status codes
            switch (ce.getStatusCode())
            {
                case WebSocketConstants.PROTOCOL:
                case WebSocketConstants.BAD_DATA:
                case WebSocketConstants.BAD_PAYLOAD:
                case WebSocketConstants.MESSAGE_TOO_LARGE:
                case WebSocketConstants.POLICY_VIOLATION:
                case WebSocketConstants.SERVER_ERROR:
                {
                    callback = Callback.NOOP;
                }
            }

            close(ce.getStatusCode(), ce.getMessage(), callback);
        }
        else if (cause instanceof WebSocketTimeoutException)
        {
            close(WebSocketConstants.SHUTDOWN, cause.getMessage(), Callback.NOOP);
        }
        else
        {
            LOG.warn("Unhandled Error (closing connection)", cause);

            // Exception on end-user WS-Endpoint.
            // Fast-fail & close connection with reason.
            int statusCode = WebSocketConstants.SERVER_ERROR;
            if (getPolicy().getBehavior() == WebSocketBehavior.CLIENT)
            {
                statusCode = WebSocketConstants.POLICY_VIOLATION;
            }
            close(statusCode, cause.getMessage(), Callback.NOOP);
        }
    }

    /**
     * Open/Activate the session.
     */
    public void onOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen() {}", this);

        try
        {
            start();

            // Upgrade success
            state.onConnected();

            if (LOG.isDebugEnabled())
                LOG.debug("ConnectionState: Transition to CONNECTED");

            try
            {
                // Open connection and handler
                state.onOpen();
                handler.onOpen();                
                if (LOG.isDebugEnabled())
                    LOG.debug("ConnectionState: Transition to OPEN");
            }
            catch (Throwable t)
            {
                LOG.warn("Error during OPEN", t);
                processError(new CloseException(WebSocketConstants.SERVER_ERROR, t));
            }

            // TODO what if we are going to start without read interest?  (eg reactive stream???)
            connection.fillInterested();
        }
        catch (Throwable t)
        {
            processError(t); // Handle error
        }
    }

    public WebSocketConnection getConnection()
    {
        return this.connection;
    }

    public Executor getExecutor()
    {
        return this.connection.getExecutor();
    }

    public ByteBufferPool getBufferPool()
    {
        return this.connection.getBufferPool();
    }

    @Override
    protected void doStop() throws Exception
    {
        this.connection.disconnect();
        super.doStop();
    }

    @Override
    public void incomingFrame(Frame frame, Callback callback)
    {
        extensionStack.incomingFrame(frame, callback);
    }

    @Override
    public void outgoingFrame(Frame frame, Callback callback, BatchMode batchMode) 
    {
        if (frame instanceof CloseFrame)
        {
            if (!state.onCloseOut(((CloseFrame)frame).getCloseStatus()))
            {
                callback.failed(new IOException("Already Closed or Closing"));
                return;
            }
            
            if (state.isClosed())
                callback = new Callback.Nested(callback)
                {
                    @Override
                    public void succeeded()
                    {
                        super.succeeded();
                        handler.onClosed(state.getCloseStatus());
                    }

                    @Override
                    public void failed(Throwable x)
                    {
                        super.failed(x);
                        handler.onClosed(state.getCloseStatus());
                    }
               
                };
              
        }

        // TODO, what is the mutual exclusion contract here? is this thread safe from here?
        // TODO should throw pending write exception to allow only one.  APIs must coordinate
  
        extensionStack.outgoingFrame(frame,callback,batchMode);
    }

    private class IncomingState implements IncomingFrames
    {
        @Override
        public void incomingFrame(Frame frame, Callback callback)
        {
            try
            {
                
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("incomingFrame({}, {}) - connectionState={}, handler={}",
                              frame, callback, state, handler);
                }
                if (state.isInOpen())
                {
                    // Handle inbound close
                    if (frame.getOpCode() == OpCode.CLOSE)
                    {
                        if (!state.onCloseIn(((CloseFrame)frame).getCloseStatus()))
                        {
                            callback.failed(new IOException("already closed"));
                        }
                        // TODO wrap callback to trigger action after close handling complete
                    }           
                    
                    // Handle the frame
                    handler.onFrame(frame, callback);
                   
                    // Ensure outbound close sent
                    if (frame.getOpCode() == OpCode.CLOSE )
                    {
                        if (state.isOutOpen())
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("ConnectionState: Sending Close response");
                            CloseFrame closeframe = (CloseFrame)frame;
                            CloseStatus closeStatus = closeframe.getCloseStatus();

                            // TODO replace with completing callback baseclass
                            // TODO look at why close status is not actually sent (see 7.9.6 ???)
                            close(closeStatus, new Callback.Nested(callback)
                            {
                                @Override
                                public void succeeded()
                                {
                                    super.succeeded();
                                    handler.onClosed(state.getCloseStatus());
                                }

                                @Override
                                public void failed(Throwable x)
                                {
                                    super.failed(x);
                                    handler.onClosed(state.getCloseStatus());
                                }
                           
                            });
                            return;
                        }
                        else
                        {
                            // Do we need to wait for a flush?
                            handler.onClosed(state.getCloseStatus());
                        }
                    }

                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Discarding post EOF frame - {}", frame);
                }
            }
            catch (Throwable t)
            {
                callback.failed(t);
            }
        }
    }

    private class OutgoingState implements OutgoingFrames
    {
        // TODO check sufficient mutually exclusion and memory barriers from channel.outgoingFrame
        byte partial = -1;
        
        @Override
        public void outgoingFrame(Frame frame, Callback callback, BatchMode batchMode)
        {
            // TODO This needs to be reviewed against RFC for possible interleavings
            switch(partial)
            {
                case OpCode.BINARY:
                case OpCode.TEXT:
                    if (frame.getOpCode()!=OpCode.CONTINUATION)
                        callback.failed(new IllegalStateException());
                    if (frame.isFin())
                        partial = -1;
                    break;
                case -1:
                    if (!frame.isFin())
                        partial = frame.getOpCode();
                    break;
                default:
                    callback.failed(new IllegalStateException());       
            }
            
            connection.outgoingFrame(frame,callback,batchMode);
        }
    }

}
