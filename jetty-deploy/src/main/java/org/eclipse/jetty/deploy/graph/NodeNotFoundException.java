// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.deploy.graph;

/**
 * NodeNotFoundException for when a referenced node cannot be found.
 */
public class NodeNotFoundException extends RuntimeException
{
    private static final long serialVersionUID = -7126395440535048386L;

    public NodeNotFoundException(String message, Throwable cause)
    {
        super(message,cause);
    }

    public NodeNotFoundException(String message)
    {
        super(message);
    }
}
