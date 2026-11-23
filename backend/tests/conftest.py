import os

import pytest

# Don't advertise over mDNS (or block on Zeroconf startup) during the test suite.
os.environ.setdefault("PROOFPAD_MDNS", "0")


def pytest_addoption(parser):
    parser.addoption("--live", action="store_true", default=False, help="run tests that hit the real Mathpix API")


def pytest_configure(config):
    config.addinivalue_line("markers", "live: hits the real Mathpix API; only runs with --live")


def pytest_collection_modifyitems(config, items):
    if config.getoption("--live"):
        return
    skip_live = pytest.mark.skip(reason="needs --live (hits the real Mathpix API)")
    for item in items:
        if "live" in item.keywords:
            item.add_marker(skip_live)
