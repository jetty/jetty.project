package org.eclipse.jetty.websocket.protocol;

import static org.hamcrest.Matchers.*;

import org.junit.Assert;
import org.junit.Test;

public class AcceptHashTest
{
    @Test
    public void testHash()
    {
        Assert.assertThat(AcceptHash.hashKey("dGhlIHNhbXBsZSBub25jZQ=="),is("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="));
    }
}
