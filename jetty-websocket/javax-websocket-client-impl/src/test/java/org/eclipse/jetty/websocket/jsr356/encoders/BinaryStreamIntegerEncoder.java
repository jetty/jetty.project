package org.eclipse.jetty.websocket.jsr356.encoders;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;

public class BinaryStreamIntegerEncoder implements Encoder.BinaryStream<Integer> {

	private int initialized = 0;
	private int destroyed = 0;
	
	public int getInitialized() {
		return initialized;
	}
	
	public int getDestroyed() {
		return destroyed;
	}

	@Override
	public void init(EndpointConfig config) {
		synchronized(this) {
			++initialized;
		}
	}

	@Override
	public void destroy() {
		synchronized(this) {
			++destroyed;
		}
	}

	@Override
	public void encode(Integer object, OutputStream os) throws EncodeException, IOException {
		if (initialized != 1) {
			throw new EncodeException(object, "Encoder initialization count is " + initialized + ", expected 1");
		}
		if (destroyed != 0) {
			throw new EncodeException(object, "Encoder destroyed count is " + destroyed + ", expected 0");
		}
		
		DataOutputStream data = new DataOutputStream(os);
		data.writeInt(object);
	}
}
