#!/usr/bin/env python3
"""
Simulatore del Pico 2 W: server Bluetooth seriale (SPP) per provare l'app Albert.

Riceve:  V <sx> <dx>   S   R
Invia:   T batt_mV sx cx dx direzione velocita emergenza modo encSx encDx   (ogni 0,5 s)

Requisiti (Arch):  sudo pacman -S bluez bluez-utils python-dbus python-gobject
Avvio:             python pico_sim.py
"""
import os
import threading
import time

import dbus
import dbus.mainloop.glib
import dbus.service
from gi.repository import GLib

SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"
SEND_TELEMETRY = True          # metti False per provare il controllo "il Pico non risponde"


def session(fd: int):
    state = {"dir": 0, "speed": 0, "emerg": 0, "mode": 0}
    stop = threading.Event()

    def telemetry():
        while not stop.is_set():
            if SEND_TELEMETRY:
                line = (f"T 7400 24 24 24 {state['dir']} {state['speed']} "
                        f"{state['emerg']} {state['mode']} 0 0\n")
                try:
                    os.write(fd, line.encode())
                except OSError:
                    stop.set()
                    break
            time.sleep(0.5)

    threading.Thread(target=telemetry, daemon=True).start()

    buf = b""
    while not stop.is_set():
        try:
            data = os.read(fd, 256)
        except OSError:
            break
        if not data:
            break
        buf += data
        while b"\n" in buf:
            raw, buf = buf.split(b"\n", 1)
            cmd = raw.decode(errors="ignore").strip()
            print("RX", cmd)
            parts = cmd.split()
            if parts and parts[0] == "V" and len(parts) == 3:
                try:
                    left, right = int(parts[1]), int(parts[2])
                except ValueError:
                    continue
                state["speed"] = max(abs(left), abs(right))
                state["mode"] = 1 if state["speed"] else 0
                state["dir"] = 0 if left == right else (90 if left > right else -90)
            elif parts and parts[0] == "S":
                state.update(speed=0, mode=2)
            elif parts and parts[0] == "R":
                state.update(speed=0, mode=0, emerg=0)

    stop.set()
    try:
        os.close(fd)
    except OSError:
        pass
    print("Sessione chiusa")


class Profile(dbus.service.Object):
    @dbus.service.method("org.bluez.Profile1", in_signature="oha{sv}", out_signature="")
    def NewConnection(self, path, fd, props):
        print("Telefono collegato:", path)
        threading.Thread(target=session, args=(fd.take(),), daemon=True).start()

    @dbus.service.method("org.bluez.Profile1", in_signature="o", out_signature="")
    def RequestDisconnection(self, path):
        print("Telefono scollegato:", path)

    @dbus.service.method("org.bluez.Profile1", in_signature="", out_signature="")
    def Release(self):
        pass


def main():
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    Profile(bus, "/test/albert")
    manager = dbus.Interface(bus.get_object("org.bluez", "/org/bluez"), "org.bluez.ProfileManager1")
    manager.RegisterProfile("/test/albert", SPP_UUID, {
        "Name": "Albert SPP",
        "Role": "server",
        "Channel": dbus.UInt16(1),
        "RequireAuthentication": False,
        "RequireAuthorization": False,
    })
    print("Simulatore pronto: associa il PC dal telefono e premi Connettiti nell'app. Ctrl+C per uscire.")
    GLib.MainLoop().run()


if __name__ == "__main__":
    main()
