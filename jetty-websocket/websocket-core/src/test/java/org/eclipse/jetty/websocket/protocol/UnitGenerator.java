package org.eclipse.jetty.websocket.protocol;

import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.generator.Generator;

/**
 * Convenience Generator.
 */
public class UnitGenerator extends Generator
{
    public UnitGenerator()
    {
        super(WebSocketPolicy.newServerPolicy(),new StandardByteBufferPool());
    }
}
