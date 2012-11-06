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

package org.eclipse.jetty.websocket.common.extensions.compress;


import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.common.extensions.AbstractExtension;

/**
 * Per Message Compression extension for WebSocket.
 * <p>
 * Attempts to follow <a href="https://tools.ietf.org/html/draft-ietf-hybi-permessage-compression-01">draft-ietf-hybi-permessage-compression-01</a>
 */
public class PerMessageCompressionExtension extends AbstractExtension
{
    private static final Logger LOG = Log.getLogger(PerMessageCompressionExtension.class);

    private CompressionMethod method;

    @Override
    public javax.net.websocket.extensions.FrameHandler createIncomingFrameHandler(javax.net.websocket.extensions.FrameHandler incoming)
    {
        return new DecompressMessageHandler(incoming,method,getBufferPool());
    }

    @Override
    public javax.net.websocket.extensions.FrameHandler createOutgoingFrameHandler(javax.net.websocket.extensions.FrameHandler outgoing)
    {
        return new CompressMessageHandler(outgoing,method,getBufferPool());
    }

    /**
     * Indicates use of RSV1 flag for indicating deflation is in use.
     */
    @Override
    public boolean isRsv1User()
    {
        return true;
    }

    @Override
    public boolean isTextDataDecoder()
    {
        // this extension is responsible for text data frames
        return true;
    }

    @Override
    public void setConfig(ExtensionConfig config)
    {
        super.setConfig(config);

        String methodOptions = config.getParameter("method","deflate");
        LOG.debug("Method requested: {}",methodOptions);

        method = new DeflateCompressionMethod();
    }

    @Override
    public String toString()
    {
        return String.format("%s[method=%s]",this.getClass().getSimpleName(),method);
    }
}
