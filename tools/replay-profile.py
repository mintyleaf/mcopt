#!/usr/bin/env python3
"""Prepare and run controlled Minecraft replays in one marker-owned Prism instance.

The normal source instance is read only. Worlds are cloned from an immutable fixture.
Cold = reset world + clear LOD cache. Warm-lod = reset world + retain LOD cache.
Warm-world = retain world + LOD cache from an earlier completed run.
GPU driver, filesystem caches, JIT scheduling and thermal state are not deterministic.
"""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid
from datetime import datetime

ROOT = Path(__file__).resolve().parents[1]
PRISM = Path('/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher')
INSTANCES = Path.home() / 'Library/Application Support/PrismLauncher/instances'
OWNER = 'mcopt-controlled-replay-v1'
MARKER = '.mcopt-replay-owned.json'


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def tree_digest(path):
    h = hashlib.sha256()
    for p in sorted(Path(path).rglob('*')):
        if p.is_symlink():
            raise ValueError('Fixture must not contain symlinks')
        if p.is_file():
            h.update(str(p.relative_to(path)).encode()); h.update(bytes.fromhex(digest(p)))
    return h.hexdigest()


def write_json(path, data):
    Path(path).write_text(json.dumps(data, indent=2) + '\n')


def read_owner(game):
    game = Path(game)
    if game.is_symlink() or game.parent.is_symlink() or (game / MARKER).is_symlink():
        raise ValueError('Owned instance must not be a symlink')
    data = json.loads((game / MARKER).read_text())
    if data.get('owner') != OWNER or Path(data['game']).resolve() != game.resolve():
        raise ValueError('Ownership marker mismatch; refusing cleanup')
    for relative in ('saves', 'saves/mcopt-replay', 'mcopt-replay-cache', 'mods', 'config',
                     'config/mcopt.properties', 'options.txt', '.replay-active.json'):
        if (game / relative).is_symlink():
            raise ValueError('Symlink in owned replay layout; refusing mutation')
    if (game.parent / 'instance.cfg').is_symlink():
        raise ValueError('Symlinked replay instance configuration')
    return data


def clear_children(path, game):
    """Only known children of a marker-owned game. Keep the save folder inode/birthtime."""
    read_owner(game)
    path = Path(path)
    if path.is_symlink() or path.resolve() not in {
        (game / 'saves/mcopt-replay').resolve(), (game / 'mcopt-replay-cache').resolve(),
    }:
        raise ValueError('Refusing cleanup outside the owned replay world/cache')
    path.mkdir(parents=True, exist_ok=True)
    for child in path.iterdir():
        if child.is_symlink() or child.is_file():
            child.unlink()
        elif child.is_dir():
            shutil.rmtree(child)
        else:
            raise ValueError('Unexpected file type in owned fixture')


def clone_world(source, destination):
    # copytree(source, destination, dirs_exist_ok=True) copies root timestamps too.
    # On APFS that can change birthtime, which is part of mcopt's cache identity.
    for child in source.iterdir():
        target = destination / child.name
        if child.is_symlink():
            raise ValueError('Fixture must not contain symlinks')
        if child.is_dir():
            shutil.copytree(child, target)
        else:
            shutil.copy2(child, target)


def alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False


def inactive(game):
    read_owner(game)
    state = game / '.replay-active.json'
    if state.exists():
        data = json.loads(state.read_text())
        live = Path(data['output']) / 'live.json'
        if live.exists() and alive(json.loads(live.read_text())['pid']):
            raise RuntimeError('Replay Minecraft is still running; refusing reset')
        # Elapsed time cannot prove a JVM never started. Keep ambiguous launches locked.
        if not live.exists():
            raise RuntimeError('Unresolved replay launch; inspect its logs/process before removing the active marker')


def validate_plan(path):
    plan = json.loads(Path(path).read_text())
    import math
    if plan.get('schema') != 1 or not 3 <= plan.get('warmupSeconds', 0) <= 120:
        raise ValueError('Expected schema 1 and warmupSeconds 3..120')
    phases = plan.get('phases', [])
    if not 1 <= len(phases) <= 32:
        raise ValueError('Expected 1..32 phases')
    names = set()
    for p in phases:
        if p['name'] in names or not p['name'].replace('_', '').isalnum():
            raise ValueError('Phase names must be unique letters/digits/underscores')
        names.add(p['name'])
        for k in ('from', 'to'):
            if len(p[k]) != 3 or not all(math.isfinite(v) for v in p[k]):
                raise ValueError('Invalid route point')
        if not all(math.isfinite(p[k]) for k in ('seconds', 'yaw', 'pitch', 'fov')) or p['seconds'] <= 0 or not 30 <= p['fov'] <= 110 or abs(p['pitch']) > 90:
            raise ValueError('Invalid duration/view')
    if sum(p['seconds'] for p in phases) > 300:
        raise ValueError('Maximum measured duration is 300 seconds')
    return plan


def prepare(args):
    source = args.source.expanduser().resolve()
    instance = INSTANCES / args.instance
    if Path(args.instance).name != args.instance or args.instance in {'.', '..'}:
        raise ValueError('Instance must be a simple name')
    game = instance / '.minecraft'
    fixture = args.fixture.expanduser().resolve()
    metal = args.metal.expanduser().resolve()
    harness = args.harness.expanduser().resolve()
    if not (fixture / 'fixture.json').is_file() or not (fixture / 'world/level.dat').is_file():
        raise ValueError('Not a generated replay fixture')
    if instance.exists():
        read_owner(game);inactive(game)
    else:
        game.mkdir(parents=True)
        write_json(game / MARKER, dict(owner=OWNER, game=str(game.resolve()), id=uuid.uuid4().hex))
    pack = json.loads((source / 'mmc-pack.json').read_text())
    version = next(c['version'] for c in pack['components'] if c['uid'] == 'net.minecraft')
    if version != '26.3':
        raise ValueError('Source instance must use Minecraft 26.3')
    (instance / 'mmc-pack.json').write_text(json.dumps(pack, indent=2) + '\n')
    source_settings = dict(line.split('=', 1) for line in (source / 'instance.cfg').read_text().splitlines() if '=' in line)
    java = source_settings.get('JavaPath', '').strip('"')
    if not Path(java).is_file():
        raise ValueError('Source instance must have an explicit installed Java path')
    mods = game / 'mods';mods.mkdir(exist_ok=True)
    # Only this owner-created directory may be replaced. Never modify or hardlink source mods/config/worlds.
    for child in mods.iterdir():
        if child.is_file() or child.is_symlink(): child.unlink()
        else: raise ValueError('Unexpected directory in replay mods')
    shutil.copy2(metal, mods / metal.name);shutil.copy2(harness, mods / harness.name)
    if args.modset == 'c2me':
        for prefix in ('c2me-', 'lithium-', 'ferritecore-', 'fabric-api-'):
            matches = list((source / '.minecraft/mods').glob(prefix + '*.jar'))
            if len(matches) != 1: raise ValueError('Expected one source mod for ' + prefix)
            shutil.copy2(matches[0], mods / matches[0].name)
    config = game / 'config';config.mkdir(exist_ok=True)
    (config / 'mcopt.properties').write_text('''profile=indie
mcopt.lod=true
mcopt.lod.radius=512
mcopt.lod.scoutRadius=512
mcopt.lod.textures=true
mcopt.lod.workload=balanced
mcopt.lod.refineIntervalMs=0
mcopt.startup.mslCache=true
mcopt.metal.jfr=true
mcopt.own.arena.remember=false
mcopt.own.arena.initialMiB=512
mcopt.own.arena.reserveMaxMiB=512
'''+ 'mcopt.lod.cacheDir=' + str(game / 'mcopt-replay-cache') + '\n')
    # Whitelisted options only; no servers, keybinds, resource packs, accounts or personal worlds copied.
    options = {'ao':'true','enableVsync':'true','maxFps':'60','renderDistance':'12','simulationDistance':'8','fov':'0.0',
        'fullscreen':'false','guiScale':'3','mipmapLevels':'2','pauseOnLostFocus':'false','soundCategory_master':'0.0',
        'tutorialStep':'none','onboardAccessibility':'false','showSubtitles':'false','inactivityFpsLimit':'afk'}
    (game / 'options.txt').write_text(''.join(k+':'+v+'\n' for k,v in options.items()))
    data = read_owner(game)
    data.update(fixture=str(fixture),fixtureSha256=tree_digest(fixture / 'world'),sourceVersion=version,java=java,
                width=args.width,height=args.height,modset=args.modset,rendererJar=metal.name,
                mods={p.name:digest(p) for p in sorted(mods.glob('*.jar'))},
                controlledOptions=(game / 'options.txt').read_text(),
                controlledConfig=(config / 'mcopt.properties').read_text())
    write_json(game / MARKER, data)
    base_cfg = {'InstanceType':'OneSix','name':'mcopt controlled replay','iconKey':'default','OverrideJavaLocation':'true',
        'JavaPath':java,'OverrideJavaArgs':'true','OverrideMemory':'true','MinMemAlloc':'512','MaxMemAlloc':'4096',
        'OverrideWindow':'true','MinecraftWinWidth':str(args.width),'MinecraftWinHeight':str(args.height),
        'LaunchMaximized':'false','OverrideCommands':'true','PreLaunchCommand':'','PostExitCommand':'','WrapperCommand':'',
        'ShowConsole':'false','ShowConsoleOnError':'false'}
    (instance / 'instance.cfg').write_text('[General]\n'+''.join(k+'='+v+'\n' for k,v in base_cfg.items()))
    print('Prepared marker-owned instance:', instance)
    print('Fixture SHA-256:', data['fixtureSha256'])


def memory_state():
    result = {}
    for name, cmd in [('vmStat',['/usr/bin/vm_stat']),('swapUsage',['/usr/sbin/sysctl','-n','vm.swapusage'])]:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
        result[name] = r.stdout.strip()
    return result


def run(args):
    game = INSTANCES / args.instance / '.minecraft'
    data = read_owner(game);inactive(game)
    plan = validate_plan(args.plan)
    fixture = Path(data['fixture'])
    if plan.get('seed') != json.loads((fixture / 'fixture.json').read_text())['seed']:
        raise ValueError('Route seed must match the immutable fixture')
    if tree_digest(fixture / 'world') != data['fixtureSha256']:
        raise ValueError('Immutable fixture changed; refusing run')
    if {p.name:digest(p) for p in sorted((game / 'mods').glob('*.jar'))} != data['mods']:
        raise ValueError('Installed replay mod set changed; prepare explicitly')
    # Minecraft persists route FOV and defaults on exit. Restore identical input settings each time.
    (game / 'options.txt').write_text(data['controlledOptions'])
    (game / 'config/mcopt.properties').write_text(data['controlledConfig'])
    output = args.output.expanduser().resolve();output.mkdir(parents=True, exist_ok=False)
    world = game / 'saves/mcopt-replay';world.mkdir(parents=True, exist_ok=True)
    birth = world.stat().st_birthtime
    if args.case != 'warm-world':
        clear_children(world, game)
        clone_world(fixture / 'world', world)
        if tree_digest(world) != data['fixtureSha256']: raise RuntimeError('World clone mismatch')
    elif not (world / 'level.dat').exists():
        raise ValueError('warm-world requires an earlier completed world')
    if world.stat().st_birthtime != birth: raise RuntimeError('World identity creation time changed')
    cache = game / 'mcopt-replay-cache'
    if args.case == 'cold': clear_children(cache, game)
    cache_files = list(cache.rglob('*.lod')) + list(cache.rglob('*.lodc')) if cache.exists() else []
    if args.case != 'cold' and not cache_files: raise ValueError('Warm case requires an existing populated LOD cache')
    route = output / 'route.json';shutil.copyfile(args.plan, route)
    cfg = game.parent / 'instance.cfg'
    lines = [l for l in cfg.read_text().splitlines() if not l.startswith('JvmArgs=')]
    flags = ['-XstartOnFirstThread','-Djava.awt.headless=true','-XX:+UseZGC','-XX:NativeMemoryTracking=summary',
             '-Dmcopt.replay.plan='+str(route),'-Dmcopt.replay.output='+str(output)]
    if any(any(ch.isspace() for ch in f) for f in flags): raise ValueError('Use an output path without spaces')
    cfg.write_text('\n'.join(lines)+'\nJvmArgs="'+' '.join(flags)+'"\n')
    manifest = dict(schema=1,case=args.case,rendererJar=data["rendererJar"],planSha256=digest(route),fixtureSha256=data['fixtureSha256'],mods=data['mods'],
        optionsSha256=digest(game / 'options.txt'),configSha256=digest(game / 'config/mcopt.properties'),
        saveDirectoryBirthTime=birth,lodCacheFilesBefore=len(cache_files),lodCacheBytesBefore=sum(p.stat().st_size for p in cache_files),
        startedEpoch=time.time(),memoryBefore=memory_state(),instance=args.instance,
        note='Controlled wall-clock route/world/cache state; rendering, generation scheduling, filesystem/GPU caches and thermal state remain nondeterministic.')
    write_json(output / 'run.json', manifest)
    write_json(game / '.replay-active.json',dict(output=str(output),startedEpoch=time.time()))
    subprocess.Popen([str(PRISM), '--launch', args.instance], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print('Launched disposable replay:', args.case, 'output:', output, flush=True)
    deadline = time.monotonic() + args.timeout
    pid = None;last_phase = None
    try:
        while time.monotonic() < deadline:
            live = output / 'live.json'
            if live.exists(): pid = json.loads(live.read_text())['pid']
            log = game / 'logs/latest.log'
            if live.exists() and log.exists():
                # Only harness progress is displayed; launcher arguments/account logs are never printed.
                events = [line.split('[mcopt-replay]',1)[1].strip() for line in log.read_text(errors='replace').splitlines() if '[mcopt-replay]' in line]
                if events and events[-1] != last_phase:
                    last_phase=events[-1];print('Replay:',last_phase,flush=True)
            if pid and not alive(pid): break
            time.sleep(1)
        else:
            if pid and alive(pid):
                import signal
                os.kill(pid, signal.SIGTERM)
            raise TimeoutError('Replay exceeded timeout; owned Minecraft termination requested')
        report_path=output/'report.json'
        if not report_path.exists(): raise RuntimeError('Minecraft exited without a replay report; inspect the disposable instance logs')
        report=json.loads(report_path.read_text())
        if not report.get('completed'): raise RuntimeError('Replay aborted: '+report.get('error','unknown'))
        shutil.copy2(game/'logs/latest.log',output/'game.log')
        manifest.update(endedEpoch=time.time(),memoryAfter=memory_state(),lodCacheFilesAfter=len(list(cache.rglob('*.lod')))+len(list(cache.rglob('*.lodc'))))
        manifest['cacheIdentities'] = [str(p.relative_to(cache)) for p in cache.rglob('world-*') if p.is_dir()]
        manifest['validForComparison'] = report['setup'].get('stableDuringMeasurement', True) and all(p['frames']>0 and p['unfocusedFrames']==0 and p['maxPositionError']<1 for p in report['phases'])
        write_json(output/'run.json',manifest)
        metadata=json.loads((output/'capture.json').read_text());metadata['endEpoch']=time.time();write_json(output/'capture.json',metadata)
        print(json.dumps(dict(validForComparison=manifest['validForComparison'],phases=report['phases']),indent=2),flush=True)
        if args.analyze:
            with (output/'analysis.log').open('w') as log:
                subprocess.run([sys.executable,str(ROOT/'tools/frame-profile.py'),'analyze',str(output)],stdout=log,stderr=subprocess.STDOUT,check=True)
            analyze_phases(output)
            print('JFR analysis complete:',output/'summary.json',flush=True)
    finally:
        if pid and not alive(pid): (game/'.replay-active.json').unlink(missing_ok=True)


def analyze_phases(output):
    """Attribute telemetry to route markers. GPU intervals use native clock mapping, not retirement time."""
    import importlib.util
    metadata = json.loads((output / 'capture.json').read_text())
    spec = importlib.util.spec_from_file_location('frame_profile', ROOT / 'tools/frame-profile.py')
    profile = importlib.util.module_from_spec(spec);spec.loader.exec_module(profile)
    kinds = ['mcopt.ReplayPhase','mcopt.MetalCommandBuffer','mcopt.MetalOperation','jdk.CPULoad',
             'jdk.ZAllocationStall','mcopt.OwnArenaGrowth','mcopt.LodClientCapture',
             'mcopt.LodScheduling','mcopt.MetalPipelineCompile']
    result = subprocess.run([str(Path(metadata['javaHome']) / 'bin/jfr'),'print','--json','--events',','.join(kinds),
                             str(output / 'recording.jfr')],capture_output=True,text=True,timeout=90,check=True)
    events = json.loads(result.stdout)['recording']['events']
    epoch = lambda value: datetime.fromisoformat(value.replace('Z','+00:00')).timestamp()
    markers = []
    for event in events:
        if event['type'] == 'mcopt.ReplayPhase':
            v = event['values'];start = epoch(v['startTime'])
            markers.append((v['phase'],start,start + profile.duration_ms(v['duration']) / 1000))
    output_rows = []
    for name, start, end in sorted(markers,key=lambda p:p[1]):
        gpu, cpu, machine, stalls, stall_sites, growth, captures, scheduling, drawables, compiles = [],[],[],[],[],[],[],[],[],[]
        for event in events:
            kind,v = event['type'],event['values']
            at = epoch(v['startTime'])
            if kind == 'mcopt.MetalCommandBuffer' and v['kind'] == 'main':
                # observedAt is sampled next to event.startTime; GPU commands retire later than they ran.
                if v.get('gpuStart',0)>0 and v.get('gpuEnd',0)>=v['gpuStart']:
                    at += v['gpuStart'] - v['observedAt']
                    if start <= at < end: gpu.append((v['gpuEnd']-v['gpuStart'])*1000)
            elif start <= at < end:
                if kind == 'jdk.CPULoad':
                    cpu.append((v['jvmUser']+v['jvmSystem'])*100);machine.append(v['machineTotal']*100)
                elif kind == 'jdk.ZAllocationStall' and (v.get('eventThread') or {}).get('javaName') == 'Render thread':
                    stalls.append(profile.duration_ms(v['duration']))
                    frames=(v.get('stackTrace') or {}).get('frames') or []
                    stall_sites.append(dict(start=v['startTime'],durationMs=stalls[-1],stack=[
                        f['method']['type']['name'].replace('/','.')+'.'+f['method']['name'] for f in frames]))
                elif kind == 'mcopt.OwnArenaGrowth': growth.append(v)
                elif kind == 'mcopt.LodClientCapture': captures.append(v.get('freezeMs',0))
                elif kind == 'mcopt.LodScheduling': scheduling.append(v)
                elif kind == 'mcopt.MetalOperation' and v.get('operation') == 'nextDrawable':
                    drawables.append(profile.duration_ms(v['duration']))
                elif kind == 'mcopt.MetalPipelineCompile': compiles.append(profile.duration_ms(v['duration']))
        row = dict(phase=name,startEpoch=start,endEpoch=end,mainGpuSpanMs=profile.stats(gpu),
            jvmCpuPercent=profile.stats(cpu),machineCpuPercent=profile.stats(machine),
            renderAllocationStallMs=profile.stats(stalls),arenaGrowthCount=len(growth),
            renderAllocationStallSites=stall_sites,
            clientCaptureFreezeMs=profile.stats(captures),drawableWaitMs=profile.stats(drawables),
            pipelineCompileMs=profile.stats(compiles),schedulingSamples=len(scheduling),
            memoryPressureSamples=sum(bool(v.get('memoryPressure')) for v in scheduling))
        if scheduling:
            row['lastScheduling'] = {k:scheduling[-1].get(k) for k in ('missingChunks','totalChunks','lodTilesMissing',
                'lodTilesNeeded','lodTilesGenerated','lodTilesLoaded','lodJobsPending','meshCompileQueued')}
        output_rows.append(row)
    report = json.loads((output / 'report.json').read_text())
    if [v['phase'] for v in output_rows] != [v['phase'] for v in report['phases']]:
        raise RuntimeError('JFR phase markers do not match the replay report')
    write_json(output / 'phase-summary.json',dict(schema=1,phases=output_rows,
        note='GPU command-buffer spans include dependencies, may overlap other buffers, and are attributed by mapped GPU start time. CPU load is machine-normalized. Drawable/capture events retain their instrumentation thresholds.'))


def compare(args):
    records=[]
    for directory in args.outputs:
        manifest=json.loads((directory/'run.json').read_text());report=json.loads((directory/'report.json').read_text())
        phases=json.loads((directory/'phase-summary.json').read_text()) if (directory/'phase-summary.json').exists() else {'phases':[]}
        records.append((directory,manifest,report,{p['phase']:p for p in phases['phases']}))
    keys=('planSha256','fixtureSha256','optionsSha256','configSha256')
    setup_keys=('framebufferWidth','framebufferHeight','renderDistance','vsync','maxFps','maxHeapBytes','backend','gpu','java','os')
    reference=records[0]
    mismatches=[]
    for directory,manifest,report,_ in records[1:]:
        for key in keys:
            if manifest.get(key)!=reference[1].get(key): mismatches.append(str(directory.name)+': '+key)
        for key in setup_keys:
            if report['setup'].get(key)!=reference[2]['setup'].get(key): mismatches.append(str(directory.name)+': '+key)
        other_mods=lambda r:{k:v for k,v in r['setup']['mods'].items() if k!='mcopt-metal'}
        if other_mods(report)!=other_mods(reference[2]): mismatches.append(str(directory.name)+': supporting mods')
        def supporting_hashes(m):
            renderer=m.get('rendererJar')
            return {k:v for k,v in m['mods'].items() if k!=renderer and (renderer or not k.startswith('mcopt-'))}
        if supporting_hashes(manifest)!=supporting_hashes(reference[1]): mismatches.append(str(directory.name)+': supporting mod hashes')

    rows=[]
    for directory,manifest,report,telemetry in records:
        for phase in report['phases']:
            row=dict(run=directory.name,case=manifest['case'],**phase)
            t=telemetry.get(phase['phase'],{})
            row.update(gpuP95Ms=t.get('mainGpuSpanMs',{}).get('p95'),
                renderAllocationStalls=t.get('renderAllocationStallMs',{}).get('count'),
                arenaGrowths=t.get('arenaGrowthCount'))
            rows.append(row)
    result=dict(schema=1,matchedInputSettings=not mismatches,inputMismatches=mismatches,
        allRunsValid=all(m.get('validForComparison',False) for _,m,_,_ in records),rows=rows,
        note='Cache cases and renderer jar hashes are explicit experiment variables. Repeats measure variance; a single run does not establish a speedup.')
    if args.output: write_json(args.output,result)
    writer=csv.DictWriter(sys.stdout,fieldnames=['run','case','phase','p50Ms','p95Ms','p99Ms','maxMs','gapsOver50ms','gpuP95Ms','renderAllocationStalls','arenaGrowths'],extrasaction='ignore')
    writer.writeheader();writer.writerows(rows)
    if mismatches: print('Input mismatches: '+', '.join(mismatches),file=sys.stderr)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest='command',required=True)
    p=sub.add_parser('prepare')
    p.add_argument('--source',type=Path,required=True);p.add_argument('--instance',default='mcopt-replay-profile')
    p.add_argument('--fixture',type=Path,required=True);p.add_argument('--metal',type=Path,required=True)
    p.add_argument('--harness',type=Path,default=ROOT/'replay/build/libs/replay-1.0.0-dev.jar')
    p.add_argument('--modset',choices=['minimal','c2me'],default='minimal')
    p.add_argument('--width',type=int,default=2560);p.add_argument('--height',type=int,default=1380)
    p=sub.add_parser('run');p.add_argument('--instance',default='mcopt-replay-profile')
    p.add_argument('--plan',type=Path,default=ROOT/'tools/replay/overworld.json')
    p.add_argument('--case',choices=['cold','warm-lod','warm-world'],default='cold')
    p.add_argument('--output',type=Path,required=True);p.add_argument('--timeout',type=float,default=360)
    p.add_argument('--analyze',action='store_true')
    p=sub.add_parser('analyze');p.add_argument('output',type=Path)
    p=sub.add_parser('compare');p.add_argument('outputs',type=Path,nargs='+');p.add_argument('--output',type=Path)
    args=parser.parse_args()
    if args.command=='prepare':prepare(args)
    elif args.command=='run':run(args)
    elif args.command=='analyze':analyze_phases(args.output)
    else:compare(args)

if __name__=='__main__':main()
