"""uvicorn entrypoint: `uvicorn server.main:app`."""
from server.app import create_app
from server.config import Settings

app = create_app(Settings.from_env())
