package org.eclipse.jetty.websocket.jsr356.decoders;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;

public class BinaryStreamIntegerDecoder implements Decoder.BinaryStream<Integer> {

	private int initialized = 0;
	private int destroyed = 0;

	public int getInitialised() {
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
	public Integer decode(InputStream is) throws DecodeException, IOException {
		if (initialized != 1) {
			throw new DecodeException("", "Decoder initialization count is " + initialized + ", expected 1");
		}
		if (destroyed != 0) {
			throw new DecodeException("", "Decoder destroyed count is " + destroyed + ", expected 0");
		}

		DataInputStream data = new DataInputStream(is);
		return data.readInt();
	}
}
