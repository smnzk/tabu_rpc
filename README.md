# Tabu Search - Distributed Computing

## Budowanie projektu

### 1. Generowanie klas z proto
Aby wygenerować klasy Java z plików `.proto`:

```bash
./gradlew build
```

To polecenie zbuduje projekt i wygeneruje wszystkie wymagane klasy gRPC z pliku `tabu_worker.proto`.

## Uruchamianie serwerów worker

Najwygodniej uruchomić przez IDE (np. IntelliJ) natomiast można też użyć Gradle.

```bash
# Dla jednego serwera:
./gradlew run --args="50051"
# Lub kilka na raz:
./gradlew run --args="50051 50052 50053 50054"
```
WAŻNE: numWorkers w Coordinatorze nie może być większe niż liczba uruchomionych serwerów worker.

### 2. Uruchomienie koordynatora
Po uruchomieniu serwerów worker, należy uruchomić Coordinator:

```bash
./gradlew runCoordinator
```

Zmienne w kodzie koordynatora zmieniają parametry wejściowe (głównie N, maxIterations, step).

## Uwagi dotyczące testów

Wyniki testów są spójne pod warunkiem, że komputer nie wykonuje ciężkich zadań w tle.
Należy się też upewnić, że logowanie jest wyłączone, w tym wbudowane logowanie gRPC (szczególnie jeśli miałoby się pojawiać w każdej iteracji pętli).
Domyślnie wg konfiguracji logback.xml logowanie jest wyłączone.
