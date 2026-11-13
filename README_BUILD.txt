Planetarium Offline - full-featured offline planetarium for Android
==================================================================

BUILD
  Open this folder in Android Studio (Hedgehog+) and Run on a device
  with a gyroscope. Command line: open once in Android Studio, then
  ./gradlew assembleDebug

FEATURES (100% offline - no INTERNET permission, no network ever)
  - 9,000 star point sprites with magnitude-dependent size/brightness
    and realistic blue-white-orange colors; adjustable mag limit 2-8
  - Milky Way band (6,000 points following the galactic plane)
  - 16 constellation stick figures from real J2000 star coordinates
    (Orion, Ursa Major, Cassiopeia, Cygnus, Lyra, Aquila, Gemini,
    Taurus, Leo, Scorpius, Canis Major, Pegasus, Crux, Centaurus,
    Canes Venatici, Bootes) + names at centroids
  - Sun, Moon and all 8 planets computed on-device from Keplerian
    orbital elements (Schlyter low-precision method, ~arcmin accuracy):
    real positions, distances, moon phase with live terminator drawing
  - Atmosphere simulation: daylight sky, twilight glow near horizon,
    stars fading at dawn
  - Point-at-sky mode (rotation-vector sensor, landscape-aware) with
    automatic handoff to manual drag mode
  - Pinch zoom (1.5-130 deg FOV), double-tap zoom in
  - Tap any object to identify it: name, type, magnitude, RA/Dec,
    azimuth/altitude, distance (AU / km), moon phase %
  - Search: 80+ named stars, planets, Sun, Moon, constellations;
    selecting one centers the view on it
  - Time travel: pause, real time, +/-60s / hour / day per second;
    "NOW" snaps back to the present - watch planets move and the sky
    change over days in seconds
  - Manual observer location (saved to prefs), persists across runs
  - Settings: constellation lines, labels, atmosphere, star mag limit

DATA / ACCURACY NOTES
  - Bright-star and constellation coordinates are REAL (J2000).
    The ~9k-star catalog mixes those with a synthetically generated
    filler population (realistic magnitude distribution) to keep the
    repo self-contained; swap in the real Hipparcos/Yale catalog via
    assets/stars.dat (same binary format) for a true 100k-star sky.
  - Planet/Sun/Moon: Schlyter elements are valid ~1900-2100, accuracy
    a few arcminutes - fine for visual observing, not for pointing
    a telescope.

POSSIBLE NEXT STEPS
  GPS location provider, DSO catalog (Messier, ~110 objects), meteor
  shower radiant markers, eclipse detection, red night mode, horizon
  landscape silhouette, star names in more languages.
