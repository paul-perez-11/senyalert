import subprocess
import sys
import os
import re
import shutil
from pathlib import Path


ENV_KEY = re.compile(r"[A-Za-z_][A-Za-z0-9_]*$")


def load_environment(project_root: Path) -> dict[str, str]:
    """Load the documented local env file without overriding process values."""
    selected = os.environ.get("SENYALERT_ENV_FILE", "").strip()
    env_file = Path(selected).expanduser() if selected else project_root / "senyalert.env"
    if selected and not env_file.is_absolute():
        env_file = (Path.cwd() / env_file).resolve()
    if not selected:
        env_file = env_file.resolve()

    # Keep the real process environment separate from values in the file.  The
    # Java dashboard reads the same file itself, which lets it correctly tell
    # an OS-level override from an intentionally blank value in senyalert.env.
    # Passing file values as process variables would make a blank template
    # prevent the approved legacy client-ID migration.
    environment = dict(os.environ)
    if not env_file.is_file():
        if selected:
            raise RuntimeError("SENYALERT_ENV_FILE must name a readable file.")
        return environment

    try:
        lines = env_file.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise RuntimeError(f"Could not read {env_file.name}.") from error
    for line_number, raw in enumerate(lines, start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise RuntimeError(f"Invalid {env_file.name} entry at line {line_number}. Use NAME=value.")
        key, value = (part.strip() for part in line.split("=", 1))
        if not ENV_KEY.fullmatch(key):
            raise RuntimeError(f"Invalid {env_file.name} variable name at line {line_number}.")
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        elif value.startswith(("'", '"')):
            raise RuntimeError(f"Unclosed quoted value in {env_file.name} at line {line_number}.")
        # Parsing above validates the documented syntax.  Do not copy this
        # value into the Java process environment; Java resolves it from the
        # selected file after applying true OS-level overrides.
    # Let the Java process resolve the same absolute source if it starts its
    # managed engine after login.
    environment["SENYALERT_ENV_FILE"] = str(env_file)
    return environment


def find_maven(java_dir: Path, environment: dict[str, str]) -> str:
    """Find a Windows Maven command that CreateProcess can launch directly."""
    wrapper_names = ("mvnw.cmd", "mvnw") if os.name == "nt" else ("mvnw",)
    for name in wrapper_names:
        wrapper = java_dir / name
        if wrapper.is_file():
            return str(wrapper)

    command_names = ("mvn.cmd", "mvn") if os.name == "nt" else ("mvn",)
    for name in command_names:
        resolved = shutil.which(name, path=environment.get("PATH"))
        if resolved:
            return resolved
    raise RuntimeError(
        "Apache Maven was not found. Install Maven or add its bin folder to PATH, then open a new terminal."
    )

def main():
    if len(sys.argv) < 2 or sys.argv[1] != "run":
        print("Usage: python senyalert.py run")
        sys.exit(1)

    print("[SENYALERT] Starting SenyAlert Emergency Triage System...")
    project_root = Path(__file__).resolve().parent
    try:
        environment = load_environment(project_root)
    except RuntimeError as error:
        print(f"[SENYALERT] Startup configuration error: {error}", file=sys.stderr)
        sys.exit(2)

    # The Java app owns its Python child only after a normal dashboard is
    # visible following successful sign-in. This launcher never pre-opens the
    # camera/OpenCV engine before the login screen.
    java_dir = project_root / "java-dashboard"
    try:
        maven = find_maven(java_dir, environment)
    except RuntimeError as error:
        print(f"[SENYALERT] Startup configuration error: {error}", file=sys.stderr)
        sys.exit(2)

    print("[SENYALERT] Launching dashboard sign-in. The ingestion engine starts after login.")
    java_proc = None
    try:
        # Do not run Maven's clean phase here. It removes useful local build
        # output and is unnecessary for a normal classroom/demo start.
        java_proc = subprocess.Popen(
            [maven, "compile", "exec:java"], cwd=java_dir, env=environment
        )
        java_proc.wait()
    except FileNotFoundError as error:
        print(f"[SENYALERT] Could not start Maven: {error.filename}", file=sys.stderr)
        sys.exit(2)
    except KeyboardInterrupt:
        print("\n[SENYALERT] Shutting down system...")
        if java_proc is not None:
            java_proc.terminate()
            java_proc.wait()

if __name__ == "__main__":
    main()
