package org.eclipse.jetty.websocket.api;

/**
 * A recoverable exception within the websocket framework.
 */
@SuppressWarnings("serial")
public class WebSocketException extends RuntimeException {
	public WebSocketException() {
		super();
	}

	public WebSocketException(String message, Throwable cause) {
		super(message, cause);
	}

	public WebSocketException(String message) {
		super(message);
	}

	public WebSocketException(Throwable cause) {
		super(cause);
	}
}
