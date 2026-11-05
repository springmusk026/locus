import configparser
import os

_HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def units():
    cfg = configparser.ConfigParser()
    if not cfg.read(os.path.join(_HERE, "config.ini")):
        raise RuntimeError("config.ini is missing: see the README's development setup")
    return cfg["skycast"]["units"]


def temperature(celsius):
    if units() == "metric":
        return f"{celsius:.0f} °C"
    return f"{celsius * 9 / 5 + 32:.0f} °F"
