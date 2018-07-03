# SVR Streaming System
Make use of object semantics in virtual reality video to optimize the power-consumption
and bandwidth usage on client-side.

## TODO
- [ ] fov logic
- [ ] efficient gui
- [ ] native/optimized decoder

## Prepare Video Segments
```
mkdir tmp/
mkdir storage/rhino
cd storage
python3 segment.py rhino.mp4 100 rhino/output
cd ..
```

## Compilation
- use intellij or
```
cd src/main/java
javac *.java
```

## License
MIT License

Copyright (c) [2018] [Chi-Chun, Chen]