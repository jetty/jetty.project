//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.compression.gzip;

import java.util.zip.Deflater;

import org.eclipse.jetty.compression.CompressionConfig;
import org.eclipse.jetty.compression.DynamicCompressionCodec;
import org.eclipse.jetty.compression.DynamicCompressionHandler;
import org.eclipse.jetty.compression.DynamicCompressionResponse;
import org.eclipse.jetty.compression.DynamicDecompressionRequest;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.compression.CompressionPool;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.eclipse.jetty.util.compression.InflaterPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GzipDynamicCompressionCodec extends ContainerLifeCycle implements DynamicCompressionCodec
{
    public static final String GZIP_HANDLER_ETAGS = GzipDynamicCompressionCodec.class.getPackageName() + ".ETag";
    public static final int DEFAULT_MIN_GZIP_SIZE = 32;
    public static final int BREAK_EVEN_GZIP_SIZE = 23;
    private static final Logger LOG = LoggerFactory.getLogger(GzipDynamicCompressionCodec.class);
    private int _minGzipSize = DEFAULT_MIN_GZIP_SIZE;

    private DeflaterPool deflaterPool;
    private InflaterPool inflaterPool;
    private Server server;

    public CompressionPool<Deflater>.Entry getDeflaterEntry(Request request, long contentLength)
    {
        if (contentLength >= 0 && contentLength < _minGzipSize)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} excluded minGzipSize {}", this, request);
            return null;
        }

        // check the accept encoding header
        if (!request.getHeaders().contains(HttpHeader.ACCEPT_ENCODING, "gzip"))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} excluded not gzip accept {}", this, request);
            return null;
        }

        return deflaterPool.acquire();
    }

    @Override
    public String getName()
    {
        return "gzip";
    }

    @Override
    public DynamicCompressionResponse newCompressionResponse(Request request, Response response, Callback callback, CompressionConfig config)
    {
        return new GzipDynamicCompressionResponse(this, request, response, callback, config);
    }

    @Override
    public DynamicDecompressionRequest newDecompressionRequest(Request request, CompressionConfig config)
    {
        return new GzipDynamicDecompressionRequest(request, config);
    }

    @Override
    public void setDynamicCompressionHandler(DynamicCompressionHandler dynamicCompressionHandler)
    {
        server = dynamicCompressionHandler.getServer();
    }

    @Override
    protected void doStart() throws Exception
    {
        if (deflaterPool == null)
        {
            deflaterPool = DeflaterPool.ensurePool(server);
            addBean(deflaterPool);
        }

        if (inflaterPool == null)
        {
            inflaterPool = InflaterPool.ensurePool(server);
            addBean(inflaterPool);
        }

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();

        removeBean(inflaterPool);
        inflaterPool = null;
        removeBean(deflaterPool);
        deflaterPool = null;
    }
}
