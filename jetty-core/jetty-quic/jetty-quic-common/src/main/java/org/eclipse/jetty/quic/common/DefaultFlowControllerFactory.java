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

package org.eclipse.jetty.quic.common;

import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;

public class DefaultFlowControllerFactory implements FlowController.Factory
{
    @Override
    public FlowController newFlowController()
    {
        return new DefaultFlowController();
    }

    private static class DefaultFlowController implements FlowController
    {
        @Override
        public void onStreamCreated(Stream stream)
        {

        }

        @Override
        public void onStreamTerminated(Stream stream)
        {

        }

        @Override
        public void onDataReceived(Session session, Stream stream, long length)
        {

        }

        @Override
        public void onMaxData(Session session, Stream stream, long maxData)
        {

        }
    }
}
