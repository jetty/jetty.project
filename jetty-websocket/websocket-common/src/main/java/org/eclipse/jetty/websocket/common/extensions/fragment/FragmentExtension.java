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

package org.eclipse.jetty.websocket.common.extensions.fragment;


import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.common.extensions.AbstractExtension;

/**
 * Fragment Extension
 */
public class FragmentExtension extends AbstractExtension
{
    private int maxLength = -1;

    @Override
    public javax.net.websocket.extensions.FrameHandler createIncomingFrameHandler(javax.net.websocket.extensions.FrameHandler incoming)
    {
        return new FragmentHandler(incoming,maxLength);
    }

    @Override
    public javax.net.websocket.extensions.FrameHandler createOutgoingFrameHandler(javax.net.websocket.extensions.FrameHandler outgoing)
    {
        return new FragmentHandler(outgoing,maxLength);
    }

    @Override
    public void setConfig(ExtensionConfig config)
    {
        super.setConfig(config);

        maxLength = config.getParameter("maxLength",maxLength);
    }
}
