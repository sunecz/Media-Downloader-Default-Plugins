package sune.app.mediadown.media_engine.iprima;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.gui.Dialog;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Response;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.CheckedConsumer;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JSON.JSONNode;
import sune.app.mediadown.util.JSON.JSONObject;
import sune.app.mediadown.util.Utils;

public final class PrimaCommon {
	
	// Forbid anyone to create an instance of this class
	private PrimaCommon() {
	}
	
	public static final void error(Throwable throwable) {
		if(throwable == null) {
			return;
		}
		
		// Special handling of translatable exceptions
		Throwable th = null;
		if((th = throwable) instanceof TranslatableException
				|| (th = throwable.getCause()) instanceof TranslatableException) {
			TranslatableException tex = (TranslatableException) th;
			Translation tr = IPrimaHelper.translation().getTranslation(tex.translationPath());
			Dialog.showError(tr.getSingle("title"), tr.getSingle("text"));
			return; // Do not continue
		} else if((th = throwable) instanceof MessageException
						|| (th = throwable.getCause()) instanceof MessageException) {
			MessageException mex = (MessageException) th;
			Translation tr = IPrimaHelper.translation().getTranslation("error.message_error");
			Dialog.showError(tr.getSingle("title"), mex.message());
			return; // Do not continue
		}
		
		MediaDownloader.error(throwable);
	}
	
	public static final <T> CheckedConsumer<ListTask<T>> handleErrors(CheckedConsumer<ListTask<T>> action) {
		return ((task) -> {
			try {
				action.accept(task);
			} catch(Exception ex) {
				// More user-friendly error messages
				error(ex);
			}
		});
	}
	
	public static class TranslatableException extends Exception {
		
		private static final long serialVersionUID = 2966737246033320221L;
		
		protected final String translationPath;
		
		protected TranslatableException(String translationPath) {
			super();
			this.translationPath = Objects.requireNonNull(translationPath);
		}
		
		protected TranslatableException(String translationPath, Throwable cause) {
			super(cause);
			this.translationPath = Objects.requireNonNull(translationPath);
		}
		
		public String translationPath() {
			return translationPath;
		}
	}
	
	public static class MessageException extends Exception {
		
		private static final long serialVersionUID = -3290197439712588070L;
		
		protected final String message;
		
		protected MessageException(String message) {
			super();
			this.message = Objects.requireNonNull(message);
		}
		
		protected MessageException(String message, Throwable cause) {
			super(cause);
			this.message = Objects.requireNonNull(message);
		}
		
		public String message() {
			return message;
		}
	}
	
	public static interface JSONSerializable {
		
		JSONNode toJSON();
	}
	
	public static final class RPC {
		
		private static final URI URL_ENDPOINT = Net.uri("https://gateway-api.prod.iprima.cz/json-rpc/");
		
		private static final HttpHeaders HEADERS = Web.Headers.ofSingle(
			"Accept", "application/json",
			"Content-Type", "application/json",
			"Accept-Encoding", "gzip"
		);
		
		// Forbid anyone to create an instance of this class
		private RPC() {
		}
		
		private static final JSONCollection rawRequest(String body) throws Exception {
			try(Response.OfStream response = Web.requestStream(
					Web.Request.of(URL_ENDPOINT).headers(HEADERS).POST(body)
			)) {
				InputStream stream = response.stream();
				boolean isGzip = response.headers()
					.firstValue("Content-Encoding")
					.filter("gzip"::equalsIgnoreCase)
					.isPresent();
				
				if(isGzip) {
					stream = new GZIPInputStream(stream);
				}
				
				JSONCollection json = JSON.read(stream);
				
				// Return the result if it exists, otherwise return the whole JSON.
				// That can happen when an error occurred, the information about it
				// is in the error collection.
				return json.getCollection("result", json);
			}
		}
		
		public static final JSONCollection request(String method, Object... params) throws Exception {
			return request(method, Utils.toMap(params));
		}
		
		public static final JSONCollection request(String method, Map<Object, Object> params) throws Exception {
			JSONCollection json = RPC.Request.bodyOf(method, params);
			json.setNull("params.profileId");
			return rawRequest(json.toString(true));
		}
		
		public static final boolean isError(JSONCollection json) {
			return json.hasCollection("error");
		}
		
		private static final class Request {
			
			// Forbid anyone to create an instance of this class
			private Request() {
			}
			
			private static final void setHeader(JSONCollection json) {
				json.set("id", "1");
				json.set("jsonrpc", "2.0");
			}
			
			private static final void setPrimitiveParam(JSONCollection parent, String name, Object value) {
				if(value instanceof JSONSerializable) {
					JSONNode json = ((JSONSerializable) value).toJSON();
					if(json == null) parent.setNull(name);
					else             parent.set(name, json);
				} else {
					parent.set(name, JSONObject.of(value));
				}
			}
			
			private static final void addPrimitiveParam(JSONCollection parent, Object value) {
				if(value instanceof JSONSerializable) {
					JSONNode json = ((JSONSerializable) value).toJSON();
					if(json == null) parent.addNull();
					else             parent.add(json);
				} else {
					parent.add(JSONObject.of(value));
				}
			}
			
			private static final JSONCollection constructMap(Map<?, ?> map) {
				JSONCollection jsonMap = JSONCollection.empty();
				
				for(Entry<?, ?> entry : map.entrySet()) {
					setObjectParam(jsonMap, String.valueOf(entry.getKey()), entry.getValue());
				}
				
				return jsonMap;
			}
			
			private static final JSONCollection constructArray(List<?> list) {
				JSONCollection jsonArray = JSONCollection.emptyArray();
				
				for(Object item : list) {
					addObjectParam(jsonArray, item);
				}
				
				return jsonArray;
			}
			
			private static final void addObjectParam(JSONCollection parent, Object value) {
				if(value instanceof Map) {
					parent.add(constructMap((Map<?, ?>) value));
				} else if(value instanceof List) {
					parent.add(constructArray((List<?>) value));
				} else {
					addPrimitiveParam(parent, value);
				}
			}
			
			private static final void setObjectParam(JSONCollection parent, String name, Object value) {
				if(value instanceof Map) {
					parent.set(name, constructMap((Map<?, ?>) value));
				} else if(value instanceof List) {
					parent.set(name, constructArray((List<?>) value));
				} else {
					setPrimitiveParam(parent, name, value);
				}
			}
			
			private static final JSONCollection paramsOf(Map<Object, Object> params) {
				JSONCollection json = JSONCollection.empty();
				
				for(Entry<Object, Object> entry : params.entrySet()) {
					setObjectParam(json, String.valueOf(entry.getKey()), entry.getValue());
				}
				
				return json;
			}
			
			public static final JSONCollection bodyOf(String method, Map<Object, Object> params) {
				JSONCollection json = JSONCollection.empty();
				setHeader(json);
				json.set("method", method);
				json.set("params", paramsOf(params));
				return json;
			}
		}
	}
}