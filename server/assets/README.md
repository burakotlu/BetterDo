`mock-video.mp4` is an original, silent, 20-second 360x640 H.264/AAC test clip.
It displays "MOCK VIDEO / Playback preview only" on a solid background. It is
not a generated teacher and intentionally does not pretend to teach the lesson.
It is served by the mock backend, never bundled in the production Android APK.

Recreate with FFmpeg (adjust the font path for your system):

```sh
ffmpeg -f lavfi -i 'color=c=0x183E34:s=360x640:r=15:d=20' \
  -f lavfi -i anullsrc=r=44100:cl=mono \
  -vf "drawtext=text='MOCK VIDEO':fontcolor=white:fontsize=26:x=(w-tw)/2:y=180,drawtext=text='Playback preview only':fontcolor=white:fontsize=16:x=(w-tw)/2:y=240" \
  -t 20 -c:v libx264 -pix_fmt yuv420p -c:a aac -movflags +faststart mock-video.mp4
```
