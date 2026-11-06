"""Erzeugt die gesprochenen Dialogzeilen mit ElevenLabs und bindet sie in die Mod ein.

Aufruf:  ELEVENLABS_API_KEY=... python voice_lines.py [--lang de] [--dry-run] [--only prologue.catch] [--force]

Der Schlüssel wird nur aus der Umgebung gelesen und nie gespeichert. Unveränderte Zeilen werden übersprungen (verglichen
wird über Text, Stimme und Einstellungen), nachträgliche Läufe kosten also nur neue oder geänderte Zeilen.
Ausgabe: assets/hha/sounds/voice/<sprache>/<zeile>.ogg, Einträge in sounds.json und HhaVoiceLengths.kt.
Ohne Sprachdatei fällt das Spiel automatisch auf die alten Stimm-Blips zurück.

Hinweis zur Lizenz: Audio aus dem kostenlosen ElevenLabs-Plan darf nur nicht-kommerziell und mit Namensnennung
verwendet werden. Wer die Mod kommerziell veröffentlicht, sollte die Zeilen mit einem bezahlten Plan neu erzeugen.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

from texkit import ASSETS

LANG_DIR = os.path.join(ASSETS, 'lang')
VOICE_DIR = os.path.join(ASSETS, 'sounds', 'voice')
MANIFEST = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'voice_manifest.json')
KOTLIN = os.path.abspath(os.path.join(ASSETS, '..', '..', '..', 'kotlin', 'dev', 'henny', 'hha', 'logic', 'boss', 'HhaVoiceLengths.kt'))
LANG_FILES = {'de': 'de_de.json', 'en': 'en_us.json'}
MODEL = 'eleven_multilingual_v2'
PREFIX = 'hha.hermes.line.'
TARGET_LUFS = -14.0

# Wer spricht, mit welcher Stimme und welcher Nachbearbeitung. Die Voice-IDs stammen aus der Liste von GET /v1/voices.
CAST = {
    'hermes': {
        'voice': 'N2lVS1w4EtoT3dr4eOWO',  # Callum, "Husky Trickster"
        'settings': {'stability': 0.38, 'similarity_boost': 0.78, 'style': 0.45, 'use_speaker_boost': True},
        'filter': 'highpass=f=70,acompressor=threshold=-18dB:ratio=3:attack=8:release=120',
    },
    'zeus': {
        'voice': 'pNInz6obpgDQGcFmaJgB',  # Adam, "Dominant, Firm"
        'settings': {'stability': 0.55, 'similarity_boost': 0.8, 'style': 0.35, 'use_speaker_boost': True},
        # Tiefer und mit Hall: Die Stimme kommt aus dem ganzen Himmel.
        'filter': 'asetrate=44100*0.84,aresample=44100,atempo=1.05,aecho=0.8:0.75:90|170|300:0.45|0.32|0.2,bass=g=5,alimiter=limit=0.95',
    },
    'shade': {
        'voice': 'N2lVS1w4EtoT3dr4eOWO',  # Derselbe Mann, nur falsch: tiefer, flacher, mit einem Echo aus der Halle.
        'settings': {'stability': 0.62, 'similarity_boost': 0.82, 'style': 0.2, 'use_speaker_boost': True},
        'filter': 'asetrate=44100*0.88,aresample=44100,atempo=1.04,aecho=0.8:0.6:45|120:0.35|0.22,lowpass=f=6500,alimiter=limit=0.95',
    },
    'narrator': {
        'voice': 'JBFqnCBsd6RMkjVDRZzb',  # George, "Warm, Captivating Storyteller"
        'settings': {'stability': 0.6, 'similarity_boost': 0.75, 'style': 0.15, 'use_speaker_boost': True},
        'filter': 'highpass=f=60,aecho=0.8:0.5:60:0.12',
    },
}

NARRATOR_KEYS = {
    'prologue.seal', 'prologue.pull', 'prologue.altar', 'feather.falls', 'zeus.feather', 'finale.catch', 'finale.stinger',
    'shade.coin', 'shade.voice', 'shade.open',
}


def speaker_of(key):
    if key in NARRATOR_KEYS:
        return 'narrator'
    if key.startswith('shade.'):
        return 'shade'
    if key in ('zeus.name', 'zeus.end_it'):
        return 'zeus'
    return 'hermes'


# Wo die Automatik nicht reicht: Großschreibung von Hauptwörtern geht beim Umwandeln der Versalien verloren.
SPOKEN_OVERRIDE = {
    'de': {'zeus.end_it': 'Du spielst mit diesem Sterblichen. Beende es!', 'zeus.name': 'Hermes!'},
    'en': {'zeus.end_it': 'You are playing with this mortal. End it!', 'zeus.name': 'Hermes!'},
}


def spoken(text):
    """Macht aus Anzeigetext Sprechtext: Großbuchstaben würden sonst teils buchstabiert, Anführungen stören den Fluss."""
    text = text.replace('„', '"').replace('“', '"').replace('…', '...').strip()
    letters = [c for c in text if c.isalpha()]
    if letters and all(c.isupper() for c in letters):
        text = '. '.join(part.strip().capitalize() for part in text.split('.') if part.strip()) + '.'
    return text


def file_name(key):
    return re.sub(r'[^a-z0-9_]', '_', key.lower())


def load_lines(lang):
    with open(os.path.join(LANG_DIR, LANG_FILES[lang]), encoding='utf8') as f:
        data = json.load(f)
    return {k[len(PREFIX):]: v for k, v in data.items() if k.startswith(PREFIX)}


def fingerprint(text, cast):
    payload = json.dumps([MODEL, text, cast['voice'], cast['settings'], cast['filter']], sort_keys=True)
    return hashlib.sha256(payload.encode('utf8')).hexdigest()


def synthesize(key, text, cast, language):
    body = json.dumps({
        'text': text,
        'model_id': MODEL,
        'voice_settings': cast['settings'],
    }).encode('utf8')
    request = urllib.request.Request(
        f"https://api.elevenlabs.io/v1/text-to-speech/{cast['voice']}?output_format=mp3_44100_128",
        data=body,
        headers={'xi-api-key': key, 'Content-Type': 'application/json', 'Accept': 'audio/mpeg', 'User-Agent': 'hha-voice-lines'},
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def encode(mp3, cast, out):
    os.makedirs(os.path.dirname(out), exist_ok=True)
    handle, tmp = tempfile.mkstemp(suffix='.mp3')
    with os.fdopen(handle, 'wb') as f:
        f.write(mp3)
    subprocess.run([
        shutil.which('ffmpeg'), '-hide_banner', '-loglevel', 'error', '-y', '-i', tmp,
        '-af', cast['filter'], '-ac', '1', '-ar', '44100', '-c:a', 'libvorbis', '-q:a', '3', out,
    ], check=True)
    os.remove(tmp)
    normalize(out)


def normalize(path):
    """Hebt eine Zeile auf gleiche Lautheit wie die Musik; Stimmen kommen sonst rund 10 dB leiser heraus."""
    probe = subprocess.run([
        shutil.which('ffmpeg'), '-hide_banner', '-nostats', '-i', path, '-af', 'ebur128=framelog=quiet', '-f', 'null', '-',
    ], capture_output=True, text=True, encoding='utf8', errors='replace', check=True)
    found = re.findall(r'I:\s+(-?[0-9.]+) LUFS', probe.stderr)
    if not found:
        return
    gain = TARGET_LUFS - float(found[-1])
    handle, tmp = tempfile.mkstemp(suffix='.ogg')
    os.close(handle)
    subprocess.run([
        shutil.which('ffmpeg'), '-hide_banner', '-loglevel', 'error', '-y', '-i', path,
        '-af', f'volume={gain:.2f}dB,alimiter=limit=0.95:level=disabled', '-c:a', 'libvorbis', '-q:a', '3', tmp,
    ], check=True)
    shutil.move(tmp, path)


def duration(path):
    result = subprocess.run([
        shutil.which('ffprobe'), '-v', 'error', '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', path,
    ], capture_output=True, text=True, check=True)
    return float(result.stdout.strip())


def write_sounds(manifest):
    path = os.path.join(ASSETS, 'sounds.json')
    with open(path, encoding='utf8') as f:
        sounds = json.load(f)
    sounds = {k: v for k, v in sounds.items() if not k.startswith('voice.')}
    for lang, lines in sorted(manifest.items()):
        for key in sorted(lines):
            name = file_name(key)
            sounds[f'voice.{lang}.{name}'] = {'sounds': [f'hha:voice/{lang}/{name}']}
    with open(path, 'w', encoding='utf8') as f:
        json.dump(sounds, f, indent=2)


def write_lengths(manifest):
    """Längste Fassung je Zeile: Der Server kennt die Sprache der Spieler nicht und wartet auf die langsamste."""
    longest = {}
    for lines in manifest.values():
        for key, info in lines.items():
            ticks = int(info['seconds'] * 20) + 12
            longest[key] = max(longest.get(key, 0), ticks)
    rows = ',\n'.join(f'        "{PREFIX}{key}" to {ticks}' for key, ticks in sorted(longest.items()))
    table = f'mapOf(\n{rows},\n    )' if rows else 'emptyMap()'
    source = f'''package dev.henny.hha.logic.boss

/**
 * GENERIERT von tools/hha_assets/voice_lines.py: wie viele Ticks jede gesprochene Dialogzeile dauert.
 * So bleibt eine Szene stehen, bis der Satz zu Ende gesprochen ist. Ohne Einträge gelten die Zeiten aus dem Drehbuch.
 */
object HhaVoiceLengths {{

    private val LENGTHS: Map<String, Int> = {table}

    fun ticks(key: String, base: Int): Int = maxOf(base, LENGTHS[key] ?: 0)
}}
'''
    with open(KOTLIN, 'w', encoding='utf8', newline='\n') as f:
        f.write(source)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--lang', default='de', choices=sorted(LANG_FILES))
    parser.add_argument('--only', nargs='*', default=None)
    parser.add_argument('--force', action='store_true')
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--normalize-only', action='store_true', help='Vorhandene Dateien nur neu einpegeln, nichts erzeugen')
    args = parser.parse_args()

    if args.normalize_only:
        folder = os.path.join(VOICE_DIR, args.lang)
        for name in sorted(os.listdir(folder)):
            normalize(os.path.join(folder, name))
        print('Eingepegelt:', args.lang)
        return

    manifest = {}
    if os.path.exists(MANIFEST):
        with open(MANIFEST, encoding='utf8') as f:
            manifest = json.load(f)
    known = manifest.setdefault(args.lang, {})
    lines = load_lines(args.lang)
    # Verwaiste Zeilen entfernen, wenn sie im Sprachfile nicht mehr vorkommen.
    for key in [k for k in known if k not in lines]:
        del known[key]

    todo = []
    for key, raw in lines.items():
        if args.only and key not in args.only:
            continue
        cast = CAST[speaker_of(key)]
        text = SPOKEN_OVERRIDE.get(args.lang, {}).get(key) or spoken(raw)
        mark = fingerprint(text, cast)
        out = os.path.join(VOICE_DIR, args.lang, file_name(key) + '.ogg')
        if not args.force and known.get(key, {}).get('hash') == mark and os.path.exists(out):
            continue
        todo.append((key, text, cast, mark, out))
    characters = sum(len(t[1]) for t in todo)
    print(f'{len(todo)} Zeilen zu erzeugen, {characters} Zeichen ({args.lang}).')
    if args.dry_run:
        for key, text, *_ in todo:
            print(f'  [{speaker_of(key)}] {key}: {text}')
        return
    if todo:
        api_key = os.environ.get('ELEVENLABS_API_KEY', '').strip()
        if not api_key:
            sys.exit('ELEVENLABS_API_KEY fehlt.')
        for key, text, cast, mark, out in todo:
            try:
                mp3 = synthesize(api_key, text, cast, args.lang)
            except urllib.error.HTTPError as error:
                detail = error.read().decode('utf8', 'replace')[:400]
                print(f'Abbruch bei {key}: HTTP {error.code} {detail}')
                break
            encode(mp3, cast, out)
            known[key] = {'hash': mark, 'seconds': round(duration(out), 2)}
            print(f"  {key}: {known[key]['seconds']} s")
            # Nach jeder Zeile sichern: Bricht der Lauf ab, sind bezahlte Zeilen nicht verloren.
            with open(MANIFEST, 'w', encoding='utf8', newline='\n') as f:
                json.dump(manifest, f, indent=2, ensure_ascii=False, sort_keys=True)
    with open(MANIFEST, 'w', encoding='utf8', newline='\n') as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False, sort_keys=True)
    write_sounds(manifest)
    write_lengths(manifest)


if __name__ == '__main__':
    main()
