package org.eclipse.jetty.client;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class RedirectionTest extends AbstractHttpClientTest
{
    @Before
    public void init() throws Exception
    {
        start(new RedirectHandler());
    }

    @Test
    public void test303() throws Exception
    {
        Response response = client.newRequest("localhost", connector.getLocalPort())
                .path("/303/done")
                .send().get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertFalse(response.headers().containsKey(HttpHeader.LOCATION.asString()));
    }

    private class RedirectHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            try
            {
                String[] paths = target.split("/", 3);
                int status = Integer.parseInt(paths[1]);
                response.setStatus(status);
                response.setHeader("Location", request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + "/" + paths[2]);
            }
            catch (NumberFormatException x)
            {
                response.setStatus(200);
            }
            finally
            {
                baseRequest.setHandled(true);
            }
        }
    }
}
