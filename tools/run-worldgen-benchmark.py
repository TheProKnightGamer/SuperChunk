#!/usr/bin/env python3
"""Run a packaged-jar Chunky benchmark in a new, isolated server directory.

Requires an existing NeoForge 1.21.1 server installation with Chunky and an
accepted eula.txt. Only its libraries, Chunky jar and optional GPU cache are
reused. Existing worlds/configuration/processes are never modified.
"""
import argparse
import hashlib
import json
from pathlib import Path
import queue
import re
import shutil
import subprocess
import threading
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-template', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True, help='New directory; must not exist')
    parser.add_argument('--radius', type=int, default=1024)
    parser.add_argument('--seed', type=int, default=8675309)
    parser.add_argument('--workers', type=int, default=12)
    parser.add_argument('--heap', default='8G')
    parser.add_argument('--gpu', action='store_true')
    parser.add_argument('--profile', action='store_true')
    parser.add_argument('--extra-mod', type=Path, action='append', default=[],
                        help='Additional mod jar for an isolated compatibility check')
    parser.add_argument('--config-file', type=Path, action='append', default=[],
                        help='Additional .toml config copied by filename into the isolated config directory')
    parser.add_argument('--setup-command', action='append', default=[],
                        help='Server console command after boot, before generation (repeatable)')
    parser.add_argument('--finish-command', action='append', default=[],
                        help='Server console command after generation, before shutdown (repeatable)')
    parser.add_argument('--jvm-arg', action='append', default=[])
    parser.add_argument('--config', action='append', default=[], metavar='KEY=VALUE',
                        help='Override a superchunk.properties setting')
    parser.add_argument('--timeout', type=int, default=900)
    args = parser.parse_args()
    template, jar, output = (p.resolve() for p in (args.server_template, args.jar, args.output))
    eula = template / 'eula.txt'
    if not eula.exists() or not re.search(r'^eula\s*=\s*true\s*$', eula.read_text(), re.M):
        parser.error('The template server must already have an accepted eula.txt')
    launchers = sorted((template / 'libraries/net/neoforged/neoforge').glob('21.1.*/unix_args.txt'),
                       key=lambda p: tuple(map(int, p.parent.name.split('.'))))
    chunky = sorted((template / 'mods').glob('*[Cc]hunky*.jar'))
    if not launchers or len(chunky) != 1 or not jar.is_file():
        parser.error('Need a NeoForge 1.21.1 installation, exactly one Chunky jar, and the input mod jar')
    if args.radius <= 0 or args.workers <= 0 or args.timeout <= 0:
        parser.error('radius, workers and timeout must be positive')
    copied_names = {'superchunk.jar', chunky[0].name}
    for extra in args.extra_mod:
        if not extra.is_file() or extra.suffix.lower() != '.jar' or extra.name in copied_names:
            parser.error('--extra-mod requires an existing jar with a unique destination filename')
        copied_names.add(extra.name)
    config_names = set()
    for config_file in args.config_file:
        if (not config_file.is_file() or config_file.suffix.lower() != '.toml'
                or config_file.name in config_names):
            parser.error('--config-file requires an existing .toml with a unique filename')
        config_names.add(config_file.name)
    for setting in args.config:
        if not re.fullmatch(r'[A-Za-z0-9_.]+=[^\r\n]*', setting):
            parser.error('--config requires one KEY=VALUE setting without line breaks')
    if any(not command.strip() or '\n' in command or '\r' in command
           for command in args.setup_command + args.finish_command):
        parser.error('Each setup/finish command must be one nonempty console line')
    output.mkdir(parents=True, exist_ok=False)
    (output / 'libraries').symlink_to(template / 'libraries', target_is_directory=True)
    (output / 'mods').mkdir()
    (output / 'config').mkdir()
    shutil.copy2(eula, output / 'eula.txt')
    shutil.copy2(jar, output / 'mods/superchunk.jar')
    shutil.copy2(chunky[0], output / 'mods' / chunky[0].name)
    for extra in args.extra_mod:
        shutil.copy2(extra, output / 'mods' / extra.name)
    for config_file in args.config_file:
        shutil.copy2(config_file, output / 'config' / config_file.name)
    cache = template / 'config/superchunk-gpu-cache'
    if args.gpu and cache.is_dir():
        shutil.copytree(cache, output / 'config/superchunk-gpu-cache')
    (output / 'server.properties').write_text(
        f'level-seed={args.seed}\nserver-ip=127.0.0.1\nserver-port=0\n'
        'online-mode=false\nview-distance=2\nsimulation-distance=2\n'
        'max-tick-time=-1\nenable-rcon=false\nspawn-protection=0\n')
    (output / 'config/superchunk.properties').write_text(
        f'gpu.enabled={str(args.gpu).lower()}\nc2me.globalExecutorParallelism={args.workers}\n'
        + '\n'.join(args.config) + '\n')
    command = ['java', f'-Xms{args.heap}', f'-Xmx{args.heap}', '-XX:+UseZGC',
               '-XX:+ZGenerational', '-Dterminal.jline=false', '-Dterminal.ansi=false',
               *args.jvm_arg, '@' + str(launchers[-1]), 'nogui']
    result = dict(jar=str(jar), sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                  neoforge=launchers[-1].parent.name, radius=args.radius, seed=args.seed,
                  workers=args.workers, heap=args.heap, gpu=args.gpu, profile=args.profile,
                  config_overrides=args.config,
                  setup_commands=args.setup_command, finish_commands=args.finish_command,
                  extra_mods=[dict(name=p.name, sha256=hashlib.sha256(
                      (output / 'mods' / p.name).read_bytes()).hexdigest()) for p in args.extra_mod],
                  config_files=[dict(name=p.name, sha256=hashlib.sha256(
                      (output / 'config' / p.name).read_bytes()).hexdigest()) for p in args.config_file],
                  chunky_sha256=hashlib.sha256(chunky[0].read_bytes()).hexdigest(),
                  java_version=subprocess.run(['java', '-version'], capture_output=True,
                                              text=True, check=True).stderr.strip(),
                  command=command,
                  boot_ok=False, generation_ok=False, health_errors=[])
    if args.gpu and cache.is_dir():
        cache_hash = hashlib.sha256()
        for cached_file in sorted(cache.rglob('*')):
            if cached_file.is_file():
                cache_hash.update(str(cached_file.relative_to(cache)).encode())
                cache_hash.update(hashlib.sha256(cached_file.read_bytes()).digest())
        result['initial_gpu_cache_sha256'] = cache_hash.hexdigest()
    (output / 'invocation.json').write_text(json.dumps(result, indent=2) + '\n')
    started = time.monotonic()
    process = subprocess.Popen(command, cwd=output, stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, bufsize=1)
    lines = queue.Queue()

    def read_output():
        with (output / 'console.log').open('w') as log:
            for line in process.stdout:
                log.write(line)
                log.flush()
                lines.put(line)
        lines.put(None)

    reader = threading.Thread(target=read_output, daemon=True)
    reader.start()

    def send(command):
        try:
            process.stdin.write(command + '\n')
            process.stdin.flush()
        except BrokenPipeError:
            pass  # The server can close stdin just before its process exits.

    failed = re.compile(r'/ERROR\]|/FATAL\]|OutOfMemoryError|SIGSEGV|Mixin apply failed|InvalidMixinException|'
                        r'InvalidInjectionException|Exception in thread|VERIFY MISMATCH|'
                        r'faults=[1-9]|MISMATCHES(?: total)?=[1-9]|mismatches=[1-9]|BUGS(?:\([^)]*\))?=[1-9]|'
                        r'Failed to start the minecraft server|Encountered an unexpected exception')
    generation_start = None
    stop_sent = False
    try:
        while time.monotonic() - started < args.timeout:
            try:
                line = lines.get(timeout=1)
            except queue.Empty:
                if process.poll() is not None:
                    break
                continue
            if line is None:
                break
            if failed.search(line):
                result['health_errors'].append(line.strip())
            if 'Done (' in line and not result['boot_ok']:
                result['boot_ok'] = True
                result['boot_seconds'] = time.monotonic() - started
                print(f"Booted in {result['boot_seconds']:.1f}s", flush=True)
                send('gamerule spawnChunkRadius 0')
                for cmd in args.setup_command:
                    send(cmd)
                if args.profile:
                    send('jfr start')
                for cmd in ('chunky world minecraft:overworld', 'chunky shape square',
                            'chunky center 2048 2048', f'chunky radius {args.radius}', 'chunky start'):
                    send(cmd)
                generation_start = time.monotonic()
            if 'Task finished for minecraft:overworld' in line and not stop_sent:
                result['generation_ok'] = True
                result['generation_seconds'] = time.monotonic() - generation_start
                result['chunky_result'] = line.strip()
                count = re.search(r'Processed: ([\d,]+)', line)
                if count:
                    result['chunks'] = int(count[1].replace(',', ''))
                    result['chunks_per_second'] = result['chunks'] / result['generation_seconds']
                print(line.strip(), flush=True)
                if args.profile:
                    send('jfr stop')
                for cmd in args.finish_command:
                    send(cmd)
                send('stop')
                stop_sent = True
        if process.poll() is None:
            if not stop_sent:
                send('stop')
            try:
                process.wait(timeout=45)
            except subprocess.TimeoutExpired:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        reader.join(timeout=5)
        result['exit_code'] = process.returncode
        result['total_seconds'] = time.monotonic() - started
        # Include errors emitted during shutdown, after the main reader loop.
        console = (output / 'console.log').read_text()
        result['health_errors'] = [line for line in console.splitlines()
                                   if failed.search(line)]
        device = re.search(r'Selected device: ([^\n]+)', console)
        result['gpu_device'] = device.group(1) if device else None
        result['gpu_backend_ready'] = 'OpenCL backend ready (context + queue created).' in console
        if args.gpu and not result['gpu_backend_ready']:
            result['health_errors'].append('GPU requested but no OpenCL context and queue became ready')
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2), flush=True)
    return 0 if (result['boot_ok'] and result['generation_ok'] and
                 result['exit_code'] == 0 and not result['health_errors']) else 1


if __name__ == '__main__':
    raise SystemExit(main())
