"""List local interface URLs and print a QR code for direct connections."""
from __future__ import annotations

import io
import socket


def local_ipv4s() -> list[str]:
    """Every non-loopback IPv4 address this machine has, best-effort."""
    ips: set[str] = set()
    try:
        import ifaddr

        for adapter in ifaddr.get_adapters():
            for ip in adapter.ips:
                addr = ip.ip
                if isinstance(addr, str) and "." in addr and not addr.startswith("127."):
                    ips.add(addr)
    except Exception:
        pass
    if not ips:
        # Fallback: the primary outbound interface (no packets are actually sent).
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ips.add(s.getsockname()[0])
            s.close()
        except Exception:
            pass
    return sorted(ips)


def urls(port: int) -> list[str]:
    return [f"http://{ip}:{port}" for ip in local_ipv4s()]


def ascii_qr(data: str) -> str:
    """A terminal-renderable QR code for [data], or an empty string if qrcode is missing."""
    try:
        import qrcode

        qr = qrcode.QRCode(border=1)
        qr.add_data(data)
        qr.make(fit=True)
        buf = io.StringIO()
        qr.print_ascii(out=buf, invert=True)
        return buf.getvalue()
    except Exception:
        return ""


def print_connection_block(port: int, version: str) -> None:
    found = urls(port)
    line = "─" * 54
    out = [f"\n{line}\n ProofPad backend {version} local URLs"]
    if found:
        out += [f"   {u}" for u in found]
        qr = ascii_qr(found[0])
        if qr:
            out.append(f"\n Scan to connect ({found[0]}):\n")
            out.append(qr)
    else:
        out.append("   (no non-loopback IPv4 found — is Wi-Fi up?)")
    out.append(f"{line}\n")
    print("\n".join(out), flush=True)
