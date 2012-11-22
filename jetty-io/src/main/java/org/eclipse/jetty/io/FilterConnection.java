//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.io;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * TODO this class is still experimental
 */
public class FilterConnection extends AbstractConnection
{
    private static final Logger LOG = Log.getLogger(FilterConnection.class);
    private static final boolean DEBUG = LOG.isDebugEnabled(); // Easy for the compiler to remove the code if DEBUG==false
    
    public interface Filter
    {
        /**
         * <p>Callback method invoked when a connection from a remote client has been accepted.</p>
         * <p>The {@code socket} parameter can be used to extract socket address information of
         * the remote client.</p>
         *
         * @param endpoint the socket associated with the remote client
         */
        public void opened(EndPoint endpoint);

        /**
         * <p>Callback method invoked when bytes sent by a remote client arrived on the server.</p>
         *
         * @param endPoint the socket associated with the remote client
         * @param bytes  the read-only buffer containing the incoming bytes
         */
        public void incoming(EndPoint endPoint, ByteBuffer bytes);

        /**
         * <p>Callback method invoked when bytes are sent to a remote client from the server.</p>
         * <p>This method is invoked after the bytes have been actually written to the remote client.</p>
         *
         * @param endPoint the socket associated with the remote client
         * @param bytes  the read-only buffer containing the outgoing bytes
         */
        public void outgoing(EndPoint endPoint, ByteBuffer bytes);

        /**
         * <p>Callback method invoked when a connection to a remote client has been closed.</p>
         * <p>The {@code socket} parameter is already closed when this method is called, so it
         * cannot be queried for socket address information of the remote client.<br />
         * However, the {@code socket} parameter is the same object passed to {@link #opened(Socket)},
         * so it is possible to map socket information in {@link #opened(Socket)} and retrieve it
         * in this method.
         *
         * @param endpoint the (closed) socket associated with the remote client
         */
        public void closed(EndPoint endpoint);
    }
    
    public static class DebugFilter implements Filter
    {
        public DebugFilter()
        {
        }
        
        @Override
        public void opened(EndPoint endpoint)
        {
            if (DEBUG)
                LOG.debug("{}@{} opened%n",endpoint.getClass().getSimpleName(),Integer.toString(endpoint.hashCode(),16));            
        }

        @Override
        public void incoming(EndPoint endpoint, ByteBuffer bytes)
        {
            if (DEBUG)
                LOG.debug("{}@{} >>> {}%n",endpoint.getClass().getSimpleName(),Integer.toString(endpoint.hashCode(),16),BufferUtil.toDetailString(bytes));  
        }

        @Override
        public void outgoing(EndPoint endpoint, ByteBuffer bytes)
        {
            if (DEBUG)
                LOG.debug("{}@{} <<< {}%n",endpoint.getClass().getSimpleName(),Integer.toString(endpoint.hashCode(),16),BufferUtil.toDetailString(bytes));  
        }

        @Override
        public void closed(EndPoint endpoint)
        {
            if (DEBUG)
                LOG.debug("{}@{} closed%n",endpoint.getClass().getSimpleName(),Integer.toString(endpoint.hashCode(),16)); 
        }   
    }

    public static class DumpToFileFilter implements Filter
    {
        final ConcurrentHashMap<EndPoint,OutputStream> _in = new ConcurrentHashMap<>();
        final ConcurrentHashMap<EndPoint,OutputStream> _out = new ConcurrentHashMap<>();
        final File _directory;
        final String _prefix;
        final boolean _deleteOnExit;
        
        public DumpToFileFilter()
        {
            this(new File(System.getProperty("java.io.tmpdir")+File.separator+"FilterConnection"),true);
        }

        public DumpToFileFilter(File directory, boolean deleteOnExit)
        {
            this(directory,"dump-",deleteOnExit);
        }

        public DumpToFileFilter(String prefix)
        {
            this(new File(System.getProperty("java.io.tmpdir")+File.separator+"FilterConnection"),prefix,true);
        }
        
        public DumpToFileFilter(
            @Name("directory") File directory, 
            @Name("prefix") String prefix, 
            @Name("deleteOnExit") boolean deleteOnExit)
        {
            _directory=directory;
            _prefix=prefix;
            _deleteOnExit=deleteOnExit;
            if (!_directory.exists() && !_directory.mkdirs())
                throw new IllegalArgumentException("cannot create "+directory);
            if (!_directory.isDirectory())
                throw new IllegalArgumentException("not directory "+directory);
            if (!_directory.canWrite())
                throw new IllegalArgumentException("cannot write "+directory);
        }
        
        @Override
        public void opened(EndPoint endpoint)
        {          
            try
            {
                File in = new File(_directory,_prefix+Integer.toHexString(endpoint.hashCode())+".in");
                File out = new File(_directory,_prefix+Integer.toHexString(endpoint.hashCode())+".out");
                if (_deleteOnExit)
                {
                    in.deleteOnExit();
                    out.deleteOnExit();
                }
                _in.put(endpoint,new FileOutputStream(in));
                _out.put(endpoint,new FileOutputStream(out));
            }
            catch (FileNotFoundException e)
            {
                LOG.warn(e);
            }
        }

        @Override
        public void incoming(EndPoint endpoint, ByteBuffer bytes)
        {
            try
            {
                OutputStream out=_in.get(endpoint);
                if (out!=null)
                    out.write(BufferUtil.toArray(bytes));
            }
            catch(IOException e)
            {
                LOG.warn(e);
            }
        }

        @Override
        public void outgoing(EndPoint endpoint, ByteBuffer bytes)
        {
            try
            {
                OutputStream out=_out.get(endpoint);
                if (out!=null)
                    out.write(BufferUtil.toArray(bytes));
            }
            catch(IOException e)
            {
                LOG.warn(e);
            }
        }

        @Override
        public void closed(EndPoint endpoint)
        {
            try
            {
                OutputStream out=_in.remove(endpoint);
                if (out!=null)
                    out.close();
            }
            catch(IOException e)
            {
                LOG.warn(e);
            }
            try
            {
                OutputStream out=_out.remove(endpoint);
                if (out!=null)
                    out.close();
            }
            catch(IOException e)
            {
                LOG.warn(e);
            }
        }   
    }
    private final ByteBufferPool _bufferPool;
    private final FilteredEndPoint _filterEndPoint;
    private final int _outputBufferSize;
    private final List<Filter> _filters = new CopyOnWriteArrayList<>();

    public FilterConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, int outputBufferSize)
    {
        super(endPoint, executor, false);
        _bufferPool = byteBufferPool;
        _filterEndPoint = newFilterEndPoint();
        _outputBufferSize=outputBufferSize;
    }

    protected FilteredEndPoint newFilterEndPoint()
    {
        return new FilteredEndPoint();
    }

    public FilteredEndPoint getFilterEndPoint()
    {
        return _filterEndPoint;
    }
    
    public void addFilter(Filter filter)
    {
        _filters.add(filter);
    }
    
    public boolean removeFilter(Filter listener)
    {
        return _filters.remove(listener);
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        for (Filter filter: _filters)
            filter.opened(getEndPoint());
        getFilterEndPoint().getConnection().onOpen();
    }

    @Override
    public void onClose()
    {
        for (Filter filter: _filters)
            filter.closed(getEndPoint());
        _filterEndPoint.getConnection().onClose();
        super.onClose();
    }

    @Override
    public int getMessagesIn()
    {
        return _filterEndPoint.getConnection().getMessagesIn();
    }

    @Override
    public int getMessagesOut()
    {
        return _filterEndPoint.getConnection().getMessagesOut();
    }

    @Override
    public void close()
    {
        getFilterEndPoint().getConnection().close();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void onFillable()
    {
        if (DEBUG)
            LOG.debug("onFillable enter {}", getEndPoint());

        // wake up whoever is doing the fill or the flush so they can
        // do all the filling, unwrapping, wrapping and flushing
        _filterEndPoint.getFillInterest().fillable();

        if (DEBUG)
            LOG.debug("onFillable exit {}", getEndPoint());
    }

    /* ------------------------------------------------------------ */
    @Override
    public void onFillInterestedFailed(Throwable cause)
    {
        _filterEndPoint.getFillInterest().onFail(cause);
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return String.format("%s@%x -> %s",
            FilterConnection.class.getSimpleName(),
            hashCode(),
            _filterEndPoint.getConnection());
    }

    /* ------------------------------------------------------------ */
    public class FilteredEndPoint extends AbstractEndPoint
    {
        private final Callback _writeCB = new Callback()
        {
            @Override
            public void succeeded()
            {
                if (BufferUtil.isEmpty(_outBuffer))
                {
                    _bufferPool.release(_outBuffer);
                    _outBuffer=null;
                }
                getWriteFlusher().completeWrite();
            }

            @Override
            public void failed(Throwable x)
            {
                if (BufferUtil.isEmpty(_outBuffer))
                {
                    _bufferPool.release(_outBuffer);
                    _outBuffer=null;
                }
                getWriteFlusher().onFail(x);
            }
        };

        private ByteBuffer _outBuffer;
        
        public FilteredEndPoint()
        {
            super(null,getEndPoint().getLocalAddress(), getEndPoint().getRemoteAddress());
            setIdleTimeout(getEndPoint().getIdleTimeout());
        }

        @Override
        public void setIdleTimeout(long idleTimeout)
        {
            super.setIdleTimeout(idleTimeout);
            getEndPoint().setIdleTimeout(idleTimeout);
        }

        @Override
        protected void onIncompleteFlush()
        {
            if (BufferUtil.isEmpty(_outBuffer))
            {
                _bufferPool.release(_outBuffer);
                _outBuffer=null;
                getWriteFlusher().completeWrite();
            }
            else
                getEndPoint().write(_writeCB,_outBuffer);
        }

        @Override
        protected boolean needsFill() throws IOException
        {
            FilterConnection.this.fillInterested();
            return false;
        }


        @Override
        public synchronized int fill(ByteBuffer buffer) throws IOException
        {
            if (DEBUG)
                LOG.debug("{} fill enter", FilterConnection.this);
            
            int orig=buffer.remaining();
            
            int filled = getEndPoint().fill(buffer);
            
            if (orig>0)
                buffer.position(buffer.position()+orig);
            for (Filter filter: _filters)
                filter.incoming(getEndPoint() ,buffer);
            if (orig>0)
                buffer.position(buffer.position()-orig);
            
            if (DEBUG)
                LOG.debug("{} fill {} exit", FilterConnection.this,filled);
            return filled;
            
        }

        @Override
        public synchronized boolean flush(ByteBuffer... buffers) throws IOException
        {
            if (DEBUG)
                LOG.debug("{} flush enter {}", FilterConnection.this, Arrays.toString(buffers));
            
            if (BufferUtil.hasContent(_outBuffer))
                return false;

            if (_outBuffer==null)
                _outBuffer=_bufferPool.acquire(_outputBufferSize,true);
      
            // Take as much data as we can
            boolean all_taken=true;
            for (ByteBuffer buffer : buffers)
            {
                if (buffer==null)
                    continue;
                BufferUtil.flipPutFlip(buffer,_outBuffer);
                
                if (BufferUtil.hasContent(buffer))
                {
                    all_taken=false;
                    break;
                }
            }
            
            for (Filter filter: _filters)
                filter.outgoing(getEndPoint() ,_outBuffer);
            
            boolean flushed = getEndPoint().flush(_outBuffer);
            if (BufferUtil.isEmpty(_outBuffer))
            {
                _bufferPool.release(_outBuffer);
                _outBuffer=null;
            }
            
            if (DEBUG)
                LOG.debug("{} flush exit, consumed {}", FilterConnection.this, flushed);
        
            return all_taken && flushed;
        }

        @Override
        public void shutdownOutput()
        {
            getEndPoint().shutdownOutput();
        }

        @Override
        public boolean isOutputShutdown()
        {
            return getEndPoint().isOutputShutdown();
        }

        @Override
        public void close()
        {
            getEndPoint().close();
        }

        @Override
        public boolean isOpen()
        {
            return getEndPoint().isOpen();
        }

        @Override
        public Object getTransport()
        {
            return getEndPoint();
        }

        @Override
        public boolean isInputShutdown()
        {
            return getEndPoint().isInputShutdown();
        }
        
        public EndPoint getWrappedEndPoint()
        {
            return getEndPoint();
        }

        @Override
        public String toString()
        {
            return super.toString()+"->"+getEndPoint().toString();
        }
    }
}
