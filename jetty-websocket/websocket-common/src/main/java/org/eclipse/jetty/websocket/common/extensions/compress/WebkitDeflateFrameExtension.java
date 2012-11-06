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


import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.common.extensions.AbstractExtension;

/**
 * Implementation of the <a href="https://tools.ietf.org/id/draft-tyoshino-hybi-websocket-perframe-deflate-05.txt">x-webkit-deflate-frame</a> extension seen out
 * in the wild.
 */
public class WebkitDeflateFrameExtension extends AbstractExtension
{
    private DeflateCompressionMethod method;

    @Override
    public javax.net.websocket.extensions.FrameHandler createIncomingFrameHandler(javax.net.websocket.extensions.FrameHandler incoming)
    {
        return new DecompressFrameHandler(incoming,method,getBufferPool());
    }

    @Override
    public javax.net.websocket.extensions.FrameHandler createOutgoingFrameHandler(javax.net.websocket.extensions.FrameHandler outgoing)
    {
        return new CompressFrameHandler(outgoing,method,getBufferPool());
    }

    /**
     * Indicates use of RSV1 flag for indicating deflation is in use.
     * <p>
     * Also known as the "COMP" framing header bit
     */
    @Override
    public boolean isRsv1User()
    {
        return true;
    }

    /**
     * Indicate that this extensions is now responsible for TEXT Data Frame compliance to the WebSocket spec.
     */
    @Override
    public boolean isTextDataDecoder()
    {
        return true;
    }

    @Override
    public void setConfig(ExtensionConfig config)
    {
        super.setConfig(config);

        method = new DeflateCompressionMethod();
    }

    @Override
    public String toString()
    {
        return this.getClass().getSimpleName() + "[]";
    }
}