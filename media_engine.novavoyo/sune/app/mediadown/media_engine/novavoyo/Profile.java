package sune.app.mediadown.media_engine.novavoyo;

public final class Profile {
	
	private final String id;
	private final String name;
	
	public Profile(String id, String name) {
		this.id = id;
		this.name = name;
	}
	
	public String id() {
		return id;
	}
	
	public String name() {
		return name;
	}
}
