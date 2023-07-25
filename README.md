# Media Downloader - Default Plugins
Default plugins for Media Downloader.

## Supported websites
- [TV Nova](https://tv.nova.cz/)
- [iPrima](https://iprima.cz/)
	- [Prima+](https://www.iprima.cz/), [Zoom](https://zoom.iprima.cz/), [CNN](https://cnn.iprima.cz/), [Pauza](https://pauza.iprima.cz/)
- [YouTube](https://youtube.com/)
- [Česká televize](https://ceskatelevize.cz/)
	- [ČT24](https://ct24.ceskatelevize.cz/), [Sport](https://sport.ceskatelevize.cz/), [iVysílání](https://www.ceskatelevize.cz/ivysilani/), [Pořady](https://www.ceskatelevize.cz/porady/), [Déčko](https://decko.ceskatelevize.cz/), [Art](https://art.ceskatelevize.cz/), [Edu](https://edu.ceskatelevize.cz/)
- [TV Barrandov](https://www.barrandov.tv/)
- [Autosalon.tv](https://autosalon.tv/)
- [Primadoma.tv](https://primadoma.tv/)
- [Voyo (Nova)](https://voyo.nova.cz/)
- [Stream.cz](https://www.stream.cz/)
- [Archív Markíza](https://videoarchiv.markiza.sk/)
- [TN.cz](https://tn.nova.cz/)
- [Voyo (Markíza)](https://voyo.markiza.sk/)

## Legal disclaimer
These plugins are for educational purposes only. Downloading copyrighted materials from streaming services may violate their Terms of Service. **Use at your own risk.**

## How to build
Building can be done either manually, or using [Apache's Ant](https://ant.apache.org/).
The Ant script can be run either directly on the host machine or in the prepared Docker image.

To run the following commands on Windows, use PowerShell.

The following subsections assume you have already built both the [Media Downloader](https://github.com/sunecz/Media-Downloader#how-to-build) project and the [Media Downloader - DRM Plugin](https://github.com/sunecz/Media-Downloader-DRM-Plugin#how-to-build) project first and you clone this repository to a directory next to the `Media-Downloader` and `Media-Downloader-DRM` directory.

### Clone the repository
```shell
git clone https://github.com/sunecz/Media-Downloader-Default-Plugins.git
cd Media-Downloader-Default-Plugins/
```

### Build using the Docker image
Finally, build the JARs:
```shell
docker run --rm -v "$(pwd):/workdir" -v "$(pwd)/../Media-Downloader:/Media-Downloader" -v "$(pwd)/../Media-Downloader-DRM:/Media-Downloader-DRM" --name md-build -it md:build ant -D"path.javafx"="/Media-Downloader/docker/openjfx"
```

# Related repositories
- Application: https://github.com/sunecz/Media-Downloader
- DRM plugin: https://github.com/sunecz/Media-Downloader-DRM-Plugin
- Launcher: https://github.com/sunecz/Media-Downloader-Launcher
