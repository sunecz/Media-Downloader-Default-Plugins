package sune.app.mediadown.media_engine.novavoyo;

import java.util.stream.Stream;

import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JSON.JSONObject;

public final class Account {
	
	private final String id;
	private final Provider provider;
	private final String name;
	private final boolean isActive;
	
	public Account(String id, Provider provider, String name, boolean isActive) {
		this.id = id;
		this.provider = provider;
		this.name = name;
		this.isActive = isActive;
	}
	
	public String id() {
		return id;
	}
	
	public Provider provider() {
		return provider;
	}
	
	public String name() {
		return name;
	}
	
	public boolean isActive() {
		return isActive;
	}
	
	public JSONCollection toJSON() {
		return JSONCollection.ofObject(
			"id", JSONObject.ofString(id),
			"provider", JSONObject.ofString(provider.value),
			"name", JSONObject.ofString(name),
			"isActive", JSONObject.ofBoolean(isActive)
		);
	}
	
	public static enum Provider {
		
		ANY(""), EBOX("eBox"), O2("O2");
		
		private final String value;
		
		private Provider(String value) {
			this.value = value;
		}
		
		public static final Provider of(String provider) {
			return Stream.of(values())
					.filter((p) -> p.value.equals(provider))
					.findFirst().orElse(Provider.ANY);
		}
	}
}
