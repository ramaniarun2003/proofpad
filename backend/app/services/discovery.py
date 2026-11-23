"""Local-network discovery helpers: list the machine's URLs, print a QR connect block,
and advertise the service over mDNS so the phone can find it without typing an IP."""
from __future__ import annotations

import io
import socket


def local_ipv4s() -> list[str]:
    """Every non-loopback IPv4 address this machine has, best-effort."""
    ips: set[str] = set()
    try:
        import ifaddr  # bundled with zeroconf

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
    out = [f"\n{line}\n ProofPad backend {version} — reachable at:"]
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


def _service_info(port: int, version: str):
    from zeroconf import ServiceInfo

    ips = local_ipv4s()
    if not ips:
        raise RuntimeError("no non-loopback IPv4 to advertise")
    return ServiceInfo(
        type_="_proofpad._tcp.local.",
        name="ProofPad backend._proofpad._tcp.local.",
        addresses=[socket.inet_aton(ip) for ip in ips],
        port=port,
        properties={"version": version},
        server=f"{socket.gethostname().split('.')[0]}.local.",
    )


async def register_mdns(port: int, version: str):
    """
    Advertise `_proofpad._tcp.local.` with a {version} TXT record. Uses the asyncio Zeroconf API so
    it runs on the server's event loop (the synchronous API raises EventLoopBlocked inside lifespan).
    Returns a handle to unregister.
    """
    from zeroconf.asyncio import AsyncZeroconf

    info = _service_info(port, version)
    aiozc = AsyncZeroconf()
    # allow_name_change avoids a NonUniqueNameException when a stale record lingers.
    await aiozc.async_register_service(info, allow_name_change=True)
    return aiozc, info


async def unregister_mdns(handle) -> None:
    aiozc, info = handle
    try:
        await aiozc.async_unregister_service(info)
    finally:
        await aiozc.async_close()
