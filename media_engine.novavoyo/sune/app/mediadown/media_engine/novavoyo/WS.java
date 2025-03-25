package sune.app.mediadown.media_engine.novavoyo;

import java.net.http.WebSocket;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;

public final class WS {
	
	private final WebSocket ws;
	private final Listener listener;
	
	public WS(Request request, Listener listener) {
		this.listener = Objects.requireNonNull(listener);
		this.ws = Web.newWebSocket(Objects.requireNonNull(request), new WebSocketListener());
	}
	
	private final void onMessageReceived(String text) {
		listener.onMessageReceived(JSON.read(text));
	}
	
	public void send(String text) {
		ws.sendText(Objects.requireNonNull(text), true);
	}
	
	public void close() {
		ws.abort();
	}
	
	public static interface Listener {
		
		void onOpen();
		void onClose(int statusCode, String reason);
		void onMessageReceived(JSONCollection json);
	}
	
	private final class WebSocketListener implements WebSocket.Listener {
		
		private final StringBuilder bufText = new StringBuilder();
		
		@Override
		public void onOpen(WebSocket webSocket) {
			listener.onOpen();
			WebSocket.Listener.super.onOpen(webSocket);
		}
		
		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			listener.onClose(statusCode, reason);
			return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
		}
		
		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			throw new RuntimeException(error);
		}
		
		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			bufText.append(data);
			
			if(last) {
				onMessageReceived(bufText.toString());
				bufText.setLength(0);
			}
			
			return WebSocket.Listener.super.onText(webSocket, data, last);
		}
	}
}
