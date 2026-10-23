# Synthetic local video

`local-video.mp4` is a one-second 320×240 orange frame, without audio. It verifies AVFoundation import/playability and byte-for-byte export with no personal media.

Recreate with FFmpeg:

```sh
ffmpeg -f lavfi -i color=c=orange:s=320x240:d=1 -an -c:v libx264 -pix_fmt yuv420p local-video.mp4
```
