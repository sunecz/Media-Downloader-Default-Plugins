// This file is used only by an IDE, each plugin is built automatically using the build script.
module sune.app.mediadown.default_plugins {
	requires transitive sune.app.mediadown;
	requires transitive sune.app.mediadown.drm;
	requires javafx.web;
	requires jdk.jsobject;
	requires ssdf2;
	requires org.jsoup;
	requires java.net.http;
	exports sune.app.mediadown.downloader.smf;
	exports sune.app.mediadown.downloader.wms;
	exports sune.app.mediadown.media_engine.iprima;
	exports sune.app.mediadown.media_engine.novaplus;
	exports sune.app.mediadown.media_engine.ceskatelevize;
	exports sune.app.mediadown.media_engine.tvbarrandov;
	exports sune.app.mediadown.media_engine.tvautosalon;
	exports sune.app.mediadown.media_engine.tvprimadoma;
	exports sune.app.mediadown.media_engine.novavoyo;
	exports sune.app.mediadown.media_engine.streamcz;
	exports sune.app.mediadown.media_engine.markizaplus;
	exports sune.app.mediadown.media_engine.tncz;
	exports sune.app.mediadown.media_engine.markizavoyo;
	exports sune.app.mediadown.media_engine.jojplay;
	exports sune.app.mediadown.server.html5;
	exports sune.app.mediadown.server.youtube;
	exports sune.app.mediadown.server.direct;
	exports sune.app.mediadown.drm_engine.ceskatelevize;
	exports sune.app.mediadown.drm_engine.novavoyo;
	exports sune.app.mediadown.drm_engine.iprima;
	exports sune.app.mediadown.drm_engine.novaplus;
	exports sune.app.mediadown.drm_engine.markizavoyo;
}