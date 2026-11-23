import os

# Don't advertise over mDNS (or block on Zeroconf startup) during the test suite.
os.environ.setdefault("PROOFPAD_MDNS", "0")
