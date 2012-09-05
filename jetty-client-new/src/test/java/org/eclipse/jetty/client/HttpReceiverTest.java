package org.eclipse.jetty.client;

public class HttpReceiverTest
{
//    private HttpClient client;
//
//    @Before
//    public void init() throws Exception
//    {
//        client = new HttpClient();
//        client.start();
//    }
//
//    @After
//    public void destroy() throws Exception
//    {
//        client.stop();
//    }
//
//    @Test
//    public void test_Receive_NoResponseContent() throws Exception
//    {
//        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
//        HttpConnection connection = new HttpConnection(client, endPoint);
//        endPoint.setInput("" +
//                "HTTP/1.1 200 OK\r\n" +
//                "Content-length: 0\r\n" +
//                "\r\n");
//        final AtomicReference<Response> responseRef = new AtomicReference<>();
//        final CountDownLatch latch = new CountDownLatch(1);
//        HttpReceiver receiver = new HttpReceiver(connection);
//        HttpExchange exchange = new HttpExchange();
//        , null, new Response.Listener.Adapter()
//        {
//            @Override
//            public void onSuccess(Response response)
//            {
//                responseRef.set(response);
//                latch.countDown();
//            }
//        });
//        receiver.receive(connection);
//
//        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
//        Response response = responseRef.get();
//        Assert.assertNotNull(response);
//        Assert.assertEquals(200, response.status());
//        Assert.assertEquals("OK", response.reason());
//        Assert.assertSame(HttpVersion.HTTP_1_1, response.version());
//        HttpFields headers = response.headers();
//        Assert.assertNotNull(headers);
//        Assert.assertEquals(1, headers.size());
//        Assert.assertEquals("0", headers.get(HttpHeader.CONTENT_LENGTH));
//    }
//
//    @Test
//    public void test_Receive_ResponseContent() throws Exception
//    {
//        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
//        HttpConnection connection = new HttpConnection(client, endPoint);
//        String content = "0123456789ABCDEF";
//        endPoint.setInput("" +
//                "HTTP/1.1 200 OK\r\n" +
//                "Content-length: " + content.length() + "\r\n" +
//                "\r\n" +
//                content);
//        BufferingResponseListener listener = new BufferingResponseListener();
//        HttpReceiver receiver = new HttpReceiver(connection, null, listener);
//        receiver.receive(connection);
//
//        Response response = listener.await(5, TimeUnit.SECONDS);
//        Assert.assertNotNull(response);
//        Assert.assertEquals(200, response.status());
//        Assert.assertEquals("OK", response.reason());
//        Assert.assertSame(HttpVersion.HTTP_1_1, response.version());
//        HttpFields headers = response.headers();
//        Assert.assertNotNull(headers);
//        Assert.assertEquals(1, headers.size());
//        Assert.assertEquals(String.valueOf(content.length()), headers.get(HttpHeader.CONTENT_LENGTH));
//        String received = listener.contentAsString("UTF-8");
//        Assert.assertEquals(content, received);
//    }
//
//    @Test
//    public void test_Receive_ResponseContent_EarlyEOF() throws Exception
//    {
//        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
//        HttpConnection connection = new HttpConnection(client, endPoint);
//        String content1 = "0123456789";
//        String content2 = "ABCDEF";
//        endPoint.setInput("" +
//                "HTTP/1.1 200 OK\r\n" +
//                "Content-length: " + (content1.length() + content2.length()) + "\r\n" +
//                "\r\n" +
//                content1);
//        BufferingResponseListener listener = new BufferingResponseListener();
//        HttpReceiver receiver = new HttpReceiver(connection, null, listener);
//        receiver.receive(connection);
//        endPoint.setInputEOF();
//        receiver.receive(connection);
//
//        try
//        {
//            listener.await(5, TimeUnit.SECONDS);
//            Assert.fail();
//        }
//        catch (ExecutionException e)
//        {
//            Assert.assertTrue(e.getCause() instanceof EOFException);
//        }
//    }
//
//    @Test
//    public void test_Receive_ResponseContent_IdleTimeout() throws Exception
//    {
//        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
//        HttpConnection connection = new HttpConnection(client, endPoint);
//        endPoint.setInput("" +
//                "HTTP/1.1 200 OK\r\n" +
//                "Content-length: 1\r\n" +
//                "\r\n");
//        BufferingResponseListener listener = new BufferingResponseListener();
//        HttpReceiver receiver = new HttpReceiver(connection, null, listener);
//        receiver.receive(connection);
//        // Simulate an idle timeout
//        receiver.idleTimeout();
//
//        try
//        {
//            listener.await(5, TimeUnit.SECONDS);
//            Assert.fail();
//        }
//        catch (ExecutionException e)
//        {
//            Assert.assertTrue(e.getCause() instanceof TimeoutException);
//        }
//    }
//
//    @Test
//    public void test_Receive_BadResponse() throws Exception
//    {
//        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
//        HttpConnection connection = new HttpConnection(client, endPoint);
//        endPoint.setInput("" +
//                "HTTP/1.1 200 OK\r\n" +
//                "Content-length: A\r\n" +
//                "\r\n");
//        BufferingResponseListener listener = new BufferingResponseListener();
//        HttpReceiver receiver = new HttpReceiver(connection, null, listener);
//        receiver.receive(connection);
//
//        try
//        {
//            listener.await(5, TimeUnit.SECONDS);
//            Assert.fail();
//        }
//        catch (ExecutionException e)
//        {
//            Assert.assertTrue(e.getCause() instanceof HttpResponseException);
//        }
//    }
}
