# compile-libs (compile-time only)

Drop **`DistantHorizonsApi-7.2.0.jar`** here (Modrinth `DistantHorizonsApi` 7.2.0:
<https://cdn.modrinth.com/data/Xs0XTOVv/versions/LTP8vW3B/DistantHorizonsApi-7.2.0.jar>).

- `compile-libs/` is the **compile-time** classpath for optional integrations whose full mod
  jar must stay off the build (scheme A for Distant Horizons): the modules compile against the
  API jar only, so API drift is observable.
- `run/mods/` stays the **runtime** mods directory and keeps the full
  `DistantHorizons-3.3.4-26.3-fabric-neoforge.jar` for `runClient`.
- Jars here are gitignored (`compile-libs/*.jar`); this README and `.gitkeep` are tracked.
