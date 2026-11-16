"""Führt die Text- und Klang-Schnipsel der Arbeitspakete in die echten Dateien zusammen (Überarbeitung 2, PLAN 11.2).

Aufruf (aus dem Projektordner; gehört P13, P00 hat es angelegt):
    python tools/hha_assets/merge_snippets.py --dry-run          # nur zeigen, was sich ändern würde
    python tools/hha_assets/merge_snippets.py                    # zusammenführen
    python tools/hha_assets/merge_snippets.py --apply-removals   # zusätzlich lang_remove.txt / sounds_remove.txt anwenden
    python tools/hha_assets/merge_snippets.py --strict           # bei doppelten Schlüsseln zwischen Paketen abbrechen

Quellen: `tmp/overhaul2/snippets/<paket>/lang_de.json`, `lang_en.json`, `sounds.json` (flache bzw. gleich aufgebaute
JSON-Objekte nur mit den eigenen Schlüsseln). Ziele: `assets/hha/lang/de_de.json`, `en_us.json`, `sounds.json`.

Regeln:
- **Fügt nur hinzu und überschreibt nur die gelieferten Schlüssel.** Alles andere in der Zieldatei bleibt Zeichen für
  Zeichen stehen (Reihenfolge, Einrückung, Nutzeränderungen): Die Datei wird nicht neu geschrieben, sondern nur an den
  betroffenen Stellen bearbeitet. Neue Schlüssel kommen ans Ende, je Paket in der Reihenfolge der Schnipsel-Datei.
- Doppelte Schlüssel zwischen Paketen werden gemeldet (mit Paketnamen); ohne `--strict` gewinnt das alphabetisch
  spätere Paket.
- `lang_remove.txt` / `sounds_remove.txt` (eine ID je Zeile, `#` = Kommentar) entfernen Schlüssel nur mit
  `--apply-removals`.
"""
import glob
import json
import os
import sys
from collections import OrderedDict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
SNIPPETS = os.path.join(ROOT, 'tmp', 'overhaul2', 'snippets')
ASSETS = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'hha')

TARGETS = [
    # (Schnipsel-Datei, Ziel, Entfern-Liste)
    ('lang_de.json', os.path.join(ASSETS, 'lang', 'de_de.json'), 'lang_remove.txt'),
    ('lang_en.json', os.path.join(ASSETS, 'lang', 'en_us.json'), 'lang_remove.txt'),
    ('sounds.json', os.path.join(ASSETS, 'sounds.json'), 'sounds_remove.txt'),
]


# ---------------------------------------------------------------------------------------------
# Kleiner JSON-Scanner: findet die Spannen der obersten Einträge, ohne die Datei neu zu formatieren
# ---------------------------------------------------------------------------------------------

def _skip_ws(text, i):
    while i < len(text) and text[i] in ' \t\r\n':
        i += 1
    return i


def _skip_string(text, i):
    assert text[i] == '"', f'Zeichenkette erwartet bei {i}'
    i += 1
    while i < len(text):
        c = text[i]
        if c == '\\':
            i += 2
            continue
        if c == '"':
            return i + 1
        i += 1
    raise ValueError('Zeichenkette ohne Ende')


def _skip_value(text, i):
    c = text[i]
    if c == '"':
        return _skip_string(text, i)
    if c in '{[':
        depth = 0
        while i < len(text):
            c = text[i]
            if c == '"':
                i = _skip_string(text, i)
                continue
            if c in '{[':
                depth += 1
            elif c in '}]':
                depth -= 1
                if depth == 0:
                    return i + 1
            i += 1
        raise ValueError('Objekt ohne Ende')
    while i < len(text) and text[i] not in ',}] \t\r\n':
        i += 1
    return i


def scan(text):
    """Oberste Einträge: Liste von (Schlüssel, Beginn des Schlüssels, Ende des Werts) und Position der schließenden Klammer."""
    i = _skip_ws(text, 0)
    if text[i] != '{':
        raise ValueError('Datei ist kein JSON-Objekt')
    i += 1
    entries = []
    while True:
        i = _skip_ws(text, i)
        if text[i] == '}':
            return entries, i
        start = i
        end_key = _skip_string(text, i)
        key = json.loads(text[start:end_key])
        i = _skip_ws(text, end_key)
        assert text[i] == ':', f'":" erwartet bei {i}'
        i = _skip_ws(text, i + 1)
        end_value = _skip_value(text, i)
        entries.append((key, start, i, end_value))
        i = _skip_ws(text, end_value)
        if text[i] == ',':
            i += 1
        elif text[i] != '}':
            raise ValueError(f'"," oder "}}" erwartet bei {i}')


def _style(text, entries):
    """Einrückung der Einträge und Schritt für verschachtelte Werte, aus der Datei abgelesen."""
    indent, step = '  ', '  '
    if entries:
        line_start = text.rfind('\n', 0, entries[0][1]) + 1
        indent = text[line_start:entries[0][1]] or '  '
        step = indent
    ascii_only = '\\u00' in text and not any(ord(c) > 127 for c in text)
    return indent, step, ascii_only


def _render(value, indent, step, ascii_only):
    """Wert im Stil der Datei; mehrzeilige Werte werden auf die Einrückung des Eintrags gesetzt."""
    raw = json.dumps(value, ensure_ascii=ascii_only, indent=len(step) if isinstance(value, (dict, list)) else None)
    return raw.replace('\n', '\n' + indent)


def _remove_one(text, key):
    """Entfernt den obersten Eintrag [key] samt passendem Komma; `None`, wenn es ihn nicht gibt."""
    entries, _ = scan(text)
    index = next((n for n, e in enumerate(entries) if e[0] == key), None)
    if index is None:
        return None
    ks, ve = entries[index][1], entries[index][3]
    if index + 1 < len(entries):
        # bis zum Beginn des nächsten Schlüssels (nimmt das Komma dahinter mit)
        return text[:ks] + text[entries[index + 1][1]:]
    if index > 0:
        # letzter Eintrag: ab dem Ende des vorigen Werts (nimmt das Komma davor mit)
        return text[:entries[index - 1][3]] + text[ve:]
    # einziger Eintrag: bis vor die schließende Klammer
    return text[:ks] + text[_skip_ws(text, ve):]


def merge_text(text, updates, removals=()):
    """Liefert (neuer Text, geändert, hinzugefügt, entfernt) für [updates] (OrderedDict) und [removals]."""
    removed = []
    for key in removals:
        if key in updates:
            continue
        result = _remove_one(text, key)
        if result is not None:
            text = result
            removed.append(key)
    entries, close = scan(text)
    indent, step, ascii_only = _style(text, entries)
    changed, added = [], []
    existing = {key: (vs, ve) for key, _, vs, ve in entries}
    edits = []  # (start, end, replacement), überschneiden sich nie
    for key, value in updates.items():
        if key in existing:
            vs, ve = existing[key]
            if json.loads(text[vs:ve]) != value:
                edits.append((vs, ve, _render(value, indent, step, ascii_only)))
                changed.append(key)
    new_keys = [k for k in updates if k not in existing]
    if new_keys:
        body = ''.join(f',\n{indent}{json.dumps(k, ensure_ascii=ascii_only)}: {_render(updates[k], indent, step, ascii_only)}' for k in new_keys)
        if entries:
            anchor = entries[-1][3]
            edits.append((anchor, anchor, body))
        else:
            edits.append((close, close, body[1:] + '\n'))
        added.extend(new_keys)
    for start, end, repl in sorted(edits, key=lambda e: (e[0], e[1]), reverse=True):
        text = text[:start] + repl + text[end:]
    json.loads(text)  # Probe: muss gültiges JSON bleiben
    return text, changed, added, removed


# ---------------------------------------------------------------------------------------------
# Schnipsel sammeln
# ---------------------------------------------------------------------------------------------

def collect(name):
    """Alle Schnipsel [name] aller Pakete: (zusammengeführte Werte, Liste der Doppelungen)."""
    merged = OrderedDict()
    owner = {}
    duplicates = []
    for path in sorted(glob.glob(os.path.join(SNIPPETS, '*', name))):
        package = os.path.basename(os.path.dirname(path))
        with open(path, encoding='utf8') as f:
            data = json.load(f, object_pairs_hook=OrderedDict)
        for key, value in data.items():
            if key in owner and owner[key] != package:
                duplicates.append((key, owner[key], package, merged[key] == value))
            merged[key] = value
            owner[key] = package
    return merged, duplicates


def collect_removals(name):
    keys = []
    for path in sorted(glob.glob(os.path.join(SNIPPETS, '*', name))):
        with open(path, encoding='utf8') as f:
            for line in f:
                line = line.split('#', 1)[0].strip()
                if line:
                    keys.append(line)
    return keys


def main(argv):
    dry = '--dry-run' in argv
    strict = '--strict' in argv
    removals_on = '--apply-removals' in argv
    collected = []
    any_duplicates = False
    for snippet, target, removal_list in TARGETS:
        updates, duplicates = collect(snippet)
        for key, first, second, same in duplicates:
            note = 'gleicher Wert' if same else 'VERSCHIEDENE Werte, es gilt ' + second
            print(f'[doppelt] {snippet}: "{key}" in {first} und {second} ({note})')
            any_duplicates = True
        collected.append((snippet, target, updates, collect_removals(removal_list) if removals_on else []))
    if strict and any_duplicates:
        print('Abbruch: doppelte Schlüssel zwischen Paketen (--strict). Nichts geschrieben.')
        return 1
    for snippet, target, updates, removals in collected:
        rel = os.path.relpath(target, ROOT)
        if not updates and not removals:
            print(f'{rel}: keine Schnipsel')
            continue
        with open(target, encoding='utf8', newline='') as f:
            text = f.read()
        new_text, changed, added, removed = merge_text(text, updates, removals)
        print(f'{rel}: {len(added)} neu, {len(changed)} geändert, {len(removed)} entfernt')
        for key in added:
            print(f'  + {key}')
        for key in changed:
            print(f'  ~ {key}')
        for key in removed:
            print(f'  - {key}')
        if not dry and new_text != text:
            with open(target, 'w', encoding='utf8', newline='') as f:
                f.write(new_text)
    if dry:
        print('(--dry-run: nichts geschrieben)')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
