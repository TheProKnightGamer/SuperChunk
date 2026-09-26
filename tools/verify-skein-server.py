#!/usr/bin/env python3
"""Exercise Skein compatibility in a fresh packaged NeoForge 1.21.1 server.

Requires Java 21 on PATH and an existing server installation with Chunky and an
accepted eula.txt. Runs CPU generation, saves, and restarts the copied world to
test tick-function/command-block reloads, blocking chunk loads, cross-dimension
selectors, conditional chains, and inventories in all three dimensions. Only
processes started by this tool are stopped.

Example:
  python3 tools/verify-skein-server.py --server-template /path/to/server \
      --jar build/libs/superchunk.jar --skein-jar /path/to/skein.jar \
      --output run/skein-verification

The output directory must not exist. Fresh logs/results remain in server/;
restart-console.log and result.json describe the restart and combined checks.
This is a bounded compatibility regression test, not a general modpack test.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import queue
import re
import subprocess
import sys
import threading
import time


DIMENSIONS = ('overworld', 'the_nether', 'the_end')
EXPECTED_PHASES = {'dimensions'}
SKEIN_CONFIG = """[general]
threadCount=3
adaptive=false
logSummaryEveryTicks=20
[dimensions]
enabled=true
[entities]
enabled=true
[randomTicks]
enabled=true
[blockEntities]
enabled=true
[scheduledTicks]
enabled=true
[saving]
enabled=true
"""
# INFO-level command failures must fail this test too: a clean process exit
# alone does not prove that its command blocks executed successfully.
FAILED = re.compile(
    r'/ERROR\]|/FATAL\]|OutOfMemoryError|SIGSEGV|Exception in thread|'
    r'InvalidMixin|InvalidInjection|Mixin apply failed|quarantined after|'
    r'\b[1-9]\d* tick failure|Unknown or incomplete command|Incorrect argument|'
    r'Failed to load function|Failed to parse|command\.failed|'
    r'No entity was found|No elements matching|'
    r'Failed to start the minecraft server|Encountered an unexpected exception',
    re.IGNORECASE)


def setup_commands():
    commands = []
    for dim in DIMENSIONS:
        prefix = f'execute in minecraft:{dim} run '
        commands.extend(prefix + command for command in (
            'forceload add 2048 2048',
            'fill 2048 100 2048 2055 100 2055 stone',
            'summon pig 2050 102 2050 {PersistenceRequired:1b,NoAI:1b,Invulnerable:1b,'
            f'Tags:["sc_skein_local_{dim}"]}}',
            'setblock 2052 101 2052 hopper',
            'setblock 2052 102 2052 '
            'chest{Items:[{Slot:0b,id:"minecraft:cobblestone",count:64}]}',
        ))
    # A separate Overworld target keeps the cross-world command independent of
    # the order in which the two local spreadplayers transactions execute.
    commands.append('execute in minecraft:overworld run summon pig 2051 102 2050 '
                    '{PersistenceRequired:1b,NoAI:1b,Invulnerable:1b,'
                    'Tags:["sc_skein_crossworld"]}')
    commands.append('skein reload')
    return commands


def console_health(console):
    phases = re.findall(r'\bphases:\s*([^\r\n]*)', console)
    return {
        'expected_phases': bool(phases) and all(set(p.strip().split(', ')) == EXPECTED_PHASES for p in phases),
        'saved': 'Saved the game' in console,
        'no_errors': not any(FAILED.search(line) for line in console.splitlines()),
    }


def check_fresh(console, benchmark, exit_code):
    checks = console_health(console)
    checks.update({
        'benchmark_passed': exit_code == 0,
        'boot': benchmark.get('boot_ok') is True,
        'generation': benchmark.get('generation_ok') is True,
        'exit_zero': benchmark.get('exit_code') == 0,
        'three_forced_chunks': console.count('to be force loaded') == 3,
        'four_pigs': console.count('Summoned new Pig') == 4,
        'three_hoppers': console.count('Changed the block at 2052, 101, 2052') == 3,
        'three_chests': console.count('Changed the block at 2052, 102, 2052') == 3,
        'console_reload': 'Skein: config re-read.' in console,
        'finished': 'SC_SKEIN_FRESH_DONE' in console,
    })
    return checks


def check_restart(console, exit_code, timed_out):
    lines = console.splitlines()
    # Count queried LastOutput / Items replies, rather than worker log messages
    # that could report one successful action more than once.
    reloads = sum('2054, 101, 2054 has the following block data:' in line
                  and ('config reload queued for the next server tick' in line
                       or 'config re-read.' in line) for line in lines)
    spreads = sum('2050, 101, 2054 has the following block data:' in line
                  and 'commands.spreadplayers.success.entities' in line for line in lines)
    hoppers = sum('2052, 101, 2052 has the following block data:' in line
                  and 'minecraft:cobblestone' in line for line in lines)
    crossworld = sum('2056, 101, 2054 has the following block data:' in line
                     and 'commands.spreadplayers.success.entities' in line for line in lines)
    checks = console_health(console)
    checks.update({
        'exit_zero': exit_code == 0,
        'within_timeout': not timed_out,
        'boot': 'Done (' in console,
        'tick_function_reload': 'SC_SKEIN_TICK_RELOAD_OK' in console,
        'deferred_commands': 'SuperChunk deferred command-block transactions until '
                             'Skein dimension workers finish.' in console,
        'three_command_block_reloads': reloads == 3,
        'two_spreadplayers': spreads == 2,
        'crossworld_spreadplayers': crossworld == 1,
        'conditional_chain': 'SC_SKEIN_CHAIN_OK' in console,
        'command_minecart': 'SC_SKEIN_CART_OK' in console,
        'three_hopper_transfers': hoppers == 3,
        'finished': 'SC_SKEIN_RESTART_DONE' in console,
    })
    return checks, {'command_block_reloads': reloads, 'spreadplayers': spreads,
                    'crossworld_spreadplayers': crossworld, 'hopper_transfers': hoppers}


def install_restart_checks(server):
    properties = server / 'server.properties'
    text = properties.read_text()
    text = re.sub(r'^enable-command-block=.*\n?', '', text, flags=re.M)
    properties.write_text(text.rstrip() + '\nenable-command-block=true\n')
    pack = server / 'world/datapacks/superchunk-skein-check'
    files = {
        'pack.mcmeta': json.dumps({'pack': {
            'pack_format': 48, 'description': 'SuperChunk Skein compatibility checks'}}),
        'data/minecraft/tags/function/load.json': json.dumps({'values': ['sc_skein:load']}),
        'data/minecraft/tags/function/tick.json': json.dumps({'values': ['sc_skein:tick']}),
        'data/sc_skein/function/load.mcfunction':
            'scoreboard objectives add sc_skein dummy\n'
            'scoreboard players set #ran sc_skein 0\n',
        'data/sc_skein/function/tick.mcfunction':
            'execute if score #ran sc_skein matches 0 run function sc_skein:reload\n',
        'data/sc_skein/function/reload.mcfunction':
            'scoreboard players set #ran sc_skein 1\nskein reload\nskein status\n'
            'say SC_SKEIN_TICK_RELOAD_OK\n',
    }
    for name, contents in files.items():
        target = pack / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(contents)


def run_restart(server, log_path, timeout):
    install_restart_checks(server)
    command = json.loads((server / 'invocation.json').read_text())['command']
    process = subprocess.Popen(command, cwd=server, stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, bufsize=1)
    lines = queue.Queue()

    def read_output():
        with log_path.open('w') as log:
            for line in process.stdout:
                log.write(line)
                log.flush()
                lines.put(line)
        lines.put(None)

    def send(command):
        try:
            process.stdin.write(command + '\n')
            process.stdin.flush()
        except BrokenPipeError:
            pass

    reader = threading.Thread(target=read_output, daemon=True)
    reader.start()
    started = time.monotonic()
    boot = None
    inspected = False
    timed_out = False
    try:
        while True:
            if time.monotonic() - started >= timeout:
                timed_out = True
                break
            try:
                line = lines.get(timeout=0.5)
            except queue.Empty:
                line = ''
            if line is None:
                break
            if 'Done (' in line and boot is None:
                boot = time.monotonic()
                print('Restart booted; exercising dimension-worker command blocks', flush=True)
                for dim in DIMENSIONS:
                    prefix = f'execute in minecraft:{dim} run '
                    send(prefix + 'setblock 2054 101 2054 minecraft:command_block'
                         '{Command:"skein reload"}')
                    send(prefix + 'setblock 2054 102 2054 minecraft:redstone_block')
                    if dim != 'the_end':
                        send(prefix + 'setblock 2050 101 2054 minecraft:command_block'
                             '{Command:"spreadplayers 8192 8192 0 1 false '
                             f'@e[type=minecraft:pig,tag=sc_skein_local_{dim},'
                             'distance=..64,limit=1]"}')
                        send(prefix + 'setblock 2050 102 2054 minecraft:redstone_block')
                # With no distance filter this selector intentionally reaches
                # into the Overworld from a Nether command block. The entire
                # impulse/conditional-chain transaction must run after workers
                # finish, including its selector, teleport and success count.
                prefix = 'execute in minecraft:the_nether run '
                send(prefix + 'setblock 2057 101 2054 '
                     'minecraft:chain_command_block[facing=east,conditional=true]'
                     '{auto:1b,Command:"setblock 2058 101 2054 minecraft:diamond_block"}')
                send(prefix + 'setblock 2056 101 2054 minecraft:command_block[facing=east]'
                     '{Command:"spreadplayers 12288 12288 0 1 false '
                     '@e[type=minecraft:pig,tag=sc_skein_crossworld,limit=1]"}')
                send(prefix + 'setblock 2056 102 2054 minecraft:redstone_block')
                # A stationary cart on a continuously powered activator rail
                # exercises the separate command-minecart transaction hook.
                prefix = 'execute in minecraft:overworld run '
                send(prefix + 'setblock 2049 100 2052 minecraft:redstone_block')
                send(prefix + 'setblock 2049 101 2052 '
                     'minecraft:activator_rail[shape=north_south,powered=true]')
                send(prefix + 'summon minecraft:command_block_minecart 2049.5 101.1 2052.5 '
                     '{Command:"say SC_SKEIN_CART_OK"}')
            if boot is not None and time.monotonic() - boot >= 20 and not inspected:
                inspected = True
                for dim in DIMENSIONS:
                    prefix = f'execute in minecraft:{dim} run '
                    send(prefix + 'data get block 2054 101 2054 LastOutput')
                    send(prefix + 'data get block 2052 101 2052 Items')
                    if dim != 'the_end':
                        send(prefix + 'data get block 2050 101 2054 LastOutput')
                    if dim == 'the_nether':
                        send(prefix + 'data get block 2056 101 2054 LastOutput')
                        send(prefix + 'execute if block 2058 101 2054 minecraft:diamond_block '
                             'run say SC_SKEIN_CHAIN_OK')
                    send(prefix + 'forceload remove all')
                for command in ('skein status', 'save-all flush',
                                'say SC_SKEIN_RESTART_DONE', 'stop'):
                    send(command)
            if process.poll() is not None:
                break
    finally:
        # No name-based process lookup: even interrupted or timed-out runs only
        # stop the Java subprocess created above.
        if process.poll() is None:
            send('stop')
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
        reader.join(timeout=5)
        try:
            process.stdin.close()
        except BrokenPipeError:
            pass  # Closing can flush bytes after the server has already exited.
        process.stdout.close()
    console = log_path.read_text()
    checks, counts = check_restart(console, process.returncode, timed_out)
    return {'checks': checks, 'counts': counts, 'exit_code': process.returncode,
            'total_seconds': time.monotonic() - started,
            'errors': [line for line in console.splitlines() if FAILED.search(line)]}


def run_fresh(args, server, config):
    # Calling the existing harness in-process preserves its subprocess cleanup
    # on Ctrl-C, without creating an intermediate process that could orphan Java.
    helper = Path(__file__).with_name('run-worldgen-benchmark.py')
    spec = importlib.util.spec_from_file_location('worldgen_benchmark', helper)
    benchmark = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(benchmark)
    arguments = [str(helper), '--server-template', str(args.server_template),
                 '--jar', str(args.jar), '--extra-mod', str(args.skein_jar),
                 '--config-file', str(config), '--output', str(server),
                 '--radius', '256', '--workers', '4', '--heap', '4G',
                 '--timeout', str(args.timeout)]
    for command in setup_commands():
        arguments.extend(('--setup-command', command))
    for command in ('skein status', 'save-all flush', 'say SC_SKEIN_FRESH_DONE'):
        arguments.extend(('--finish-command', command))
    previous_argv = sys.argv
    try:
        sys.argv = arguments
        return benchmark.main()
    finally:
        sys.argv = previous_argv


def main():
    global EXPECTED_PHASES
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-template', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--skein-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True, help='New directory; must not exist')
    parser.add_argument('--parallel-phases', action='store_true', help='Require the versioned Skein bridge to retain all tick phases')
    parser.add_argument('--timeout', type=int, default=180,
                        help='Maximum active seconds per server phase (1..180); cleanup has a short grace period')
    args = parser.parse_args()
    if args.parallel_phases:
        EXPECTED_PHASES = {'dimensions', 'scheduledTicks', 'randomTicks', 'entities', 'blockEntities'}
    if not 1 <= args.timeout <= 180:
        parser.error('--timeout must be between 1 and 180 seconds')
    for name in ('server_template', 'jar', 'skein_jar', 'output'):
        setattr(args, name, getattr(args, name).resolve())
    if not args.server_template.is_dir():
        parser.error('--server-template must be an existing directory')
    if any(not jar.is_file() or jar.suffix.lower() != '.jar'
           for jar in (args.jar, args.skein_jar)):
        parser.error('--jar and --skein-jar must be existing jar files')
    if args.output.exists():
        parser.error('--output must not exist')
    args.output.mkdir(parents=True)
    config = args.output / 'skein-common.toml'
    config.write_text(SKEIN_CONFIG)
    server = args.output / 'server'
    fresh_exit = run_fresh(args, server, config)
    fresh_console = (server / 'console.log').read_text()
    fresh = {'checks': check_fresh(fresh_console,
                                  json.loads((server / 'result.json').read_text()), fresh_exit),
             'errors': [line for line in fresh_console.splitlines() if FAILED.search(line)]}
    result = {'fresh': fresh, 'restart': None, 'passed': False}
    result_path = args.output / 'result.json'
    result_path.write_text(json.dumps(result, indent=2) + '\n')
    if all(fresh['checks'].values()):
        print('Fresh generation and saves passed; restarting the same world', flush=True)
        result['restart'] = run_restart(server, args.output / 'restart-console.log', args.timeout)
        result['passed'] = all(result['restart']['checks'].values())
    result_path.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2), flush=True)
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
