package org.eclipse.jetty.websocket.jsr356.annotations;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.net.URI;
import java.nio.ByteBuffer;

import javax.websocket.ContainerProvider;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.jsr356.EchoHandler;
import org.eclipse.jetty.websocket.jsr356.endpoints.JsrEndpointEventDriver;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class OnMessageBinaryStreamDecoderTest {
	private static Server server;
	private static EchoHandler handler;
	private static URI serverUri;

	@BeforeClass
	public static void startServer() throws Exception
	{
		server = new Server();
		ServerConnector connector = new ServerConnector(server);
		server.addConnector(connector);

		handler = new EchoHandler();

		ContextHandler context = new ContextHandler();
		context.setContextPath("/");
		context.setHandler(handler);
		server.setHandler(context);

		// Start Server
		server.start();

		String host = connector.getHost();
		if (host == null)
		{
			host = "localhost";
		}
		int port = connector.getLocalPort();
		serverUri = new URI(String.format("ws://%s:%d/",host,port));
	}


	@AfterClass
	public static void stopServer()
	{
		try
		{
			server.stop();
		}
		catch (Exception e)
		{
			e.printStackTrace(System.err);
		}
	}

	@Test
	public void testAnnotationInitializedOnMessage() throws Exception
	{
		WebSocketContainer container = ContainerProvider.getWebSocketContainer();
		IntegerBinarySocket socket = new IntegerBinarySocket();

		try (StacklessLogging logging = new StacklessLogging(IntegerBinarySocket.class,JsrEndpointEventDriver.class))
		{
			// expecting ArrayIndexOutOfBoundsException during onOpen
			Session session = container.connectToServer(socket,serverUri);

			assertThat("Session.isOpen",session.isOpen(),is(true));
			assertThat("Should have had 0 errors so far",socket.errors.size(),is(0));

			Integer sent = new Integer(1234);
			ByteBuffer integerBuffer = ByteBuffer.allocate(Integer.BYTES);
			integerBuffer.putInt(sent);
			
			session.getAsyncRemote().sendBinary(integerBuffer);
			Integer received = socket.popReceived(1000);
			assertThat("Received integer", received, is(sent));

			Throwable cause = socket.errors.pop();
			assertThat("Error",cause,instanceOf(ArrayIndexOutOfBoundsException.class));
		}
	}
}
