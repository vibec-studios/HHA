"""Alle Boss-Modelle: Texturen und Kotlin-Modelldaten (client/model/HhaModelData.kt).

Aufruf:
    python boss_models.py                 # alle Modelle neu erzeugen
    python boss_models.py --only hades    # nur dieses Modell (Komma-Liste erlaubt), die anderen bleiben, wie sie sind
    python boss_models.py --list          # bekannte Modellnamen

Jedes Modell lebt in seiner eigenen Datei (siehe REGISTRY). Ein Modul darf zusätzlich `variants(model, albedo, glow)`
anbieten, um aus dem fertigen Atlas weitere Texturen abzuleiten (z. B. den Schatten des Hermes).

Der Kotlin-Teil jedes Modells wird als Teilstück unter tools/hha_assets/gen/<name>.kt.part abgelegt; HhaModelData.kt
wird danach immer aus allen Teilstücken zusammengesetzt. So können mehrere Leute gleichzeitig an verschiedenen
Modellen arbeiten, ohne sich gegenseitig die Daten zu überschreiben oder mit einem halbfertigen Modell zu stören.
"""
import importlib
import os
import sys

from modelkit import assemble_kotlin, model_chunk
from texkit import flush

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, 'gen')

# Modellname -> (Modul, Bau-Funktion). Die Reihenfolge bestimmt nur die Reihenfolge in HhaModelData.kt.
REGISTRY = {
    'hermes': ('hermes_model', 'hermes'),
    'hades': ('hades_model', 'hades'),
    'serpent': ('serpent_model', 'serpent'),
    'herma': ('hermes_props_model', 'herma'),
    'parcel': ('hermes_props_model', 'parcel'),
    'projectiles': ('hermes_props_model', 'projectiles'),
    'imp': ('imp_model', 'imp'),
    'kronos': ('kronos_model', 'kronos'),
    'warden_of_chains': ('warden_model', 'warden'),
    'sisyphus': ('sisyphus_model', 'sisyphus'),
    'ixion': ('ixion_model', 'ixion'),
    'tantalus': ('tantalus_model', 'tantalus'),
    'kampe': ('kampe_model', 'kampe'),
    # Überarbeitung 2 (PLAN 10.1): Olymp. Gerüste von P00, die Modell-Pakete füllen sie.
    'hekate': ('hekate_model', 'hekate'),
    'brontes': ('brontes_model', 'brontes'),
    'kratos': ('kratos_model', 'kratos'),
    'bia': ('kratos_model', 'bia'),
    'zeus': ('zeus_model', 'zeus'),
    'kurete': ('kurete_model', 'kurete'),
    'griffin': ('griffin_model', 'griffin'),
    'automaton': ('automaton_model', 'automaton'),
    'olymp_props': ('olymp_props_model', 'olymp_props'),
}


def build(name):
    """Baut ein Modell aus der Registry (für Vorschau-Skripte)."""
    module, fn = REGISTRY[name]
    model = getattr(importlib.import_module(module), fn)()
    if model.name != name:
        raise SystemExit(f'{module}.{fn}() liefert Modell "{model.name}", erwartet "{name}"')
    return model


def generate(name):
    module, _ = REGISTRY[name]
    model = build(name)
    model.pack()
    albedo, glow = model.paint()
    variants = getattr(importlib.import_module(module), 'variants', None)
    if variants is not None:
        variants(model, albedo, glow)
    os.makedirs(GEN, exist_ok=True)
    part = os.path.join(GEN, f'{name}.kt.part')
    tmp = f'{part}.{os.getpid()}.tmp'
    with open(tmp, 'w', encoding='utf8', newline='\n') as f:
        f.write(model_chunk(model))
    os.replace(tmp, part)


def assemble():
    chunks, missing = [], []
    for name in REGISTRY:
        part = os.path.join(GEN, f'{name}.kt.part')
        if not os.path.exists(part):
            missing.append(name)
            continue
        with open(part, encoding='utf8') as f:
            chunks.append(f.read())
    if missing:
        raise SystemExit('Teilstücke fehlen für: ' + ', '.join(missing) + ' (einmal ohne --only laufen lassen)')
    assemble_kotlin(chunks)


def main(argv):
    if '--list' in argv:
        print('\n'.join(REGISTRY))
        return
    names = list(REGISTRY)
    if '--only' in argv:
        names = [n.strip() for n in argv[argv.index('--only') + 1].split(',') if n.strip()]
        unknown = [n for n in names if n not in REGISTRY]
        if unknown:
            raise SystemExit('Unbekannte Modelle: ' + ', '.join(unknown) + ' (siehe --list)')
    for name in names:
        generate(name)
    flush()
    assemble()


if __name__ == '__main__':
    main(sys.argv[1:])
