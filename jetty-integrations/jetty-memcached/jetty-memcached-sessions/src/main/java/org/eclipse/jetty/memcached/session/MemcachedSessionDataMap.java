//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.memcached.session;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

import net.rubyeye.xmemcached.MemcachedClient;
import net.rubyeye.xmemcached.XMemcachedClientBuilder;
import net.rubyeye.xmemcached.transcoders.SerializingTranscoder;
import org.eclipse.jetty.session.SessionContext;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.SessionDataMap;
import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;

/**
 * MemcachedSessionDataMap
 *
 * Uses memcached as a cache for SessionData.
 */
@ManagedObject
public class MemcachedSessionDataMap extends AbstractLifeCycle implements SessionDataMap
{
    public static final String DEFAULT_HOST = "localhost";
    public static final String DEFAULT_PORT = "11211";
    protected MemcachedClient _client;
    protected int _expirySec = 0;
    protected boolean _heartbeats = true;
    protected XMemcachedClientBuilder _builder;
    protected SessionContext _context;

    /**
     * SessionDataTranscoder
     *
     * We override memcached deserialization to use our classloader-aware
     * ObjectInputStream.
     */
    public static class SessionDataTranscoder extends SerializingTranscoder
    {

        @Override
        protected Object deserialize(byte[] in)
        {
            Object rv = null;

            if (in != null)
            {
                try (ByteArrayInputStream bis = new ByteArrayInputStream(in);
                     ClassLoadingObjectInputStream is = new ClassLoadingObjectInputStream(bis))
                {
                    rv = is.readObject();
                }
                catch (IOException e)
                {
                    log.error("Caught IOException decoding " + in.length + " bytes of data", e);
                }
                catch (ClassNotFoundException e)
                {
                    log.error("Caught CNFE decoding " + in.length + " bytes of data", e);
                }
            }
            return rv;
        }
    }

    /**
     * @param host address of memcache server
     * @param port address of memcache server
     */
    public MemcachedSessionDataMap(String host, String port)
    {
        if (host == null || port == null)
            throw new IllegalArgumentException("Host: " + host + " port: " + port);
        _builder = new XMemcachedClientBuilder(host + ":" + port);
    }

    public MemcachedSessionDataMap(List<InetSocketAddress> addresses)
    {
        _builder = new XMemcachedClientBuilder(addresses);
    }

    public MemcachedSessionDataMap(List<InetSocketAddress> addresses, int[] weights)
    {
        _builder = new XMemcachedClientBuilder(addresses, weights);
    }

    /**
     * @return the builder
     */
    public XMemcachedClientBuilder getBuilder()
    {
        return _builder;
    }

    /**
     * @param sec the expiry to use in seconds
     */
    public void setExpirySec(int sec)
    {
        _expirySec = sec;
    }

    /**
     * Expiry time for memached entries.
     *
     * @return memcached expiry time in sec
     */
    @ManagedAttribute(value = "memcached expiry time in sec", readonly = true)
    public int getExpirySec()
    {
        return _expirySec;
    }

    @ManagedAttribute(value = "enable memcached heartbeats", readonly = true)
    public boolean isHeartbeats()
    {
        return _heartbeats;
    }

    public void setHeartbeats(boolean heartbeats)
    {
        _heartbeats = heartbeats;
    }

    @Override
    public void initialize(SessionContext context)
    {
        if (isStarted())
            throw new IllegalStateException("Context set after MemcachedSessionDataMap started");
        
        try
        {
            _context = context;
            _builder.setTranscoder(new SessionDataTranscoder());
            _client = _builder.build();
            _client.setEnableHeartBeat(isHeartbeats());
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public SessionData load(String id) throws Exception
    {
        if (!isStarted())
            throw new IllegalStateException("Not started");
        
        final FuturePromise<SessionData> result = new FuturePromise<>();

        Runnable r = () ->
        {
            try
            {
                result.succeeded(_client.get(id));
            }
            catch (Exception e)
            {
                result.failed(e);
            }
        };

        _context.run(r);
        return result.getOrThrow();
    }

    @Override
    public void store(String id, SessionData data) throws Exception
    {
        if (!isStarted())
            throw new IllegalStateException("Not started");
        
        final FuturePromise<Void> result = new FuturePromise<>();
        Runnable r = () ->
        {
            try
            {
                _client.set(id, _expirySec, data);
                result.succeeded(null);
            }
            catch (Exception e)
            {
                result.failed(e);
            }
        };
        _context.run(r);
        result.getOrThrow();
    }

    @Override
    public boolean delete(String id) throws Exception
    {
        _client.delete(id);
        return true; //delete returns false if the value didn't exist
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        if (_client != null)
        {
            _client.shutdown();
            _client = null;
        }
    }
}
