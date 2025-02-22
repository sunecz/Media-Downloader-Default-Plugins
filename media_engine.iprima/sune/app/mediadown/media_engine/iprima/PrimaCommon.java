package sune.app.mediadown.media_engine.iprima;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
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
import sune.app.mediadown.util.JSON.JSONType;
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
				json.set("id", "web-1");
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
	
	public static final class Nuxt {
		
		private final JSONCollection root;
		
		private Nuxt(JSONCollection root) {
			this.root = Objects.requireNonNull(root);
		}
		
		public static final Nuxt extract(String html) {
			int idx;
			if((idx = html.indexOf("id=\"__NUXT_DATA__\"")) < 0
					|| (idx = html.indexOf("[", idx)) < 0) {
				throw new IllegalStateException("Nuxt data do not exist");
			}
			
			String content = Utils.bracketSubstring(html, '[', ']', false, idx, html.length());
			JSONCollection root = Parser.parse(content);
			
			return new Nuxt(root);
		}
		
		public String get(String name, String defaultValue) {
			JSONObject object = data().getObject(name);
			
			if(object == null) {
				return defaultValue;
			}
			
			return object.stringValue();
		}
		
		public JSONCollection root() {
			return root;
		}
		
		public JSONCollection data() {
			return root.getCollection("data");
		}
		
		public JSONCollection state() {
			return root.getCollection("state");
		}
		
		// Reference: https://dwl2jqo5jww9m.cloudfront.net/_nuxt/entry.D0sFU1Jx.js
		// Search __NUXT_DATA__ to see what is done with the JSON data in that element.
		// Updated: 2024-10-03
		private static final class Parser {
			
			private static final int q2 = -1;
			private static final int Y2 = -2;
			private static final int ey = -3;
			private static final int ry = -4;
			private static final int ny = -5;
			private static final int ay = -6;
			
			private static final JSONCollection T0(String json) {
				return oy(json);
			}
			
			private static final JSONCollection oy(String json) {
				return ty(JSON.read(json));
			}
			
			private static final JSONNode checkNode(JSONNode node) {
				return node != null ? node : JSONObject.ofNull();
			}
			
			private static final JSONCollection toCollection(Set<JSONNode> set) {
				JSONCollection c = JSONCollection.emptyArray();
				for(JSONNode n : set) c.add(checkNode(n));
				return c;
			}
			
			private static final JSONCollection toCollection(Map<JSONNode, JSONNode> map) {
				JSONCollection c = JSONCollection.empty();
				for(Entry<JSONNode, JSONNode> e : map.entrySet()) {
					c.set(((JSONObject) e.getKey()).stringValue(), checkNode(e.getValue()));
				}
				return c;
			}
			
			/*
				function o(t, i = !1) {
					if (t === q2) return;
					if (t === ey) return NaN;
					if (t === ry) return 1 / 0;
					if (t === ny) return -1 / 0;
					if (t === ay) return -0;
					if (i) throw new Error("Invalid input");
					if (t in a) return a[t];
					const d = n[t];
					if (!d || typeof d != "object") a[t] = d;
					else if (Array.isArray(d))
						if (typeof d[0] == "string") {
							const s = d[0],
								l = r == null ? void 0 : r[s];
							if (l) return a[t] = l(o(d[1]));
							switch (s) {
								case "Date":
									a[t] = new Date(d[1]);
									break;
								case "Set":
									const c = new Set;
									a[t] = c;
									for (let y = 1; y < d.length; y += 1) c.add(o(d[y]));
									break;
								case "Map":
									const u = new Map;
									a[t] = u;
									for (let y = 1; y < d.length; y += 2) u.set(o(d[y]), o(d[y + 1]));
									break;
								case "RegExp":
									a[t] = new RegExp(d[1], d[2]);
									break;
								case "Object":
									a[t] = Object(d[1]);
									break;
								case "BigInt":
									a[t] = BigInt(d[1]);
									break;
								case "null":
									const m = Object.create(null);
									a[t] = m;
									for (let y = 1; y < d.length; y += 2) m[d[y]] = o(d[y + 1]);
									break;
								default:
									throw new Error(`Unknown type ${s}`)
							}
						} else {
							const s = new Array(d.length);
							a[t] = s;
							for (let l = 0; l < d.length; l += 1) {
								const c = d[l];
								c !== Y2 && (s[l] = o(c))
							}
						}
					else {
						const s = {};
						a[t] = s;
						for (const l in d) {
							const c = d[l];
							s[l] = o(c)
						}
					}
					return a[t]
				}
			*/
			private static final JSONNode o(JSONCollection n, JSONNode[] a, int t) {
				if(t == q2) return JSONObject.ofNull();
				if(t == ey) return JSONObject.ofDouble(Double.NaN);
				if(t == ry) return JSONObject.ofDouble(1.0 / 0.0);
				if(t == ny) return JSONObject.ofDouble(-1.0 / 0.0);
				if(t == ay) return JSONObject.ofDouble(-0.0);
				if(a[t] != null) return a[t].copy();
				
				JSONNode dObj = n.get(t);
				boolean isCollection;
				
				if(dObj == null
						|| !(isCollection = dObj.isCollection())
						|| ((JSONCollection) dObj).isEmpty()) {
					a[t] = dObj;
					return a[t];
				}
				
				if(isCollection) {
					JSONCollection d = (JSONCollection) dObj;
					
					if(d.type() == JSONType.ARRAY) {
						JSONNode f = d.get(0);
						
						if(f.type() == JSONType.STRING || f.type() == JSONType.STRING_UNQUOTED) {
							String s = ((JSONObject) f).stringValue();
							
							if(s.equals("Reactive")) {
								// Simplified as opposed to the original code to always return
								// just the object itself.
								JSONNode obj = o(n, a, d.getInt(1));
								a[t] = obj;
								return obj;
							}
							
							switch(s) {
								case "Date":
									a[t] = d.get(1);
									break;
								case "Set":
									Set<JSONNode> c = new HashSet<>();
									for(int y = 1; y < d.length(); y += 1) c.add(o(n, a, d.getInt(y)));
									a[t] = toCollection(c);
									break;
								case "Map":
								// "null" behaves very similarly to "Map" in the original code,
								// in this implementation we can use the same procedure for both.
								case "null":
									Map<JSONNode, JSONNode> u = new HashMap<>();
									for(int y = 1; y < d.length(); y += 2) {
										u.put(o(n, a, d.getInt(y)), o(n, a, d.getInt(y + 1)));
									}
									a[t] = toCollection(u);
									break;
								case "RegExp":
									a[t] = d;
									break;
								case "Object":
								case "BigInt":
									a[t] = d.get(1);
									break;
								default:
									throw new RuntimeException("Unknown type " + s);
							}
						} else {
							JSONCollection s = JSONCollection.emptyArray();
							a[t] = s;
							
							for(int l = 0; l < d.length(); l += 1) {
								int c = d.getInt(l);
								
								if(c != Y2) {
									s.set(l, checkNode(o(n, a, c)));
								}
							}
						}
					} else {
						JSONCollection s = JSONCollection.empty();
						a[t] = s;
						
						for(JSONObject l : d.objectsIterable()) {
							int c = l.intValue();
							s.set(l.name(), checkNode(o(n, a, c)));
						}
					}
				}
				
				return a[t];
			}
			
			/*
				function ty(e, r) {
					if (typeof e == "number") return o(e, !0);
					if (!Array.isArray(e) || e.length === 0) throw new Error("Invalid input");
					const n = e,
						a = Array(n.length);
					return o(0)
				}
			*/
			private static final JSONCollection ty(JSONCollection json) {
				JSONNode node = o(json, new JSONNode[json.length()], 0);
				
				if(!node.isCollection()) {
					throw new IllegalStateException("Not a collection");
				}
				
				return (JSONCollection) node;
			}
			
			public static final JSONCollection parse(String content) {
				return T0(content);
			}
		}
	}
}