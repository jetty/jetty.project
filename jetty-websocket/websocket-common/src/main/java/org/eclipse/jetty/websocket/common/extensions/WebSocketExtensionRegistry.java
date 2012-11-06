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

package org.eclipse.jetty.websocket.common.extensions;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Extension;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;

public class WebSocketExtensionRegistry extends ExtensionFactory
{
    private WebSocketPolicy policy;
    private ByteBufferPool bufferPool;

    public WebSocketExtensionRegistry(WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        this.policy = policy;
        this.bufferPool = bufferPool;

        // FIXME this.registry.put("identity",IdentityExtension.class);
        // FIXME this.registry.put("fragment",FragmentExtension.class);
        // FIXME this.registry.put("x-webkit-deflate-frame",WebkitDeflateFrameExtension.class);
        // FIXME this.registry.put("permessage-compress",PerMessageCompressionExtension.class);
    }

    public Extension newInstance(ExtensionConfig config)
    {
        if (config == null)
        {
            return null;
        }

        String name = config.getName();
        if (StringUtil.isBlank(name))
        {
            return null;
        }

        Extension registeredExt = getExtension(name);
        if (registeredExt == null)
        {
            return null;
        }

        Class<? extends Extension> extClass = registeredExt.getClass();

        try
        {
            Extension ext = extClass.newInstance();
            if (ext instanceof AbstractExtension)
            {
                AbstractExtension aext = (AbstractExtension)ext;
                aext.setConfig(config);
                aext.setPolicy(policy);
                aext.setBufferPool(bufferPool);
            }
            return ext;
        }
        catch (InstantiationException | IllegalAccessException e)
        {
            throw new WebSocketException("Cannot instantiate extension: " + extClass,e);
        }
    }
}
