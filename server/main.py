"""uvicorn entrypoint: `uvicorn server.main:app`."""
import logging
import os

from server.app import create_app
from server.config import Settings
from server.control_client import ControlClient

# Our warnings, plus the per-turn timing lines from the session (no content).
logging.basicConfig(level=logging.WARNING, format="%(asctime)s %(name)s %(levelname)s %(message)s")
logging.getLogger("server.session").setLevel(logging.INFO)

socket = os.environ.get('JARVIS_CONTROL_SOCKET')
app = create_app(Settings.from_env(), control=ControlClient(socket) if socket else None)
