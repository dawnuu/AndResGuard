#!/usr/bin/env python3
"""Build the sibling Android demo and verify mode selection and final artifacts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


def run(args, cwd):
    result = subprocess.run([str(a) for a in args], cwd=cwd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(result.stdout)
    return result.stdout


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument('--demo', type=Path, default=root.parent / 'AndResGuardDemo')
    args = parser.parse_args()
    demo = args.demo.resolve()
    wrapper = demo / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    results = {}
    for mode in ('default', 'apk', 'all'):
        command = [wrapper, 'andresguardRelease', '--console=plain']
        if mode != 'default':
            command.append('-PguardFormat=' + mode)
        graph = run(command + ['--dry-run'], demo)
        tasks = set(re.findall(r'^(:app:\S+) SKIPPED$', graph, re.MULTILINE))
        assert ':app:andresguardRelease' in tasks, tasks
        assert (':app:packageReleaseBundle' in tasks) == (mode != 'apk'), tasks
        assert (':app:packageRelease' in tasks) == (mode != 'default'), tasks
        output = run(command, demo)
        assert 'AndResGuard aab output:' in output if mode != 'apk' else 'AndResGuard aab output:' not in output
        assert 'AndResGuard apk output:' in output if mode != 'default' else 'AndResGuard apk output:' not in output
        results[mode] = {'tasks': sorted(tasks), 'built': True}

    # Standard build tasks select their own format regardless of the extension.
    for entry, kind, other in (('bundleRelease', 'Aab', 'Apk'), ('assembleRelease', 'Apk', 'Aab')):
        for mode in ('aab', 'apk', 'all'):
            command = [wrapper, entry, '-PguardFormat=' + mode, '--console=plain']
            graph = run(command + ['--dry-run'], demo)
            tasks = set(re.findall(r'^(:app:\S+) SKIPPED$', graph, re.MULTILINE))
            assert ':app:andresguard' + kind + 'Release' in tasks, tasks
            assert ':app:andresguard' + other + 'Release' not in tasks, tasks
        output = run(command, demo)
        assert output.count('AndResGuard ' + kind.lower() + ' output:') == 1, output
        assert 'AndResGuard ' + other.lower() + ' output:' not in output, output
        results[entry] = {'formatIndependent': True, 'built': True}

    combined = run([wrapper, 'bundleRelease', 'assembleRelease', 'andresguardRelease',
                    '-PguardFormat=all', '--console=plain'], demo)
    for kind in ('aab', 'apk'):
        assert combined.count('AndResGuard ' + kind + ' output:') == 1, combined
    results['combinedEntries'] = {'processedOncePerFormat': True}
    debug = run([wrapper, 'assembleDebug', 'bundleDebug', '--dry-run', '--console=plain'], demo)
    assert not re.search(r'^:app:andresguard\S+ SKIPPED$', debug, re.MULTILINE), debug
    results['debug'] = {'noProcessing': True}

    # One project property must name both formats, including legacy .aab templates.
    shared = run([wrapper, 'andresguardRelease', '-PguardFormat=all',
                  '-PandResGuard.fileName=shared_{channel}.aab', '-PandResGuard.var.channel=store',
                  '--console=plain'], demo)
    for kind in ('aab', 'apk'):
        expected = demo / ('app/build/outputs/andresguard/' + kind + '/release/shared_store.' + kind)
        assert expected.is_file(), 'Shared file-name override did not produce ' + str(expected)
        assert str(expected) in shared, 'Task did not report the overridden ' + kind + ' output'
    results['sharedFileName'] = {'projectProperty': 'andResGuard.fileName',
                                 'template': 'shared_{channel}.aab', 'channel': 'store', 'verified': True}

    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        for line in (demo / 'local.properties').read_text().splitlines():
            if line.startswith('sdk.dir='):
                sdk = line.split('=', 1)[1]
    assert sdk, 'Configure ANDROID_HOME or sdk.dir in the demo'
    tools = max((p for p in (Path(sdk) / 'build-tools').iterdir() if re.fullmatch(r'\d+\.\d+\.\d+', p.name)),
                key=lambda p: tuple(map(int, p.name.split('.'))))
    exe = '.exe' if os.name == 'nt' else ''
    java = Path(os.environ['JAVA_HOME']) / 'bin' / ('java' + exe) if os.environ.get('JAVA_HOME') else Path('java' + exe)
    output = demo / 'app/build/outputs/andresguard'
    guarded = next((output / 'apk/release').glob('*.apk'))
    original = next((demo / 'app/build/outputs/apk/release').glob('*.apk'))
    certs = []
    for apk in (original, guarded):
        certs.append(re.search(r'certificate SHA-256 digest: (\w+)',
                              run([java, '-jar', tools / 'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', apk], demo)).group(1))
    assert certs[0] == certs[1], 'APK signing identity changed'
    run([tools / ('zipalign' + exe), '-c', '-P', '16', '4', guarded], demo)
    dumps = [run([tools / ('aapt2' + exe), 'dump', 'resources', apk], demo) for apk in (original, guarded)]
    ids = [set(re.findall(r'resource (0x[0-9a-fA-F]+)', dump)) for dump in dumps]
    assert ids[0] and ids[0] == ids[1], 'Resource IDs changed'
    assert 'string/keep_name' in dumps[1], 'Whitelisted dynamic lookup resource was renamed'
    run([tools / ('aapt2' + exe), 'dump', 'xmltree', '--file', 'AndroidManifest.xml', guarded], demo)
    dex = {}
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(guarded) as target:
        assert target.getinfo('resources.arsc').compress_type == zipfile.ZIP_STORED
        for name in source.namelist():
            if re.fullmatch(r'classes\d*\.dex', name) or name.startswith('lib/'):
                assert source.read(name) == target.read(name), name + ' payload changed'
                assert source.getinfo(name).compress_type == target.getinfo(name).compress_type, name + ' compression changed'
                dex[name] = hashlib.sha256(target.read(name)).hexdigest()
        assert dex, 'Demo has no DEX payload'
    aab = next((output / 'aab/release').glob('*.aab'))
    with zipfile.ZipFile(aab) as archive:
        assert 'BundleConfig.pb' in archive.namelist()
        assert 'base/resources.pb' in archive.namelist()
        assert any(name.endswith('.SF') for name in archive.namelist()), 'AAB is unsigned'
    for kind in ('aab', 'apk'):
        assert list((output / kind / 'release').glob('*mapping*.txt')), 'Missing ' + kind + ' mapping'
    results['artifacts'] = {'apk': str(guarded), 'aab': str(aab), 'resourceIds': len(ids[0]),
                            'signerSha256': certs[0], 'dexSha256': dex}
    report = root / 'build/reports/demo-verification.json'
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(results, indent=2) + '\n')
    print('Verified lifecycle tasks, format selection, duplicate execution, debug isolation, resource IDs, whitelist, DEX, signing, alignment and mapping.')
    print('Report: ' + str(report))


if __name__ == '__main__':
    main()
