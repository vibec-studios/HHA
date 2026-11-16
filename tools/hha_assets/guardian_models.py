"""Sammelpunkt der vier Wächter-Modelle (für ältere Vorschau-Skripte). Die Modelle selbst liegen in
warden_model.py, sisyphus_model.py, ixion_model.py und tantalus_model.py, die Helfer in guardian_kit.py."""
from guardian_kit import *  # noqa: F401,F403


def warden():
    from warden_model import warden as build
    return build()


def sisyphus():
    from sisyphus_model import sisyphus as build
    return build()


def ixion():
    from ixion_model import ixion as build
    return build()


def tantalus():
    from tantalus_model import tantalus as build
    return build()


def all_guardians():
    return [warden(), sisyphus(), ixion(), tantalus()]
