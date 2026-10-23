"""uvicorn entrypoint: `uvicorn server.main:app`."""
import logging

from server.app import create_app
from server.config import Settings

# Our warnings, plus the per-turn timing lines from the session (no content).
logging.basicConfig(level=logging.WARNING, format="%(asctime)s %(name)s %(levelname)s %(message)s")
logging.getLogger("server.session").setLevel(logging.INFO)

app = create_app(Settings.from_env())
