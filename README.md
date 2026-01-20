# Tabu Search - Distributed Computing

## Budowanie projektu

### 1. Generowanie klas z proto
Aby wygenerować klasy Java z plików `.proto`:

```bash
./gradlew build
```

To polecenie zbuduje projekt i wygeneruje wszystkie wymagane klasy gRPC z pliku `tabu_worker.proto`.

## Uruchamianie projektu

```bash
# Dla jednego serwera:
./gradlew run --args="50051"
# Lub kilka na raz:
./gradlew run --args="50051 50052 50053"
```

### 2. Uruchomienie koordynatora
Po uruchomieniu serwerów worker, należy uruchomić Coordinator. Zmienne w kodzie coordinatora zmieniają parametry wejściowe (głównie N, maxIterations, step).

**Uwaga:** Jeśli masz osobną klasę Coordinator, uruchom ją podobnie używając Gradle lub stwórz osobny task w build.gradle.kts.

## Uwagi dotyczące testów

Wyniki testów są spójne pod warunkiem, że komputer nie wykonuje ciężkich zadań w tle.
Należy się też upewnić, że logowanie jest wyłączone, w tym wbudowane logowanie gRPC (szczególnie jeśli miałoby się pojawiać w każdej iteracji pętli).
Domyślnie wg konfiguracji logback.xml logowanie jest wyłączone.
