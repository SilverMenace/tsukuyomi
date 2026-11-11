#!/usr/bin/env python3
"""Turn .env into shell exports, including the JDBC-style variables Spring Boot reads.

Usage (from the repo root):   eval "$(python3 scripts/dev-env.py)"

.env holds a postgresql://user:password@host/db URL (the format hosts hand out).
Spring Boot wants the URL, user and password separately, so this splits them.
Nothing is printed to the terminal when used with eval.
"""
import pathlib
import shlex
from urllib.parse import urlparse, unquote

env = {}
for line in (pathlib.Path(__file__).resolve().parent.parent / ".env").read_text().splitlines():
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    key, value = line.split("=", 1)
    value = value.strip().strip('"').strip("'")
    if value:  # a later non-empty value wins; empty template lines are ignored
        env[key.strip()] = value

url = urlparse(env["DATABASE_URL"])
port = f":{url.port}" if url.port else ""
env["SPRING_DATASOURCE_URL"] = f"jdbc:postgresql://{url.hostname}{port}{url.path}?sslmode=require"
env["SPRING_DATASOURCE_USERNAME"] = unquote(url.username)
env["SPRING_DATASOURCE_PASSWORD"] = unquote(url.password)

for key, value in env.items():
    print(f"export {key}={shlex.quote(value)}")
