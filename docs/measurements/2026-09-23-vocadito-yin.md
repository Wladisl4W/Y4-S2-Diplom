# YIN on Vocadito solo singing

40 local, annotated solo-vocal WAV files. Centered 4096-sample windows at about 100 ms intervals;
matching thresholds: 50 cents and one semitone. The production detector is used without extra smoothing.

| Metric | Result |
| --- | ---: |
| Voiced frames | 5450 |
| Voice detected on voiced frames | 86.3% |
| Correct within 50 cents | 82.2% |
| Correct within one semitone | 84.6% |
| False voice on silent frames | 10.0% |
| Median absolute error on detected voice | 6.5 cents |
| Mean processing time | 1.06 ms/frame |

Centered windows have about 92.9 ms of audio; this is not a measured live UI latency.
Vocadito solo singing does not represent songs with accompaniment.

Среда измерения: Ubuntu 26.04.1 LTS, AMD Ryzen 5 7520U, OpenJDK 21.0.12.1; версия приложения 1.4.0. Аудио и F0-разметка Vocadito v3 хранились локально в `/tmp/vocadito` и не входят в репозиторий. Команда: `./gradlew vocaditoYinReport -PdatasetPath=/tmp/vocadito`.
