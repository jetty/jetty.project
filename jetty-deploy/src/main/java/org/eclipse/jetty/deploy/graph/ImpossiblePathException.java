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

public class ImpossiblePathException extends RuntimeException
{
    private static final long serialVersionUID = 8423437443748056467L;

    public ImpossiblePathException(String message, Throwable cause)
    {
        super(message,cause);
    }

    public ImpossiblePathException(String message)
    {
        super(message);
    }
}
