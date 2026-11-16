"""Gradle mit Sperre: Wenn mehrere Agenten im selben Checkout arbeiten, läuft immer nur ein Gradle-Build gleichzeitig.

Aufruf (aus dem Projektordner):
    python tools/hha_assets/gradle_locked.py compileKotlin          # schneller Kompiliertest
    python tools/hha_assets/gradle_locked.py build                  # voller Build inkl. Jar
    python tools/hha_assets/gradle_locked.py runVerifyWorldgen      # Bauplan-/Gelände-Check ohne Spielfenster

Wartet, bis die Sperre tmp/gradle.lock frei ist (eine Sperre, die älter als 20 Minuten ist, gilt als verwaist), führt
Gradle offline aus und gibt die Fehlerzeilen (e:, w: nur mit --warnings) und das Ergebnis aus. Der Exit-Code ist der von
Gradle. Startet nie den Minecraft-Client.

Hinweis für parallele Arbeit: Ein Kompilierfehler kann aus einer Datei stammen, an der gerade jemand anderes arbeitet.
Schau auf den Dateipfad im Fehler; Fehler in fremden Dateien sind nicht deine, warte dann kurz und versuche es erneut.
"""
import os
import subprocess
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
LOCK = os.path.join(ROOT, 'tmp', 'gradle.lock')
GRADLE = 'C:/Users/henny/.gradle/wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle'
STALE = 20 * 60
FORBIDDEN = ('runClient', 'runClientGameTest', 'runServer')


def acquire():
    os.makedirs(os.path.dirname(LOCK), exist_ok=True)
    waited = 0
    while True:
        try:
            fd = os.open(LOCK, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            os.write(fd, f'{os.getpid()} {time.time():.0f}'.encode())
            os.close(fd)
            return
        except FileExistsError:
            try:
                age = time.time() - os.path.getmtime(LOCK)
            except OSError:
                continue
            if age > STALE:
                print(f'Verwaiste Sperre ({age / 60:.0f} min) wird entfernt')
                try:
                    os.remove(LOCK)
                except OSError:
                    pass
                continue
            if waited % 30 == 0:
                print(f'Warte auf Gradle-Sperre ({waited} s) …', flush=True)
            time.sleep(3)
            waited += 3


def main(argv):
    warnings = '--warnings' in argv
    tasks = [a for a in argv if a != '--warnings'] or ['compileKotlin']
    if any(t in FORBIDDEN for t in tasks):
        raise SystemExit('Minecraft wird hier nie gestartet; der Nutzer testet selbst.')
    acquire()
    try:
        shell = 'C:/Program Files/Git/bin/bash.exe'
        cmd = [GRADLE, '--offline', '--console=plain', *tasks]
        if os.name == 'nt' and os.path.exists(shell):
            cmd = [shell, '-c', ' '.join(f'"{c}"' for c in cmd)]
        proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, encoding='utf8', errors='replace')
    finally:
        try:
            os.remove(LOCK)
        except OSError:
            pass
    out = (proc.stdout or '') + (proc.stderr or '')
    shown = 0
    for line in out.splitlines():
        if line.startswith('e:') or (warnings and line.startswith('w:')) or 'FAILED' in line or 'BUILD ' in line \
                or line.startswith('* What went wrong') or 'Exception' in line[:60]:
            print(line)
            shown += 1
            if shown > 120:
                print('… (gekürzt)')
                break
    if proc.returncode != 0 and shown == 0:
        print(out[-4000:])
    sys.exit(proc.returncode)


if __name__ == '__main__':
    main(sys.argv[1:])
